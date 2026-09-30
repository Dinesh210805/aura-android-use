package com.aura.aura_ui.remote

/**
 * Minimum time between two [DeviceRegistry] writes when nothing in the row changed.
 *
 * Why: the free Firestore tier allows 20,000 writes a day for the whole project. One write per
 * install per day keeps the owner's "last seen today" meaningful for up to about 20,000 daily
 * installs; past that, writes fail quietly and the app is unaffected (Analytics still counts
 * users). Android restarts services freely and a crash-looping process can start many times a
 * minute, so writing on every launch would burn the quota on one broken device.
 */
const val REPORT_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

/**
 * Decides whether this launch should write the device's registry row. Pure; covered by
 * `ReportDecisionTest`.
 *
 * - Contract: returns false whenever diagnostics are off. Otherwise returns true when any of
 *   these holds:
 *   1. never reported (`lastReportMs <= 0`): a new install must appear immediately
 *   2. the version code changed: upgrades are reported without waiting for the interval
 *   3. [rowChanged]: another reported field changed (the user signed in, renamed AURA, updated
 *      Android, moved time zone)
 *   4. `intervalMs` has passed since the last report
 * - A clock that went backwards (`nowMs < lastReportMs`) counts as "report now". Otherwise
 *   reporting would stall until real time caught up.
 */
fun shouldReport(
    diagnosticsEnabled: Boolean,
    nowMs: Long,
    lastReportMs: Long,
    lastReportedVersionCode: Long,
    currentVersionCode: Long,
    rowChanged: Boolean = false,
    intervalMs: Long = REPORT_INTERVAL_MS,
): Boolean {
    if (!diagnosticsEnabled) return false
    if (lastReportMs <= 0L) return true
    if (lastReportedVersionCode != currentVersionCode) return true
    if (rowChanged) return true
    val elapsed = nowMs - lastReportMs
    if (elapsed < 0L) return true
    return elapsed >= intervalMs
}
