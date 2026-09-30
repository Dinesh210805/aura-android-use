package com.aura.aura_ui.eval

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The device's half of the AndroidWorld handshake.
 *
 * ### Why the phone cannot score itself
 *
 * AndroidWorld does not grade an agent by watching what it did. It grades the *device state* left
 * behind: `task.is_successful(env)` reads the app's SQLite rows, the file it should have written,
 * the setting it should have flipped. Both that check and its mirror image — `initialize_task(env)`,
 * which force-stops the app, wipes and re-seeds its database, and pins the clock — are Python that
 * drives adb from a PC.
 *
 * None of that can move on-device. So the cockpit becomes the *trigger* and a small host process
 * (`scripts/bench/referee.py`) stays the *referee*. The phone asks it to set the world up, runs the
 * goal the way a user would, then asks it for the verdict.
 *
 * ### Why the goal comes back from [prepare] rather than living in the catalog
 *
 * AndroidWorld generates each goal from the run seed at setup time — "Create a note named
 * `2023_10_15_wistful_dawn.md`" is a different sentence on a different seed. `androidworld.json`
 * therefore carries a placeholder, and the sentence the agent is actually given arrives here. This
 * matters beyond cosmetics: the trace is joined back to its task *by goal string*, so a runner that
 * kept using the placeholder would find no trace for any of the 116 and never say why.
 *
 * ### Failure is not a fail
 *
 * If [prepare] cannot reach the host, or setup throws, the task must **not** run. An agent turned
 * loose on an unprepared device produces a confident failure that looks exactly like a real one —
 * a false negative in a published benchmark, which is worse than a missing row. The runner routes
 * that to [EvalStatus.ERROR] instead.
 */
class EvalHostReferee(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    @Serializable
    private data class Reply(
        val ok: Boolean = false,
        val error: String = "",
        val goal: String = "",
        val success: Boolean = false,
        val detail: String = "",
    )

    /**
     * Seed the device for one task and start recording. Returns the goal to give the agent.
     *
     * @throws IOException when the host is unreachable or setup failed. Deliberately loud: see the
     *   class note on why an unprepared run is worse than no run.
     */
    suspend fun prepare(task: EvalTask): String {
        val name = requireNotNull(task.awTask) { "task ${task.number} is not an AndroidWorld task" }
        val reply = post("prepare", name)
        if (!reply.ok || reply.goal.isBlank()) {
            throw IOException(reply.error.ifBlank { "host returned no goal for $name" })
        }
        return reply.goal
    }

    /**
     * Stop recording, ask AndroidWorld for the verdict, and tear the task down.
     *
     * Called even when the agent threw or timed out — the host still has a camera running and a
     * task to dismantle, and "what does the device look like now" is a meaningful question after a
     * timeout. A run that never touched the right app simply scores 0.
     */
    suspend fun score(task: EvalTask): Pair<EvalVerdict, String> {
        val name = requireNotNull(task.awTask) { "task ${task.number} is not an AndroidWorld task" }
        val reply = post("score", name)
        if (!reply.ok) throw IOException(reply.error.ifBlank { "host could not score $name" })
        val verdict = if (reply.success) EvalVerdict.PASS else EvalVerdict.FAIL
        return verdict to reply.detail
    }

    /**
     * Tell the host to drop whatever it prepared and stop recording.
     *
     * Needed because [EvalRunner.abort] cancels the coroutine, so [score] — which is what normally
     * stops the camera — never runs. Left alone the host keeps `screenrecord` chunking to
     * `/sdcard` until something else happens to prepare a task, filling the emulator's storage and
     * leaving the aborted task set up underneath the next one.
     *
     * Best-effort by design: this fires while the user is already walking away from a stuck phone,
     * and an unreachable host at that moment is not worth a second error on screen.
     */
    suspend fun abandon() {
        runCatching { post("abandon", "") }
            .onFailure { Log.w(TAG, "could not abandon on the host: ${it.message}") }
    }

    /** True when the host is up. Used to warn before a sweep rather than at task 1. */
    suspend fun reachable(): Boolean = runCatching { post("ping", "").ok }.getOrDefault(false)

    private suspend fun post(path: String, task: String): Reply = withContext(Dispatchers.IO) {
        val body = json.encodeToString(mapOf("task" to task)).toByteArray()
        val conn = (URL("$baseUrl/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            // Generous, and not a guess: seeding an app means force-stopping it, pushing a database
            // and waiting for it to come back, and `is_successful` re-reads it. Thirty seconds is
            // routine. A short read timeout here would abort setup halfway and leave the emulator
            // in a state the next task inherits.
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            json.decodeFromString<Reply>(text.ifBlank { "{}" })
        } catch (io: IOException) {
            Log.w(TAG, "referee $path failed: ${io.message}")
            throw IOException("host referee unreachable at $baseUrl — is referee.py running?", io)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "EvalHostReferee"

        /**
         * `10.0.2.2` is the emulator's alias for the host loopback — the only address the PC has
         * from inside the AVD. AndroidWorld requires the emulator anyway (it talks to the console
         * port), so there is no case where a physical device needs a different address here.
         */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8778"

        private const val CONNECT_TIMEOUT_MS = 5_000
        /**
         * Five minutes. Setup is the long pole, not scoring: a handful of tasks push media onto
         * the device before the agent is allowed to look at it (MarkorTranscribeVideo,
         * ExpenseAddMultipleFromGallery). A read timeout mid-setup does not just fail one task, it
         * leaves the emulator half-seeded for the next one.
         */
        private const val READ_TIMEOUT_MS = 300_000

        private val json = Json { ignoreUnknownKeys = true }
    }
}
