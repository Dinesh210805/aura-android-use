package com.aura.aura_ui.mcp.log

import java.io.File

/**
 * Naming for the images a session stores.
 *
 * The raw-capture path returns **JPEG** — `ScreenshotBridge.captureBase64Png` is PNG-era naming
 * over `AccessibilityShot.base64Jpeg` — while both loggers used to write the bytes to
 * `screenshots/<idx>.png`. Every consumer happened to survive that because bitmap decoders and
 * browsers sniff magic bytes, but the log was stating a format it did not contain, which is a
 * trap for anything reading these sessions programmatically.
 *
 * So: write the extension the bytes actually are, and read whichever extension is present —
 * sessions recorded before this change are still `.png` and must keep opening.
 */

/** Extensions a raw screenshot may carry, newest convention first. */
private val RAW_EXTENSIONS = listOf("jpg", "png")

/** The extension matching [bytes]: `jpg` for a JPEG SOI marker, `png` otherwise. */
internal fun imageExtensionFor(bytes: ByteArray): String =
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) "jpg" else "png"

/** True when [name] is a stored image — `<index>.jpg` or `<index>.png`. */
internal fun isSessionImageName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in RAW_EXTENSIONS

/**
 * The raw screenshot for [index] inside [sessionDir], whichever extension it was written with,
 * or null when none was captured.
 */
internal fun rawScreenshotFile(sessionDir: File, index: Int): File? {
    val dir = File(sessionDir, "screenshots")
    return RAW_EXTENSIONS.asSequence()
        .map { File(dir, "$index.$it") }
        .firstOrNull { it.exists() }
}

/**
 * Path of the raw screenshot for [index] relative to [sessionDir] (for an `<img src>` served with
 * `baseUrl = file://<sessionDir>/`), or null when none was captured.
 */
internal fun rawScreenshotRelativePath(sessionDir: File, index: Int): String? =
    rawScreenshotFile(sessionDir, index)?.let { "screenshots/${it.name}" }

/**
 * The "(verdict …, proof …)" tail of the end-of-task line.
 *
 * [RunVerdict.target] is null for goal types the count gate does not apply to; rendering that as
 * `0` produced "proof 1/0" in a transcript, so an uncounted run states the count alone.
 */
internal fun proofSummaryOf(verdict: RunVerdict): String {
    val proof = verdict.target?.let { "${verdict.proven}/$it" } ?: "${verdict.proven}"
    return " (verdict ${verdict.verdict}, proof $proof)"
}
