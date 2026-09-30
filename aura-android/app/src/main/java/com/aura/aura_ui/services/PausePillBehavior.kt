package com.aura.aura_ui.services

/**
 * The on-screen run controls: pure behaviour, no Android types.
 *
 * ### What changed, and why the old doctrine is gone (2026-09-07)
 *
 * This file used to describe a *pause pill*: a thing that appeared only once the human had
 * already grabbed the wheel, draggable so it could get out of the way, shrinking to a dot
 * when ignored, and needing two taps to resume. Every one of those choices followed from a
 * single premise — that the control only exists **after** an interruption, while the user
 * is busy doing something urgent of their own.
 *
 * The premise no longer holds. The controls are now on screen for the whole run, in one
 * fixed place, so the user can reach for Pause *before* grabbing the phone rather than
 * after. That inverts the reasoning: a fixed position is now right (one place to look, the
 * argument the old KDoc made for the *status* pill), and a shrink-to-dot state would be
 * actively wrong — a control you might need in a hurry must not first have to be found and
 * opened. Hence one tap pauses, one tap resumes, and nothing hides itself.
 *
 * Cancel is deliberately **not** always present. It appears only once paused, so ending a
 * run is a two-step act: stop the thing, look at where it got to, then decide. A Cancel
 * button sitting under a moving run is one thumb-brush away from throwing work out.
 */
internal object PausePillBehavior {

    /** What the controls show, derived from the two facts that drive them. */
    enum class Controls {
        /** Nothing on screen — no local run has the wheel. */
        HIDDEN,

        /** A run is going: pause only. Cancel stays out of reach of a stray thumb. */
        PAUSE_ONLY,

        /** Paused: resume, and now cancel too. */
        RESUME_AND_CANCEL,
    }

    fun controlsFor(localRunActive: Boolean, paused: Boolean): Controls = when {
        !localRunActive -> Controls.HIDDEN
        paused -> Controls.RESUME_AND_CANCEL
        else -> Controls.PAUSE_ONLY
    }

    /**
     * Keep the controls off the navigation bar.
     *
     * They sit bottom-centre, which is where the gesture bar lives, and while the lock is
     * held this is the only way back — so a button behind the nav bar is a dead end, not a
     * cosmetic problem.
     *
     * The keyboard is deliberately *not* handled here. The controls are `NOT_FOCUSABLE`,
     * so they never raise an IME themselves; a keyboard on screen belongs to whatever the
     * user is doing, and covering the controls in that moment is the one case where the
     * old draggable design was better. Left as a known gap rather than guessed at.
     */
    fun bottomMarginPx(navBarInsetPx: Int, minimumPx: Int): Int =
        maxOf(navBarInsetPx, minimumPx)
}
