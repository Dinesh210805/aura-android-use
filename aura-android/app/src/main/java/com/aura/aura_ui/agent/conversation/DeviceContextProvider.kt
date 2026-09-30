package com.aura.aura_ui.agent.conversation

/**
 * Cheap read-only grounding for the `device_context` Live tool. Any signal may be null when
 * unavailable; the spoken summary simply omits it (never guesses).
 */
data class DeviceContextSnapshot(
    val timeText: String,
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val foregroundApp: String?,
    val nowPlaying: String?,
    val wifiOn: Boolean?,
    val bluetoothOn: Boolean?,
    val dndOn: Boolean?,
)

/** Pure → a short spoken sentence. Order: time, battery, now-playing, foreground, connectivity. */
fun DeviceContextSnapshot.toSpokenSummary(): String {
    val parts = mutableListOf<String>()
    parts += "It's $timeText"
    batteryPercent?.let { parts += "battery's at $it%" + if (isCharging) " and charging" else "" }
    nowPlaying?.let { parts += "playing $it" }
    foregroundApp?.let { parts += "you're in $it" }
    val flags = buildList {
        wifiOn?.let { add("wifi ${onOff(it)}") }
        bluetoothOn?.let { add("bluetooth ${onOff(it)}") }
        dndOn?.let { if (it) add("do not disturb on") }
    }
    if (flags.isNotEmpty()) parts += flags.joinToString(", ")
    return parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
}

private fun onOff(b: Boolean) = if (b) "on" else "off"

/** Produces a [DeviceContextSnapshot]; the Android binding is [com.aura.aura_ui.mcp.bridge.AppDeviceContextProvider]. */
interface DeviceContextProvider {
    suspend fun snapshot(): DeviceContextSnapshot
}
