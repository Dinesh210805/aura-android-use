package com.aura.aura_ui.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.aura.aura_ui.agent.conversation.PersonaOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Settings → Companion. The single home for voice-mode selection and the BYOK Gemini key.
 *
 *  - Voice mode is a two-way choice: **Gemini Live** (native bidirectional audio, needs a key)
 *    or **TTS / STT** (classic). Exactly one is always active — the two switches map onto one
 *    boolean, so turning either off flips to the other. Without a key, TTS/STT holds.
 *  - Assistant Voice opens the voice picker (both the Live voice and the classic TTS voice).
 *  - The Gemini key is stored encrypted via ProviderKeyStore and never logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen(
    onNavigateBack: () -> Unit,
    onOpenVoiceSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm = remember { CompanionViewModel(context) }
    val state by vm.state.collectAsState()
    var keyInput by remember { mutableStateOf("") }

    // Refresh when returning from the voice picker so the Assistant Voice subtitle stays current.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AuraScreenScaffold(
        title = "Voice & Companion",
        subtitle = "Gemini Live, voices, conversation",
        onBack = onNavigateBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Choose how the assistant talks and listens. Gemini Live uses your own Gemini key " +
                    "(stored encrypted on this device, never leaving it except inside your Live session); " +
                    "TTS/STT is the classic on-device speech pipeline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── What AURA calls you ───────────────────────────────────────────────
            // Until this is set the persona speaks in plain second person. It never
            // guesses a name or a gender, and it is never read from the Google
            // account (frequently a legal name, a handle, or a shared device).
            Text("YOUR NAME", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            var ownerName by remember { mutableStateOf(PersonaOwner.get(context).orEmpty()) }
            OutlinedTextField(
                value = ownerName,
                onValueChange = { entered ->
                    ownerName = entered
                    PersonaOwner.set(context, entered)
                },
                label = { Text("What should AURA call you?") },
                supportingText = {
                    Text(
                        if (ownerName.isBlank()) {
                            "Optional. Leave empty and AURA just speaks to you directly."
                        } else {
                            "AURA will address you as \"${ownerName.trim()}\". Applies next session."
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Voice mode: two mutually-exclusive switches over one boolean ──────
            Text("VOICE MODE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                Column {
                    ModeRow(
                        title = "Use Gemini Live",
                        subtitle = when {
                            !state.hasKey -> "Add a Gemini key below to enable."
                            else -> "Native bidirectional audio · Voice: ${state.liveVoice}"
                        },
                        checked = state.liveMode,
                        enabled = state.hasKey,
                        onCheckedChange = { on -> vm.setLiveMode(on) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    ModeRow(
                        title = "TTS / STT",
                        subtitle = "Classic speech-to-text + text-to-speech · Voice: ${state.classicVoice}",
                        checked = !state.liveMode,
                        enabled = true,
                        // Turning classic "off" means switch to Live (no-op without a key).
                        onCheckedChange = { on -> vm.setLiveMode(!on) },
                    )
                }
            }

            // ── Assistant Voice (moved here from Settings) ────────────────────────
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenVoiceSettings)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.RecordVoiceOver,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Assistant Voice", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (state.liveMode) state.liveVoice else state.classicVoice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── BYOK Gemini key ───────────────────────────────────────────────────
            Text("GEMINI KEY (BYOK)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                placeholder = { Text("AIza...") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.saveKey(keyInput); keyInput = "" },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.hasKey) "Update key" else "Save key") }
            Text(
                "Get a free key at aistudio.google.com/apikey. Live preview models are free-tier eligible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModeRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
