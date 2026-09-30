package com.aura.aura_ui.presentation.screens.trace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.mcp.log.SessionLog
import com.aura.aura_ui.mcp.log.ToolInvocation
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme
import java.io.File

/**
 * "What did it tap?" — the element's own box, cut out of the screen it was chosen from.
 *
 * A marker at a coordinate on a full screenshot makes the reader do the work of finding a small
 * cross on a busy screen and guessing what is under it. The box, cropped with a margin of context
 * and outlined, answers the question directly.
 *
 * The source is the screen the model picked the som_id from: the newest earlier image the run
 * captured — the annotated look (its boxes share the som_id's coordinates) or a look tool's own
 * screenshot. Only when there is none does it fall back to this call's own screenshot, which is
 * taken as the call starts and so may already show the result.
 */
internal object TargetCrop {

    /** A box in some pixel space: left, top, right, bottom. */
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
    }

    private val LOOK_TOOLS = setOf("read_screen", "perceive_screen", "get_screenshot")

    fun parseBounds(bounds: String?): Box? {
        val p = bounds?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        if (p.size != 4 || p[2] < p[0] || p[3] < p[1]) return null
        return Box(p[0], p[1], p[2], p[3])
    }

    /** The image the som_id was chosen from; see the class doc for the order. */
    fun sourceFor(
        invocations: List<ToolInvocation>,
        index: Int,
        somFile: (Int) -> File?,
        shotFile: (Int) -> File?,
    ): File? {
        for (j in index - 1 downTo 0) {
            val prior = invocations.getOrNull(j) ?: continue
            if (prior.somImage) somFile(j)?.let { return it }
            if (prior.toolName in LOOK_TOOLS && prior.hasScreenshot) shotFile(j)?.let { return it }
        }
        return shotFile(index)
    }

    /**
     * Where to cut, in the IMAGE's pixels. The box is scaled from screen pixels, then widened by a
     * margin so the element is seen in its surroundings — never narrower than [MIN_WIDTH_FRACTION]
     * of the image, since a crop of just a 40-pixel icon shows an icon and no context.
     */
    fun cropRegion(box: Box, screenW: Int, screenH: Int, imgW: Int, imgH: Int): Pair<Box, Box> {
        val sx = imgW.toFloat() / screenW
        val sy = imgH.toFloat() / screenH
        val target = Box((box.l * sx).toInt(), (box.t * sy).toInt(), (box.r * sx).toInt(), (box.b * sy).toInt())
        val cx = (target.l + target.r) / 2
        val cy = (target.t + target.b) / 2
        val halfW = maxOf(target.w / 2 + (target.w * 0.6f).toInt() + 24, (imgW * MIN_WIDTH_FRACTION / 2).toInt())
        val halfH = maxOf(target.h / 2 + (target.h * 0.6f).toInt() + 24, halfW / 2)
        val crop = Box(
            (cx - halfW).coerceAtLeast(0),
            (cy - halfH).coerceAtLeast(0),
            (cx + halfW).coerceAtMost(imgW),
            (cy + halfH).coerceAtMost(imgH),
        )
        return crop to target
    }

    /** Cut and outline. Null when anything is missing — the caller then shows the full screenshot. */
    fun render(session: SessionLog, inv: ToolInvocation, source: File?): ImageBitmap? = runCatching {
        val box = parseBounds(inv.targetBounds) ?: return null
        val sw = session.screenWidth ?: return null
        val sh = session.screenHeight ?: return null
        val bmp = BitmapFactory.decodeFile(source?.path ?: return null) ?: return null
        val (crop, target) = cropRegion(box, sw, sh, bmp.width, bmp.height)
        if (crop.w <= 0 || crop.h <= 0) return null
        val out = Bitmap.createBitmap(bmp, crop.l, crop.t, crop.w, crop.h).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val r = RectF(
            (target.l - crop.l).toFloat(), (target.t - crop.t).toFloat(),
            (target.r - crop.l).toFloat(), (target.b - crop.t).toFloat(),
        )
        val stroke = maxOf(3f, out.width / 160f)
        // Two strokes, dark under light, so the outline reads on any app's colours.
        c.drawRoundRect(r, stroke * 2, stroke * 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke * 2.2f; color = 0xCC000000.toInt()
        })
        c.drawRoundRect(r, stroke * 2, stroke * 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = 0xFFFFFFFF.toInt()
        })
        out.asImageBitmap()
    }.getOrNull()

    private const val MIN_WIDTH_FRACTION = 0.55f
}

/** The crop, captioned with what was touched. Tapping it opens the full screen it came from. */
@Composable
internal fun TargetCropView(image: ImageBitmap, caption: String, scheme: MonoScheme, onOpenFull: (() -> Unit)?) {
    Column {
        Image(
            bitmap = image,
            contentDescription = caption,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(Mono.ShapeChip)
                .border(1.dp, scheme.outline, Mono.ShapeChip)
                .let { m -> onOpenFull?.let { m.clickable(onClick = it) } ?: m },
        )
        Text(caption, style = MaterialTheme.typography.labelSmall, color = scheme.textSecondary)
    }
}
