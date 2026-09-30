package com.aura.aura_ui.mcp.bridge

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.MediaStore
import android.provider.Settings
import com.aura.aura_ui.agent.conversation.ControlResult
import com.aura.aura_ui.agent.conversation.DeviceControls
import com.aura.mcp.bridge.DeviceBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aura.aura_ui.services.startActivityAsAura

/**
 * Android binding for [DeviceControls]. Directly toggles what the OS permits (flashlight, DND,
 * volume, brightness) and OPENS the settings panel for what Android 10+ forbids apps from flipping
 * silently (wifi/bluetooth/airplane/battery_saver/nfc) — the spoken result says which happened, so
 * the model never claims a toggle it only opened. UI-nav actions route through [DeviceBridge].
 */
class AppDeviceControls(
    context: Context,
    private val deviceBridge: DeviceBridge,
) : DeviceControls {

    private val app = context.applicationContext

    override suspend fun applySetting(setting: String, state: String?): ControlResult =
        withContext(Dispatchers.Default) {
            when (setting.trim().lowercase()) {
                "flashlight", "torch" -> flashlight(state)
                "dnd", "do_not_disturb" -> dnd(state)
                "volume" -> volume(state)
                "brightness" -> brightness(state)
                "wifi" -> openPanel(Settings.Panel.ACTION_WIFI, "Wi-Fi")
                "bluetooth" -> openPanel(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")
                "airplane", "airplane_mode" -> openPanel(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode")
                "battery_saver", "battery" -> openPanel(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery saver")
                "nfc" -> openPanel(Settings.ACTION_NFC_SETTINGS, "NFC")
                else -> ControlResult("I can't change '$setting'.", ok = false)
            }
        }

    override suspend fun doAction(action: String): ControlResult = withContext(Dispatchers.Default) {
        when (action.trim().lowercase()) {
            "home" -> boolResult(deviceBridge.performHome(), "Home.")
            "back" -> boolResult(deviceBridge.performBack(), "Back.")
            "recents", "recent_apps" -> boolResult(deviceBridge.performRecents(), "Here are your recent apps.")
            "take_photo", "camera", "selfie" -> takePhoto()
            else -> ControlResult("I can't do '$action'.", ok = false)
        }
    }

    // ── settings ────────────────────────────────────────────────────────────────

    private fun flashlight(state: String?): ControlResult = runCatching {
        val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ControlResult("This phone has no flashlight I can control.", ok = false)
        val on = state?.trim()?.lowercase() != "off"
        manager.setTorchMode(camId, on)
        ControlResult(if (on) "Flashlight on." else "Flashlight off.", ok = true)
    }.getOrElse { ControlResult("I couldn't change the flashlight.", ok = false) }

    private fun dnd(state: String?): ControlResult {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            openPanel(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "Do Not Disturb access")
            return ControlResult("I need Do Not Disturb access — I've opened the settings so you can grant it once.", ok = false)
        }
        val on = state?.trim()?.lowercase() != "off"
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return ControlResult(if (on) "Do Not Disturb on." else "Do Not Disturb off.", ok = true)
    }

    private fun volume(state: String?): ControlResult {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = when (state?.trim()?.lowercase()) {
            "down", "lower" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            else -> AudioManager.ADJUST_RAISE
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return ControlResult(
            when (dir) {
                AudioManager.ADJUST_LOWER -> "Volume down."
                AudioManager.ADJUST_MUTE -> "Muted."
                else -> "Volume up."
            },
            ok = true,
        )
    }

    private fun brightness(state: String?): ControlResult {
        if (!Settings.System.canWrite(app)) {
            openPanel(Settings.ACTION_MANAGE_WRITE_SETTINGS, "modify system settings")
            return ControlResult("I need permission to change brightness — I've opened the settings for you to allow it once.", ok = false)
        }
        val value = when (state?.trim()?.lowercase()) {
            "dim", "low", "down", "min" -> 40
            "max", "full" -> 255
            else -> 220 // bright / up / default
        }
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return ControlResult("Brightness set.", ok = true)
    }

    private fun openPanel(action: String, name: String): ControlResult = runCatching {
        app.startActivityAsAura(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ControlResult("I opened $name settings — toggle it there.", ok = true)
    }.getOrElse { ControlResult("I couldn't open $name settings.", ok = false) }

    // ── actions ─────────────────────────────────────────────────────────────────

    private fun takePhoto(): ControlResult = runCatching {
        app.startActivityAsAura(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ControlResult("Opening the camera.", ok = true)
    }.getOrElse { ControlResult("I couldn't open the camera.", ok = false) }

    private fun boolResult(ok: Boolean, spokenOk: String): ControlResult =
        if (ok) ControlResult(spokenOk, ok = true) else ControlResult("That didn't work.", ok = false)
}
