package com.aura.aura_ui.agentcore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads what the user attached, for the model: pictures sized for a model, the text of documents
 * a window at a time, and the text in a picture for models that cannot see.
 *
 * - Contract: every call is safe on any thread (IO inside) and returns null rather than throwing.
 *   A PDF is read page by page only as far as the window asked for, so a 50-page scan costs one
 *   page's OCR per window, not fifty before the first answer.
 * - Fails: an unreadable or oversized file is null; a page that fails to render is skipped.
 * - Reads: the attachment's content URI (the chat holds a lasting read grant).
 * - Why JPEG at 1,568 px: every provider accepts it, and a bigger picture costs tokens without
 *   showing a model more.
 */
class AttachmentReader(private val app: Context) {
    private val jpegs = object : LruCache<String, ByteArray>(JPEG_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val texts = LruCache<String, String>(TEXT_CACHE_ENTRIES)
    private val pdfPages = LruCache<String, PdfText>(TEXT_CACHE_ENTRIES)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The picture as JPEG, at most [MAX_IMAGE_PX] on its long side. Same bytes every call, so a replayed image keeps the prompt cache. */
    suspend fun image(file: Attachment): ByteArray? = withContext(Dispatchers.IO) {
        jpegs.get(file.uri)?.let { return@withContext it }
        val bitmap = decode(Uri.parse(file.uri), MAX_IMAGE_PX) ?: return@withContext null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (out.size() > MAX_IMAGE_BYTES) {
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY_SMALL, out)
        }
        out.toByteArray().also { jpegs.put(file.uri, it) }
    }

    /** The text in a picture, "" when there is none, null when it cannot be read. */
    suspend fun ocr(file: Attachment): String? = withContext(Dispatchers.IO) {
        val bitmap = decode(Uri.parse(file.uri), OCR_PX) ?: return@withContext null
        runCatching { recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim() }.getOrNull()
    }

    /** [WINDOW] characters of the attachment's text from [offset], or null when it has no text AURA can read. */
    suspend fun text(file: Attachment, offset: Int = 0): DocChunk? = withContext(Dispatchers.IO) {
        val start = offset.coerceAtLeast(0)
        if (file.mimeType == PDF) return@withContext pdf(file, start)
        val whole = texts.get(file.uri) ?: readWhole(file)?.also { texts.put(file.uri, it) } ?: return@withContext null
        DocChunk(whole.drop(start).take(WINDOW), start.coerceAtMost(whole.length), whole.length)
    }

    private fun readWhole(file: Attachment): String? {
        val bytes = bytes(file) ?: return null
        return when {
            file.mimeType.startsWith("text/") || file.mimeType == "application/json" -> String(bytes)
            file.mimeType == DocText.DOCX || file.displayName.endsWith(".docx", true) -> DocText.docx(bytes)
            file.mimeType == DocText.XLSX || file.displayName.endsWith(".xlsx", true) -> DocText.xlsx(bytes)
            else -> null
        }
    }

    private class PdfText(val pages: MutableList<String> = mutableListOf(), var count: Int = -1) {
        val joined: String get() = pages.joinToString("\n\n")
    }

    private suspend fun pdf(file: Attachment, start: Int): DocChunk? {
        val state = pdfPages.get(file.uri) ?: PdfText().also { pdfPages.put(file.uri, it) }
        val pfd = runCatching { app.contentResolver.openFileDescriptor(Uri.parse(file.uri), "r") }.getOrNull() ?: return null
        runCatching {
            PdfRenderer(pfd).use { renderer ->
                state.count = renderer.pageCount
                while (state.pages.size < state.count && state.joined.length < start + WINDOW) {
                    val index = state.pages.size
                    val text = renderer.openPage(index).use { page -> pageText(page) }
                    state.pages += "[page ${index + 1} of ${state.count}]\n$text"
                }
            }
        }.onFailure { pfd.close() }
        val whole = state.joined
        if (whole.isBlank()) return null
        val total = if (state.pages.size == state.count) whole.length else null
        return DocChunk(whole.drop(start).take(WINDOW), start.coerceAtMost(whole.length), total)
    }

