package com.aura.aura_ui.mcp.bridge

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.aura.aura_ui.notifications.AuraNotificationListenerService
import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.MediaCommand
import com.aura.mcp.bridge.MediaCommandResult
import com.aura.mcp.bridge.MediaSessionSnapshot
import com.aura.mcp.bridge.SpecialAccessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:app` binding for [MediaBridge] over `MediaSessionManager`.
 *
 * `getActiveSessions` is gated on the SAME "Notification access" grant as the
 * notification listener — Android hands out session visibility per listener
 * component, which is why [AuraNotificationListenerService.componentName] is
 * the lookup key here.
 */
class AppMediaBridge(context: Context) : MediaBridge {

    private val appContext = context.applicationContext

    override fun accessState(): SpecialAccessState =
        if (AuraNotificationListenerService.isAccessGranted(appContext)) {
            SpecialAccessState.ENABLED
        } else {
            SpecialAccessState.DISABLED
        }

    override suspend fun activeSessions(): List<MediaSessionSnapshot> =
        withContext(Dispatchers.Default) {
            controllers().map { it.toSnapshot() }
        }

    override suspend fun sendCommand(
        packageName: String?,
        command: MediaCommand,
    ): MediaCommandResult = withContext(Dispatchers.Default) {
        val controllers = controllers()
        if (controllers.isEmpty()) {
            return@withContext MediaCommandResult(
                success = false,
                targetPackage = null,
                error = "no active media session — nothing is playing or recently paused",
            )
        }
        val target = if (packageName != null) {
            controllers.firstOrNull { it.packageName == packageName }
                ?: return@withContext MediaCommandResult(
                    success = false,
                    targetPackage = null,
                    error = "no active media session for '$packageName' — " +
                        "active: ${controllers.joinToString(", ") { it.packageName }}",
                )
        } else {
            // getActiveSessions returns highest-priority (most recently active) first.
            controllers.first()
        }
        runCatching {
            target.transportControls.run {
                when (command) {
                    MediaCommand.PLAY -> play()
                    MediaCommand.PAUSE -> pause()
                    MediaCommand.STOP -> stop()
                    MediaCommand.NEXT -> skipToNext()
                    MediaCommand.PREVIOUS -> skipToPrevious()
                    MediaCommand.PLAY_PAUSE ->
                        if (target.playbackState?.state == PlaybackState.STATE_PLAYING) pause() else play()
                }
            }
            MediaCommandResult(success = true, targetPackage = target.packageName, error = null)
        }.getOrElse { t ->
            MediaCommandResult(false, target.packageName, t.message ?: "transport control failed")
        }
    }

    private fun controllers(): List<MediaController> = runCatching {
        val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager.getActiveSessions(AuraNotificationListenerService.componentName(appContext))
    }.getOrDefault(emptyList()) // SecurityException when access not granted — tools gate on accessState()

    private fun MediaController.toSnapshot(): MediaSessionSnapshot {
        val meta = metadata
        return MediaSessionSnapshot(
            packageName = packageName,
            appName = appLabel(packageName),
            playbackState = when (playbackState?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
                PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, null -> "stopped"
                else -> "other"
            },
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            positionMs = playbackState?.position?.takeIf { it >= 0 },
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
        )
    }

    private fun appLabel(packageName: String): String = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
