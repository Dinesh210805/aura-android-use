package com.aura.aura_ui.agentcore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.agent.llm.ModelInfo
import com.aura.aura_ui.presentation.components.AuraDivider
import com.aura.aura_ui.presentation.components.AuraRow
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.auraCard

/** Above this many models the sheet shows a filter field. */
private const val FILTER_FROM = 10

/**
 * The model sheet: the providers that are set up, the selected provider's models as its
 * `/models` listing returns them, and a way to the full provider settings.
 *
 * - Contract: picking a model saves it with its context window and vision flag
 *   (`ProviderKeyStore.saveModelChoice`), the same as the provider settings page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(vm: ChatViewModel, onDismiss: () -> Unit, onMoreSettings: () -> Unit, onAddKey: () -> Unit) {
    val aura = LocalAura.current
    val providers by vm.models.collectAsState()
    val catalog by vm.catalog.collectAsState()
    val selected = providers.firstOrNull { it.selected }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(selected?.endpointId) { if (selected != null) vm.loadCatalog(selected.endpointId) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = aura.surfaceStrong,
        contentColor = aura.label,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Model", style = AuraType.title, color = aura.label)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { choice ->
                    ProviderPill(choice.provider, choice.selected) { vm.pickProvider(choice) }
                }
                ProviderPill("Add provider", false, icon = true, onClick = onAddKey)
            }
            val shown = catalog?.takeIf { it.endpointId == selected?.endpointId }
            when {
                selected == null -> Text("Add an API key to choose a model.", style = AuraType.callout, color = aura.secondary)
                shown == null || (shown.loading && shown.models.isEmpty()) -> Loading()
                else -> {
                    shown.error?.let { Text(it, style = AuraType.footnote, color = aura.danger) }
                    if (shown.models.size > FILTER_FROM) Filter(filter) { filter = it }
                    val models = shown.models.filter { filter.isBlank() || it.id.contains(filter.trim(), ignoreCase = true) }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).auraCard()) {
                        items(models, key = { it.id }) { model ->
                            if (model != models.first()) AuraDivider(16.dp)
                            ModelRow(model, model.id == selected.model) {
                                vm.chooseModel(model)
                                onDismiss()
                            }
                        }
                    }
                    if (shown.loading) Loading()
                }
            }
            val usage by vm.usage.collectAsState()
            usage?.let { ContextMeter(it) }
            Column(Modifier.fillMaxWidth().auraCard()) {
                AuraRow("More settings", icon = Icons.Outlined.Tune, onClick = onMoreSettings)
            }
        }
    }
}

@Composable
private fun ProviderPill(label: String, selected: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    val aura = LocalAura.current
    Row(
        Modifier.clip(AuraShape.Pill).background(if (selected) aura.accent else aura.well)
            .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) Icon(Icons.Rounded.Add, contentDescription = null, tint = aura.label, modifier = Modifier.padding(end = 4.dp).size(16.dp))
        Text(label, style = AuraType.caption, color = if (selected) aura.onAccent else aura.label)
    }
}

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    val aura = LocalAura.current
    val facts = listOfNotNull(
        if (model.visionCapable) "Sees the screen" else "Text only",
        model.contextWindow?.let { "${it / 1000}k context" },
        "Free".takeIf { model.isFree == true },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.id.substringAfterLast('/'), style = AuraType.body, color = aura.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(facts, style = AuraType.footnote, color = aura.secondary)
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = aura.label, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Filter(value: String, onChange: (String) -> Unit) {
    val aura = LocalAura.current
    Box(Modifier.fillMaxWidth().clip(AuraShape.Inner).background(aura.well).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text("Filter models", style = AuraType.body, color = aura.secondary)
        BasicTextField(value, onChange, Modifier.fillMaxWidth(), textStyle = AuraType.body.copy(color = aura.label), cursorBrush = SolidColor(aura.label), singleLine = true)
    }
}

@Composable
private fun Loading() {
    val aura = LocalAura.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = aura.label, strokeWidth = 2.dp)
        Text("Listing models", Modifier.padding(start = 10.dp), style = AuraType.footnote, color = aura.secondary)
    }
}

/**
 * How full this chat's context was at its last request, and how much of it the provider served
 * from its cache. From the provider's own counts, not an estimate.
 */
@Composable
private fun ContextMeter(usage: Usage) {
    val aura = LocalAura.current
    Column(Modifier.fillMaxWidth().auraCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val window = usage.window?.let { " of ${tokens(it)}" }.orEmpty()
        val cache = usage.cached?.takeIf { usage.input > 0 }?.let { " · ${it * 100 / usage.input}% from cache" }.orEmpty()
        Text("Context: ${tokens(usage.input)}$window tokens$cache", style = AuraType.footnote, color = aura.secondary)
        usage.fill?.let { fill ->
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fill },
                modifier = Modifier.fillMaxWidth(),
                color = if (fill > METER_WARN) aura.danger else aura.label,
                trackColor = aura.well,
            )
        }
    }
}

private fun tokens(n: Int): String = if (n >= 1_000) "${n / 1_000}k" else "$n"

/** Past this the chat is close to being summarised. */
private const val METER_WARN = 0.8f
