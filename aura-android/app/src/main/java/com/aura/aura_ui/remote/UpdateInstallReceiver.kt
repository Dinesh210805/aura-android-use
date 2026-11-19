package com.aura.aura_ui.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Receives Android installer results for the session [AppUpdater] committed.
 *
 * - Contract: `STATUS_PENDING_USER_ACTION` opens the system "update this app?" screen;
 *   `STATUS_SUCCESS` does nothing (Android replaces this process); anything else sets
 *   [UpdateProgress.Failed], so the screens offer Try again and the browser.
 * - Change together: the `<receiver>` entry in `AndroidManifest.xml` (not exported).
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    AppUpdater.publish(UpdateProgress.Failed("The update wasn't installed."))
                    return
                }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { AppUpdater.publish(UpdateProgress.Failed("Couldn't open the installer.")) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("UpdateInstallReceiver", "install ended with status $status: $message")
                AppUpdater.publish(UpdateProgress.Failed(if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "The update was cancelled." else "The update wasn't installed."))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
