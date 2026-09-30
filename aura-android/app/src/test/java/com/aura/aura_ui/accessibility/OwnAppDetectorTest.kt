package com.aura.aura_ui.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Is this event AURA's own?" — the question three hardcoded lists got wrong at once.
 *
 * Every list named the Gradle `namespace` (`com.aura.aura_ui`) rather than the
 * `applicationId` a running process actually has (`com.aura.aura_ui.feature[.debug]`), so
 * the answer was permanently "no". `AuraAccessibilityService` used it to decide whether an
 * accessibility event came from AURA, which meant **every touch on AURA's own overlay was
 * classified as the user grabbing the phone** — the reported "I open the overlay and it
 * says paused".
 *
 * These tests deliberately use the real strings from `build.gradle.kts`, because the
 * failure was never in the comparison logic — it was in the values, and a test using
 * `"pkg"` and `"other"` would have passed happily throughout.
 */
class OwnAppDetectorTest {

    private companion object {
        /** `namespace` in build.gradle.kts. Names generated R/BuildConfig — never a process. */
        const val NAMESPACE = "com.aura.aura_ui"

        /** `applicationId`. What the release process is actually called. */
        const val RELEASE_ID = "com.aura.aura_ui.feature"

        /** `applicationId` + debug `applicationIdSuffix`. */
        const val DEBUG_ID = "com.aura.aura_ui.feature.debug"
    }

    @Test
    fun `AURA recognises itself in a release build`() {
        assertTrue(OwnAppDetector.isOwnApp(RELEASE_ID, null, ownPackage = RELEASE_ID))
    }

    @Test
    fun `AURA recognises itself in a debug build`() {
        assertTrue(OwnAppDetector.isOwnApp(DEBUG_ID, null, ownPackage = DEBUG_ID))
    }

    @Test
    fun `the namespace is not a package and must never be treated as one`() {
        // The regression, stated directly. Comparing against the namespace can never match
        // a real event, which is exactly why the old check silently answered "not AURA"
        // for AURA's own windows in every build ever shipped.
        assertFalse(OwnAppDetector.isOwnApp(NAMESPACE, null, ownPackage = RELEASE_ID))
        assertFalse(OwnAppDetector.isOwnApp(NAMESPACE, null, ownPackage = DEBUG_ID))
    }

    @Test
    fun `a debug install is not confused with a release install`() {
        // Both can exist on one device side by side, and only one of them is this process.
        assertFalse(OwnAppDetector.isOwnApp(DEBUG_ID, null, ownPackage = RELEASE_ID))
        assertFalse(OwnAppDetector.isOwnApp(RELEASE_ID, null, ownPackage = DEBUG_ID))
    }

    @Test
    fun `another app is not AURA`() {
        assertFalse(OwnAppDetector.isOwnApp("com.whatsapp", null, ownPackage = DEBUG_ID))
    }

    @Test
    fun `a null package is not AURA`() {
        // Events genuinely arrive with no package. Defaulting those to "ours" would blind
        // the pause trigger; defaulting to "theirs" at worst costs a spurious pause.
        assertFalse(OwnAppDetector.isOwnApp(null, null, ownPackage = DEBUG_ID))
    }

    @Test
    fun `SystemUI relaying one of AURA's windows still counts as AURA`() {
        // SystemUI reports its own package for windows it hosts. Pre-existing behaviour,
        // kept: the active window's real package is the better answer when it is available.
        assertTrue(
            OwnAppDetector.isOwnApp(
                eventPackage = OwnAppDetector.SYSTEM_UI,
                activeWindowPackage = DEBUG_ID,
                ownPackage = DEBUG_ID,
            ),
        )
    }

    @Test
    fun `SystemUI relaying somebody else's window is not AURA`() {
        assertFalse(
            OwnAppDetector.isOwnApp(
                eventPackage = OwnAppDetector.SYSTEM_UI,
                activeWindowPackage = "com.whatsapp",
                ownPackage = DEBUG_ID,
            ),
        )
    }

    @Test
    fun `SystemUI on its own is not AURA`() {
        assertFalse(OwnAppDetector.isOwnApp(OwnAppDetector.SYSTEM_UI, null, ownPackage = DEBUG_ID))
    }
}
