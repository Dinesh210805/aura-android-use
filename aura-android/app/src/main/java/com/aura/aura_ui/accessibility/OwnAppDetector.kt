package com.aura.aura_ui.accessibility

/**
 * "Did this accessibility event come from AURA itself?"
 *
 * ### Why this exists instead of a constant (2026-08-03)
 *
 * Three places in the app answered this question with a hardcoded list, all three lists
 * disagreed, and **every one of them was wrong** — because they listed the Gradle
 * `namespace` (`com.aura.aura_ui`) rather than the `applicationId`, which is what a
 * running process is actually called:
 *
 * | Build | `namespace` | real package (`applicationId`) |
 * |---|---|---|
 * | release | `com.aura.aura_ui` | `com.aura.aura_ui.feature` |
 * | debug | `com.aura.aura_ui` | `com.aura.aura_ui.feature.debug` |
 *
 * The namespace only names the generated `R` and `BuildConfig` classes. It never appears
 * as a package name at runtime, so a comparison against it can never be true. The cost was
 * not cosmetic: `AuraAccessibilityService` used it to decide whether an event was AURA's
 * own, so **"is this AURA?" always answered no** and every touch on AURA's own overlay was
 * classified as the user grabbing the phone — the reported "I open the overlay and it says
 * paused". `UITreeExtractor` had the same defect in release builds only, where it meant
 * the agent perceived AURA's own overlay as part of the screen it was trying to drive.
 *
 * The fix is to stop writing the answer down. `Context.getPackageName()` returns the
 * applicationId, suffix included, for whatever variant is actually running — so it is
 * correct in every build by construction, including variants that do not exist yet. Every
 * caller here already has a `Context`; the lists were never necessary.
 *
 * This stays a separate object rather than an inline `==` so the SystemUI indirection
 * below has somewhere to be tested.
 */
internal object OwnAppDetector {

    /**
     * SystemUI relays events on behalf of windows it hosts, reporting *its* package
     * instead of the originating app's. Pre-existing behaviour, kept: when SystemUI is the
     * nominal source, the active window's real package is the better answer.
     */
    const val SYSTEM_UI = "com.android.systemui"

    /**
     * @param eventPackage the package named on the [android.view.accessibility.AccessibilityEvent].
     * @param activeWindowPackage the package owning the active window, if it was cheap to
     *   look up. Only consulted for the SystemUI case — resolving the root node on every
     *   event would be a per-event tree read on a firehose.
     * @param ownPackage `context.packageName`. Never a literal.
     */
    fun isOwnApp(
        eventPackage: String?,
        activeWindowPackage: String?,
        ownPackage: String,
    ): Boolean {
        if (eventPackage == null) return false
        if (eventPackage == ownPackage) return true
        return eventPackage == SYSTEM_UI && activeWindowPackage == ownPackage
    }
}
