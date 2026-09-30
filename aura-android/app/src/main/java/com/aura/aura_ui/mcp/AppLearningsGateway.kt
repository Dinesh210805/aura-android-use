package com.aura.aura_ui.mcp

import android.content.Context
import com.aura.aura_ui.agent.mcpbridge.hooks.PostActionObservationReader
import com.aura.aura_ui.agent.memory.AppPackageResolver
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.agent.memory.EncryptedLearningsStore
import com.aura.aura_ui.agent.memory.LearningsAccumulator
import com.aura.aura_ui.agent.memory.LearningsStore
import com.aura.aura_ui.agent.memory.MemoryPrefs
import com.aura.mcp.bridge.LearningsGateway
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/**
 * The MCP lane of shared memory (spec 2026-07-17): external clients' tool calls
 * stream through one [LearningsAccumulator] per phone-side session (reset at each
 * `end_session` — the same boundary SessionLogSink uses; the daemon serializes tool
 * calls, so the stream is sequential). Verified paths require the explicit
 * `outcome="success"` arg — unlike the agent hook lane, an allowed `end_session`
 * alone is NOT structural proof here, so the `end_session` call itself is never
 * streamed as a step. Gated by the same MemoryPrefs toggle as the on-device agent;
 * every entry point is fail-soft (memory is never load-bearing).
 */
class AppLearningsGateway private constructor(
    private val storeProvider: () -> LearningsStore?,
    private val appVersionOf: (String) -> String,
) : LearningsGateway {

    private var acc: LearningsAccumulator? = null

    private fun accumulator(): LearningsAccumulator? {
        acc?.let { return it }
        val store = runCatching { storeProvider() }.getOrNull() ?: return null
        return LearningsAccumulator(store, goal = null, defaultSource = "mcp-client", appVersionOf = appVersionOf)
            .also { acc = it }
    }

    override fun onToolExecuted(
        toolName: String,
        args: JsonObject?,
        result: CallToolResult,
        failed: Boolean,
        clientLabel: String?,
    ) {
        // Verification comes ONLY from onSessionEnd's explicit outcome arg —
        // never from the end_session dispatch itself (see class doc).
        if (toolName == "end_session") return
        val a = accumulator() ?: return
        val observation = runCatching { PostActionObservationReader.observation(result) }.getOrNull()
        a.onStep(
            toolName = toolName,
            args = args ?: JsonObject(emptyMap()),
            failed = failed,
            screenChanged = observation?.let { PostActionObservationReader.screenChanged(it) },
            foregroundApp = observation?.let { PostActionObservationReader.foregroundApp(it) },
            sourceLabel = clientLabel,
            topLabel = observation?.let { PostActionObservationReader.topLabel(it) },
        )
    }

    override suspend fun onSessionEnd(reason: String, outcome: String?, goalType: String?) {
        val a = acc ?: return
        if (outcome == "success") a.markVerified()
        runCatching { a.flush(reasonText = reason, explicitGoalType = goalType) }
        acc = null // next tool call starts a fresh session
    }

    override suspend fun hintsFor(appPackage: String): String {
        val store = runCatching { storeProvider() }.getOrNull() ?: return ""
        // launch_app may pass a human name ("WhatsApp") — resolve to a package;
        // fall back to the raw string (it may already be a package id).
        val pkg = AppPackageResolver.resolve(appPackage, listOf(appPackage))
        val target = if (pkg == "unknown") appPackage else pkg
        // Installed version lets the renderer flag paths recorded on older builds.
        val installed = runCatching { appVersionOf(target) }.getOrDefault("").takeIf { it.isNotBlank() }
        return runCatching { store.appHints(target, installed) }.getOrDefault("")
    }

    companion object {
        /** Production factory (kept as `invoke` so call sites read like a constructor). */
        operator fun invoke(context: Context): AppLearningsGateway {
            val app = context.applicationContext
            return AppLearningsGateway(
                storeProvider = provider@{
                    if (!MemoryPrefs(app).learningsEnabled) return@provider null
                    EncryptedLearningsStore(EncryptedJsonStore(app, "aura_learnings", failClosedWhenUnencrypted = true))
                },
                appVersionOf = { pkg ->
                    runCatching {
                        app.packageManager.getPackageInfo(pkg, 0).versionName.orEmpty()
                    }.getOrDefault("")
                },
            )
        }

        /** JVM-test factory: fixed store, no Android context. */
        fun forTest(store: LearningsStore): AppLearningsGateway =
            AppLearningsGateway(storeProvider = { store }, appVersionOf = { "" })
    }
}