    private suspend fun pageText(page: PdfRenderer.Page): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PDF_TEXT_EXTENSION) {
            @Suppress("NewApi")
            val text = runCatching { page.textContents.joinToString("\n") { it.text } }.getOrNull()
            if (!text.isNullOrBlank()) return text.trim()
        }
        // A scanned page has no text layer: render it and read it.
        val scale = OCR_PX.toFloat() / maxOf(page.width, page.height)
        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return runCatching { recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim() }.getOrDefault("")
    }

    private fun bytes(file: Attachment): ByteArray? = runCatching {
        if (file.sizeBytes > MAX_FILE_BYTES) return null
        app.contentResolver.openInputStream(Uri.parse(file.uri))?.use { it.readBytes() }
    }.getOrNull()

    /** The picture at most [px] on its long side, upright (EXIF applied where the platform decoder does it). */
    private fun decode(uri: Uri, px: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                val long = maxOf(info.size.width, info.size.height)
                if (long > px) decoder.setTargetSize(info.size.width * px / long, info.size.height * px / long)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = sampleSize(bounds.outWidth, bounds.outHeight, px)
            app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }.getOrNull()

    companion object {
        const val WINDOW = 20_000
        const val PDF = "application/pdf"
        /** What the composer's file picker offers. */
        val PICKABLE = arrayOf("image/*", "text/*", "application/json", PDF, DocText.DOCX, DocText.XLSX)
        private const val MAX_IMAGE_PX = 1_568
        private const val MAX_IMAGE_BYTES = 300 * 1024
        private const val JPEG_QUALITY = 85
        private const val JPEG_QUALITY_SMALL = 65
        private const val OCR_PX = 2_000
        private const val MAX_FILE_BYTES = 20L * 1024 * 1024
        private const val JPEG_CACHE_BYTES = 6 * 1024 * 1024
        private const val TEXT_CACHE_ENTRIES = 8
        private const val PDF_TEXT_EXTENSION = 13
    }
}

/**
 * `read_attachment`: the next window of a file attached in the current chat.
 * - Contract: resolves [name] only among the turn's own chat's attachments, so a model cannot
 *   reach another chat's files. The result is fenced FILE text and taints the turn.
 */
fun attachmentTools(reader: AttachmentReader, threads: ThreadStore): List<NativeTool> = listOf(
    NativeTool(
        ToolSpec(
            ai.koog.agents.core.tools.ToolDescriptor(
                name = "read_attachment",
                description = "Read more of a file the user attached in this chat (PDF, Word, Excel or text). Returns up to " +
                    "20,000 characters of its text starting at offset, and the total length. Use it only when the excerpt in " +
                    "the chat stops before the part the user asks about.",
                requiredParameters = listOf(
                    ai.koog.agents.core.tools.ToolParameterDescriptor(
                        "name",
                        "The attached file's name, exactly as shown in the chat.",
                        ai.koog.agents.core.tools.ToolParameterType.String,
                    ),
                ),
                optionalParameters = listOf(
                    ai.koog.agents.core.tools.ToolParameterDescriptor(
                        "offset",
                        "The character to start from. Default 0. To continue, pass the number of characters you have already seen.",
                        ai.koog.agents.core.tools.ToolParameterType.Integer,
                    ),
                ),
            ),
            ToolRisk.READ,
            ToolOrigin.NATIVE,
            ToolHub.CORE,
            resultOrigin = Origin.FILE,
        ),
    ) { args, turn ->
        val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val files = threads.attachments(turn.threadId).filterNot { it.isImage }
        val file = files.lastOrNull { it.displayName == name } ?: files.lastOrNull { it.displayName.equals(name, ignoreCase = true) }
        val chunk = file?.let { reader.text(it, offset) }
        when {
            file == null -> SourceResult(
                "No file named \"$name\" is attached in this chat. Attached: ${files.joinToString { it.displayName }.ifBlank { "none" }}.",
                isError = true,
            )
            chunk == null -> SourceResult("\"$name\" has no text AURA can read.", isError = true)
            else -> SourceResult(
                "Characters ${chunk.offset + 1} to ${chunk.end} of ${chunk.total ?: "more than ${chunk.end}"}:\n${chunk.text}" +
                    if (chunk.more) "" else "\n[end of file]",
            )
        }
    },
)
