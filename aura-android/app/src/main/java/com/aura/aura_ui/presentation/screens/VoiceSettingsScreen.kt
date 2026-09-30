package com.aura.aura_ui.presentation.screens

import android.content.Context
import android.util.Log
import com.aura.aura_ui.audio.AuraTTSManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.R
import com.aura.aura_ui.agent.conversation.CompanionConfig
import com.aura.aura_ui.agent.conversation.LiveModelResolver
import com.aura.aura_ui.data.network.TTSVoiceDto
import com.aura.aura_ui.presentation.components.MonoTopBar
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.presentation.utils.AuraHapticType
import com.aura.aura_ui.presentation.utils.rememberHapticFeedback
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme
import kotlinx.coroutines.launch

private const val TAG = "VoiceSettingsScreen"
private const val PREFS_NAME = "aura_voice_settings"
private const val KEY_SELECTED_VOICE = "selected_voice_id"
private const val DEFAULT_VOICE = "en-US-AriaNeural"
private const val KEY_GEMINI_LIVE_VOICE = "gemini_live_voice"
private const val DEFAULT_GEMINI_VOICE = "Achird"

// Gemini Live prebuilt voice definitions. There is no ListVoices API — Gemini's Live/TTS voices are
// a fixed enum (PrebuiltVoiceConfig.voice_name) documented at ai.google.dev/gemini-api/docs/live-api,
// not something any endpoint returns, so this list is maintained by hand against Google's docs rather
// than fetched. Kept to the 10 originally curated (with descriptions) plus the remaining 20 Google has
// since added, verified against the docs 2026-08-04.
private data class GeminiVoice(val name: String, val description: String, val gender: String)

private val GEMINI_LIVE_VOICES = listOf(
    GeminiVoice("Charon",         "Deep & authoritative",    "male"),
    GeminiVoice("Fenrir",         "Expressive & animated",   "male"),
    GeminiVoice("Puck",           "Upbeat & bright",         "male"),
    GeminiVoice("Gacrux",         "Mature & measured",       "male"),
    GeminiVoice("Achird",         "Casual & friendly",       "male"),
    GeminiVoice("Zubenelgenubi",  "Relaxed & casual",        "male"),
    GeminiVoice("Aoede",          "Bright & clear",          "female"),
    GeminiVoice("Kore",           "Firm & confident",        "female"),
    GeminiVoice("Schedar",        "Even & composed",         "female"),
    GeminiVoice("Pulcherrima",    "Forward & expressive",    "female"),
    GeminiVoice("Zephyr",         "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Leda",           "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Orus",           "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Callirrhoe",     "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Autonoe",        "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Enceladus",      "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Iapetus",        "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Umbriel",        "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Algieba",        "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Despina",        "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Erinome",        "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Algenib",        "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Rasalgethi",     "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Laomedeia",      "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Achernar",       "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Alnilam",        "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Vindemiatrix",   "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Sadachbia",      "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Sadaltager",     "Prebuilt Gemini voice",   "unspecified"),
    GeminiVoice("Sulafat",        "Prebuilt Gemini voice",   "unspecified"),
)

// Local voice definitions - no backend needed
private val LOCAL_VOICES = listOf(
    TTSVoiceDto(
        id = "en-US-AriaNeural",
        name = "Aria",
        description = "Friendly & warm female voice",
        gender = "female",
        accent = "American",
        previewText = "Welcome boss! How can I help you today?"
    ),
    TTSVoiceDto(
        id = "en-US-GuyNeural",
        name = "Guy",
        description = "Professional male voice",
        gender = "male",
        accent = "American",
        previewText = "Hey there boss! I'm ready to assist you."
    ),
    TTSVoiceDto(
        id = "en-US-JennyNeural",
        name = "Jenny",
        description = "Cheerful & energetic female voice",
        gender = "female",
        accent = "American",
        previewText = "Hi boss! Let's get things done together!"
    ),
    TTSVoiceDto(
        id = "en-US-ChristopherNeural",
        name = "Christopher",
        description = "Calm & confident male voice",
        gender = "male",
        accent = "American",
        previewText = "Good to see you boss! What's on the agenda?"
    ),
    TTSVoiceDto(
        id = "en-GB-SoniaNeural",
        name = "Sonia",
        description = "Elegant British female voice",
        gender = "female",
        accent = "British",
        previewText = "Hello boss! Shall we get started?"
    ),
    TTSVoiceDto(
        id = "en-GB-RyanNeural",
        name = "Ryan",
        description = "Sophisticated British male voice",
        gender = "male",
        accent = "British",
        previewText = "At your service, boss! How may I assist you?"
    ),
    TTSVoiceDto(
        id = "en-AU-NatashaNeural",
        name = "Natasha",
        description = "Friendly Australian female voice",
        gender = "female",
        accent = "Australian",
        previewText = "G'day boss! Ready when you are!"
    ),
    TTSVoiceDto(
        id = "en-US-EmmaNeural",
        name = "Emma",
        description = "Clear & articulate female voice",
        gender = "female",
        accent = "American",
        previewText = "Hello boss! I'm here to make your life easier."
    ),
)

