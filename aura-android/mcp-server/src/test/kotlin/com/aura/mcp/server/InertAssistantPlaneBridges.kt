package com.aura.mcp.server

import com.aura.mcp.bridge.BrowserAction
import com.aura.mcp.bridge.BrowserBridge
import com.aura.mcp.bridge.BrowserCapture
import com.aura.mcp.bridge.ExtractedRow
import com.aura.mcp.bridge.BrowserPage
import com.aura.mcp.bridge.BrowserResult
import com.aura.mcp.bridge.BrowserSessionMode
import com.aura.mcp.bridge.FindOutcome
import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.MediaCommand
import com.aura.mcp.bridge.MediaCommandResult
import com.aura.mcp.bridge.MediaSessionSnapshot
import com.aura.mcp.bridge.NotificationBridge
import com.aura.mcp.bridge.NotificationOpResult
import com.aura.mcp.bridge.NotificationSnapshot
import com.aura.mcp.bridge.SpecialAccessState
import com.aura.mcp.bridge.SystemIntentBridge
import com.aura.mcp.bridge.SystemIntentResult
import com.aura.mcp.bridge.SystemIntentSpec

/**
 * Shared inert fakes for the assistant-plane bridges (notifications, media,
 * system intents) used by every test that builds the real server but never
 * dispatches these tools.
 */
internal object InertNotificationBridge : NotificationBridge {
    override fun accessState(): SpecialAccessState = error("not dispatched")
    override suspend fun activeNotifications(): List<NotificationSnapshot> = error("not dispatched")
    override suspend fun fireAction(key: String, actionTitle: String, replyText: String?): NotificationOpResult =
        error("not dispatched")
    override suspend fun dismiss(key: String): NotificationOpResult = error("not dispatched")
}

internal object InertMediaBridge : MediaBridge {
    override fun accessState(): SpecialAccessState = error("not dispatched")
    override suspend fun activeSessions(): List<MediaSessionSnapshot> = error("not dispatched")
    override suspend fun sendCommand(packageName: String?, command: MediaCommand): MediaCommandResult =
        error("not dispatched")
}

internal object InertSystemIntentBridge : SystemIntentBridge {
    override fun dispatch(spec: SystemIntentSpec): SystemIntentResult = error("not dispatched")
}

/**
 * Inert browser bridge. Registration never dispatches, so nothing here is ever
 * called — but it MUST be passed by every test that builds the real server:
 * [RegisteredToolNames] is process-global and additive, so whichever test builds
 * first defines the ground-truth tool set. Omitting this in one call site makes
 * the browser_* tools invisible to the coverage tests depending on test order.
 */
internal object InertBrowserBridge : BrowserBridge {
    override fun isAvailable(mode: BrowserSessionMode): Boolean = error("not dispatched")
    override suspend fun open(url: String, mode: BrowserSessionMode, visible: Boolean): BrowserResult<BrowserPage> =
        error("not dispatched")
    override suspend fun read(mode: BrowserSessionMode): BrowserResult<BrowserPage> = error("not dispatched")
    override suspend fun act(
        mode: BrowserSessionMode,
        selector: String,
        action: BrowserAction,
        value: String?,
    ): BrowserResult<BrowserPage> = error("not dispatched")
    override suspend fun find(mode: BrowserSessionMode, text: String): BrowserResult<FindOutcome> =
        error("not dispatched")
    override suspend fun waitFor(
        mode: BrowserSessionMode,
        text: String?,
        timeoutMs: Long,
    ): BrowserResult<BrowserPage> = error("not dispatched")
    override suspend fun upload(
        mode: BrowserSessionMode,
        selector: String,
        fileUri: String,
    ): BrowserResult<BrowserPage> = error("not dispatched")
    override suspend fun extract(
        mode: BrowserSessionMode,
        fields: List<String>,
    ): BrowserResult<List<ExtractedRow>> = error("not dispatched")
    override suspend fun tabs(
        mode: BrowserSessionMode,
        action: com.aura.mcp.bridge.BrowserTabAction,
        url: String?,
        index: Int?,
    ): BrowserResult<com.aura.mcp.bridge.BrowserTabs> = error("not dispatched")
    override suspend fun handoff(
        mode: BrowserSessionMode,
        prompt: String,
    ): BrowserResult<com.aura.mcp.bridge.HandoffState> = error("not dispatched")
    override suspend fun handoffState(
        mode: BrowserSessionMode,
    ): BrowserResult<com.aura.mcp.bridge.HandoffState> = error("not dispatched")
    override suspend fun screenshot(mode: BrowserSessionMode): BrowserResult<BrowserCapture> =
        error("not dispatched")
    override suspend fun close(mode: BrowserSessionMode): BrowserResult<Unit> = error("not dispatched")
}
