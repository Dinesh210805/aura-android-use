package com.aura.mcp.bridge

/**
 * Port for reading the structured UI tree from Android's accessibility service,
 * plus draining accessibility events that have occurred since the last drain.
 *
 * Both operations are accessibility-derived and tend to share an underlying
 * extractor, so they live on the same bridge to keep the surface area
 * predictable.
 */
interface UiTreeBridge {
    /**
     * Take a snapshot of the currently visible UI tree.
     *
     * Returns a pre-serialised JSON string in [UiTreeSnapshot.payloadJson]
     * so the `:mcp-server` module never has to know the internal shape — the
     * `:app` adapter is free to evolve the schema without churn here.
     */
    suspend fun snapshot(): UiTreeSnapshot

    /**
     * Block for at most [timeoutMs] milliseconds collecting accessibility
     * events from the underlying observation buffer. Returns up to
     * [maxEvents] events; drains the buffer when called.
     *
     * Matches the contract of the Python `watch_device_events` tool: a
     * timeboxed batched read, not a true push-based stream. Implementations
     * may return early if [maxEvents] is reached before [timeoutMs] elapses.
     */
    suspend fun drainEvents(timeoutMs: Long, maxEvents: Int = 50): List<DeviceEvent>

}

data class UiTreeSnapshot(
    val ok: Boolean,
    /** Raw JSON the tool can pass straight to MCP `TextContent`. */
    val payloadJson: String,
)

data class DeviceEvent(
    /** Android event type name, e.g. `TYPE_WINDOW_STATE_CHANGED`. */
    val type: String,
    /** Package of the app that produced the event, empty string if unknown. */
    val packageName: String,
    /** Event timestamp in epoch milliseconds. */
    val timestampMs: Long,
    /** Short human-readable description, e.g. class name + text. */
    val description: String,
    /**
     * How much this event says about the screen having actually moved, as decided by
     * [com.aura.mcp.cache.ScreenActivity.classify]. Defaults to AMBIENT so any
     * producer that does not classify (tests, future transports) is treated as
     * "tells us nothing on its own" rather than as a false screen transition.
     */
    val changeClass: com.aura.mcp.cache.ScreenActivity.Class =
        com.aura.mcp.cache.ScreenActivity.Class.AMBIENT,
)
