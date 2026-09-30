package com.aura.mcp.lan

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import org.junit.Test

class PinGateTest {

    private var now = 1_000_000L
    private var next = 0
    private val changes = mutableListOf<String>()
    private val gate = PinGate(newPin = { "%06d".format(next++) }, clock = { now })
        .also { g -> g.onPinChanged = { changes += it } }

    @Test fun `a correct PIN is accepted once and then replaced`() {
        assertEquals("000000", gate.pin)
        assertEquals(PinGate.Result.Accepted, gate.tryPin("000000"))
        assertEquals("000001", gate.pin)
        assertEquals(listOf("000001"), changes)
        assertIs<PinGate.Result.Wrong>(gate.tryPin("000000"))
    }

    @Test fun `five wrong PINs lock, rotate, and the lock doubles`() {
        repeat(PinGate.MAX_FAILURES - 1) { assertIs<PinGate.Result.Wrong>(gate.tryPin("999999")) }
        val locked = gate.tryPin("999999")
        assertEquals(PinGate.Result.Locked(PinGate.BASE_LOCK_MS), locked)
        assertNotEquals("000000", gate.pin)

        // Even the right PIN is refused while locked.
        assertIs<PinGate.Result.Locked>(gate.tryPin(gate.pin))

        now += PinGate.BASE_LOCK_MS
        repeat(PinGate.MAX_FAILURES - 1) { gate.tryPin("999999") }
        assertEquals(PinGate.Result.Locked(PinGate.BASE_LOCK_MS * 2), gate.tryPin("999999"))
    }

    @Test fun `lock never exceeds the maximum`() {
        repeat(20) {
            repeat(PinGate.MAX_FAILURES) { gate.tryPin("999999") }
            now += PinGate.MAX_LOCK_MS
        }
        repeat(PinGate.MAX_FAILURES - 1) { gate.tryPin("999999") }
        assertEquals(PinGate.Result.Locked(PinGate.MAX_LOCK_MS), gate.tryPin("999999"))
    }

    @Test fun `a correct PIN resets the doubling`() {
        repeat(PinGate.MAX_FAILURES) { gate.tryPin("999999") }
        now += PinGate.BASE_LOCK_MS
        assertEquals(PinGate.Result.Accepted, gate.tryPin(gate.pin))
        repeat(PinGate.MAX_FAILURES - 1) { gate.tryPin("999999") }
        assertEquals(PinGate.Result.Locked(PinGate.BASE_LOCK_MS), gate.tryPin("999999"))
    }
}
