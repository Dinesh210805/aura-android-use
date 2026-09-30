package com.aura.aura_ui.mcp.log

import android.content.Context
import android.util.Base64
import android.util.Log
import com.aura.aura_ui.presentation.screens.trace.TraceRedaction
import com.aura.aura_ui.presentation.screens.trace.TraceVisibility
import java.io.File

/**
 * Export one session as a **single self-contained JSON file** — metadata plus every
 * screenshot embedded as base64.
 *
 * Why JSON rather than the existing PDF/HTML exports: a trace is evidence, and the two
 * things you want to do with evidence are diff it and feed it to something else. A PDF is
 * neither. JSON has no image type of its own, but base64-in-a-string is the standard way
 * around that, so one file still travels with its screenshots attached and nothing has to
 * be re-rendered to read it.
 *
 * Output goes to `cacheDir/exports/` because that path is already declared in
 * `file_paths.xml`, so the file can be handed straight to a share sheet through
 * `FileProvider` with no storage permission and no manifest change.
 *
 * ## Shape
 * ```json
 * {
 *   "export": { "format": "aura.session.v1", "exportedAtMillis": 0, "imagesEmbedded": true },
 *   "session": { …metadata.json, verbatim… },
 *   "images": [ { "index": 0, "kind": "som", "mimeType": "image/png", "base64": "iVBOR…" } ]
 * }
 * ```
 * `session` is copied through byte-for-byte rather than re-serialised, so an export can
 * never drift from the canonical record — including fields this build does not know about.
 */
class SessionJsonExporter(private val context: Context) {

    private val store = McpSessionStore(context)

    /**
     * Write the export and return the file, or null when the session cannot be read.
     *
     * Set [includeImages] false for a small, text-only export (base64 inflates PNGs by ~33%,
     * so a long run with full-resolution screenshots can reach tens of megabytes).
     */
    /**
     * The whole session folder as a zip — metadata, every raw request/response/result byte-exact,
     * screenshots. The developer export: the JSON export redacts and cannot carry tens of MB of
     * raw bodies, and a trace someone else is going to debug needs exactly those.
     */
    fun exportFolder(sessionId: String): File? {
        val dir = store.sessionDir(sessionId) ?: return null
        val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, "aura-trace-$sessionId.zip")
        return runCatching {
            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            out
        }.getOrNull()
    }

    fun export(sessionId: String, includeImages: Boolean = true): File? {
        val dir = store.sessionDir(sessionId) ?: return null
        val metadata = File(dir, "metadata.json").takeIf { it.exists() } ?: return null

        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "aura-trace-$sessionId.json")

        return runCatching {
            // Streamed, never assembled in memory: a 20-step run held as one String — twice,
            // once to encode and once to write — is an OOM on a real device, not a theory.
            outFile.bufferedWriter().use { out ->
                out.write("{\n")
                out.write("  \"export\": {\n")
                out.write("    \"format\": \"$FORMAT\",\n")
                out.write("    \"exportedAtMillis\": ${System.currentTimeMillis()},\n")
                out.write("    \"sessionId\": ${quote(sessionId)},\n")
                out.write("    \"imagesEmbedded\": $includeImages\n")
                out.write("  },\n")

                out.write("  \"session\": ")
                // Redacted here and not only at the screen, because THIS is the copy that leaves
                // the phone. The screen decides what someone looks at; an export is what they send
                // to somebody else. In a release build TraceRedaction always strips the internals
                // — see TraceVisibility for why that is pinned rather than defaulted.
                out.write(
                    TraceRedaction.redactMetadata(
                        metadata.readText(),
                        TraceVisibility.debugMode(context),
                    ),
                )
                out.write(",\n")

                out.write("  \"images\": [")
                if (includeImages) {
                    var first = true
                    imagesOf(sessionId, dir).forEach { (kind, index, file) ->
                        if (!first) out.write(",")
                        first = false
                        out.write("\n    {\n")
                        out.write("      \"index\": $index,\n")
                        out.write("      \"kind\": ${quote(kind)},\n")
                        out.write("      \"mimeType\": ${quote(sniffMimeType(file))},\n")
                        out.write("      \"bytes\": ${file.length()},\n")
                        out.write("      \"base64\": \"")
                        streamBase64(file, out)
                        out.write("\"\n    }")
                    }
                    if (!first) out.write("\n  ")
                }
                out.write("]\n}\n")
            }
            Log.i(TAG, "Exported ${outFile.length()} bytes to ${outFile.absolutePath}")
            outFile
        }.onFailure { Log.w(TAG, "JSON export failed for $sessionId", it) }.getOrNull()
    }

    /**
     * Read the type off the bytes, not off the filename.
     *
     * Raw screenshots are written as **JPEG** into a file called `<idx>.png`
     * (`AccessibilityScreenshotSource` compresses to JPEG for size), while the SoM and
     * gesture-annotated images really are PNG. Trusting the extension would stamp
     * `image/png` on JPEG payloads, and anything decoding the export by declared type would
     * fail on exactly the images the run actually captured.
     */
    private fun sniffMimeType(file: File): String = runCatching {
        val head = ByteArray(3)
        file.inputStream().use { it.read(head) }
        when {
            head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "image/png"
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }.getOrDefault("application/octet-stream")

    /** Every stored image for the session: the SoM overlay, the gesture annotation, the raw shot. */
    private fun imagesOf(sessionId: String, dir: File): List<Triple<String, Int, File>> =
        buildList {
            listOf("som", "annotated", "screenshots").forEach { kind ->
                File(dir, kind).listFiles()
                    // Raw shots are JPEG-named now; som/annotated stay PNG (both are re-encoded).
                    ?.filter { it.isFile && isSessionImageName(it.name) }
                    ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
                    ?.forEach { file ->
                        val index = file.nameWithoutExtension.toIntOrNull() ?: return@forEach
                        add(Triple(kind, index, file))
                    }
            }
        }

    /**
     * Base64-encode [file] straight into [out] in chunks.
     *
     * The chunk size must be a multiple of 3: base64 encodes 3 input bytes to 4 output
     * characters, so a 3-aligned chunk emits no padding and the pieces concatenate into a
     * valid whole. [Base64.NO_WRAP] matters too — the default inserts newlines, which are
     * not legal inside a JSON string.
     */
    private fun streamBase64(file: File, out: Appendable) {
        val buffer = ByteArray(CHUNK_BYTES)
        file.inputStream().buffered().use { input ->
            while (true) {
                // Fill the buffer completely before encoding — a short read mid-file would
                // otherwise emit a padded (`=`) chunk in the middle of the stream, and the
                // concatenation would no longer decode. (`readNBytes` is API 33+; minSdk is 26.)
                var filled = 0
                while (filled < buffer.size) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
                if (filled == 0) break
                out.append(Base64.encodeToString(buffer.copyOf(filled), Base64.NO_WRAP))
                if (filled < buffer.size) break
            }
        }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val TAG = "SessionJsonExport"
        const val FORMAT = "aura.session.v1"
        /** 48 KiB, and a multiple of 3 so chunks concatenate cleanly. */
        const val CHUNK_BYTES = 49_152
    }
}
