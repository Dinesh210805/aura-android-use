package com.aura.aura_ui.presentation.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.agent.conversation.CompanionConfig
import com.aura.aura_ui.audio.AuraTTSManager
import com.aura.aura_ui.presentation.components.ColumnScopeMarker
import com.aura.aura_ui.presentation.components.MonoCardGroup
import com.aura.aura_ui.presentation.components.MonoDivider
import com.aura.aura_ui.presentation.components.MonoRow
import com.aura.aura_ui.presentation.components.MonoSectionLabel
import com.aura.aura_ui.presentation.components.MonoSegmented
import com.aura.aura_ui.presentation.components.MonoTopBar
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.presentation.utils.AuraHapticType
import com.aura.aura_ui.presentation.utils.rememberHapticFeedback
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme
import com.aura.aura_ui.voice.GeminiVoicePreview
import com.aura.aura_ui.voice.LocalVoiceModels
import com.aura.aura_ui.voice.LocalVoices
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private const val PREFS_NAME = "aura_voice_settings"
private const val KEY_SELECTED_VOICE = "selected_voice_id"
private const val KEY_GEMINI_LIVE_VOICE = "gemini_live_voice"
private const val DEFAULT_GEMINI_VOICE = "Achird"

/**
 * Gemini Live prebuilt voices. There is no ListVoices API — the set is a fixed enum
 * (`PrebuiltVoiceConfig.voice_name`) documented at ai.google.dev/gemini-api/docs/live-api, so it is
 * maintained by hand. [description] is null where Google gives no character note.
 */
private data class GeminiVoice(val name: String, val description: String? = null)

private val GEMINI_LIVE_VOICES = listOf(
    GeminiVoice("Achird", "Casual & friendly"),
    GeminiVoice("Charon", "Deep & authoritative"),
    GeminiVoice("Fenrir", "Expressive & animated"),
    GeminiVoice("Puck", "Upbeat & bright"),
    GeminiVoice("Gacrux", "Mature & measured"),
    GeminiVoice("Zubenelgenubi", "Relaxed & casual"),
    GeminiVoice("Aoede", "Bright & clear"),
    GeminiVoice("Kore", "Firm & confident"),
    GeminiVoice("Schedar", "Even & composed"),
    GeminiVoice("Pulcherrima", "Forward & expressive"),
) + listOf(
    "Zephyr", "Leda", "Orus", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
    "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Vindemiatrix",
    "Sadachbia", "Sadaltager", "Sulafat",
).map { GeminiVoice(it) }

/**
 * Voice settings, split by engine: on-device (Moonshine recognition + Piper voices) and Gemini Live.
 *
 * - Reads/Writes: prefs `aura_voice_settings` keys `selected_voice_id`, `gemini_live_voice`.
 * - Why the tab opens on the active mode: the mode switch itself lives on the Companion screen;
 *   this screen only configures each engine.
 */
@Composable
fun VoiceSettingsScreen(modifier: Modifier = Modifier) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val companion = remember { CompanionConfig(context) }
    val liveInUse = remember { companion.useGeminiLive && companion.byokKey() != null }
    var tab by rememberSaveable { mutableIntStateOf(if (liveInUse) 1 else 0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.canvas),
    ) {
        MonoTopBar(
            title = "Voice",
            subtitle = "In use: " + if (liveInUse) "Gemini Live" else "On-device",
            scheme = scheme,
        )
        MonoSegmented(
            options = listOf("On-device", "Gemini Live"),
            selectedIndex = tab,
            onSelect = { tab = it },
            scheme = scheme,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 4.dp),
        )
        when (tab) {
            0 -> OnDeviceTab(scheme)
            else -> GeminiLiveTab(scheme, companion)
        }
    }
}

