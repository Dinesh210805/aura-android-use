package com.aura.aura_ui.presentation.screens.trace

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

// ============================================================================
// TRACE ATOMS — the small pieces the agent-trace screen is built from.
//
// Colour discipline (Mono, plus this screen's developer-trace extension):
//   • status is the only place a *whole element* is coloured — ✓ ok / ✗ fail
//   • LLM meta carries one indigo accent so a turn's model call is findable
//   • JSON tokens get muted syntax hues (see JsonPalette) — never Blood
// Everything else stays ink-on-canvas.
// ============================================================================

internal val OkGreen = Color(0xFF2E7D32)
internal val FailRed = Mono.Blood
internal val WarnAmber = Color(0xFFB26A00)
internal val LlmIndigo = Color(0xFF5457C4)

/** Below this many characters a payload renders open; above it, it starts collapsed. */
internal const val INLINE_LIMIT = 180

@Composable
internal fun SectionTitle(text: String, scheme: MonoScheme) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = scheme.textPrimary,
    )
}

@Composable
internal fun MonoLine(text: String, scheme: MonoScheme, color: Color = scheme.textSecondary) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}

/** A wrapping row of chips. Wrapping, not scrolling — this screen has no sideways anything. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlowRowChips(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
internal fun TraceChip(text: String, color: Color, scheme: MonoScheme, outlined: Boolean = false) {
    Surface(
        color = if (outlined) scheme.chip else color.copy(alpha = 0.13f),
        shape = Mono.ShapeChip,
        border = if (outlined) BorderStroke(1.dp, scheme.outline) else null,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = if (outlined) scheme.textSecondary else color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun StatusGlyph(success: Boolean) {
    val color = if (success) OkGreen else FailRed
    Box(
        modifier = Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (success) "✓" else "✗",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

// ── Payloads ────────────────────────────────────────────────────────────────

/**
 * A labelled payload: header (label · size · raw⇄pretty · copy) over a wrapped body.
 *
 * Two rules this screen is built on live here:
 *  - **The body wraps.** Nothing on the trace scrolls horizontally; a JSON line that runs
 *    off-screen is a line nobody reads.
 *  - **Copy takes the raw string**, never the prettified one — see [humanizePayload].
 */
@Composable
internal fun PayloadSection(
    label: String,
    raw: String,
    scheme: MonoScheme,
    accent: Color = scheme.outline,
    collapsible: Boolean = true,
    startCollapsed: Boolean = true,
    /** Original length when the logger capped this payload; null when [raw] is complete. */
    truncatedFrom: Int? = null,
) {
    var collapsed by remember(label, raw) { mutableStateOf(collapsible && startCollapsed) }
    var pretty by remember(label, raw) { mutableStateOf(true) }
    // Parse only what is on screen. A collapsed 24k perceive payload parsed + re-encoded the
    // moment its LazyColumn item scrolled into view is precisely the cost collapsing exists
    // to defer, so this stays behind the collapsed check.
    val blocks = remember(raw, collapsed) {
        if (collapsed) null else buildPayloadBlocks(raw)
    }
    // Offering a toggle that changes nothing is noise; only show it when the two differ.
    val reformatted = blocks != null && (blocks.size > 1 || blocks.firstOrNull().let {
        it !is PayloadBlock.Prose || it.text != raw
    })

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .let { if (collapsible) it.clickable { collapsed = !collapsed } else it }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (collapsible) {
                Icon(
                    if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (collapsed) "Expand" else "Collapse",
                    modifier = Modifier.size(18.dp),
                    tint = scheme.textSecondary,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (collapsible) "$label · ${raw.length} chars" else label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.textSecondary,
            )
            // A copied payload that is silently a preview is worse than no copy button at all —
            // say so, in the one place someone is about to copy it from.
            truncatedFrom?.let {
                Spacer(Modifier.width(6.dp))
                TraceChip("cut from $it", WarnAmber, scheme)
            }
            Spacer(Modifier.weight(1f))
            if (!collapsed && reformatted) {
                Text(
                    if (pretty) "raw" else "formatted",
                    modifier = Modifier
                        .clip(Mono.ShapeChip)
                        .clickable { pretty = !pretty }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.textSecondary,
                )
            }
            CopyButton(raw, label, scheme)
        }
        if (!collapsed && blocks != null) {
            if (pretty) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    blocks.forEach { block -> PayloadBlockView(block, scheme, accent) }
                }
            } else {
                RawBox(raw, scheme, accent)
            }
        }
    }
}

/**
 * Render one block in the typeface its content deserves.
 *
 * The single biggest readability win on this screen is refusing to render everything as code.
 * A tool's summary line is a sentence and reads as one; only the structured half is monospace.
 */
