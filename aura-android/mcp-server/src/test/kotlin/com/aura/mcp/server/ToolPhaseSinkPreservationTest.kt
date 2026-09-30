package com.aura.mcp.server

import com.aura.mcp.bridge.AnnotationGroup
import com.aura.mcp.bridge.AppLookupResult
import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.DeepLinkBridge
import com.aura.mcp.bridge.DeepLinkCatalog
import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.DeviceEvent
import com.aura.mcp.bridge.DeviceStatus
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.NoOpAuditLogger
import com.aura.mcp.bridge.OpenUriResult
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.PerceptionResult
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.ScrollDirection
import com.aura.mcp.bridge.ToolPhaseSink
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.UiTreeSnapshot
import com.aura.mcp.bridge.UriResolution
import com.aura.mcp.bridge.VolumeDirection
import com.aura.mcp.bridge.WebSearchBridge
import com.aura.mcp.bridge.WebSearchResult
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression: the on-device agent connects through [InProcessMcpServer], which
 * calls [McpServerBuilder.build] WITHOUT a phase sink. Because `phaseSink` is a
 * process-global, a naive build would reset it to no-op — freezing the status-bar
 * verb during every on-device agent run (STT/TTS and Gemini Live drive_phone) and
 * silencing the external-MCP path too.
 *
 * A build that does not supply a phase sink must LEAVE the previously installed
 * one intact, so the in-process server's tool calls (which read the same global)
 * keep driving the chip.
 */
class ToolPhaseSinkPreservationTest {

    /** A distinct, non-no-op sink standing in for StatusToolPhaseSink. */
    private object RecordingSink : ToolPhaseSink {
        override fun onPhase(toolName: String, scope: McpScope, phase: ToolPhaseSink.Phase) = Unit
    }

    /** External/host server: installs a real chip-driving sink (like AssistantForegroundService). */
    private fun buildExternalServer(sink: ToolPhaseSink) {
        McpServerBuilder.build(
            serverName = "phase-sink-external",
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
            browserBridge = InertBrowserBridge,
            auditLogger = NoOpAuditLogger,
            toolPhaseSink = sink,
        )
    }

    /** In-process agent server: builds WITHOUT a phase sink, exactly like [InProcessMcpServer]. */
    private fun buildInProcessServer() {
        McpServerBuilder.build(
            serverName = "phase-sink-inprocess",
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
            browserBridge = InertBrowserBridge,
            auditLogger = NoOpAuditLogger,
            // toolPhaseSink intentionally omitted — mirrors InProcessMcpServer.connect
        )
    }

    @Test
    fun `in-process build without a sink preserves the installed phase sink`() {
        // The external server installs the real (chip-driving) sink at startup.
        buildExternalServer(RecordingSink)
        assertSame(RecordingSink, phaseSink, "external build should install the sink")

        // An agent run builds the in-process server WITHOUT a sink.
        buildInProcessServer()

        // It must NOT have clobbered the installed sink to no-op.
        assertSame(
            RecordingSink,
            phaseSink,
            "in-process build must not reset the process-global phase sink",
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
}