@Composable
private fun OnDeviceTab(scheme: MonoScheme) {
    val context = LocalContext.current
    val haptic = rememberHapticFeedback()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var selectedId by remember { mutableStateOf(LocalVoices.byId(prefs.getString(KEY_SELECTED_VOICE, null)).id) }
    var playingId by remember { mutableStateOf<String?>(null) }
    val selected = LocalVoices.byId(selectedId)
    val selectedState by remember(selectedId) { LocalVoiceModels.state(context, selected.model) }.collectAsState()
    // Previews go through the same engine replies use, so the user hears exactly what they will get.
    val tts = remember { AuraTTSManager(context) }
    DisposableEffect(Unit) { onDispose { tts.release() } }

    fun play(voice: LocalVoices.Voice) {
        playingId = voice.id
        tts.speak(LocalVoices.PREVIEW_TEXT, voice.id) { if (playingId == voice.id) playingId = null }
    }
    fun stop() {
        tts.stop()
        playingId = null
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            MonoSectionLabel("Speech recognition", scheme)
            MonoCardGroup(scheme) {
                ModelRow(LocalVoiceModels.MOONSHINE, Icons.Default.Mic, "Moonshine", "Understands what you say")
            }
            Hint("Everything runs on this phone — nothing is sent anywhere. Download once, over Wi-Fi.", scheme)
        }
        item {
            MonoSectionLabel("Voice", scheme)
            if (selectedState != LocalVoiceModels.State.Ready) {
                Hint("Download ${selected.name} to hear it. Until then the phone's built-in voice speaks.", scheme)
            }
            MonoCardGroup(scheme) {
                LocalVoices.all.forEachIndexed { i, voice ->
                    if (i > 0) MonoDivider()
                    VoiceRow(
                        voice = voice,
                        selected = voice.id == selectedId,
                        playing = voice.id == playingId,
                        scheme = scheme,
                        onSelect = { ready ->
                            haptic(AuraHapticType.LIGHT)
                            prefs.edit().putString(KEY_SELECTED_VOICE, voice.id).apply()
                            selectedId = voice.id
                            // A voice that is not on the phone yet is fetched, never previewed with the system voice.
                            if (ready) play(voice) else LocalVoiceModels.startDownload(context, voice.model)
                        },
                        onPlayToggle = { if (voice.id == playingId) stop() else play(voice) },
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** One voice: tap to use it (downloading it first if needed); preview, progress or Download on the right. */
@Composable
private fun ColumnScopeMarker.VoiceRow(
    voice: LocalVoices.Voice,
    selected: Boolean,
    playing: Boolean,
    scheme: MonoScheme,
    onSelect: (ready: Boolean) -> Unit,
    onPlayToggle: () -> Unit,
) {
    val context = LocalContext.current
    val state by remember(voice.id) { LocalVoiceModels.state(context, voice.model) }.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val base = "${voice.accent} · ${voice.gender}"

    MonoRow(
        icon = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        title = voice.name,
        subtitle = when (val st = state) {
            LocalVoiceModels.State.Ready -> base
            LocalVoiceModels.State.Missing -> "$base · ${voice.model.approxMb} MB"
            is LocalVoiceModels.State.Downloading -> "$base · downloading ${(st.progress * 100).toInt()}%"
            is LocalVoiceModels.State.Failed -> "$base · ${st.message}"
        },
        onClick = { onSelect(state == LocalVoiceModels.State.Ready) },
        trailing = {
            when (val st = state) {
                LocalVoiceModels.State.Ready -> Row {
                    IconButton(onClick = onPlayToggle) {
                        Icon(
                            imageVector = if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Stop preview" else "Preview ${voice.name}",
                            tint = scheme.textPrimary,
                        )
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete ${voice.name}", tint = scheme.textSecondary)
                    }
                }
                is LocalVoiceModels.State.Downloading -> DownloadProgress(st.progress, scheme)
                LocalVoiceModels.State.Missing, is LocalVoiceModels.State.Failed ->
                    DownloadButton(retry = st is LocalVoiceModels.State.Failed, scheme) {
                        LocalVoiceModels.startDownload(context, voice.model)
                    }
            }
        },
    )

    if (confirmDelete) {
        ConfirmDeleteDialog(voice.name, voice.model.approxMb, onDismiss = { confirmDelete = false }) {
            LocalVoiceModels.delete(context, voice.model)
        }
    }
}

/** One downloadable model: live status in the subtitle, Download / progress / Delete on the right. */
@Composable
private fun ColumnScopeMarker.ModelRow(
    model: LocalVoiceModels.Model,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
) {
    val context = LocalContext.current
    val scheme = rememberMonoScheme()
    val state by remember(model) { LocalVoiceModels.state(context, model) }.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    MonoRow(
        icon = icon,
        title = title,
        subtitle = when (val st = state) {
            LocalVoiceModels.State.Ready -> "$description · ready"
            LocalVoiceModels.State.Missing -> "$description · ${model.approxMb} MB"
            is LocalVoiceModels.State.Downloading -> "Downloading ${(st.progress * 100).toInt()}%"
            is LocalVoiceModels.State.Failed -> st.message
        },
        trailing = {
            when (val st = state) {
                LocalVoiceModels.State.Ready ->
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = scheme.textSecondary) }
                is LocalVoiceModels.State.Downloading -> DownloadProgress(st.progress, scheme)
                LocalVoiceModels.State.Missing, is LocalVoiceModels.State.Failed ->
                    DownloadButton(retry = st is LocalVoiceModels.State.Failed, scheme) {
                        LocalVoiceModels.startDownload(context, model)
                    }
            }
        },
    )

    if (confirmDelete) {
        ConfirmDeleteDialog(title, model.approxMb, onDismiss = { confirmDelete = false }) {
            LocalVoiceModels.delete(context, model)
        }
    }
}

@Composable
private fun DownloadProgress(progress: Float, scheme: MonoScheme) {
    CircularProgressIndicator(
        progress = { progress },
        modifier = Modifier.padding(horizontal = 12.dp).size(24.dp),
        strokeWidth = 3.dp,
        color = scheme.textPrimary,
    )
}

@Composable
private fun DownloadButton(retry: Boolean, scheme: MonoScheme, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(if (retry) "Retry" else "Download", color = scheme.textPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConfirmDeleteDialog(name: String, approxMb: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        text = { Text("Frees $approxMb MB. You can download it again any time.") },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Delete", color = Mono.Blood) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeminiLiveTab(scheme: MonoScheme, companion: CompanionConfig) {
    val context = LocalContext.current
    val haptic = rememberHapticFeedback()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf(companion.voice()) }
    val selectedVoice = GEMINI_LIVE_VOICES.firstOrNull { it.name == selected }
    val apiKey = remember { companion.byokKey() }
    val scope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var previewing by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }

    // One preview at a time: a new tap cancels the old one, which stops its audio immediately.
    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewing = null
        loading = false
    }
    fun preview(voice: String) {
        val key = apiKey ?: return
        stopPreview()
        previewError = null
        previewing = voice
        loading = true
        previewJob = scope.launch {
            try {
                val ok = GeminiVoicePreview.play(context, key, voice) { loading = false }
                if (!ok) previewError = "Couldn't load a preview of $voice. Check your connection or key quota."
            } finally {
                // Only the current preview may clear the state; a cancelled one finishes after its successor starts.
                if (previewJob === coroutineContext.job) {
                    previewing = null
                    loading = false
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { previewJob?.cancel() } }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            MonoSectionLabel("Model", scheme)
            MonoCardGroup(scheme) {
                LiveModelRow(scheme, Modifier.padding(16.dp))
            }
        }
        item {
            MonoSectionLabel("Voice", scheme)
            MonoCardGroup(scheme) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = selected + (selectedVoice?.description?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = scheme.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        when {
                            apiKey == null -> Unit
                            previewing == selected && loading -> CircularProgressIndicator(
                                modifier = Modifier.padding(12.dp).size(24.dp),
                                strokeWidth = 3.dp,
                                color = scheme.textPrimary,
                            )
                            else -> IconButton(onClick = { if (previewing == selected) stopPreview() else preview(selected) }) {
                                Icon(
                                    imageVector = if (previewing == selected) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (previewing == selected) "Stop preview" else "Preview $selected",
                                    tint = scheme.textPrimary,
                                )
                            }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GEMINI_LIVE_VOICES.forEach { voice ->
                            VoiceChip(voice.name, voice.name == selected, scheme) {
                                haptic(AuraHapticType.LIGHT)
                                prefs.edit().putString(KEY_GEMINI_LIVE_VOICE, voice.name).apply()
                                selected = voice.name
                                preview(voice.name)
                            }
                        }
                    }
                }
            }
            previewError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Mono.Blood, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
            Hint(
                if (apiKey == null) {
                    "Add a Gemini key in Settings → API keys to hear voice previews."
                } else {
                    "Tap a voice to hear it. Each preview is fetched once with your key, then saved on the phone."
                },
                scheme,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VoiceChip(label: String, selected: Boolean, scheme: MonoScheme, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = Mono.ShapePill,
        color = if (selected) Mono.Ink else scheme.chip,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Mono.TextOnInk else scheme.textPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Hint(text: String, scheme: MonoScheme) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.textSecondary,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
    )
}

/** The Live model, which is fixed: AURA's voice is tuned for Gemini 3.8 Live and nothing else. */
@Composable
private fun LiveModelRow(scheme: MonoScheme, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("Gemini 3.8 Live", style = MaterialTheme.typography.bodyLarge, color = scheme.textPrimary)
        Text(
            "Speech in and out only; AURA's agent does the thinking.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.textSecondary,
        )
    }
}