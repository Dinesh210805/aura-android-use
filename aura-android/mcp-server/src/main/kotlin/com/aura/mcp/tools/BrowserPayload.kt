package com.aura.mcp.tools

import com.aura.mcp.bridge.BrowserErrorKind
import com.aura.mcp.bridge.BrowserSessionMode
import com.aura.mcp.bridge.BrowserTab
import com.aura.mcp.bridge.HandoffState
import com.aura.mcp.bridge.HandoffEnd
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * JSON shaping for the `browser_*` tools.
 *
 * Split out from registration so the wire contract can be asserted directly — the
 * payload *is* the thing the model reasons over, so its shape carries as much risk
 * as the logic that produces it.
 *
 * Every field here is spent tokens. Optional fields are therefore **omitted when
 * uninteresting** rather than emitted as `null`/`false`: on a 60-element page,
 * writing `"value": null, "disabled": false` on every entry is pure waste against
 * the on-device agent's 30k TPM ceiling.
 */
internal object BrowserPayload {

    fun page(snapshot: PageSnapshot, mode: BrowserSessionMode): JsonObject = buildJsonObject {
        put("success", true)
        put("session", mode.wire)
        put("url", snapshot.url)
        put("title", snapshot.title)
        // The model echoes this back on browser_act so we can catch it acting on a
        // page we've already navigated away from.
        put("generation", snapshot.generation)
        put("text", snapshot.text)
        if (snapshot.textTruncated) put("text_truncated", true)
        put("total_elements", snapshot.totalElements)
        if (snapshot.elementsTruncated) put("elements_truncated", true)

        putJsonArray("elements") {
            for (el in snapshot.elements) {
                add(
                    buildJsonObject {
                        put("el_id", el.elId)
                        put("role", el.role)
                        put("label", el.label)
                        el.value?.let { put("value", it) }
                        if (el.disabled) put("disabled", true)
                    },
                )
            }
        }

        // A scratch browser can NEVER satisfy a login wall — it holds none of the
        // user's cookies. Saying so turns an infinite retry loop into one decision.
        if (snapshot.looksLikeLoginWall && mode == BrowserSessionMode.SCRATCH) {
            put("requires_session", true)
            put(
                "hint",
                "This page wants a login, and the scratch browser has none of the user's " +
                    "cookies. Retry the same URL with session=\"mine\" to use the user's " +
                    "real signed-in browser, or ask the user to sign in.",
            )
        }
    }

    /**
     * `browser_find` result: the page, plus what the search actually resolved to.
     *
     * Three outcomes the agent must be able to tell apart, because the right next move
     * differs for each: found-and-clickable (act on `found_el_id`), found-but-not-
     * interactive (it's a heading or a price — read it, don't try to click), and
     * not-found (search elsewhere, or the page hasn't loaded it).
     */
    fun found(
        snapshot: PageSnapshot,
        mode: BrowserSessionMode,
        query: String,
        matchedElId: Int?,
        scrolled: Boolean,
    ): JsonObject {
        val base = page(snapshot, mode)
        return buildJsonObject {
            for ((k, v) in base) put(k, v)
            put("query", query)
            put("found", matchedElId != null || snapshot.text.contains(query, ignoreCase = true))
            matchedElId?.let { put("found_el_id", it) }
            if (scrolled) put("required_scrolling", true)
            if (matchedElId == null) {
                put(
                    "hint",
                    "No clickable element matched '$query'. It may be plain text (read it from " +
                        "'text'), or the page may not have loaded it — try browser_wait.",
                )
            }
        }
    }

    /**
     * Structured rows from a list page.
     *
     * `total_found` and `truncated` are the load-bearing fields. Without them the model
     * reads twenty-five rows as "the whole page" and confidently tells the user there are
     * twenty-five products when there were two hundred — truncation the model cannot see is
     * worse than truncation.
     */
    fun extract(projection: BrowserExtractModel.Projection, mode: BrowserSessionMode): JsonObject =
        buildJsonObject {
            put("success", true)
            put("session", mode.name.lowercase())
            put("returned", projection.rows.size)
            put("total_found", projection.totalFound)
            put("truncated", projection.truncated)
            putJsonArray("rows") {
                projection.rows.forEach { row ->
                    add(
                        buildJsonObject {
                            put("text", row.text)
                            row.link?.let { put("link", it) }
                            if (row.fields.isNotEmpty()) {
                                put(
                                    "fields",
                                    buildJsonObject { row.fields.forEach { (k, v) -> put(k, v) } },
                                )
                            }
                        },
                    )
                }
            }
            put(
                "summary",
                if (projection.rows.isEmpty()) {
                    "No repeated list found on this page — it may not be a results page. " +
                        "Use browser_read instead."
                } else {
                    "Found ${projection.totalFound} items" +
                        if (projection.truncated) ", showing the first ${projection.rows.size}." else "."
                },
            )
        }

