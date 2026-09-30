package com.aura.mcp.server

import com.aura.mcp.bridge.AnnotationGroup
import com.aura.mcp.bridge.AppLookupResult
import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.ContactResolution
import com.aura.mcp.bridge.ContactsBridge
import com.aura.mcp.bridge.DeepLinkBridge
import com.aura.mcp.bridge.DeepLinkCatalog
import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.DeviceEvent
import com.aura.mcp.bridge.DeviceStatus
import com.aura.mcp.bridge.FileSearchResult
import com.aura.mcp.bridge.FilesBridge
import com.aura.mcp.bridge.McpAuditLogger
import com.aura.mcp.bridge.NoOpAuditLogger
import com.aura.mcp.bridge.NoOpSessionLogSink
import com.aura.mcp.bridge.OpenUriResult
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.PerceptionResult
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.ScrollDirection
import com.aura.mcp.bridge.SessionLogSink
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.UiTreeSnapshot
import com.aura.mcp.bridge.UriResolution
import com.aura.mcp.bridge.VolumeDirection
import com.aura.mcp.bridge.WebSearchBridge
import com.aura.mcp.bridge.WebSearchResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * GP1 — audit/session/observer sinks must be per-SERVER, not process-global.
 *
 * Two servers are built per process: the external WebRTC server (real audit +
 * forensic-session sinks) and, per agent run, an in-process server built with
 * NO sinks. Under the old global-`var` design, starting an agent run clobbered
 * the external server's sinks to no-op, silently disabling its audit + forensic
 * trail until the WebRTC client reconnected.
 *
 * These tests build the second (in-process) server and assert the FIRST server
 * still owns its own sinks — a dispatch-free proof, since [Server.sinks] is what
 * [scopedTool] captures at registration time into every handler closure.
 */
class AuditSinkPerServerTest {

    private class RecordingAuditLogger : McpAuditLogger {
        val calls = CopyOnWriteArrayList<String>()
        override fun log(
            toolName: String,
            tokenId: String?,
            success: Boolean,
            scopeDenied: Boolean,
            durationMs: Long,
            errorSummary: String?,
        ) {
            calls += toolName
        }
    }

    private class RecordingSessionSink : SessionLogSink {
        val ended = CopyOnWriteArrayList<String>()
        override fun onToolStart(toolName: String, argsJson: String?, tokenId: String?, agentLabel: String?) = Unit
        override fun onToolEnd(toolName: String, success: Boolean, outputSummary: String, durationMs: Long) = Unit
        override fun endSession(reason: String) { ended += reason }
    }

    private fun buildServer(
        name: String,
        audit: McpAuditLogger = NoOpAuditLogger,
        session: SessionLogSink = NoOpSessionLogSink,
    ): Server = McpServerBuilder.build(
        serverName = name,
        version = "0.0.0",
        deviceBridge = InertDeviceBridge,
        screenshotBridge = InertScreenshotBridge,
        uiTreeBridge = InertUiTreeBridge,
        perceptionBridge = InertPerceptionBridge,
        webSearchBridge = InertWebSearchBridge,
        deepLinkBridge = InertDeepLinkBridge,
        notificationBridge = InertNotificationBridge,
        mediaBridge = InertMediaBridge,
        systemIntentBridge = InertSystemIntentBridge,
        // Build the FULL tool set (contacts + files) so the process-global
        // RegisteredToolNames accumulator is complete regardless of test order —
        // otherwise a partial build here would starve ToolNameListsTest.
        contactsBridge = InertContactsBridge,
        filesBridge = InertFilesBridge,
        browserBridge = InertBrowserBridge,
        auditLogger = audit,
        sessionLogSink = session,
    )

