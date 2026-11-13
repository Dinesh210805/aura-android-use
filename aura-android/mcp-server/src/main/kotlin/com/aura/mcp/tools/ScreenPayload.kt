package com.aura.mcp.tools

import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.cache.PerceptionCache

/**
 * Exactly what `read_screen` sends the model: the character-grid picture of the screen,
 * then a table of the things it can act on.
 *
 * Ported from `screenview.py:agent_payload`, whose docstring reads *"Exactly what would
 * be sent to the agent for this frame. Nothing else."* — the desktop workbench where
 * this format was designed against a live device with a token counter attached. This is
 * that format, now actually sent.
 *
 * ### Why not JSON
 *
 * A positional JSON array states position and hides structure: from `[40,420,600,900]`
 * the model must infer that element 12 sits inside card 8 by comparing bounds pairwise
 * across every element on screen. The grid shows containment, adjacency and reading
 * order the way a screenshot does — and costs less, because a drawn label needs no
 * coordinates at all.
 *
 * Coordinates are absent on purpose. The model taps by som_id and
 * [com.aura.mcp.cache.PerceptionCache] resolves it to full-resolution pixels
 * server-side, so a pixel value in the payload would be something the model can only
 * misuse.
 *
 * ### Each label is written once
 *
 * A label drawn on the grid does not repeat in the table. Measured on the workbench,
 * writing every label in both places cost **43% of the payload for no added
 * information** — so the table's label column carries only the ones that could not be
 * drawn inside their box.
 */
internal object ScreenPayload {

    /** The rendered payload, plus the renumbered elements the caller must cache. */
    data class Result(
        val text: String,
        /**
         * Elements carrying the som_ids **printed on the grid**.
         *
         * [ScreenGrid] renumbers by area (largest first) so the numbering is stable and
         * reading-ordered rather than an accident of tree traversal. The caller MUST
         * update `PerceptionCache` with these and not with the input list, or every
         * `tap(som_id)` lands on a different element than the one the model saw.
         */
        val elements: List<DetectedElement>,
        /** The subset of [elements] numbered on the grid — what the annotated image draws. */
        val marked: List<DetectedElement> = emptyList(),
    )

    /** How many labels the SEEN line names — enough to identify a screen, not describe it. */
    private const val MAX_SEEN_LABELS = 4

    /** Longest label worth putting in a caption; anything longer is body text, not a name. */
    private const val MAX_SEEN_LABEL = 32

    private const val NOTE =
        "numbers are som_id; N+k = N plus k more sharing those bounds; " +
            "`in` = som_id of the smallest element containing this one; " +
            "labels are drawn on the grid, listed below only when they would not fit"

    /**
     * Build the payload. Null when there is nothing to draw — the caller must say so
     * rather than send an empty grid, which reads as "the screen is blank".
     *
     * @param escalateReason non-null when the tree could not describe this screen; the
     *   line is placed LAST because that is the freshest position in the model's
     *   context and this is the one instruction that overrides everything above it.
     */
    /**
     * `SEEN: <package> | label | label | label` — the screen in the fewest words that still
     * name it, for a human-facing caption.
     *
     * Picks the LARGEST labelled elements rather than the first few: headers, titles and
     * primary actions are big, while the first element in tree order is usually a
     * back-arrow or a status-bar fragment. Labels are trimmed and de-duplicated because a
     * list screen repeats the same word down the page.
     */
    internal fun seenLine(pkg: String, elements: List<DetectedElement>): String {
        val labels = elements
            .asSequence()
            .filter { it.label.isNotBlank() }
            .sortedByDescending { (it.bbox.x2 - it.bbox.x1).toLong() * (it.bbox.y2 - it.bbox.y1) }
            .map { it.label.trim().replace('|', '/') }
            .filter { it.length in 2..MAX_SEEN_LABEL }
            .distinct()
            .take(MAX_SEEN_LABELS)
            .toList()
        return (listOf("SEEN: ${pkg.ifBlank { "?" }}") + labels).joinToString(" | ")
    }

    /**
     * What to print below the header.
     *
     * - [Full]: the grid and the table.
     * - [Changes]: only the rows that are new or changed since [Changes.previous], then every
     *   other actionable row by som_id and name. Falls back to [Full] when there is nothing to
     *   compare with, or when most of the screen changed (a diff would be longer than the picture).
     * - [Query]: only the rows whose label contains [Query.text], plus off-screen text matching it.
     * - [Revealed]: after a scroll, the rows whose label was not visible before, then the rest by
     *   name. Matched by label, not fingerprint: a scroll moves every row, so by fingerprint
     *   everything would read as changed.
     */
    sealed interface View {
        data object Full : View
        data class Changes(val previous: List<DetectedElement>) : View
        data class Query(val text: String) : View
        data class Revealed(val seenLabels: Set<String>) : View
    }