/**
 * Apple-Inspired Voice Settings Screen
 * Allows users to select and preview TTS voices
 */
@Composable
fun VoiceSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    
    // Haptic feedback
    val hapticFeedback = rememberHapticFeedback()
    
    // State - use local voices, no loading needed
    val voices = remember { LOCAL_VOICES }
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var currentVoiceId by remember { mutableStateOf(prefs.getString(KEY_SELECTED_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE) }
    var geminiLiveVoice by remember { mutableStateOf(prefs.getString(KEY_GEMINI_LIVE_VOICE, DEFAULT_GEMINI_VOICE) ?: DEFAULT_GEMINI_VOICE) }
    var previewingVoiceId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    // Why: previews go through the same engine replies use, so the user hears the phone voice
    // they will actually get. Built on screen entry because TextToSpeech binds asynchronously.
    val tts = remember { AuraTTSManager(context) }
    DisposableEffect(Unit) {
        onDispose { tts.release() }
    }
    fun stopPreview() {
        tts.stop()
        isPlaying = false
        previewingVoiceId = null
    }
    fun preview(voice: TTSVoiceDto) {
        previewingVoiceId = voice.id
        isPlaying = true
        tts.speak(voice.previewText, voice.id) {
            if (previewingVoiceId == voice.id) {
                isPlaying = false
                previewingVoiceId = null
            }
        }
    }
    
    val labelSecondary = scheme.textSecondary

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.canvas)
    ) {
        // Back is owned by the global capsule nav bar — no redundant top-bar back.
        MonoTopBar(
            title = "Voice",
            subtitle = "Spoken replies & Gemini Live voice",
            scheme = scheme,
        )

        // Content - voices are local, no loading needed
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Current voice highlight
            item {
                Spacer(modifier = Modifier.height(8.dp))
                val currentVoice = voices.find { it.id == currentVoiceId }
                if (currentVoice != null) {
                    CurrentVoiceHeader(
                        voice = currentVoice,
                        scheme = scheme,
                        isPreviewing = previewingVoiceId == currentVoice.id && isPlaying,
                        onPreview = { preview(currentVoice) },
                        onStop = { stopPreview() }
                    )
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "ALL VOICES",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.5.sp
                    ),
                    color = labelSecondary,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
            }
            
            items(voices) { voice ->
                        VoiceCard(
                            voice = voice,
                            isSelected = voice.id == currentVoiceId,
                            isPreviewing = previewingVoiceId == voice.id && isPlaying,
                            scheme = scheme,
                            onSelect = {
                                // Haptic feedback for selection
                                hapticFeedback(AuraHapticType.LIGHT)
                                // Save selection locally
                                prefs.edit().putString(KEY_SELECTED_VOICE, voice.id).apply()
                                currentVoiceId = voice.id
                                Log.d(TAG, "Voice selected: ${voice.id}")
                            },
                            onPreview = { preview(voice) }
                        )
                    }
                    
                    // ── Gemini Live Voice section ─────────────────────────────
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "GEMINI LIVE VOICE",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Normal,
                                letterSpacing = 0.5.sp
                            ),
                            color = labelSecondary,
                            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Used when Gemini Live Mode is enabled in Settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = labelSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                    }

                    items(GEMINI_LIVE_VOICES) { voice ->
                        GeminiVoiceCard(
                            voice = voice,
                            isSelected = voice.name == geminiLiveVoice,
                            scheme = scheme,
                            onSelect = {
                                hapticFeedback(AuraHapticType.LIGHT)
                                prefs.edit().putString(KEY_GEMINI_LIVE_VOICE, voice.name).apply()
                                geminiLiveVoice = voice.name
                                Log.d(TAG, "Gemini Live voice selected: ${voice.name}")
                            }
                        )
                    }

                    // ── Gemini Live Model section ─────────────────────────────
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "GEMINI LIVE MODEL",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Normal,
                                letterSpacing = 0.5.sp
                            ),
                            color = labelSecondary,
                            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Which Live model handles the conversation. Auto lets AURA pick the best one your key exposes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = labelSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                    }
                    item {
                        LiveModelPicker(
                            scheme = scheme,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
    }
}

