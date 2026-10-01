package com.aura.mcp.tools

import java.util.concurrent.atomic.AtomicReference

/**
 * Spec 2026-07-31 — the one-shot file arming behind `browser_upload`.
 *
 * A `<input type="file">` does nothing in a WebView without
 * `WebChromeClient.onShowFileChooser`. The trap is that the callback fires whenever **any**
 * page opens a file picker — including a page AURA merely visited, which the user never
 * agreed to give a file to. Implementing upload by simply answering every chooser would
 * hand every site AURA browses a way to make the browser attach a file.
 *
 * So the file is never chosen in response to the page. The agent [arm]s exactly one file
 * for exactly one chooser; [consume] clears it, and an unarmed chooser gets nothing (the
 * engine then cancels it). Re-arming REPLACES rather than queues, because a queued file
 * from an abandoned upload could otherwise fire later against an unrelated page.
 *
 * Atomic because `onShowFileChooser` arrives on the main thread while the tool call that
 * armed it runs on a dispatcher — a plain field would be a race on a security boundary.
 */
class PendingUpload {

    private val armed = AtomicReference<String?>(null)

    /**
     * Offer exactly one file to the next chooser. Replaces anything previously armed.
     *
     * - Contract: returns false, and leaves nothing armed, unless [uri] passes [isMediaStoreUri].
     * - Why: the chooser callback reads the URI with AURA's own permissions, so a `file://`
     *   path or an AURA provider URI would upload AURA's private storage (pairing tokens
     *   included) to the page.
     */
    fun arm(uri: String): Boolean {
        val ok = isMediaStoreUri(uri)
        armed.set(uri.takeIf { ok })
        return ok
    }

    /** Take the armed file, clearing it. Null means: cancel this chooser. */
    fun consume(): String? = armed.getAndSet(null)
}

/** True for `content://media/…`, the only URIs `find_files` returns. */
fun isMediaStoreUri(uri: String): Boolean {
    val parsed = runCatching { java.net.URI(uri.trim()) }.getOrNull() ?: return false
    return parsed.scheme.equals("content", ignoreCase = true) && parsed.rawAuthority == "media"
}
