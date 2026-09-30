package com.aura.aura_ui.compat

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android 14 (API 34) made foreground-service types load-bearing: calling
 * `startForeground()` with `FOREGROUND_SERVICE_TYPE_MICROPHONE` while
 * RECORD_AUDIO is not granted throws `SecurityException` and kills the process.
 *
 * This policy decides which type mask is *safe* to claim right now. Pure
 * arithmetic, so every OS/permission combination is testable off-device.
 */
class ForegroundTypePolicyTest {

    private val MIC = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val SPECIAL = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    private val CONNECTED = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    @Test
    fun `below API 29 the type parameter is meaningless`() {
        assertEquals(0, ForegroundTypePolicy.resolve(MIC or SPECIAL, micGranted = true, sdkInt = 26))
        assertEquals(0, ForegroundTypePolicy.resolve(MIC or SPECIAL, micGranted = false, sdkInt = 28))
    }

    @Test
    fun `keeps the microphone type when the permission is granted`() {
        val resolved = ForegroundTypePolicy.resolve(MIC or SPECIAL, micGranted = true, sdkInt = 34)
        assertEquals(MIC or SPECIAL, resolved)
    }

    @Test
    fun `strips the microphone type when the permission is missing`() {
        val resolved = ForegroundTypePolicy.resolve(MIC or SPECIAL, micGranted = false, sdkInt = 34)
        assertEquals(SPECIAL, resolved)
    }

    @Test
    fun `strips microphone but keeps every other declared type`() {
        val resolved = ForegroundTypePolicy.resolve(MIC or CONNECTED, micGranted = false, sdkInt = 35)
        assertEquals(CONNECTED, resolved)
    }

    @Test
    fun `falls back to specialUse when stripping would leave no type at all`() {
        // A microphone-only service with the permission revoked still has to
        // claim *something* on API 34+, or startForeground rejects the call.
        val resolved = ForegroundTypePolicy.resolve(MIC, micGranted = false, sdkInt = 34)
        assertEquals(SPECIAL, resolved)
    }

    @Test
    fun `pre-34 keeps the declared mask untouched`() {
        // API 29-33 accept a microphone type without the runtime grant; changing
        // the mask there would be a behaviour regression, not a fix.
        val resolved = ForegroundTypePolicy.resolve(MIC or SPECIAL, micGranted = false, sdkInt = 33)
        assertEquals(MIC or SPECIAL, resolved)
    }

    @Test
    fun `a service that declares no types resolves to none`() {
        assertEquals(0, ForegroundTypePolicy.resolve(0, micGranted = true, sdkInt = 34))
    }
}
