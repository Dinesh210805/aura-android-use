package com.aura.aura_ui.agent

/**
 * Turn-0 "what is already open in the browser" note, the sibling of [ForegroundPreamble].
 *
 * ### Why (device report 2026-08-05)
 *
 * *"1st command 'open amazon', 2nd command 'search for an item' — it opened another tab, went
 * to amazon again, then searched. It could have done it in the same tab."*
 *
 * Half of that was lifetime: the browser bridge was rebuilt per voice command, so the tab was
 * genuinely gone (fixed by `AppBrowserBridge.shared`). But even with the tab alive, the model
 * would still have reopened it — every run starts with a fresh context, so command 2 has no
 * memory of command 1 and no reason to believe amazon.in is already loaded.
 *
 * `SYSTEM_PROMPT` already says *"browser_open reuses the SAME tab for the rest of the task…
 * do NOT call browser_open again"*, and that works **within** a run. Across runs there is
 * nothing to reuse from the model's point of view. So, exactly as [ForegroundPreamble] does
 * for the foreground app: make the answer free at turn 0 rather than hoping the model spends
 * a `browser_tabs list` call discovering it.
 *
 * ### Trust
 *
 * Page titles are chosen by the page, so they are attacker-influenced in precisely the way
 * an app label is. This note rides the USER channel next to the goal — never the system
 * prompt — and titles are flattened to a single line and length-capped so a crafted title
 * cannot forge a new instruction block.
 */
object BrowserPreamble {

    /** One open tab as the model needs to see it. */
    data class OpenTab(val title: String?, val url: String, val active: Boolean)

    /** Titles are page-controlled; keep them short enough to be a label, not a payload. */
    private const val MAX_TITLE_CHARS = 60

    /** Returns the note, or null when the browser has nothing open worth mentioning. */
    fun forTabs(tabs: List<OpenTab>): String? {
        val open = tabs.filter { it.url.isNotBlank() }
        if (open.isEmpty()) return null

        val lines = open.joinToString("; ") { tab ->
            val label = tab.title
                ?.replace(Regex("\\s+"), " ")   // flatten newlines: a title must not forge a line
                ?.trim()
                ?.take(MAX_TITLE_CHARS)
                ?.takeIf { it.isNotEmpty() }
            val who = if (label != null) "\"$label\" (${tab.url})" else tab.url
            if (tab.active) "$who [active]" else who
        }

        return "[open browser tabs] AURA's browser already has these open: $lines. " +
            "If your task continues from one of them, do NOT call browser_open again — it is " +
            "already loaded. Use browser_read to see the current page, or browser_find / " +
            "browser_act to work on it. Open a NEW tab only when you genuinely need two pages " +
            "at once."
    }
}