    @Test
    fun `an in-process build does not clobber the external server audit sink`() {
        val externalAudit = RecordingAuditLogger()
        val external = buildServer("gp1-external", audit = externalAudit)
        assertSame(externalAudit, external.sinks().audit, "external server should own its audit sink")

        // An agent run builds a second server with NO audit sink (default no-op).
        val inProcess = buildServer("gp1-inprocess")

        // GP1: the external server's audit trail must survive the agent run.
        assertSame(
            externalAudit,
            external.sinks().audit,
            "starting an agent run must not disable the external audit trail",
        )
        // The in-process server has its own distinct bundle.
        assertSame(NoOpAuditLogger, inProcess.sinks().audit)
        assertNotSame(external.sinks(), inProcess.sinks(), "servers must not share a sink bundle")
    }

    @Test
    fun `each server binds its own forensic session sink`() {
        val externalSession = RecordingSessionSink()
        val external = buildServer("gp1-sess-external", session = externalSession)
        assertSame(externalSession, external.sinks().session)

        buildServer("gp1-sess-inprocess") // no session sink

        assertSame(
            externalSession,
            external.sinks().session,
            "an in-process build must not clobber the external forensic session sink",
        )
    }

    // ── inert fakes: registration never dispatches, so no method is ever called ──

    private object InertDeviceBridge : DeviceBridge {
        override fun performTap(x: Int, y: Int): Boolean = error("not dispatched")
        override fun performHome(): Boolean = error("not dispatched")
        override fun performBack(): Boolean = error("not dispatched")
        override fun adjustVolume(direction: VolumeDirection): Boolean = error("not dispatched")
        override fun getDeviceStatus(): DeviceStatus = error("not dispatched")
        override fun performDoubleTap(x: Int, y: Int): Boolean = error("not dispatched")
        override fun performLongPress(x: Int, y: Int, durationMs: Long): Boolean = error("not dispatched")
        override fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = error("not dispatched")
        override fun performScroll(direction: ScrollDirection): Boolean = error("not dispatched")
        override fun performRecents(): Boolean = error("not dispatched")
        override fun pressEnter(): Boolean = error("not dispatched")
        override fun typeText(text: String): Boolean = error("not dispatched")
        override fun restoreKeyboard(): Boolean = error("not dispatched")
        override fun launchApp(packageName: String): Boolean = error("not dispatched")
        override fun lookupApp(query: String): AppLookupResult = error("not dispatched")
    }

    private object InertScreenshotBridge : ScreenshotBridge {
        override fun isReady(): Boolean = error("not dispatched")
        override fun requestPermission(): Boolean = error("not dispatched")
        override suspend fun captureBase64Png(): CaptureResult = error("not dispatched")
    }

    private object InertUiTreeBridge : UiTreeBridge {
        override suspend fun snapshot(): UiTreeSnapshot = error("not dispatched")
        override suspend fun drainEvents(timeoutMs: Long, maxEvents: Int): List<DeviceEvent> = error("not dispatched")
    }

    private object InertPerceptionBridge : PerceptionBridge {
        override suspend fun detectElements(
            pngBytes: ByteArray,
            withOcr: Boolean,
            withAnnotatedImage: Boolean,
        ): PerceptionResult = error("not dispatched")

        override suspend fun drawAnnotations(
            pngBytes: ByteArray,
            groups: List<AnnotationGroup>,
            maxLongSidePx: Int,
        ): String? = error("not dispatched")
    }

    private object InertWebSearchBridge : WebSearchBridge {
        override fun isConfigured(): Boolean = error("not dispatched")
        override suspend fun search(query: String, maxResults: Int, topic: String): WebSearchResult =
            error("not dispatched")
    }

    private object InertDeepLinkBridge : DeepLinkBridge {
        override suspend fun listDeepLinks(packageName: String): DeepLinkCatalog = error("not dispatched")
        override fun resolveUri(uri: String): UriResolution = error("not dispatched")
        override fun openUri(uri: String, packageName: String?): OpenUriResult = error("not dispatched")
    }

    private object InertContactsBridge : ContactsBridge {
        override suspend fun resolveContact(name: String): ContactResolution = error("not dispatched")
    }

    private object InertFilesBridge : FilesBridge {
        override suspend fun findFiles(query: String?, kind: String?, limit: Int): FileSearchResult =
            error("not dispatched")
        override fun openFile(uri: String): OpenUriResult = error("not dispatched")
    }
}
