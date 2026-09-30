package com.aura.aura_ui.presentation.screens.trace

import android.content.Context
import com.aura.aura_ui.BuildConfig

/**
 * One place that decides what a run's trace shows a user, and what it shows us.
 *
 * ### The rule
 *
 * A user opens the trace to answer "what did AURA do, and why did it do that". That is satisfied
 * by what it thought, what it looked at, what it did and what it said. It is not satisfied by the
 * system prompt, the tool argument JSON, or the ASCII redraw of the screen — those answer "how is
 * AURA built", which is a different question and not one the trace is for.
 *
 * The screen picture in particular is deliberate: the character-grid rendering of a screen is how
 * AURA's perception works and is the thing worth keeping to ourselves. It is also the single
 * biggest block on the page, so removing it makes the normal trace *better* to read, not thinner.
 *
 * ### Why an enum and not `if (BuildConfig.DEBUG)` at each call site
 *
 * There are eight places that would each need the check, across the Compose screen and the JSON
 * export. Eight copies of a rule is eight chances for one of them to be forgotten — and the one
 * most likely to be forgotten is the export, which is the artifact that actually leaves the phone
 * and gets sent to someone. So the rule lives here once and every reader asks it.
 *
 * ### Release is pinned, not defaulted
 *
 * [debugMode] can only ever be true in a debug build. A runtime switch that merely *defaults* to
 * off in release is a switch that can be turned on in release, and the whole point is that these
 * blocks cannot reach a user. In release the toggle does not exist and [visible] is answered by
 * [NORMAL_MODE] unconditionally.
 */
object TraceVisibility {

    /** A block of the trace that has to be decided about. Adding one here forces a decision. */
    enum class Facet {
        /** The assembled identity prompt. Ours; never a user's question. */
        SYSTEM_PROMPT,

        /** `read_screen`'s character grid and `perceive_screen`'s redrawn canvas. */
        SCREEN_ASCII,

        /** Raw tool arguments and raw tool output — the wire format, not the outcome. */
        TOOL_IO,

        /** The prompt delta fed to the model each turn. Ours, and the largest block on the page. */
        PROMPT_DELTA,

        /** Tool count, remote servers, skills, injected hints. */
        ASSEMBLY,

        /** Tokens, requests, wall clock, retry policy. */
        BUDGET,

        /** The model's reasoning. Kept in normal mode: it is the honest answer to "why". */
        THINKING,

        /** What AURA actually saw. Kept: it is the evidence behind every step. */
        SCREENSHOT,

        /** What AURA said out loud. Kept. */
        SPOKEN,

        /** Which tool ran, whether it worked, how long it took. Kept: this IS what it did. */
        TOOL_TIMELINE,
    }

    /**
     * What a user sees. Everything absent from this set is internal by construction, so a new
     * facet is hidden until someone deliberately adds it — the safe direction to be wrong in.
     */
    private val NORMAL_MODE: Set<Facet> = setOf(
        Facet.THINKING,
        Facet.SCREENSHOT,
        Facet.SPOKEN,
        Facet.TOOL_TIMELINE,
    )

    /** Pure rule, so the whole policy is testable without a Context or a build variant. */
    fun visible(facet: Facet, debugMode: Boolean): Boolean = debugMode || facet in NORMAL_MODE

    /** Convenience for the readers, which all share one process-wide answer. */
    fun visible(context: Context, facet: Facet): Boolean = visible(facet, debugMode(context))

    /**
     * Whether internals are being shown. Always false in a release build — see the class KDoc.
     * Cached after the first read because the trace screen asks this several times per row.
     */
    fun debugMode(context: Context): Boolean {
        if (!isToggleAvailable(context)) return false
        cached?.let { return it }
        return prefs(context).getBoolean(KEY, DEFAULT_IN_DEBUG).also { cached = it }
    }

    /** No-op in release, so a caller cannot turn internals on where they must not appear. */
    fun setDebugMode(context: Context, on: Boolean) {
        if (!isToggleAvailable(context)) return
        cached = on
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    /** Whether to offer the switch at all. The trace screen hides the row entirely in release. */
    val isToggleAvailable: Boolean get() = BuildConfig.DEBUG

    /**
     * Debug builds, and any build with Settings → Developer mode on: the full trace is how a
     * developer sees what the agent did on a real phone running the release build.
     */
    fun isToggleAvailable(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        com.aura.aura_ui.data.preferences.DeveloperModeStore.hydrate(context)
        return com.aura.aura_ui.data.preferences.DeveloperModeStore.enabled.value
    }

    /**
     * Debug builds start with internals ON: a debug build exists to be inspected, and defaulting
     * it off would mean every debugging session begins with the same trip to Settings.
     */
    private const val DEFAULT_IN_DEBUG = true

    private const val PREFS = "aura_trace_visibility"
    private const val KEY = "debug_mode"

    @Volatile private var cached: Boolean? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Test seam. */
    internal fun resetCacheForTest() { cached = null }
}