    /**
     * The open tabs, plus the active tab's page when the action changed which page is live.
     *
     * Nesting the page under `page` rather than flattening it keeps one rule true across
     * the whole browser plane: `el_id` values always arrive with the `generation` they
     * belong to, in the same object. Flattening would put a generation at the top level of
     * a result whose *subject* is the tab list, and the next `browser_act` would be
     * echoing a generation it never really read.
     */
    fun tabs(
        tabs: List<BrowserTab>,
        activeIndex: Int,
        snapshot: PageSnapshot?,
        mode: BrowserSessionMode,
    ): JsonObject = buildJsonObject {
        put("success", true)
        put("session", mode.wire)
        put("active_index", activeIndex)
        put("open_tabs", tabs.size)
        putJsonArray("tabs") {
            tabs.forEach { tab ->
                add(
                    buildJsonObject {
                        put("index", tab.index)
                        put("title", tab.title)
                        put("url", tab.url)
                        if (tab.active) put("active", true)
                    },
                )
            }
        }
        snapshot?.let { put("page", page(it, mode)) }
        put(
            "summary",
            when {
                tabs.isEmpty() -> "No tabs open. Use browser_open or browser_tabs action=\"open\"."
                else -> "${tabs.size} tab(s) open; tab $activeIndex is active. Every other " +
                    "browser tool acts on the active tab."
            },
        )
    }

    /**
     * A handoff, running or finished.
     *
     * `waiting_for_user` is the field the agent's loop turns on, and it is phrased as a
     * *state* rather than a success flag on purpose: a handoff in progress is neither a
     * success nor a failure, and a model handed `success: false` treats it as something to
     * retry — which here would mean opening a second window over the one the user is
     * typing into.
     */
    fun handoff(state: HandoffState, snapshot: PageSnapshot?, mode: BrowserSessionMode): JsonObject =
        buildJsonObject {
            put("success", true)
            put("session", mode.wire)
            put("waiting_for_user", state.handedOff)
            state.prompt?.let { put("prompt", it) }
            state.endedBy?.let { put("ended_by", it.name.lowercase()) }
            snapshot?.let { put("page", page(it, mode)) }
            put(
                "summary",
                when {
                    state.handedOff ->
                        "The user has the browser. Do NOT act on the page. Call " +
                            "browser_handoff with check=true every few seconds until " +
                            "waiting_for_user is false; browser_read still works if you want " +
                            "to watch. Never ask the user for a password."
                    state.endedBy == HandoffEnd.WINDOW_GONE ->
                        "The window closed without the user finishing. Check the page before " +
                            "assuming the step was completed — it may not have been."
                    else ->
                        "The user is done and the browser is yours again. The page may have " +
                            "changed completely — work from the page in this result, not from " +
                            "what you saw before the handoff."
                },
            )
        }

    fun failure(kind: BrowserErrorKind, message: String, mode: BrowserSessionMode): JsonObject =
        buildJsonObject {
            put("success", false)
            put("session", mode.wire)
            put("error_kind", kind.wire)
            put("error", message)
            hintFor(kind)?.let { put("hint", it) }
        }

    /** Stale handles get their own shape so the agent can branch on it structurally. */
    fun staleHandles(currentGeneration: Int, mode: BrowserSessionMode): JsonObject =
        buildJsonObject {
            put("success", false)
            put("session", mode.wire)
            put("error_kind", "stale_handles")
            put("stale_handles", true)
            put("current_generation", currentGeneration)
            put(
                "error",
                "The el_id you used belongs to an older page state; the page has changed since.",
            )
            put("hint", "Call browser_read again and pick an el_id from the fresh result.")
        }

    fun unknownHandle(elId: Int, mode: BrowserSessionMode): JsonObject = buildJsonObject {
        put("success", false)
        put("session", mode.wire)
        put("error_kind", "unknown_handle")
        put("error", "No element with el_id $elId on the current page.")
        put("hint", "Call browser_read to list the elements actually present.")
    }

    private fun hintFor(kind: BrowserErrorKind): String? = when (kind) {
        BrowserErrorKind.UNAVAILABLE ->
            "This browser engine isn't available on this device/build."
        BrowserErrorKind.INVALID_URL ->
            "Pass an absolute http(s) URL, e.g. https://example.com."
        BrowserErrorKind.LOAD_FAILED ->
            "Check the URL, or use web_search to find the right one."
        BrowserErrorKind.TIMEOUT ->
            "The page didn't settle in time. Retry once; if it fails again, try a different source."
        BrowserErrorKind.NO_PAGE ->
            "Call browser_open first — there is no page loaded in this session."
        BrowserErrorKind.REQUIRES_SESSION ->
            "Retry with session=\"mine\" to use the user's real signed-in browser."
        BrowserErrorKind.ACT_FAILED ->
            "The element may have moved or vanished. Call browser_read and retry with a fresh el_id."
        BrowserErrorKind.BAD_REQUEST ->
            "The arguments were wrong, so retrying as-is will fail the same way. Call browser_tabs " +
                "with action=\"list\" to see the valid indexes."
    }
}

/** Lowercase wire names, kept next to the payload code that emits them. */
internal val BrowserSessionMode.wire: String
    get() = name.lowercase()

internal val BrowserErrorKind.wire: String
    get() = name.lowercase()
