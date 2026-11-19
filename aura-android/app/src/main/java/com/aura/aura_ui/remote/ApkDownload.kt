package com.aura.aura_ui.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads [url] into [dest], reporting progress as a whole percent.
 *
 * - Contract: returns true only when the whole body is on disk. [onPercent] gets 0–100, or null
 *   when the server sends no `Content-Length`. Runs on [Dispatchers.IO].
 * - Fails: a non-2xx response or an IO error deletes [dest] and returns false. Cancelling the
 *   coroutine cancels the HTTP call, deletes [dest] and rethrows the cancellation.
 * - Why the caller picks the client: the update APK is about 156 MB, so it needs a per-read stall
 *   timeout, not the long whole-call timeouts of the agent's client.
 */
suspend fun downloadApk(client: OkHttpClient, url: String, dest: File, onPercent: (Int?) -> Unit): Boolean =
    withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).build())
        // A child is cancelled the moment this coroutine is, even while it is blocked in read(),
        // so the socket closes now rather than after the 60 s read timeout. Without that, a quick
        // "Try again" could race the old download for the same file. UNDISPATCHED: a queued child
        // cancelled before it ever ran skips its body, finally included, so it must be waiting now.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return@withContext false.also { dest.delete() }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 }
                dest.parentFile?.mkdirs()
                var read = 0L
                var lastPercent = -1
                onPercent(if (total == null) null else 0)
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total != null) {
                                val percent = (read * 100 / total).toInt().coerceAtMost(100)
                                if (percent != lastPercent) onPercent(percent).also { lastPercent = percent }
                            }
                        }
                    }
                }
                true
            }
        } catch (e: IOException) {
            dest.delete()
            ensureActive() // a cancelled call surfaces as IOException; report it as cancellation
            false
        } catch (e: CancellationException) {
            dest.delete()
            throw e
        } finally {
            watcher.cancel()
        }
    }
