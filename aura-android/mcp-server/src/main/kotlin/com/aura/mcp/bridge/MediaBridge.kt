package com.aura.mcp.bridge

/**
 * Port into `MediaSessionManager` — cross-app media playback control.
 *
 * This is the assistant-grade path for "pause the music" / "next track":
 * one transport-control call against the app's own `MediaSession`, with the
 * screen never involved. No launch, no perception, works with the screen off.
 *
 * Android gates `getActiveSessions()` behind the SAME "Notification access"
 * grant as [NotificationBridge], so one onboarding screen unlocks both planes.
 */
interface MediaBridge {

    fun accessState(): SpecialAccessState

    /** Active media sessions, most-recently-active first. */
    suspend fun activeSessions(): List<MediaSessionSnapshot>

    /**
     * Send [command] to the session owned by [packageName], or to the
     * top-priority active session when [packageName] is null.
     */
    suspend fun sendCommand(packageName: String?, command: MediaCommand): MediaCommandResult
}

/**
 * @param playbackState coarse state string: `playing`, `paused`, `stopped`,
 *   `buffering`, or `other`
 * @param positionMs current playback position, when the session reports one
 */
data class MediaSessionSnapshot(
    val packageName: String,
    val appName: String,
    val playbackState: String,
    val title: String?,
    val artist: String?,
    val positionMs: Long?,
    val durationMs: Long?,
)

enum class MediaCommand {
    PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS, STOP;

    companion object {
        /** Parse a model-supplied command string; null when unrecognized. */
        fun parse(raw: String?): MediaCommand? =
            raw?.trim()?.uppercase()?.let { candidate ->
                entries.firstOrNull { it.name == candidate }
            }
    }
}

data class MediaCommandResult(
    val success: Boolean,
    val targetPackage: String?,
    val error: String?,
)
