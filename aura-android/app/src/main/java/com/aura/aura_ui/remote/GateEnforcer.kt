package com.aura.aura_ui.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.aura.aura_ui.MainActivity
import com.aura.aura_ui.R
import com.aura.aura_ui.overlay.AuraOverlayService
import com.aura.aura_ui.services.AssistantForegroundService
import com.aura.aura_ui.services.WakeWordListeningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Turns a blocking [GateState] (kill switch, blocklist, version floor) into "AURA does nothing":
 * stops the MCP server, the wake word, the overlay and any agent run, and tells the user why.
 *
 * - Contract: [watch] once per process (from `AuraApplication`). The moment
 *   [RemoteGateManager.state] turns blocking, [shutDown] runs; when it clears, [restore] brings
 *   back the wake word if the user had it on. Entry points (overlay, wake word, assist, volume
 *   shortcut, boot, MCP start) also check [RemoteGateManager.isBlocked] themselves, so nothing
 *   starts in the gap before this collector runs.
 * - Why stop services with `stopService` and not their own "stop" actions: those also save the
 *   user's setting as off (for example the wake word), and a block must not rewrite settings.
 * - Fails: every step is best-effort and logged; one failure never skips the others.
 */
object GateEnforcer {

    private const val TAG = "GateEnforcer"
    private const val CHANNEL_ID = "aura_gate"
    private const val NOTIFICATION_ID = 7110

    fun watch(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch(Dispatchers.Main) {
            var wasBlocked = false
            RemoteGateManager.state
                .map { it.isBlocking() }
                .distinctUntilChanged()
                .collect { blocked ->
                    if (blocked) shutDown(app) else if (wasBlocked) restore(app)
                    wasBlocked = blocked
                }
        }
    }

    /** Stops everything AURA runs in the background and posts a notification explaining why. */
    fun shutDown(context: Context) {
        Log.w(TAG, "Gate is blocking; stopping AURA's services")
        step("overlay") { AuraOverlayService.hideIfRunning() }
        step("overlay service") { context.stopService(Intent(context, AuraOverlayService::class.java)) }
        step("wake word") { context.stopService(Intent(context, WakeWordListeningService::class.java)) }
        // Hosts the MCP server; its onDestroy closes the LAN port and any PC connection.
        step("MCP server") { context.stopService(Intent(context, AssistantForegroundService::class.java)) }
        step("notification") { notifyBlocked(context) }
    }

    /** Undoes [shutDown] where the user's own settings ask for it. */
    fun restore(context: Context) {
        Log.i(TAG, "Gate cleared; restoring services")
        step("notification") {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIFICATION_ID)
        }
        step("wake word") {
            if (context.getSharedPreferences("aura_settings", Context.MODE_PRIVATE).getBoolean("wake_word_enabled", false)) {
                WakeWordListeningService.start(context)
            }
        }
    }

    /**
     * Shows the full-screen block page (MainActivity renders `BlockScreen` while blocked). Used
     * when the user tries to open AURA from outside the app.
     *
     * - Fails: if the activity can't be started from here, shows the message as a toast.
     */
    fun openBlockScreen(context: Context) {
        val ok = runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.isSuccess
        if (!ok) {
            val msg = RemoteGateManager.blockMessage() ?: "AURA is currently unavailable."
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun notifyBlocked(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AURA availability", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when AURA needs an update or has been turned off."
                },
            )
        }
        val state = RemoteGateManager.latestState
        val title = if (state is GateState.VersionBlocked) "Update AURA to keep using it" else "AURA is turned off"
        val body = state.blockMessageOrNull() ?: "AURA is currently unavailable."
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Step '$name' failed: ${e.message}")
        }
    }
}
