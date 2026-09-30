package com.aura.aura_ui.utils

import android.content.Intent
import com.aura.aura_ui.data.AppInfo
import com.aura.aura_ui.data.IntentFilterInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [isLaunchable] — the fix for a real bug where the
 * Restricted Apps scan silently dropped Google Pay because OEMs preload it as
 * a system app (`isSystemApp = true`) on this device. `isSystemApp` only means
 * "shipped in the system partition"; it says nothing about whether the app has
 * a launcher icon the user can actually open. [isLaunchable] checks that
 * directly instead.
 */
class AppInventoryScannerTest {

    private fun launcherApp(pkg: String, systemApp: Boolean) = AppInfo(
        packageName = pkg,
        appName = pkg,
        isSystemApp = systemApp,
        intentFilters = listOf(IntentFilterInfo(action = Intent.ACTION_MAIN, categories = listOf(Intent.CATEGORY_LAUNCHER))),
    )

    @Test fun `a system app with a launcher entry is launchable — the Google Pay repro`() {
        // com.google.android.apps.nbu.paisa.user (Google Pay India) is flagged
        // FLAG_SYSTEM on OEM-preloaded devices but has a normal launcher icon.
        val gpay = launcherApp("com.google.android.apps.nbu.paisa.user", systemApp = true)
        assertTrue(gpay.isLaunchable())
    }

    @Test fun `a third-party app with a launcher entry is launchable`() {
        val app = launcherApp("com.example.bank", systemApp = false)
        assertTrue(app.isLaunchable())
    }

    @Test fun `an app with no intent filters at all is not launchable`() {
        val headless = AppInfo(packageName = "com.oem.internal.service", appName = "svc", isSystemApp = true)
        assertFalse(headless.isLaunchable())
    }

    @Test fun `an app with only a non-MAIN intent filter is not launchable`() {
        val viewOnly = AppInfo(
            packageName = "com.example.contentprovider",
            appName = "provider",
            intentFilters = listOf(IntentFilterInfo(action = Intent.ACTION_VIEW, dataScheme = "https")),
        )
        assertFalse(viewOnly.isLaunchable())
    }

    // ── Report.degraded ────────────────────────────────────────────────────────
    // The near-empty "Add app" picker shipped because every failure path collapsed
    // to a shorter list with no signal: a scan that found nothing and a device with
    // no apps rendered identically. `degraded` is the signal that was missing, so it
    // needs to be true in exactly the cases the UI must warn about.

    private fun report(apps: List<AppInfo>, launcherEntries: Int, error: String? = null) =
        AppInventoryScanner.Report(
            apps = apps,
            installedPackages = apps.size,
            launcherEntries = launcherEntries,
            skipped = 0,
            error = error,
        )

    @Test fun `a scan that returns every launchable app the OS reported is not degraded`() {
        val apps = listOf(launcherApp("com.a", false), launcherApp("com.b", false))
        assertFalse(report(apps, launcherEntries = 2).degraded)
    }

    @Test fun `finding fewer launchable apps than the OS reported is degraded`() {
        // The actual production symptom: the OS resolves 154 launcher entries but the
        // bulk installed-app query came back nearly empty.
        val apps = listOf(launcherApp("com.a", false))
        assertTrue(report(apps, launcherEntries = 154).degraded)
    }

    @Test fun `an error marks the scan degraded even when the counts happen to line up`() {
        val apps = listOf(launcherApp("com.a", false))
        assertTrue(report(apps, launcherEntries = 1, error = "Binder transaction too large").degraded)
    }

    @Test fun `a genuinely app-less device is not reported as degraded`() {
        // No launcher entries and no apps is consistent, not a failure - this is the
        // case that must NOT warn, or the warning becomes noise users learn to ignore.
        assertFalse(report(emptyList(), launcherEntries = 0).degraded)
    }

    @Test fun `non-launchable apps do not count toward the launchable total`() {
        val headless = AppInfo(packageName = "com.oem.svc", appName = "svc", isSystemApp = true)
        val r = report(listOf(headless, launcherApp("com.a", false)), launcherEntries = 2)
        assertEquals(2, r.apps.size)
        // One of the two is headless, so only 1 launchable vs 2 reported -> degraded.
        assertTrue(r.degraded)
    }
}
