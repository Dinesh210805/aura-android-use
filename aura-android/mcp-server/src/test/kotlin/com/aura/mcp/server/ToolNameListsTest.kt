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
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.OpenUriResult
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.PerceptionResult
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.ScrollDirection
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.UiTreeSnapshot
import com.aura.mcp.bridge.UriResolution
import com.aura.mcp.bridge.VolumeDirection
import com.aura.mcp.bridge.WebSearchBridge
import com.aura.mcp.bridge.WebSearchResult
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T4 — hand-maintained tool-name lists must never drift from the actually-registered
 * tool set. [RegisteredToolNames] is recorded at the single registration chokepoint
 * ([scopedTool]), so building the real server with inert fakes yields the ground-truth
 * name set. Adding a new tool without classifying it in every list fails HERE, loudly,
 * instead of fail-closed surprises (scope map) or silently missing settle/observe.
 */
class ToolNameListsTest {

    @BeforeTest
    fun buildServerOnce() {
        if (RegisteredToolNames.names.isEmpty()) buildTestServer()
    }

    @Test
    fun `the server registers a non-trivial tool set`() {
        assertTrue(RegisteredToolNames.names.size >= 30, "expected the full device tool set, got ${RegisteredToolNames.names}")
    }

    @Test
    fun `every registered tool is explicitly classified in the scope map`() {
        val unclassified = RegisteredToolNames.names - McpToolScopes.toolScopeMap.keys
        assertTrue(
            unclassified.isEmpty(),
            "tools registered but not classified in McpToolScopes.toolScopeMap (fail-closed default is a smell, classify explicitly): $unclassified",
        )
    }

    @Test
    fun `every scope map entry corresponds to a registered tool`() {
        val phantom = McpToolScopes.toolScopeMap.keys - RegisteredToolNames.names
        assertTrue(phantom.isEmpty(), "scope map names no registered tool (typo or removed tool): $phantom")
    }

    @Test
    fun `WRITE tools partition exactly into observed and documented pixel-free exemptions`() {
        val writeTools = RegisteredToolNames.names
            .filter { McpToolScopes.requiredScopeFor(it) == McpScope.WRITE }
            .toSet()
        assertEquals(
            writeTools,
            PostActionObservation.OBSERVED_TOOLS + PostActionObservation.UNOBSERVED_WRITE_TOOLS,
            "every WRITE tool must be in OBSERVED_TOOLS or explicitly exempted in UNOBSERVED_WRITE_TOOLS",
        )
        assertTrue(
            (PostActionObservation.OBSERVED_TOOLS intersect PostActionObservation.UNOBSERVED_WRITE_TOOLS).isEmpty(),
            "a tool cannot be both observed and exempt",
        )
    }

    @Test
    fun `observed tools reference only registered names`() {
        val phantom = PostActionObservation.OBSERVED_TOOLS - RegisteredToolNames.names
        assertTrue(phantom.isEmpty(), "OBSERVED_TOOLS names no registered tool: $phantom")
    }

    // ── inert fakes: registration never dispatches, so no method is ever called ──
    // Not private: [buildTestServer] below is shared with ToolDescriptionBudgetTest, which
    // measures what registration produced. One fixture, one server, one measurement.

    internal object FakeDeviceBridge : DeviceBridge {
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

    internal object FakeScreenshotBridge : ScreenshotBridge {
        override fun isReady(): Boolean = error("not dispatched")
        override fun requestPermission(): Boolean = error("not dispatched")
        override suspend fun captureBase64Png(): CaptureResult = error("not dispatched")
    }

    internal object FakeUiTreeBridge : UiTreeBridge {
        override suspend fun snapshot(): UiTreeSnapshot = error("not dispatched")
        override suspend fun drainEvents(timeoutMs: Long, maxEvents: Int): List<DeviceEvent> = error("not dispatched")
    }

    internal object FakePerceptionBridge : PerceptionBridge {
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

    internal object FakeWebSearchBridge : WebSearchBridge {
        override fun isConfigured(): Boolean = error("not dispatched")
        override suspend fun search(query: String, maxResults: Int, topic: String): WebSearchResult =
            error("not dispatched")
    }

    internal object FakeDeepLinkBridge : DeepLinkBridge {
        override suspend fun listDeepLinks(packageName: String): DeepLinkCatalog = error("not dispatched")
        override fun resolveUri(uri: String): UriResolution = error("not dispatched")
        override fun openUri(uri: String, packageName: String?): OpenUriResult = error("not dispatched")
    }

    internal object FakeContactsBridge : ContactsBridge {
        override suspend fun resolveContact(name: String): ContactResolution = error("not dispatched")
    }

    internal object FakeFilesBridge : FilesBridge {
        override suspend fun findFiles(query: String?, kind: String?, limit: Int): FileSearchResult =
            error("not dispatched")
        override fun openFile(uri: String): OpenUriResult = error("not dispatched")
    }
}

/**
 * Build the real server with inert fakes, recording every tool at the [scopedTool] chokepoint.
 *
 * Shared rather than inlined per test: `RegisteredToolNames` is process-global, so two tests
 * each building their own server would register everything twice and double every measurement
 * `ToolDescriptionBudgetTest` takes. One builder, guarded on emptiness by each caller.
 */
internal fun buildTestServer() {
    McpServerBuilder.build(
        serverName = "t4-test",
        version = "0.0.0",
        deviceBridge = ToolNameListsTest.FakeDeviceBridge,
        screenshotBridge = ToolNameListsTest.FakeScreenshotBridge,
        uiTreeBridge = ToolNameListsTest.FakeUiTreeBridge,
        perceptionBridge = ToolNameListsTest.FakePerceptionBridge,
        webSearchBridge = ToolNameListsTest.FakeWebSearchBridge,
        deepLinkBridge = ToolNameListsTest.FakeDeepLinkBridge,
        notificationBridge = InertNotificationBridge,
        mediaBridge = InertMediaBridge,
        systemIntentBridge = InertSystemIntentBridge,
        contactsBridge = ToolNameListsTest.FakeContactsBridge,
        filesBridge = ToolNameListsTest.FakeFilesBridge,
        browserBridge = InertBrowserBridge,
        auditLogger = com.aura.mcp.bridge.NoOpAuditLogger,
    )
}
