package com.aura.aura_ui.agentcore

import com.aura.aura_ui.uistream.ScreenIdle
import com.aura.mcp.tools.ScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Records every screen a run passes through, whether or not the model reads it: after any
 * accessibility event, once the screen has been still for [quietMs] (or has kept moving for
 * [busyMs]), it reads the screen once and hands it to [onScreen].
 *
 * - Contract: one per turn, started at the turn's first phone tool call and cancelled when the
 *   turn ends ([run] loops until its coroutine is cancelled). [onScreen] returns true when the
 *   screen was new; after [MAX_PASSED] new screens it calls [onLimit] once and reads no more.
 * - Why events and not `ScreenActivity.meaningfulCount`: that counter ignores scrolls, text
 *   edits and list updates, which are exactly the changes a long task makes.
 *   [ScreenIdle.lastEventMs] moves on every event outside AURA's own package.
 * - Why [busyMs]: a spinner or a video never goes quiet; without it such a screen would never
 *   be recorded. A frame shorter than [quietMs] inside a burst is not recorded; nobody could
 *   have read it.
 * - Fails: a null read (accessibility off) records nothing and waits for the next event.
 */
class ScreenWatcher(
    private val read: suspend () -> ScreenSnapshot.Snapshot?,
    private val onScreen: suspend (ScreenSnapshot.Snapshot) -> Boolean,
    private val onLimit: suspend () -> Unit = {},
    private val lastEventMs: () -> Long = { ScreenIdle.lastEventMs },
    private val now: () -> Long = System::currentTimeMillis,
    private val quietMs: Long = ScreenIdle.DEFAULT_QUIET_MS,
    private val busyMs: Long = BUSY_READ_MS,
) {
    private var seenEventMs = lastEventMs()

    /** A change not yet read. True at start, so the screen the run starts on is recorded too. */
    private var pending = true
    private var movedAtMs = now()
    private var added = 0

    suspend fun run() {
        while (coroutineContext.isActive) {
            tick()
            delay(TICK_MS)
        }
    }

    /** One check; reads the screen when a change has settled or kept going for [busyMs]. */
    suspend fun tick() {
        if (added >= MAX_PASSED) return
        val event = lastEventMs()
        if (event != seenEventMs) {
            seenEventMs = event
            if (!pending) {
                pending = true
                movedAtMs = now()
            }
        }
        if (!pending) return
        val t = now()
        if (t - event < quietMs && t - movedAtMs < busyMs) return
        pending = false
        val snap = read() ?: return
        if (onScreen(snap) && ++added == MAX_PASSED) onLimit()
    }

    companion object {
        const val TICK_MS = 250L
        const val BUSY_READ_MS = 2_000L
        const val MAX_PASSED = 300
    }
}
