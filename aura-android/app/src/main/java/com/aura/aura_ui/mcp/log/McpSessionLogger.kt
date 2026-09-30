package com.aura.aura_ui.mcp.log

import android.content.Context
import android.util.Base64
import android.util.Log
import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.SessionLogSink
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Phase 10B — file-backed forensic session logger.
 *
 * One open session at a time. Session boundary closes when:
 *   • the `end_session` MCP tool fires, OR
 *   • [INACTIVITY_TIMEOUT_MS] elapses with no tool calls.
 *
 * Screenshot capture happens inside [onToolStart] on a background
 * dispatcher so it never blocks the MCP request thread. If capture
 * fails (permission revoked, etc.), the invocation is still recorded
 * minus the image.
 *
 * Per-session layout:
 *   <files>/mcp_logs/<sessionId>/metadata.json
 *   <files>/mcp_logs/<sessionId>/screenshots/<index>.jpg   (JPEG from the capture path)
 */
class McpSessionLogger(
    context: Context,
    private val screenshotBridge: ScreenshotBridge,
) : SessionLogSink {

    private val rootDir: File = File(context.applicationContext.filesDir, "mcp_logs").apply { mkdirs() }
    // Critical: unhandled exceptions in a launch{} normally propagate to the
    // JVM thread and crash the entire app process — which kills the MCP
    // server too. The handler keeps logger failures isolated to logger
    // failures: log loudly and keep serving tool calls.
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Session logger coroutine failed — swallowed to protect the MCP server", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private var currentSession: SessionLog? = null
    private var currentSessionDir: File? = null
    private var pendingInvocation: ToolInvocation? = null
    private var lastActivityMillis: Long = 0

    /**
     * Tools we DON'T capture screenshots for, either because they don't
     * affect the screen or because they would create useless duplicates.
     * Keeping this list small — the user wants forensic detail and
     * disk is cheap.
     */
    private val skipScreenshotFor = setOf("echo", "connect_device", "end_session")

    // ── SessionLogSink ─────────────────────────────────────────────

    override fun onToolStart(
        toolName: String,
        argsJson: String?,
        tokenId: String?,
        agentLabel: String?,
    ) {
        scope.launch {
            mutex.withLock {
                val now = System.currentTimeMillis()
                rotateIfNeeded(now, tokenId, agentLabel)
                val session = currentSession ?: return@launch
                val invocation = ToolInvocation(
                    index = session.invocations.size,
                    timestampMillis = now,
                    toolName = toolName,
                    argsJson = argsJson?.take(ARGS_MAX_CHARS),
                )
                session.invocations.add(invocation)
                pendingInvocation = invocation
                lastActivityMillis = now
                if (toolName !in skipScreenshotFor) {
                    captureScreenshotInto(invocation)
                }
                persist(session)
            }
        }
    }

    override fun onToolEnd(
        toolName: String,
        success: Boolean,
        outputSummary: String,
        durationMs: Long,
    ) {
        scope.launch {
            mutex.withLock {
                val session = currentSession ?: return@launch
                val invocation = pendingInvocation ?: return@launch
                if (invocation.toolName == toolName) {
                    invocation.success = success
                    invocation.outputSummary = outputSummary
                    invocation.durationMs = durationMs
                    pendingInvocation = null
                    lastActivityMillis = System.currentTimeMillis()
                    persist(session)
                }
            }
        }
    }

    override fun endSession(reason: String) {
        scope.launch {
            mutex.withLock { closeCurrent(reason) }
        }
    }

    // ── Internals ──────────────────────────────────────────────────

    private fun rotateIfNeeded(now: Long, tokenId: String?, agentLabel: String?) {
        val session = currentSession
        if (session == null) {
            openNew(now, tokenId, agentLabel)
            return
        }
        if (now - lastActivityMillis > INACTIVITY_TIMEOUT_MS) {
            closeCurrent("inactivity-timeout")
            openNew(now, tokenId, agentLabel)
        }
    }

    private fun openNew(now: Long, tokenId: String?, agentLabel: String?) {
        val sessionId = "$now-${randomSuffix()}"
        val dir = File(rootDir, sessionId).apply { mkdirs() }
        File(dir, "screenshots").mkdirs()
        currentSession = SessionLog(
            sessionId = sessionId,
            startedAtMillis = now,
            agentLabel = agentLabel,
            tokenId = tokenId,
        )
        currentSessionDir = dir
        lastActivityMillis = now
    }

    private fun closeCurrent(reason: String) {
        val session = currentSession ?: return
        session.endedAtMillis = System.currentTimeMillis()
        session.endReason = reason
        persist(session)
        currentSession = null
        currentSessionDir = null
        pendingInvocation = null
    }

    private fun persist(session: SessionLog) {
        val dir = currentSessionDir ?: return
        try {
            File(dir, "metadata.json").writeText(json.encodeToString(session))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist session ${session.sessionId}", t)
        }
    }

    private suspend fun captureScreenshotInto(invocation: ToolInvocation) {
        val dir = currentSessionDir ?: return
        val result = runCatching { screenshotBridge.captureBase64Png() }
            .getOrElse { t ->
                Log.w(TAG, "Screenshot capture failed for ${invocation.toolName}", t)
                return
            }
        if (result !is CaptureResult.Success) return
        try {
            val bytes = Base64.decode(result.base64Png, Base64.DEFAULT)
            // Named for what the bytes ARE: the capture path returns JPEG (see [imageExtensionFor]).
            val target = File(File(dir, "screenshots"), "${invocation.index}.${imageExtensionFor(bytes)}")
            target.writeBytes(bytes)
            invocation.hasScreenshot = true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write screenshot for invocation ${invocation.index}", t)
        }
    }

    private fun randomSuffix(): String =
        UUID.randomUUID().toString().substringBefore('-')

    companion object {
        private const val TAG = "McpSessionLogger"
        /** How long without any tool call before we close the session implicitly. */
        const val INACTIVITY_TIMEOUT_MS: Long = 5L * 60_000L  // 5 minutes
        private const val ARGS_MAX_CHARS = 1_500
    }
}