@Composable
private fun PayloadBlockView(block: PayloadBlock, scheme: MonoScheme, accent: Color) {
    when (block) {
        is PayloadBlock.Prose -> Text(
            block.text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            // Justified: these are full-width paragraphs, and a flush edge keeps a wall of
            // summary text from looking as ragged as the JSON beside it.
            textAlign = TextAlign.Justify,
            color = if (block.isWarning) WarnAmber else scheme.textPrimary,
            fontWeight = if (block.isWarning) FontWeight.Medium else FontWeight.Normal,
        )

        is PayloadBlock.Json -> {
            val palette = remember(scheme) { JsonPalette.of(scheme) }
            val rendered = remember(block, scheme) {
                highlightJson(block.payload, palette, scheme.textPrimary)
            }
            Surface(
                color = scheme.canvas,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.5.dp, accent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    rendered,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = scheme.textPrimary,
                )
            }
        }

        is PayloadBlock.Elided -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.chip)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("🖼", fontSize = 13.sp)
            Text(
                block.label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.textSecondary,
            )
        }
    }
}

/** The exact stored string, unformatted — what the `raw` toggle shows. */
@Composable
private fun RawBox(raw: String, scheme: MonoScheme, accent: Color) {
    Surface(
        color = scheme.canvas,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            raw,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = scheme.textSecondary,
        )
    }
}

/**
 * The text-SoM for one grounding step — see `ScreenCanvas`.
 *
 * Deliberately NOT a [PayloadSection]. That component's contract is "the body wraps;
 * nothing on the trace scrolls horizontally", which is right for JSON and fatal here:
 * a wrapped character grid is not a degraded picture, it is noise. Every row must stay
 * on one line or the drawing is gone.
 *
 * Rather than break the no-horizontal-scroll rule, the font is sized to fit. Monospace
 * advance width is ~0.6 em, so the widest row the canvas can produce (`cols` + the two
 * border characters) is solved back into a font size and clamped to something still
 * readable. `ScreenCanvas` renders at 44 columns precisely so this lands mid-range on
 * an ordinary phone rather than at the floor.
 */
@Composable
internal fun ScreenCanvasSection(canvas: String, scheme: MonoScheme, accent: Color = scheme.outline) {
    var collapsed by remember(canvas) { mutableStateOf(false) }
    val widestRow = remember(canvas) { canvas.lines().maxOf { it.length } }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { collapsed = !collapsed }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                modifier = Modifier.size(18.dp),
                tint = scheme.textSecondary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "screen",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            CopyButton(canvas, "screen", scheme)
        }
        if (!collapsed) {
            Surface(
                color = scheme.canvas,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.5.dp, accent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BoxWithConstraints(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    val fitted = (maxWidth.value / (widestRow.coerceAtLeast(1) * 0.6f))
                        .coerceIn(5f, 11f)
                    Text(
                        canvas,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fitted.sp,
                        // Tight, because a character grid drawn with airy line spacing
                        // stops reading as connected boxes.
                        lineHeight = (fitted * 1.15f).sp,
                        softWrap = false,
                        color = scheme.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * The verbatim `read_screen` grid — the exact string the agent read, never re-derived.
 *
 * See [ScreenCanvasSection] for the sibling that *redraws* a picture from JSON; this one
 * has nothing to redraw because the tool's own result already IS the picture. Tap opens
 * [GridZoomDialog] full-screen; the inline preview here stays small and canvas-only so
 * the trace list doesn't turn into a wall of ASCII on a busy run.
 */
@Composable
internal fun GridPayloadSection(raw: String, scheme: MonoScheme, accent: Color = scheme.outline) {
    var expanded by remember { mutableStateOf(false) }
    val canvasOnly = remember(raw) { raw.screenCanvasRegion() }
    val widestRow = remember(canvasOnly) { canvasOnly.lines().maxOf { it.length } }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "screen",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            CopyButton(raw, "screen", scheme)
        }
        Surface(
            color = scheme.canvas,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.5.dp, accent.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        ) {
            BoxWithConstraints(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                val fitted = (maxWidth.value / (widestRow.coerceAtLeast(1) * 0.6f))
                    .coerceIn(5f, 11f)
                Text(
                    canvasOnly,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fitted.sp,
                    lineHeight = (fitted * 1.15f).sp,
                    softWrap = false,
                    color = scheme.textSecondary,
                )
            }
        }
    }
    if (expanded) {
        GridZoomDialog(raw, onDismiss = { expanded = false })
    }
}

/**
 * Full-screen viewer for a [GridPayloadSection] grid. Pinch-zoom, pan and double-tap-to-fit
 * — the same interaction [ImageZoomDialog] uses for a screenshot, just wrapping monospace
 * text instead of a bitmap: there is no image here, only characters, so the "photo" is the
 * text itself scaled up.
 *
 * Shows the canvas only, never the element table below it — that table exists for scanning
 * a long list of som_ids, which is a "read", not a "look", and doesn't belong on a picture
 * someone just asked to see full-screen. The Copy button still copies the FULL raw payload
 * (canvas + table), so nothing in the table is ever unreachable — just not drawn here.
 */
@Composable
internal fun GridZoomDialog(raw: String, onDismiss: () -> Unit) {
    val canvasOnly = remember(raw) { raw.screenCanvasRegion() }
    val widestRow = remember(canvasOnly) { canvasOnly.lines().maxOf { it.length }.coerceAtLeast(1) }
    val lineCount = remember(canvasOnly) { canvasOnly.lines().size.coerceAtLeast(1) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // Largest font at which the WHOLE grid — every row, every column — sits on
            // screen with no wrap and no clip. This is the double-tap-to-fit baseline;
            // pinch zooms past it via graphicsLayer, same as the image viewer.
            val fitBySize = maxWidth.value / (widestRow * 0.6f)
            val fitByLines = maxHeight.value / (lineCount * 1.15f)
            val fitSp = min(fitBySize, fitByLines).coerceIn(4f, 48f)

            Text(
                canvasOnly,
                fontFamily = FontFamily.Monospace,
                fontSize = fitSp.sp,
                lineHeight = (fitSp * 1.15f).sp,
                softWrap = false,
                color = Color.White,
                modifier = Modifier
                    // Tap detection first — see ImageZoomDialog for why the order matters.
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = min(DOUBLE_TAP_SCALE, MAX_SCALE)
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            scale = (scale * gestureZoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            val maxX = max(0f, size.width * (scale - 1f) / 2f)
                            val maxY = max(0f, size.height * (scale - 1f) / 2f)
                            offsetX = (offsetX + pan.x * scale).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y * scale).coerceIn(-maxY, maxY)
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            )
            Row(
                Modifier.align(Alignment.TopEnd).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${"%.1f".format(scale)}×  ·  double-tap to fit",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy full payload",
                    tint = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            clipboard.setText(AnnotatedString(raw))
                            Toast.makeText(context, "screen copied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(6.dp)
                        .size(22.dp),
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .padding(6.dp)
                        .size(22.dp),
                )
            }
        }
    }
}

/** Copies the exact stored string to the clipboard. */
@Composable
internal fun CopyButton(raw: String, label: String, scheme: MonoScheme) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Icon(
        Icons.Filled.ContentCopy,
        contentDescription = "Copy $label",
        modifier = Modifier
            .clip(CircleShape)
            .clickable {
                clipboard.setText(AnnotatedString(raw))
                Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
            }
            .padding(4.dp)
            .size(16.dp),
        tint = scheme.textSecondary,
    )
}

