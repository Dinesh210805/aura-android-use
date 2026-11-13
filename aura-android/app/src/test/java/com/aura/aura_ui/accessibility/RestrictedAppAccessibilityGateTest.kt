package com.aura.aura_ui.accessibility

import android.view.accessibility.AccessibilityEvent
import com.aura.mcp.bridge.RestrictedAppCategory
import com.aura.mcp.bridge.RestrictedAppEntry
import com.aura.mcp.bridge.RestrictedAppSource
import com.aura.mcp.bridge.RestrictedTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RestrictedAppAccessibilityGate.shouldDisableFor — the pure decision behind
 * the Phase 2 auto-disable. Everything else in the gate (posting the
 * notification, calling disableSelf()) is Android-framework side effect and
 * isn't unit-testable without instrumentation; this is the one piece of logic
 * that determines WHETHER those side effects should fire, so it's the piece
 * worth isolating and covering.
 */
class RestrictedAppAccessibilityGateTest {

    private fun entry(tier: RestrictedTier) = RestrictedAppEntry(
        packageName = "com.bank.app",
        appName = "MyBank",
        category = RestrictedAppCategory.BANKING,
        tier = tier,
        source = RestrictedAppSource.USER_ADDED,
    )

    @Test fun `DISABLE_ACCESSIBILITY tier triggers the auto-disable`() {
        assertTrue(RestrictedAppAccessibilityGate.shouldDisableFor(entry(RestrictedTier.DISABLE_ACCESSIBILITY)))
    }

    @Test fun `DECLINE_ONLY tier does not touch accessibility`() {
        assertFalse(RestrictedAppAccessibilityGate.shouldDisableFor(entry(RestrictedTier.DECLINE_ONLY)))
    }

    @Test fun `no entry (unrestricted app) does not touch accessibility`() {
        assertFalse(RestrictedAppAccessibilityGate.shouldDisableFor(null))
    }

    // --- matchLabel: the EARLIEST trigger (launcher icon tap) -----------------
    // This one fires before the restricted app's process spawns, so it buys the
    // biggest head start on the race against their onResume() check. It is also
    // the easiest to make dangerously loose, hence these cases.

    private val restricted = entry(RestrictedTier.DISABLE_ACCESSIBILITY)
    private val declineOnly = RestrictedAppEntry(
        packageName = "com.wallet.app",
        appName = "MyWallet",
        category = RestrictedAppCategory.BANKING,
        tier = RestrictedTier.DECLINE_ONLY,
        source = RestrictedAppSource.USER_ADDED,
    )

    @Test fun `exact label match on a restricted app fires`() {
        assertEquals(restricted, RestrictedAppAccessibilityGate.matchLabel("MyBank", listOf(restricted)))
    }

    @Test fun `label match ignores case and surrounding whitespace`() {
        assertEquals(restricted, RestrictedAppAccessibilityGate.matchLabel("  mybank ", listOf(restricted)))
    }

    @Test fun `substring mentions do not fire — prose must not disable AURA`() {
        assertNull(RestrictedAppAccessibilityGate.matchLabel("Pay MyBank bill", listOf(restricted)))
        assertNull(RestrictedAppAccessibilityGate.matchLabel("MyBank Rewards", listOf(restricted)))
    }

    @Test fun `DECLINE_ONLY app is never matched by label`() {
        assertNull(RestrictedAppAccessibilityGate.matchLabel("MyWallet", listOf(declineOnly)))
    }

    @Test fun `null and blank labels are ignored`() {
        assertNull(RestrictedAppAccessibilityGate.matchLabel(null, listOf(restricted)))
        assertNull(RestrictedAppAccessibilityGate.matchLabel("   ", listOf(restricted)))
    }

    // --- which events mean the app is on screen --------------------------------

    @Test fun `a notification or toast from a restricted app does not count as opening it`() {
        assertFalse(
            RestrictedAppAccessibilityGate.meansAppOnScreen(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED),
        )
    }

    @Test fun `window and view events still count`() {
        assertTrue(RestrictedAppAccessibilityGate.meansAppOnScreen(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(RestrictedAppAccessibilityGate.meansAppOnScreen(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(RestrictedAppAccessibilityGate.meansAppOnScreen(AccessibilityEvent.TYPE_VIEW_FOCUSED))
    }
}
