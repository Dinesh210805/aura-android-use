package com.aura.mcp.tools

import com.aura.mcp.bridge.DetectedElement

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

    fun render(
        elements: List<DetectedElement>,
        pkg: String,
        idle: Boolean,
        settled: Boolean,
        escalateReason: String? = null,
        offscreen: List<String> = emptyList(),
    ): Result? {
        val drawn = ScreenGrid.fit(elements) ?: return null
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
            appendLine(NOTE)
            appendLine(drawn.canvas)
            appendLine()
            append("som  in   flg  label (only if not on the grid)")
            for (row in drawn.rows.sortedBy { it.somId }) {
                // Wrappers are drawn and numbered but never listed: on a launcher home
                // screen 288 of 312 nodes are pure scaffolding, and listing them buries
                // the two dozen things the model can actually act on.
                if (!row.actionable()) continue
                append("\n")
                append(row.somId.toString().padEnd(5))
                append((row.parent?.toString() ?: "-").padEnd(5))
                append(row.flags.padEnd(5))
                if (!row.labelOnGrid) append(row.label)
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
