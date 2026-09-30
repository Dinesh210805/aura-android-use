package com.aura.aura_ui.services

import android.content.Context
import android.content.Intent

/**
 * Start an activity **as AURA**, stamping the control lock's self-action window first.
 *
 * ### Why launches need attributing too (2026-08-03)
 *
 * `TYPE_WINDOW_STATE_CHANGED` is deliberately not a [PauseTrigger] signal, so it is
 * tempting to think launches are already invisible to the lock. They are not. The window
 * change is filtered; everything the launching app then *renders* is not. A cold start
 * paints for seconds and emits `TYPE_VIEW_TEXT_CHANGED` and `TYPE_VIEW_SCROLLED`
 * throughout as its lists bind and its text fills in — all of it AURA's doing, none of it
 * previously attributed to AURA.
 *
 * The practical effect was that opening an app paused the agent immediately after opening
 * it, which is the first step of most tasks.
 *
 * [SelfActionWindow.ActionKind.LAUNCH] carries the longest settle for this reason, and it
 * is still not obviously enough on a loaded device — a cold start can exceed any fixed
 * budget. The remaining exposure is bounded rather than eliminated: worst case is one
 * spurious pause with a visible pill and a 30 s release, not a silent stall.
 */
internal fun Context.startActivityAsAura(intent: Intent) {
    ControlLockStore.shared.noteDispatch(SelfActionWindow.ActionKind.LAUNCH)
    startActivity(intent)
}
