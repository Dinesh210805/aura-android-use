package com.aura.aura_ui.agentcore

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Attachments as the chat shows them: image thumbnails and file chips, in the composer before
 * sending and in the user's bubble after; a tap opens the image full screen or the file in its app.
 *
 * - Contract: no agent logic. Images decode off the main thread at display size, and are kept in a
 *   small cache keyed by URI and size, so scrolling the transcript never decodes one twice.
 * - Fails: a file that can no longer be read shows its chip without a picture; nothing throws.
 */
@Composable
fun AttachmentRail(items: List<Attachment>, onRemove: ((Attachment) -> Unit)? = null, modifier: Modifier = Modifier, wrap: Boolean = false) {
    var open by remember { mutableStateOf<Attachment?>(null) }
    val context = LocalContext.current
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val layout: @Composable (@Composable () -> Unit) -> Unit = if (wrap) {
        { content -> androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
    } else {
        { content -> Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
    }
    layout {
        items.forEach { item ->
            val onOpen = { if (item.isImage) open = item else openFile(context, item) }
            Box {
                if (item.isImage) ImageThumb(item.uri, THUMB, onOpen) else FileChip(item, onOpen)
                onRemove?.let { remove ->
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)).clickable(onClickLabel = "Remove ${item.displayName}") { remove(item) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, contentDescription = "Remove ${item.displayName}", tint = Color.White, modifier = Modifier.size(14.dp)) }
                }
            }
        }
    }
    open?.let { FullImage(it, onDismiss = { open = null }) }
}

@Composable
private fun ImageThumb(uri: String, size: Dp, onClick: () -> Unit) {
    val aura = LocalAura.current
    val bitmap = rememberBitmap(uri, size)
    Box(Modifier.size(size).clip(AuraShape.Inner).background(aura.well).clickable(onClickLabel = "Open image", onClick = onClick)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Attached image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun FileChip(item: Attachment, onClick: () -> Unit) {
    val aura = LocalAura.current
    Row(
        Modifier.size(height = THUMB, width = FILE_CHIP).clip(AuraShape.Inner).background(aura.well)
            .clickable(onClickLabel = "Open ${item.displayName}", onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(fileIcon(item.mimeType), contentDescription = null, tint = aura.secondary, modifier = Modifier.size(22.dp))
        Text(
            item.displayName,
            Modifier.padding(start = 8.dp).widthIn(max = FILE_CHIP),
            style = AuraType.caption,
            color = aura.label,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FullImage(item: Attachment, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = "Close", onClick = onDismiss), contentAlignment = Alignment.Center) {
            rememberBitmap(item.uri, FULL)?.let {
                Image(it.asImageBitmap(), contentDescription = item.displayName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun rememberBitmap(uri: String, size: Dp): Bitmap? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(Thumbnails.cached(uri, px), uri, px) {
        if (value == null) value = withContext(Dispatchers.IO) { Thumbnails.load(context.contentResolver, uri, px) }
    }
    return bitmap
}

/** Every file sent in [rows], newest first, each once. Pure. */
internal fun chatFiles(rows: List<ItemRow>): List<Attachment> =
    rows.filter { it.kind == ItemKind.USER.name }.flatMap { attachments(it.attachmentsJson) }.distinctBy { it.uri }.reversed()

/** The chat's Files sheet: every picture and document sent in it, to open again. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FilesSheet(files: List<Attachment>, onDismiss: () -> Unit) {
    val aura = LocalAura.current
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = aura.surfaceStrong) {
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Files in this chat", style = AuraType.headline, color = aura.label, modifier = Modifier.padding(bottom = 12.dp))
            AttachmentRail(files, wrap = true)
        }
    }
}

private fun fileIcon(mime: String): ImageVector = when {
    mime == "application/pdf" -> Icons.Outlined.PictureAsPdf
    mime.contains("spreadsheet") || mime.contains("excel") || mime == "text/csv" -> Icons.Outlined.GridOn
    mime.startsWith("text/") || mime.contains("wordprocessing") || mime.contains("msword") -> Icons.Outlined.Description
    else -> Icons.Outlined.InsertDriveFile
}

private fun openFile(context: Context, item: Attachment) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri), item.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Decoded pictures for the chat, at most [px] on the long side.
 * - Contract: [load] is blocking; call it off the main thread. Null when the URI is gone or not an image.
 */
internal object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(uri: String, px: Int): Bitmap? = cache.get("$px:$uri")

    fun load(resolver: ContentResolver, uri: String, px: Int): Bitmap? {
        cached(uri, px)?.let { return it }
        val parsed = Uri.parse(uri)
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(parsed, Size(px, px), null)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val sample = sampleSize(bounds.outWidth, bounds.outHeight, px)
                resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }
        }.getOrNull() ?: return null
        cache.put("$px:$uri", bitmap)
        return bitmap
    }

    private const val CACHE_BYTES = 16 * 1024 * 1024
}

/** The largest power of two that keeps the long side at or above [px]. Pure. */
internal fun sampleSize(width: Int, height: Int, px: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= px) sample *= 2
    return sample
}

private val THUMB = 72.dp
private val FILE_CHIP = 150.dp
/** About 1,800 px on a 3x screen: sharp full screen, and a bounded decode. */
private val FULL = 600.dp
