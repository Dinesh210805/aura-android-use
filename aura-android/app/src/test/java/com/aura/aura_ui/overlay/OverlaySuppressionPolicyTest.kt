package com.aura.aura_ui.overlay

import com.aura.aura_ui.compat.Oem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "my overlay doesn't show on Redmi" diagnosis, expressed as a pure decision
 * table. The inputs are the only four facts an app can actually observe; the
 * output decides which recovery the user is offered.
 */
class OverlaySuppressionPolicyTest {

    @Test
    fun `a window that drew is simply visible`() {
        assertEquals(
            OverlayVerdict.VISIBLE,
            OverlaySuppressionPolicy.verdict(
                canDrawOverlays = true,
                addViewThrew = false,
                drewWithinTimeout = true,
                oem = Oem.XIAOMI,
            ),
        )
    }

    // ── False-positive guards. Telling a user whose phone is FINE that their
    //    manufacturer is blocking AURA is worse than the silent bug this whole
    //    mechanism exists to detect — and it would land hardest on the budget
    //    Xiaomi devices that are the actual target. ──────────────────────────

    @Test
    fun `an overlay taken down before it drew is not a suppression`() {
        // The user dismissed the bubble inside the probe window. Nothing is
        // wrong with their phone, so they must not be told that there is.
        assertEquals(
            OverlayVerdict.DISMISSED,
            OverlaySuppressionPolicy.verdict(
                canDrawOverlays = true,
                addViewThrew = false,
                drewWithinTimeout = false,
                oem = Oem.XIAOMI,
                stillAttached = false,
            ),
        )
    }

    @Test
    fun `dismissal counts as success so no recovery prompt is posted`() {
        assertTrue(OverlayVerdict.DISMISSED.isSuccess)
    }

    @Test
    fun `dismissal outranks the vendor-gate diagnosis on every OEM`() {
        Oem.entries.forEach { oem ->
            assertEquals(
                "$oem must not be blamed when the overlay was simply taken down",
                OverlayVerdict.DISMISSED,
                OverlaySuppressionPolicy.verdict(
                    canDrawOverlays = true,
                    addViewThrew = false,
                    drewWithinTimeout = false,
                    oem = oem,
                    stillAttached = false,
                ),
            )
        }
    }

    @Test
    fun `a still-attached window that has not drawn is the suppression signature`() {
        // Attached + permitted + never drew is the ONLY state that justifies
        // pointing the user at a vendor settings screen.
        assertEquals(
            OverlayVerdict.OEM_SUPPRESSED,
            OverlaySuppressionPolicy.verdict(
                canDrawOverlays = true,
                addViewThrew = false,
                drewWithinTimeout = false,
                oem = Oem.XIAOMI,
                stillAttached = true,
            ),
        )
    }

    @Test
    fun `missing AOSP permission outranks every other explanation`() {
        assertEquals(
            OverlayVerdict.PERMISSION_MISSING,
            OverlaySuppressionPolicy.verdict(
                canDrawOverlays = false,
                addViewThrew = true,
                drewWithinTimeout = false,
                oem = Oem.XIAOMI,
            ),
        )
    }

    @Test
    fun `addView throwing with permission granted is a token or type failure`() {
        assertEquals(
            OverlayVerdict.ADD_FAILED,
            OverlaySuppressionPolicy.verdict(
                canDrawOverlays = true,
                addViewThrew = true,
                drewWithinTimeout = false,
                oem = Oem.OTHER,
            ),
        )
    }

    @Test
    fun `permission granted plus clean addView plus no draw on MIUI is the hidden gate`() {
        // This is the exact Redmi signature: nothing failed, nothing rendered.
        assertEquals(
            OverlayVerdict.OEM_SUPPRESSED,
            OverlaySuppressionPolicy.verdict(
                canDrawOverlays = true,
                addViewThrew = false,
                drewWithinTimeout = false,
                oem = Oem.XIAOMI,
            ),
        )
    }

    @Test
    fun `same signature on ColorOS Funtouch and EMUI is also the hidden gate`() {
        listOf(Oem.OPPO, Oem.VIVO, Oem.HUAWEI, Oem.HONOR).forEach { oem ->
            assertEquals(
                "$oem should be diagnosed as OEM-suppressed",
                OverlayVerdict.OEM_SUPPRESSED,
                OverlaySuppressionPolicy.verdict(
                    canDrawOverlays = true,
                    addViewThrew = false,
                    drewWithinTimeout = false,
                    oem = oem,
                ),
            )
        }
    }

    @Test
    fun `same signature on stock Android is not blamed on the OEM`() {
        // Pixel/Samsung have no hidden gate — claiming otherwise would send the
        // user hunting for a settings screen that does not exist.
        listOf(Oem.OTHER, Oem.SAMSUNG, Oem.ONEPLUS).forEach { oem ->
            assertEquals(
                "$oem must not be blamed on a vendor gate",
                OverlayVerdict.NOT_DRAWN,
                OverlaySuppressionPolicy.verdict(
                    canDrawOverlays = true,
                    addViewThrew = false,
                    drewWithinTimeout = false,
                    oem = oem,
                ),
            )
        }
    }

    @Test
    fun `only the two non-fault outcomes count as success`() {
        // VISIBLE = it worked. DISMISSED = it was taken down on purpose.
        // Everything else is a real fault the user needs told about.
        val silent = setOf(OverlayVerdict.VISIBLE, OverlayVerdict.DISMISSED)
        OverlayVerdict.entries.forEach { verdict ->
            assertEquals("$verdict", verdict in silent, verdict.isSuccess)
        }
    }

    @Test
    fun `success verdicts carry no user-facing message`() {
        // A "successful" verdict that still had text would surface a prompt.
        OverlayVerdict.entries.filter { it.isSuccess }.forEach { verdict ->
            assertTrue("$verdict must stay silent", verdict.userMessage.isBlank())
        }
    }

    @Test
    fun `every failure verdict is actionable by the user`() {
        OverlayVerdict.entries.filter { !it.isSuccess }.forEach { verdict ->
            assert(verdict.userMessage.isNotBlank()) { "$verdict needs a user-facing message" }
        }
    }
}
