package com.aura.aura_ui.agentcore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.ui.theme.LocalAura
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A recorded screen drawn as a picture from its `ScreenFrame`: every element's box at its real
 * place and scale, its label inside, tappable ones in the accent colour. The way the UI-stream
 * viewer draws a frame, but live in the run page: nothing is stored as an image.
 *
 * - Contract: [ScreenPicture.parse] is pure and returns null for a frame it can't read, so the
 *   page falls back to the text grid.
 * - Change together: the frame shape in `ScreenFrame.encode` (`:mcp-server`).
 */
object ScreenPicture {

    data class Box(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val label: String, val tappable: Boolean) {
        val area: Long get() = (x2 - x1).toLong() * (y2 - y1)
    }

    data class Frame(val width: Int, val height: Int, val boxes: List<Box>)

    /** `{"w","h","e":[[x1,y1,x2,y2,"label","flags","Class"], …]}` → boxes, largest first so small ones draw on top. */
    fun parse(frame: String?): Frame? = runCatching {
        val root = Json.parseToJsonElement(frame ?: return null).jsonObject
        val w = root["w"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return null
        val h = root["h"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return null
        val boxes = (root["e"] as? JsonArray).orEmpty().mapNotNull { el ->
            val a = el.jsonArray
            fun int(i: Int) = a.getOrNull(i)?.jsonPrimitive?.intOrNull
            fun str(i: Int) = a.getOrNull(i)?.jsonPrimitive?.contentOrNull.orEmpty()
            Box(int(0) ?: return@mapNotNull null, int(1) ?: return@mapNotNull null, int(2) ?: return@mapNotNull null, int(3) ?: return@mapNotNull null, str(4), '*' in str(5))
        }.filter { it.x2 > it.x1 && it.y2 > it.y1 }
        Frame(w, h, boxes.sortedByDescending { it.area })
    }.getOrNull()
}

/** Draws [frame] at the full width given, with the phone's aspect ratio. */
@Composable
fun ScreenPictureView(frame: ScreenPicture.Frame, modifier: Modifier = Modifier) {
    val aura = LocalAura.current
    val measurer = rememberTextMeasurer()
    val label = remember(aura) { TextStyle(fontSize = LABEL_SIZE, color = aura.label) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scale = constraints.maxWidth.toFloat() / frame.width
        val heightDp = with(androidx.compose.ui.platform.LocalDensity.current) { (frame.height * scale).toDp() }
        Canvas(Modifier.fillMaxWidth().height(heightDp)) {
            drawRect(aura.well)
            val stroke = Stroke(width = 1.dp.toPx())
            frame.boxes.forEach { b ->
                val topLeft = Offset(b.x1 * scale, b.y1 * scale)
                val size = Size((b.x2 - b.x1) * scale, (b.y2 - b.y1) * scale)
                val colour = if (b.tappable) aura.accent else aura.tertiary
                if (b.tappable) drawRect(colour.copy(alpha = 0.08f), topLeft, size)
                drawRect(colour.copy(alpha = 0.7f), topLeft, size, style = stroke)
                val pad = 2.dp.toPx()
                if (b.label.isNotBlank() && size.width > 4 * pad && size.height > LABEL_MIN_HEIGHT.toPx()) {
                    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
                        val layout = measurer.measure(
                            b.label,
                            label,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                            constraints = Constraints(maxWidth = (size.width - 2 * pad).toInt().coerceAtLeast(1)),
                        )
                        drawText(layout, topLeft = topLeft + Offset(pad, pad))
                    }
                }
            }
        }
    }
}

private val LABEL_SIZE = 8.sp

/** A box shorter than this gets no label; its outline still shows. */
private val LABEL_MIN_HEIGHT = 10.dp
