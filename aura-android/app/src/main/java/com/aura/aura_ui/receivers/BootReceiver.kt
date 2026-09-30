package com.aura.aura_ui.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aura.aura_ui.services.AssistantForegroundService
import com.aura.aura_ui.services.WakeWordListeningService

/**
 * BroadcastReceiver that starts background services after device boot
 * based on user-configured preferences.
 *
 * Handles:
 * - BOOT_COMPLETED: Device finished booting
 * - QUICKBOOT_POWERON: Fast boot on some devices
 * - MY_PACKAGE_REPLACED: App was updated
 *
 * Services started (independently, each guarded by its own preference):
 * - [WakeWordListeningService] — if `aura_settings/wake_word_enabled` is true
 * - [AssistantForegroundService] with ACTION_START_CONNECTION_ONLY —
 *   if `aura_prefs/keep_alive_background` is true, so the MCP server can
 *   reach the device immediately after boot without the user opening the app.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val WAKE_PREFS_NAME     = "aura_settings"
        private const val KEY_WAKE_WORD       = "wake_word_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                handleBoot(context)
            }
        }
    }

    private fun handleBoot(context: Context) {
        // Alarms are wiped by a reboot; reminders are not.
        com.aura.aura_ui.agent.memory.ReminderScheduler.rescheduleAll(context)

        // AuraApplication has restored the last gate verdict by now.
        if (com.aura.aura_ui.remote.RemoteGateManager.isBlocked()) {
            Log.i(TAG, "Remote gate blocks AURA — not starting services")
            return
        }

        if (isWakeWordEnabled(context)) {
            Log.i(TAG, "Wake word enabled — starting WakeWordListeningService")
            WakeWordListeningService.start(context)
        }

        if (isKeepAliveEnabled(context)) {
            Log.i(TAG, "Keep-alive enabled — starting AssistantForegroundService (connection only)")
            AssistantForegroundService.startConnectionOnly(context)
        }
    }

    private fun isWakeWordEnabled(context: Context): Boolean =
        context.getSharedPreferences(WAKE_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAKE_WORD, false)

    private fun isKeepAliveEnabled(context: Context): Boolean =
        context.getSharedPreferences(AssistantForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(AssistantForegroundService.PREF_KEEP_ALIVE, false)
}
