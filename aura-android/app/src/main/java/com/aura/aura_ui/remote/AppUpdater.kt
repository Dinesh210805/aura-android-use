package com.aura.aura_ui.remote

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Where an in-app update stands, for `UpdateDialog` and `BlockScreen`. */
sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    /** "Install unknown apps" is off for AURA; the user must allow it, then tap Update again. */
    data object NeedsInstallPermission : UpdateProgress
    /** [percent] is null when the server sent no length. */
    data class Downloading(val percent: Int?) : UpdateProgress
    /** Handed to Android; its confirm screen is up. */
    data object WaitingForInstall : UpdateProgress
    data class Failed(val reason: String) : UpdateProgress
}

/**
 * Downloads a release APK, checks it, and hands it to Android's installer.
 *
 * - Contract: one [install] at a time (the caller, `RemoteGateViewModel`, holds a single job).
 *   [progress] is process-wide so [UpdateInstallReceiver] can report the installer's answer.
 * - Fails: every failure ends in [UpdateProgress.Failed] with a one-line reason; the screens then
 *   offer the browser. Cancelling the coroutine deletes the partial file and returns to Idle.
 * - Writes: `cacheDir/update/aura-update.apk`, deleted once the session holds it or on failure.
 * - Reads: the URL in Remote Config `apk_url` (`RemoteGateManager.KEY_APK_URL`).
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    client: OkHttpClient,
) {
    // Per-read stall limit only: the APK is ~156 MB, so a whole-call timeout would cut slow links.
    private val downloadClient = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    val progress: StateFlow<UpdateProgress> = state

    suspend fun install(apkUrl: String) {
        if (apkUrl.isBlank()) return publish(UpdateProgress.Failed("No download link for this version."))
        if (!context.packageManager.canRequestPackageInstalls()) return publish(UpdateProgress.NeedsInstallPermission)

        val file = File(context.cacheDir, "update/aura-update.apk")
        publish(UpdateProgress.Downloading(null))
        val downloaded = try {
            downloadApk(downloadClient, apkUrl, file) { publish(UpdateProgress.Downloading(it)) }
        } catch (e: CancellationException) {
            publish(UpdateProgress.Idle)
            throw e
        }
        if (!downloaded) return publish(UpdateProgress.Failed("Download failed. Check your connection."))

        val installed = installedApkFacts(context)
        val verdict = if (installed == null) ApkVerdict.Unreadable else checkUpdateApk(readApkFacts(context, file), installed)
        if (verdict != ApkVerdict.Ok) {
            file.delete()
            return publish(UpdateProgress.Failed(reasonFor(verdict)))
        }

        try {
            withContext(Dispatchers.IO) { commitSession(file) }
            publish(UpdateProgress.WaitingForInstall)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "install session failed", e)
            publish(UpdateProgress.Failed("The update wasn't installed."))
        } finally {
            file.delete()
        }
    }

    /** Back to [UpdateProgress.Idle], e.g. after the user dismisses a failure. */
    fun reset() = publish(UpdateProgress.Idle)

    private fun commitSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("aura.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            // MUTABLE: the installer fills in EXTRA_STATUS and the confirm intent.
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun reasonFor(verdict: ApkVerdict): String = when (verdict) {
        ApkVerdict.Unreadable -> "The download is damaged, so it wasn't installed."
        ApkVerdict.WrongApp -> "The download isn't AURA, so it wasn't installed."
        ApkVerdict.NotNewer -> "The download isn't newer than this version."
        ApkVerdict.WrongSigner -> "This file isn't signed by AURA, so it wasn't installed."
        ApkVerdict.Ok -> ""
    }

    companion object {
        private const val TAG = "AppUpdater"
        private val state = MutableStateFlow<UpdateProgress>(UpdateProgress.Idle)

        /** Used by [UpdateInstallReceiver] for the installer's answer. */
        internal fun publish(progress: UpdateProgress) {
            state.value = progress
        }
    }
}
