package com.aura.aura_ui.presentation.screens.trace

import android.content.Context

/**
 * Which view of a run the person last chose: the full log, or the plain-words story.
 *
 * - Contract: a view preference and nothing more. Every build records, shows and exports the same
 *   full log; this only picks what the trace screen opens on. Full is the default.
 * - Reads/Writes: `debug_mode` in the `aura_trace_visibility` prefs.
 */
object TraceVisibility {

    private const val PREFS = "aura_trace_visibility"
    private const val KEY = "debug_mode"

    /** Cached because the trace screen asks several times per row. */
    @Volatile private var cached: Boolean? = null

    fun showFull(context: Context): Boolean =
        cached ?: prefs(context).getBoolean(KEY, true).also { cached = it }

    fun setShowFull(context: Context, on: Boolean) {
        cached = on
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
