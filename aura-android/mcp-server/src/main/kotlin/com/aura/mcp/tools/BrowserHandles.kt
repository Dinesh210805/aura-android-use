package com.aura.mcp.tools

import com.aura.mcp.bridge.BrowserPage

/**
 * Generation-stamped `el_id` → selector map for one browser session.
 *
 * This is the browser analogue of `ScreenGeneration` in `mcp/cache/`. Every page
 * read mints a fresh generation; a handle quoted from an older one is refused.
 *
 * The bug this exists to prevent is the costly one: the page navigated, the model
 * still holds `el_id: 3` from the previous page, and "click 3" now hits whatever
 * happens to be third on the new page. That is the false-success failure mode —
 * the agent reports it clicked Checkout, and nothing was bought. Forcing a re-read
 * costs one cheap call; acting blind costs the user's trust.
 *
 * Thread-safe: MCP dispatch and the on-device agent can both be in flight.
 */
internal class BrowserHandles(
    private val defaultMaxTextChars: Int = BrowserPageModel.DEFAULT_MAX_TEXT_CHARS,
    private val defaultMaxElements: Int = BrowserPageModel.DEFAULT_MAX_ELEMENTS,
) {
    private val lock = Any()

    private var currentGeneration: Int = 0
    private var handles: Map<Int, String> = emptyMap()

    /** Project [page] into a fresh snapshot and adopt its handle map. */
    fun mint(
        page: BrowserPage,
        maxTextChars: Int = defaultMaxTextChars,
        maxElements: Int = defaultMaxElements,
    ): PageSnapshot = synchronized(lock) {
        currentGeneration += 1
        val snapshot = BrowserPageModel.snapshot(
            page = page,
            generation = currentGeneration,
            maxTextChars = maxTextChars,
            maxElements = maxElements,
        )
        handles = snapshot.handles
        snapshot
    }

    /**
     * Resolve a model-supplied handle.
     *
     * [generation] is optional: clients that echo it get the staleness check, clients
     * that don't still work. Requiring it would break the simple call path for a
     * guarantee we can only offer as advice anyway.
     */
    fun resolve(elId: Int, generation: Int? = null): HandleResolution = synchronized(lock) {
        if (currentGeneration == 0) return HandleResolution.Unknown
        if (generation != null && generation != currentGeneration) {
            return HandleResolution.Stale(currentGeneration)
        }
        val selector = handles[elId] ?: return HandleResolution.Unknown
        HandleResolution.Resolved(selector)
    }

    /** Drop the map so a closed session can't be acted on. */
    fun clear() = synchronized(lock) {
        handles = emptyMap()
    }
}

internal sealed interface HandleResolution {
    data class Resolved(val selector: String) : HandleResolution

    /** The model is holding handles from a page we've already moved past. */
    data class Stale(val currentGeneration: Int) : HandleResolution

    /** No such handle on the current page (or nothing loaded yet). */
    data object Unknown : HandleResolution
}