    /** Unchanged rows listed by name in a [View.Changes] payload, at most. */
    private const val MAX_UNCHANGED = 40

    fun render(
        elements: List<DetectedElement>,
        pkg: String,
        idle: Boolean,
        settled: Boolean,
        escalateReason: String? = null,
        offscreen: List<String> = emptyList(),
        view: View = View.Full,
        /** Keeps som_ids the same across looks at one screen; null numbers by area every look. */
        ids: com.aura.mcp.cache.SomIds? = null,
    ): Result? {
        val drawn = ScreenGrid.fit(elements, ids, pkg) ?: return null
        val screenW = elements.maxOf { it.bbox.x2 }
        val screenH = elements.maxOf { it.bbox.y2 }

        val text = buildString {
            // A plain summary of WHAT IS ON THIS SCREEN, ahead of the grid.
            //
            // The rest of this payload is a coordinate grid and a flag table — precise, and
            // unreadable by anyone but the model. The on-screen status strip needs to tell a
            // person what AURA is looking at, and the labels to say it with exist only here.
            // Emitting them as a small delimited line keeps the prose (and resolving a
            // package name to an app name, which only the app side can do) out of the server.
            //
            // Costs ~12 tokens per read. Deliberate: it is also a one-line answer to "where
            // am I" that the model would otherwise reconstruct from the grid every turn.
            appendLine(seenLine(pkg, elements))
            append("SCREEN ${screenW}x$screenH  ")
            append(pkg.ifBlank { "?" })
            append("  ")
            // Two different facts, both worth stating: BUSY means the screen was still
            // moving when the cap cut us off, so this picture may be mid-transition.
            append(if (idle) "IDLE" else "BUSY")
            if (!settled) append(" (still moving when the settle cap expired)")
            append("  ${drawn.rows.size} elements in ${drawn.boxCount} boxes")
            if (drawn.frameCount > 0) append("  (${drawn.frameCount} full-screen frame(s) not drawn)")
            appendLine()
            // Covers are recorded as bounds (som_ids are assigned after occlusion runs); map
            // them back to this numbering, preferring an interactive element on shared bounds.
            val somByBox = drawn.rows.sortedBy { it.element.interactive }
                .associate { it.element.bbox to it.somId }
            fun row(r: ScreenGrid.Row, withLabel: Boolean) {
                append("\n")
                append(r.somId.toString().padEnd(5))
                append((r.parent?.toString() ?: "-").padEnd(5))
                append(r.flags.padEnd(5))
                if (withLabel) append(r.label)
                r.element.coveredBy?.let { cover ->
                    if (withLabel) append(' ')
                    append("under ").append(somByBox[cover]?.toString() ?: "?")
                }
            }
            // Wrappers are drawn and numbered but never listed: on a launcher home
            // screen 288 of 312 nodes are pure scaffolding, and listing them buries
            // the two dozen things the model can actually act on.
            val listed = drawn.rows.sortedBy { it.somId }.filter { it.actionable() }
            val diff = when (view) {
                is View.Changes -> changesAgainst(view.previous, listed)
                // A list's own label is borrowed from its rows, so it changes on every scroll; the
                // list itself did not come into view.
                is View.Revealed -> listed.filter { it.label !in view.seenLabels && !it.element.scrollable } to emptyList()
                else -> null
            }
            when {
                view is View.Query -> {
                    val q = view.text.trim().lowercase()
                    val hits = listed.filter { q in it.label.lowercase() }
                    if (hits.isEmpty()) {
                        append("NO on-screen element is labelled like \"${view.text}\".")
                    } else {
                        append("MATCHES for \"${view.text}\" (som_ids are current):\nsom  in   flg  label")
                        hits.forEach { row(it, withLabel = true) }
                    }
                    val off = offscreen.filter { q in it.lowercase() }
                    if (off.isNotEmpty()) {
                        append("\n\nOFFSCREEN matches (exist but not visible; scroll to reach): ")
                        append(off.joinToString(" | "))
                    }
                    if (escalateReason != null) append("\n\nESCALATE to perceive_screen — $escalateReason")
                    return@buildString
                }
                diff != null -> {
                    val (changed, gone) = diff
                    if (view is View.Revealed) {
                        if (changed.isEmpty()) {
                            append("Nothing new came into view.")
                        } else {
                            append("CAME INTO VIEW (som_ids are current; tap them directly):\nsom  in   flg  label")
                            changed.forEach { row(it, withLabel = true) }
                        }
                    } else if (changed.isEmpty() && gone.isEmpty()) {
                        append("NO CHANGES since your last look.")
                    } else {
                        append(
                            if (ids != null) "CHANGES since your last look; som_ids of unchanged elements are the same."
                            else "CHANGES since your last look; som_ids are renumbered every look, use these.",
                        )
                        if (changed.isNotEmpty()) {
                            append("\nnew or changed:\nsom  in   flg  label")
                            changed.forEach { row(it, withLabel = true) }
                        }
                        if (gone.isNotEmpty()) append("\ngone: ").append(gone.joinToString(" | "))
                    }
                    // Only what can be acted on: every element has some name (down to its class), so
                    // listing all of them repeats the layout scaffolding the grid exists to hide.
                    val rest = listed.filterNot { it in changed }
                        .filter { it.element.interactive || it.element.editable || it.element.scrollable }
                    if (rest.isNotEmpty()) {
                        append(if (view is View.Revealed) "\nstill in view: " else "\nunchanged: ")
                        append(rest.take(MAX_UNCHANGED).joinToString(" · ") { "${it.somId} ${it.label}" })
                        if (rest.size > MAX_UNCHANGED) append(" (+${rest.size - MAX_UNCHANGED} more; read_screen for all)")
                    }
                }
                else -> {
                    if (view is View.Changes) appendLine("(most of the screen changed, so here is all of it)")
                    appendLine(NOTE)
                    appendLine(drawn.canvas)
                    appendLine()
                    append("som  in   flg  label (only if not on the grid)")
                    listed.forEach { row(it, withLabel = !it.labelOnGrid) }
                }
            }
            if (offscreen.isNotEmpty()) {
                append("\n\nOFFSCREEN (exists but not visible; no som_id, cannot be tapped): ")
                append(offscreen.joinToString(" | "))
            }
            if (escalateReason != null) {
                append("\n\nESCALATE to perceive_screen — $escalateReason")
            }
        }
        val elements = drawn.rows.map { it.element }
        val marked = drawn.marked.toSet()
        return Result(text, elements, elements.filter { it.somId in marked })
    }