// ── Images ──────────────────────────────────────────────────────────────────

/**
 * Inline screenshot thumbnail, decoded down to [THUMB_MAX_WIDTH_PX].
 *
 * A trace can hold twenty full-resolution PNGs; decoding each at native size into a card
 * that is ~1000px wide is how a LazyColumn starts dropping frames and then OOMs.
 */
@Composable
internal fun TraceImage(file: File, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, THUMB_MAX_WIDTH_PX) }
    }
    bmp?.let {
        Image(
            bitmap = it,
            contentDescription = "Screenshot — tap to zoom",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClick() },
            contentScale = ContentScale.FillWidth,
        )
    }
}

/**
 * Full-screen viewer with pinch-zoom, pan and double-tap-to-fit.
 *
 * Dismiss is an explicit ✕ (and the system back gesture) rather than tap-anywhere: once a
 * drag can pan the image, a stray tap mid-inspection closing the viewer is infuriating.
 */
@Composable
internal fun ImageZoomDialog(file: File, onDismiss: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, FULL_MAX_WIDTH_PX) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = "Screenshot (zoomable)",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        // Tap detection is declared FIRST, deliberately: modifiers run outer-to-
                        // inner, and a transform detector declared ahead of it consumes the
                        // pointer events the tap detector needs, so double-tap silently dies.
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.01f) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        scale = min(DOUBLE_TAP_SCALE, MAX_SCALE)
                                    }
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                scale = (scale * gestureZoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                // Panning only makes sense once the image overflows its box,
                                // and the bound keeps it from being flung off-screen.
                                val maxX = max(0f, size.width * (scale - 1f) / 2f)
                                val maxY = max(0f, size.height * (scale - 1f) / 2f)
                                offsetX = (offsetX + pan.x * scale).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y * scale).coerceIn(-maxY, maxY)
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                )
            }
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${"%.1f".format(scale)}×  ·  double-tap to fit",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .padding(6.dp)
                        .size(22.dp),
                )
            }
        }
    }
}

private const val THUMB_MAX_WIDTH_PX = 1080
private const val FULL_MAX_WIDTH_PX = 2160
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 3f

/**
 * The picture half of a `read_screen` payload — everything up to (not including) the
 * `som  in  flg  label` table that follows it. `ScreenPayload.render()` always writes
 * that table header verbatim right after a blank line, so it's a stable cut point.
 * Falls back to the whole string if the marker isn't there (an escalate-only or empty
 * payload has no table to cut).
 */
private fun String.screenCanvasRegion(): String {
    val cut = indexOf("\nsom  in")
    return if (cut >= 0) substring(0, cut).trimEnd() else this
}

/** Decode [file] at the smallest power-of-two sample that still covers [maxWidthPx]. */
private fun decodeSampled(file: File, maxWidthPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxWidthPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
}.getOrNull()
