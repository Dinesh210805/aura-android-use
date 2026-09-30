package com.aura.aura_ui.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OEM detection is a pure string → enum decision so it stays testable on the JVM.
 * Every branch here maps to hardware we do NOT own — these tests are the only
 * coverage those code paths will ever get before release.
 */
class OemCompatTest {

    @Test
    fun `detects xiaomi family from manufacturer`() {
        assertEquals(Oem.XIAOMI, OemCompat.detect("Xiaomi", "Redmi"))
        assertEquals(Oem.XIAOMI, OemCompat.detect("xiaomi", "POCO"))
    }

    @Test
    fun `detects redmi and poco even when manufacturer is unhelpful`() {
        // Redmi/POCO devices frequently report brand, not manufacturer.
        assertEquals(Oem.XIAOMI, OemCompat.detect("unknown", "Redmi"))
        assertEquals(Oem.XIAOMI, OemCompat.detect("", "POCO"))
    }

    @Test
    fun `detects bbk family separately`() {
        assertEquals(Oem.OPPO, OemCompat.detect("OPPO", "realme"))
        assertEquals(Oem.OPPO, OemCompat.detect("realme", "realme"))
        assertEquals(Oem.VIVO, OemCompat.detect("vivo", "vivo"))
        assertEquals(Oem.ONEPLUS, OemCompat.detect("OnePlus", "OnePlus"))
    }

    @Test
    fun `detects huawei and honor`() {
        assertEquals(Oem.HUAWEI, OemCompat.detect("HUAWEI", "HUAWEI"))
        assertEquals(Oem.HONOR, OemCompat.detect("HONOR", "HONOR"))
    }

    @Test
    fun `unknown manufacturer falls back to OTHER`() {
        assertEquals(Oem.OTHER, OemCompat.detect("Google", "google"))
        assertEquals(Oem.OTHER, OemCompat.detect("", ""))
    }

    @Test
    fun `detection is null-safe`() {
        assertEquals(Oem.OTHER, OemCompat.detect(null, null))
    }

    // ── Remediation policy ────────────────────────────────────────────────

    @Test
    fun `xiaomi needs the hidden background-popup grant`() {
        assertTrue(OemCompat.hasHiddenOverlayGate(Oem.XIAOMI))
    }

    @Test
    fun `stock android does not need extra overlay steps`() {
        assertFalse(OemCompat.hasHiddenOverlayGate(Oem.OTHER))
        assertFalse(OemCompat.hasHiddenOverlayGate(Oem.SAMSUNG))
    }

    @Test
    fun `every OEM with a hidden gate ships user-facing instructions`() {
        Oem.entries.filter { OemCompat.hasHiddenOverlayGate(it) }.forEach { oem ->
            val steps = OemCompat.overlayFixSteps(oem)
            assertNotNull("$oem must have fix steps", steps)
            assertTrue("$oem steps must not be empty", steps!!.isNotEmpty())
        }
    }

    @Test
    fun `OEMs without a hidden gate have no extra steps`() {
        assertNull(OemCompat.overlayFixSteps(Oem.OTHER))
    }

    @Test
    fun `xiaomi instructions name the actual MIUI toggle`() {
        val steps = OemCompat.overlayFixSteps(Oem.XIAOMI)!!.joinToString(" ")
        assertTrue(steps.contains("Other permissions"))
        assertTrue(steps.contains("pop-up", ignoreCase = true))
    }

    // ── Settings component candidates ─────────────────────────────────────

    @Test
    fun `xiaomi exposes the permission editor component first`() {
        val candidates = OemCompat.overlaySettingsComponents(Oem.XIAOMI)
        assertTrue(candidates.isNotEmpty())
        assertEquals("com.miui.securitycenter", candidates.first().first)
    }

    @Test
    fun `component candidates are non-blank pairs for every OEM`() {
        Oem.entries.forEach { oem ->
            OemCompat.overlaySettingsComponents(oem).forEach { (pkg, cls) ->
                assertTrue("$oem package blank", pkg.isNotBlank())
                assertTrue("$oem class blank", cls.isNotBlank())
            }
        }
    }

    @Test
    fun `battery restriction components exist for the aggressive OEMs`() {
        listOf(Oem.XIAOMI, Oem.OPPO, Oem.VIVO, Oem.HUAWEI, Oem.ONEPLUS).forEach { oem ->
            assertTrue(
                "$oem should expose an autostart/battery screen",
                OemCompat.autoStartComponents(oem).isNotEmpty(),
            )
        }
    }
}