/**
 * Picks which Gemini Live model drives the conversation. Fetches ONLY bidiGenerateContent-capable
 * models via [LiveModelResolver.listLiveModels] — the same filter [CompanionLiveController] uses to
 * auto-resolve — so this list can never show a model Live can't actually connect with. "Auto"
 * (null) keeps today's behavior: [LiveModelResolver] picks the best match at connect time. Mirrors
 * the fetch -> dropdown -> persist shape of ProviderSettingsScreen's model picker, adapted to Mono
 * styling and the smaller Live-only model set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveModelPicker(scheme: MonoScheme, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = remember { CompanionConfig(context) }
    var selected by remember { mutableStateOf(cfg.liveModel) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val hasKey = remember { cfg.byokKey() != null }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hasKey) {
            Text(
                text = "Add a Gemini key in Settings → Brain to choose a Live model.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.textSecondary,
            )
            return@Column
        }

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected?.removePrefix("models/") ?: "Auto (recommended)",
                onValueChange = {},
                readOnly = true,
                label = { Text("Live model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Auto (recommended)",
                            fontWeight = if (selected == null) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { selected = null; cfg.liveModel = null; expanded = false },
                )
                if (models.isNotEmpty()) HorizontalDivider(color = scheme.divider)
                models.forEach { m ->
                    val label = m.removePrefix("models/")
                    val isTranslate = m.contains("translate")
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, fontWeight = if (m == selected) FontWeight.SemiBold else FontWeight.Normal)
                                if (isTranslate) {
                                    Text("translation", style = MaterialTheme.typography.labelSmall, color = scheme.textSecondary)
                                }
                            }
                        },
                        onClick = { selected = m; cfg.liveModel = m; expanded = false },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = !fetching,
                onClick = {
                    scope.launch {
                        fetching = true
                        error = null
                        val key = cfg.byokKey()
                        if (key == null) {
                            error = "No Gemini key configured."
                        } else {
                            val live = LiveModelResolver.listLiveModels(key)
                            if (live.isEmpty()) error = "Couldn't fetch Live models. Check your key."
                            models = live
                        }
                        fetching = false
                    }
                },
            ) {
                if (fetching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Mono.Ink)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (models.isEmpty()) "Fetch Live models" else "Refresh (${models.size})")
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Mono.Blood)
        }
    }
}

/**
 * Header showing the currently selected voice with prominent styling
 */
@Composable
private fun CurrentVoiceHeader(
    voice: TTSVoiceDto,
    scheme: MonoScheme,
    isPreviewing: Boolean,
    onPreview: () -> Unit,
    onStop: () -> Unit,
) {
    val groupBackground = scheme.card
    val labelPrimary = scheme.textPrimary
    val labelSecondary = scheme.textSecondary
    val accentColor = Mono.Ink
    // Mono is monochrome — no decorative gender tint; the avatar uses ink on chip.
    val genderColor = scheme.chipContent

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = groupBackground,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Avatar with gradient ring
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .border(3.dp, accentColor, CircleShape)
                    .background(genderColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = genderColor,
                    modifier = Modifier.size(40.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "Current Voice",
                style = MaterialTheme.typography.labelMedium,
                color = labelSecondary,
            )
            
            Text(
                text = voice.name,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = labelPrimary,
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = voice.description,
                style = MaterialTheme.typography.bodyMedium,
                color = labelSecondary,
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Play/Stop button
            Button(
                onClick = if (isPreviewing) onStop else onPreview,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPreviewing) Mono.Blood else accentColor,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Icon(
                    imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPreviewing) "Stop" else "Preview",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPreviewing) "Stop Preview" else "Play Preview",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun GeminiVoiceCard(
    voice: GeminiVoice,
    isSelected: Boolean,
    scheme: MonoScheme,
    onSelect: () -> Unit,
) {
    val groupBackground = scheme.card
    val labelPrimary = scheme.textPrimary
    val labelSecondary = scheme.textSecondary
    val accentColor = Mono.Ink
    val selectedBorderColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else Color.Transparent,
        animationSpec = tween(200),
        label = "gemini_border_color"
    )
    val genderColor = scheme.chipContent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = selectedBorderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onSelect),
        color = groupBackground,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(genderColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Voice info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = voice.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = labelPrimary,
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = voice.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = labelSecondary,
                )
                if (voice.gender != "unspecified") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = genderColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = voice.gender.replaceFirstChar { it.uppercaseChar() },
                            style = MaterialTheme.typography.labelSmall,
                            color = genderColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(
    voice: TTSVoiceDto,
    isSelected: Boolean,
    isPreviewing: Boolean,
    scheme: MonoScheme,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    val groupBackground = scheme.card
    val labelPrimary = scheme.textPrimary
    val labelSecondary = scheme.textSecondary
    val accentColor = Mono.Ink
    val selectedBorderColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else Color.Transparent,
        animationSpec = tween(200),
        label = "border_color"
    )

    // Mono is monochrome — the avatar uses ink content on a chip tint.
    val genderColor = scheme.chipContent
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = selectedBorderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onSelect),
        color = groupBackground,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(genderColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (voice.gender.lowercase() == "female") 
                        Icons.Default.Face else Icons.Default.Face,
                    contentDescription = null,
                    tint = genderColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Voice Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = voice.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = labelPrimary,
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = voice.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Accent chip
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = scheme.chip,
                    ) {
                        Text(
                            text = voice.accent,
                            style = MaterialTheme.typography.labelSmall,
                            color = labelSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    // Gender chip
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = genderColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = voice.gender.replaceFirstChar { it.uppercaseChar() },
                            style = MaterialTheme.typography.labelSmall,
                            color = genderColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Preview button
            IconButton(
                onClick = onPreview,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.1f))
            ) {
                if (isPreviewing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = accentColor,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Preview voice",
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}


