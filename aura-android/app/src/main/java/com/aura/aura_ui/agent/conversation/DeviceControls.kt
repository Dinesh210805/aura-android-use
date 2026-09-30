package com.aura.aura_ui.agent.conversation

/** Result of a device setting/action: a short spoken sentence + whether it succeeded. */
data class ControlResult(val spoken: String, val ok: Boolean)

/**
 * Device settings + UI-navigation actions for the Live path. Android-heavy, so it sits behind this
 * interface — [CompanionDirectTools] stays unit-testable with a fake. The Android binding is
 * [com.aura.aura_ui.mcp.bridge.AppDeviceControls].
 *
 * Honesty contract: settings the OS forbids apps from silently toggling (wifi/bluetooth/airplane/
 * battery_saver/nfc on Android 10+) OPEN the relevant settings panel; the spoken result must say so,
 * never claim the toggle happened.
 */
interface DeviceControls {
    suspend fun applySetting(setting: String, state: String?): ControlResult
    suspend fun doAction(action: String): ControlResult
}
