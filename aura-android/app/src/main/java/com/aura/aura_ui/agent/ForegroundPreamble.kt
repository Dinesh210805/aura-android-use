package com.aura.aura_ui.agent

/**
 * Turn-0 "where am I" note prepended to the model's first input.
 *
 * The agent used to start every run blind: nothing in its context said which app
 * was on screen, so asking it to do something *while already on the target
 * screen* led it to `launch_app` and re-enter the app from its home screen,
 * throwing away the user's position. `get_device_status` could answer that, but
 * the decision tree never spent a call on it at turn 0.
 *
 * This makes the answer free. It is a hint, not the enforcement — `launch_app`
 * refuses a redundant relaunch on its own (`already_foreground`); this just lets
 * the model plan correctly on its first move instead of learning by bouncing.
 *
 * Trust: the app label is screen-derived and therefore attacker-influenced (a
 * malicious app can name itself anything), so it rides the USER channel next to
 * the goal — never the system prompt, matching how learned hints are fenced.
 */
object ForegroundPreamble {

    /**
     * Returns the note, or null when there is nothing useful to say — no
     * accessibility service (null package), or AURA's own UI is in front, which
     * tells the model nothing about the user's task.
     */
    fun forForeground(
        packageName: String?,
        appLabel: String? = null,
        ownPackage: String? = null,
    ): String? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (ownPackage != null && pkg == ownPackage) return null

        val label = appLabel?.trim()?.takeIf { it.isNotEmpty() }
        val who = if (label != null) "$label ($pkg)" else pkg
        return "[current screen] The phone is showing $who. If the task is in this app, start " +
            "from this screen — do not relaunch it or deep-link back in. Launch only to reach a " +
            "different app."
    }
}
