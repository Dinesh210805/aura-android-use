package com.aura.aura_ui.presentation.screens.trace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A raw body the logger wrote byte-exact — a model request, its response, a tool's full result —
 * shown whole, on its own screen.
 *
 * The file on disk is never altered. For reading, JSON is indented and every long base64 run (the
 * screenshots riding inside a request) is folded to a size tag, because a 300 KB line of base64
 * is not something a person reads — and one of them would make the text unscrollable. Copy and
 * share take the exact bytes.
 */
@Composable
internal fun RawBodyDialog(file: File, title: String, onDismiss: () -> Unit) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    var lines by remember(file) { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(file) {
        lines = withContext(Dispatchers.IO) { readableLines(file) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(scheme.canvas)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.textPrimary)
                    Text(
                        "${file.name} · ${sizeLabel(file.length())} · exact bytes on disk",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.textSecondary,
                    )
                }
                Action("Copy", scheme) { copyOrShare(context, file) }
                Action("Close", scheme, onDismiss)
            }
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(Mono.ShapeChip)
                    .background(scheme.chip)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (query.isEmpty()) Text("Search", style = MaterialTheme.typography.bodySmall, color = scheme.textSecondary)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = scheme.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(scheme.textPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val all = lines
            if (all == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = scheme.textPrimary) }
                return@Column
            }
            val shown = remember(all, query) {
                if (query.isBlank()) all.withIndex().toList()
                else all.withIndex().filter { it.value.contains(query, ignoreCase = true) }
            }
            if (query.isNotBlank()) {
                Text(
                    "${shown.size} matching lines",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.textSecondary,
                )
            }
            SelectionContainer {
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    itemsIndexed(shown, key = { _, l -> l.index }) { _, line ->
                        Row {
                            Text(
                                "${line.index + 1}",
                                modifier = Modifier.width(40.dp).padding(end = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = scheme.textSecondary,
                            )
                            Text(
                                line.value,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                fontFamily = FontFamily.Monospace,
                                color = scheme.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, scheme: com.aura.aura_ui.ui.theme.MonoScheme, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(Mono.ShapePill)
            .background(scheme.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = scheme.textPrimary,
    )
}

/** Base64 runs this long are images, not text anyone reads. */
private val BASE64_RUN = Regex("(data:[a-z]+/[a-z0-9.+-]+;base64,)?[A-Za-z0-9+/]{400,}={0,2}")

/**
 * The file as lines a person can read: base64 folded to size tags first (so indenting never has
 * to carry megabytes), then JSON indented when it parses. Anything else is shown as it is.
 */
internal fun readableLines(file: File): List<String> {
    val raw = runCatching { file.readText() }.getOrElse { return listOf("(could not read ${file.name}: ${it.message})") }
    return readableText(raw).lines()
}

internal fun readableText(raw: String): String {
    val folded = BASE64_RUN.replace(raw) { m -> "‹base64 · ${sizeLabel(m.value.length * 3L / 4)}›" }
    val trimmed = folded.trim()
    return runCatching {
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
            else -> folded
        }
    }.getOrDefault(folded).let(::unescape)
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** Small bodies go to the clipboard; big ones would blow the binder limit, so they are shared as a file. */
private fun copyOrShare(context: Context, file: File) {
    if (file.length() <= CLIPBOARD_MAX) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(file.name, file.readText()))
        Toast.makeText(context, "Copied ${file.name}", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, file.name)
        file.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share ${file.name}",
            ),
        )
    }.onFailure { Toast.makeText(context, "Could not share: ${it.message}", Toast.LENGTH_LONG).show() }
}

private const val CLIPBOARD_MAX = 400_000L