    /**
     * Rows of [listed] that were not on [previous] (new, or changed in label, flags or place),
     * and the labels on [previous] that are gone. Null when the diff would not be worth reading:
     * nothing to compare with, or more than half the rows changed.
     */
    private fun changesAgainst(
        previous: List<DetectedElement>,
        listed: List<ScreenGrid.Row>,
    ): Pair<List<ScreenGrid.Row>, List<String>>? = with(PerceptionCache) {
        if (previous.isEmpty()) return null
        // Covered-ness counts as a change: a popup landing on a button changes nothing about the
        // button itself, but it changes whether a tap on it works.
        fun DetectedElement.key() = fingerprint() to (coveredBy != null)
        val before = previous.map { it.key() }.toSet()
        val now = listed.map { it.element.key() }.toSet()
        val changed = listed.filter { it.element.key() !in before }
        val gone = previous
            .filter { it.label.isNotBlank() && it.key() !in now }
            .map { it.label }
            .filterNot { label -> listed.any { it.label == label } }
            .distinct()
        if (changed.size + gone.size > maxOf(4, listed.size / 2)) null else changed to gone
    }

    private fun ScreenGrid.Row.actionable(): Boolean =
        label.isNotBlank() || element.interactive || element.scrollable || element.editable

    /**
     * The part of a payload that is identical across two looks at one unchanged screen.
     *
     * `ActionGuard`'s loop detection hashes a grounding result to notice the agent
     * repeating itself. The `SCREEN …` header carries `IDLE`/`BUSY`, which flips between
     * two looks at the SAME still screen — hashing the whole payload would therefore
     * report two different screens and no loop would ever be detected. Everything below
     * the header is a pure function of the elements.
     */
    fun stablePart(payload: String): String =
        payload.lineSequence().filterNot { it.startsWith("SCREEN ") }.joinToString("\n")

    /** Whether [text] looks like one of these payloads rather than JSON. */
    fun looksLikePayload(text: String): Boolean = text.lineSequence().any { it.startsWith("SCREEN ") }
}
