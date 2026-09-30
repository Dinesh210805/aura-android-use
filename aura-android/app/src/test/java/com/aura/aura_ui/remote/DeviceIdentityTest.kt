package com.aura.aura_ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hashing half of [DeviceIdentity]. Every branch that decides whether a device CAN be blocked
 * lives here, so none of it needs a handset to check.
 */
class DeviceIdentityTest {

    @Test fun `the same ssaid always produces the same key`() {
        // The entire premise: a reinstall keeps the SSAID, so it must keep the block.
        assertEquals(DeviceIdentity.hashOf("a1b2c3d4e5f60718"), DeviceIdentity.hashOf("a1b2c3d4e5f60718"))
    }

    @Test fun `different devices produce different keys`() {
        assertNotEquals(DeviceIdentity.hashOf("a1b2c3d4e5f60718"), DeviceIdentity.hashOf("0000000000000001"))
    }

    @Test fun `the key is a lowercase sha-256 hex digest, and carries no trace of the id`() {
        val ssaid = "a1b2c3d4e5f60718"
        val hash = DeviceIdentity.hashOf(ssaid)!!
        assertEquals("sha-256 is 64 hex chars", 64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
        assertTrue("the raw id must not survive into the stored value", !hash.contains(ssaid))
    }

    /**
     * A long tail of devices and emulators ship this literal instead of a real id. They would all
     * hash identically, so blocking one would block every stranger carrying it.
     */
    @Test fun `the known-bad shared ssaid is refused rather than hashed`() {
        assertNull(DeviceIdentity.hashOf("9774d56d682e549c"))
        assertNull("case must not smuggle it past", DeviceIdentity.hashOf("9774D56D682E549C"))
    }

    @Test fun `an absent or empty id yields no key at all`() {
        assertNull(DeviceIdentity.hashOf(null))
        assertNull(DeviceIdentity.hashOf(""))
        assertNull(DeviceIdentity.hashOf("   "))
        assertNull(DeviceIdentity.hashOf(DeviceIdentity.UNKNOWN))
    }

    @Test fun `case and surrounding space do not change the key`() {
        val a = DeviceIdentity.hashOf("A1B2C3D4E5F60718")
        val b = DeviceIdentity.hashOf("  a1b2c3d4e5f60718 ")
        assertEquals("or the same phone would hash two ways and shed its block", a, b)
    }

    /**
     * Device and account are separate namespaces only by what goes into them; both are salted the
     * same way so a blocklist id looks the same whichever it came from.
     */
    @Test fun `an account hash is produced for a real uid and refused for a blank one`() {
        assertEquals(64, DeviceIdentity.accountHash("firebase-uid-123")!!.length)
        assertNull(DeviceIdentity.accountHash(null))
        assertNull(DeviceIdentity.accountHash("  "))
    }
}
