package com.aura.aura_ui.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aura.aura_ui.agent.conversation.PersonaOwner
import com.aura.aura_ui.presentation.components.AuraDivider
import com.aura.aura_ui.presentation.components.AuraPage
import com.aura.aura_ui.presentation.components.AuraRow
import com.aura.aura_ui.presentation.components.AuraSection
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura

private val ICON_INSET = 52.dp

/**
 * Voice: what AURA calls you, how it talks (Gemini Live or on-device speech), and its voice.
 *
 * - Contract: voice mode is a two-way choice; exactly one is always on. Gemini Live needs a Gemini
 *   key, which is added on the API keys page ([onOpenApiKeys]), never here.
 * - Reads/Writes: `PersonaOwner` (the name), `CompanionConfig` through [CompanionViewModel].
 */
@Composable
fun CompanionScreen(onOpenVoiceSettings: () -> Unit, onOpenApiKeys: () -> Unit) {
    val context = LocalContext.current
    val aura = LocalAura.current
    val vm = remember { CompanionViewModel(context) }
    val state by vm.state.collectAsState()
    var ownerName by remember { mutableStateOf(PersonaOwner.get(context).orEmpty()) }

    // Refresh on return from the voice picker or the API keys page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AuraPage("Voice") {
        // It never guesses a name, and never reads one from the Google account.
        AuraSection(title = "What AURA calls you") {
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (ownerName.isEmpty()) Text("Your name", style = AuraType.body, color = aura.tertiary)
                BasicTextField(
                    value = ownerName,
                    onValueChange = {
                        ownerName = it
                        PersonaOwner.set(context, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = AuraType.body.copy(color = aura.label),
                    cursorBrush = SolidColor(aura.label),
                    singleLine = true,
                )
            }
        }

        AuraSection(title = "How AURA talks") {
            AuraRow(
                "Gemini Live",
                subtitle = if (state.hasKey) "Natural, real-time conversation" else "Needs a Gemini key",
                onClick = if (state.hasKey) ({ vm.setLiveMode(true) }) else onOpenApiKeys,
                trailing = { if (state.liveMode) Icon(Icons.Rounded.Check, "Selected", tint = aura.label, modifier = Modifier.size(20.dp)) },
            )
            AuraDivider(16.dp)
            AuraRow(
                "On-device speech",
                subtitle = "Speech to text and text to speech",
                onClick = { vm.setLiveMode(false) },
                trailing = { if (!state.liveMode) Icon(Icons.Rounded.Check, "Selected", tint = aura.label, modifier = Modifier.size(20.dp)) },
            )
        }

        AuraSection {
            AuraRow(
                "Assistant voice",
                icon = Icons.Outlined.RecordVoiceOver,
                value = if (state.liveMode) state.liveVoice else state.classicVoice,
                onClick = onOpenVoiceSettings,
            )
            AuraDivider(ICON_INSET)
            AuraRow("Gemini key", icon = Icons.Outlined.Key, value = if (state.hasKey) "Saved" else "Add", onClick = onOpenApiKeys)
        }
    }
}
