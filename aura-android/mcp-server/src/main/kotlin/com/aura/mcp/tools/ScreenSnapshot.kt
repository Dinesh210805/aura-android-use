package com.aura.mcp.tools

import com.aura.mcp.bridge.UiTreeBridge

/**
 * A full read of the current screen for the record, in the same text the model reads, that
 * changes nothing the model relies on.
 *
 * - Contract: never touches `PerceptionCache` (the som_ids the next `tap` resolves) or
 *   `LastSeenScreen` (the "CHANGES since your last look" baseline), so a read here can't renumber
 *   elements behind the model's back. Its som_ids are numbered fresh and are not tappable.
 *   Does not settle: the caller reads only once the screen has been quiet.
 * - Fails: null when the bridge answers `ok = false` (accessibility off) or throws.
 * - Why it lives here: the helpers it reuses are `internal` to `:mcp-server`, and `:app` (the
 *   agentcore screen ledger) can't name [ScreenSignature], so [Snapshot] carries plain keys.
 * - Change together: the `render` arguments in `registerReadScreenTool`, so both texts match.
 */
object ScreenSnapshot {

    /**
     * One screen as read.
     * - [contentKey] equal ⇒ nothing visible changed; [layoutKey] equal ⇒ same screen, data may
     *   differ. Both mean nothing when [blind] (WebView, game, canvas): compare by [pkg] instead.
     */
    data class Snapshot(
        val pkg: String,
        val text: String,
        val contentKey: Long,
        val layoutKey: Long,
        val blind: Boolean,
        /**
         * Every element's box, label and flags as a `ScreenFrame` JSON (`{"w","h","e":[[x1,y1,x2,y2,label,flags,class]…]}`),
         * for drawing the screen as a picture. Empty when there are no elements.
         */
        val frame: String = "",
    )

    suspend fun read(bridge: UiTreeBridge): Snapshot? {
        val tree = runCatching { bridge.snapshot() }.getOrNull()?.takeIf { it.ok } ?: return null
        return of(tree.payloadJson)
    }

    /** Pure: the snapshot of one tree payload. */
    internal fun of(payloadJson: String): Snapshot {
        val signature = ScreenSignature.of(payloadJson)
        val elements = UiTreeToElements.extract(payloadJson, minElements = 1)
        val meta = ScreenFrame.metaOf(payloadJson)
        val pkg = meta.pkg
        val text = if (elements.isEmpty()) null else ScreenPayload.render(
            elements = elements,
            pkg = pkg,
            idle = true,
            settled = true,
            escalateReason = ReadScreen.escalateReason(
                elementCount = elements.size,
                treeBlind = signature.treeBlind,
                hidesControls = TreeSufficiency.looksDegraded(
                    elements,
                    UiTreeHeuristics.summarize(payloadJson).screenHeightPx,
                ),
            ),
            offscreen = ScreenText.offscreen(payloadJson),
        )?.text
        return Snapshot(
            pkg = pkg,
            text = text ?: "SEEN: $pkg | (no readable elements)",
            contentKey = signature.content,
            layoutKey = signature.layout,
            blind = signature.treeBlind,
            frame = if (elements.isEmpty()) "" else ScreenFrame.encode(elements, meta, idle = true, settled = true),
        )
    }
}
