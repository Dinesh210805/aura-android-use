package com.aura.aura_ui.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The on-device speech models (Piper voices, Moonshine STT): what to fetch, where it lives, whether it is ready.
 *
 * - Contract: a model is usable only once [isReady]. [startDownload] runs in a process scope (survives
 *   leaving the screen), is resumable per file, and is a no-op while one is already running.
 * - Fails: an HTTP/IO error ends in [State.Failed]; finished files are kept so a retry only fetches the rest.
 * - Reads/Writes: `huggingface.co/{repo}/resolve/main/{file}` → `filesDir/voice-models/{dirName}/`.
 * - Why Hugging Face and not the sherpa-onnx GitHub release: releases are `.tar.bz2`, and Android has no
 *   bzip2 decoder; the HF mirrors serve the same files unpacked.
 */
object LocalVoiceModels {

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    data class Model(val dirName: String, val repo: String, val files: List<String>, val approxMb: Int)

    /**
     * The English subset of espeak-ng that Piper phonemizes with. All three `lang/gmw` files are
     * included because each voice export names its own espeak voice (`en-us`, `en-gb-x-rp` or `en`)
     * and fails to load when that voice's file is missing.
     */
    internal val ESPEAK_EN: List<String> = listOf(
        "phondata", "phondata-manifest", "phonindex", "phontab", "intonations", "en_dict",
        "lang/gmw/en", "lang/gmw/en-US", "lang/gmw/en-GB-x-rp",
    ).map { "espeak-ng-data/$it" }

    /**
     * One Piper (VITS, medium quality, 22 kHz) voice, e.g. `en_US-lessac-medium`. Each voice is its own
     * self-contained model; the ~0.9 MB espeak subset is duplicated per voice rather than shared.
     * Contract: `files.first()` is the `.onnx` model (read by `PiperSpeaker`).
     */
    fun piper(voice: String) = Model(
        dirName = "vits-piper-$voice",
        repo = "csukuangfj/vits-piper-$voice",
        files = listOf("$voice.onnx", "tokens.txt") + ESPEAK_EN,
        approxMb = 64,
    )

    // ponytail: base (287 MB) for accuracy; csukuangfj/sherpa-onnx-moonshine-tiny-en-int8 is 124 MB if size matters.
    val MOONSHINE = Model(
        dirName = "moonshine-base-en-int8",
        repo = "csukuangfj/sherpa-onnx-moonshine-base-en-int8",
        files = listOf(
            "preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx",
            "cached_decode.int8.onnx", "tokens.txt",
        ),
        approxMb = 287,
    )

    private const val COMPLETE_MARKER = ".complete"
    private const val TAG = "LocalVoiceModels"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = ConcurrentHashMap<String, MutableStateFlow<State>>()

    fun state(context: Context, model: Model): StateFlow<State> =
        states.getOrPut(model.dirName) {
            MutableStateFlow(if (isReady(context, model)) State.Ready else State.Missing)
        }

    fun startDownload(context: Context, model: Model) {
        val flow = state(context, model) as MutableStateFlow
        if (flow.value is State.Downloading || flow.value == State.Ready) return
        flow.value = State.Downloading(0f)
        val appContext = context.applicationContext
        scope.launch {
            flow.value = try {
                download(appContext, model) { flow.value = State.Downloading(it) }
                State.Ready
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Download of ${model.dirName} failed", t)
                State.Failed(t.message ?: "Download failed")
            }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun dir(context: Context, model: Model): File =
        File(context.filesDir, "voice-models/${model.dirName}")

    fun isReady(context: Context, model: Model): Boolean =
        File(dir(context, model), COMPLETE_MARKER).exists()

    /** Fetch every missing file. [onProgress] gets 0..1, estimated from [Model.approxMb]. */
    private suspend fun download(context: Context, model: Model, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val root = dir(context, model)
            val expected = model.approxMb * 1_000_000f
            var done = 0L
            for (name in model.files) {
                val target = File(root, name)
                if (target.exists()) { done += target.length(); continue }
                target.parentFile?.mkdirs()
                val part = File(target.path + ".part")
                val request = Request.Builder()
                    .url("https://huggingface.co/${model.repo}/resolve/main/$name")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("Download failed for $name (HTTP ${resp.code})")
                    val body = resp.body ?: error("Empty response for $name")
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress((done / expected).coerceAtMost(0.99f))
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) error("Couldn't save $name")
            }
            File(root, COMPLETE_MARKER).createNewFile()
            onProgress(1f)
        }

    fun delete(context: Context, model: Model) {
        val flow = state(context, model) as MutableStateFlow
        if (flow.value is State.Downloading) return
        dir(context, model).deleteRecursively()
        flow.value = State.Missing
    }

    /**
     * Free the ~360 MB of the retired Kokoro voice, plus its `tts-cache/` clips (keyed by Kokoro
     * speaker ids, so they would replay in the wrong voice). Cheap no-op once gone.
     */
    fun deleteRetired(context: Context) {
        val kokoro = File(context.filesDir, "voice-models/kokoro-multi-lang-v1_0")
        if (!kokoro.exists()) return
        kokoro.deleteRecursively()
        File(context.filesDir, "tts-cache").deleteRecursively()
        Log.i(TAG, "Deleted retired Kokoro model and its TTS cache")
    }
}
