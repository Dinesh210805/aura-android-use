package com.aura.aura_ui.mcp.bridge

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper around Google ML Kit Text Recognition (Latin script).
 *
 * Used to overlay human-readable labels on top of the OmniParser-detected
 * bboxes — most clickable UI elements have visible text, and where they do
 * an OCR result is more useful than a generic "icon" class name.
 *
 * Bundle: `com.google.mlkit:text-recognition` ships the Latin model in-app
 * (~10MB), so no Play-Services download is required at runtime.
 */
class MlKitTextReader {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class TextBox(val text: String, val bounds: Rect)

    suspend fun readText(bitmap: Bitmap): List<TextBox> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val out = mutableListOf<TextBox>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val bounds = line.boundingBox ?: continue
                            val text = line.text.trim()
                            if (text.isNotEmpty()) out.add(TextBox(text, bounds))
                        }
                    }
                    if (cont.isActive) cont.resume(out)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }
}
