package com.aura.aura_ui.mcp.bridge

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.agent.conversation.DeviceContextProvider
import com.aura.aura_ui.agent.conversation.DeviceContextSnapshot
import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.SpecialAccessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Composite read-only grounding for `device_context`. Every signal is best-effort: a throw or a
 * missing permission degrades that field to null (the spoken summary omits it) rather than failing.
 *
 * `foregroundApp` is intentionally null in Slice 1 — there is no public accessor for the
 * accessibility service's last-known foreground package yet, and guessing is worse than omitting.
 */
class AppDeviceContextProvider(
    context: Context,
    private val mediaBridge: MediaBridge,
) : DeviceContextProvider {

    private val appContext = context.applicationContext

    override suspend fun snapshot(): DeviceContextSnapshot = withContext(Dispatchers.Default) {
        DeviceContextSnapshot(
            timeText = SimpleDateFormat("h:mm a, EEEE", Locale.getDefault()).format(Date()),
            batteryPercent = batteryPercent(),
            isCharging = isCharging(),
            foregroundApp = foregroundAppLabel(),
            nowPlaying = nowPlaying(),
            wifiOn = runCatching {
                (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
            }.getOrNull(),
            bluetoothOn = runCatching {
                (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled
            }.getOrNull(),
            dndOn = runCatching {
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            }.getOrNull(),
        )
    }

    private fun batteryIntent(): Intent? = runCatching {
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    /**
     * The app the user is actually looking at, as its human-readable label.
     *
     * This was hardcoded to null since the field was introduced, so `device_context` has always
     * claimed not to know which app was open even though the accessibility service had the package
     * name all along. Null stays a legitimate answer — the service may be off — and the spoken
     * summary simply omits it rather than guessing.
     */
    private fun foregroundAppLabel(): String? = runCatching {
        val pkg = AuraAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
            ?: return@runCatching null
        // Our own overlay is never what the user means by "the app I'm in".
        if (pkg == appContext.packageName) return@runCatching null
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun batteryPercent(): Int? {
        val i = batteryIntent() ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100) / scale else null
    }

    private fun isCharging(): Boolean {
        val status = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private suspend fun nowPlaying(): String? {
        if (mediaBridge.accessState() != SpecialAccessState.ENABLED) return null
        val s = mediaBridge.activeSessions().firstOrNull { it.playbackState == "playing" } ?: return null
        val title = s.title ?: return s.appName
        return s.artist?.let { "$title by $it" } ?: title
    }
}
