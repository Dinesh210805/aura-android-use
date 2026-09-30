package com.aura.aura_ui.presentation.screens

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.agent.llm.AuthScheme
import com.aura.aura_ui.agent.llm.GenerationConfig
import com.aura.aura_ui.agent.llm.LlmEndpoint
import com.aura.aura_ui.agent.llm.ModelCatalog
import com.aura.aura_ui.agent.llm.ModelInfo
import com.aura.aura_ui.agent.llm.ModelListStyle
import com.aura.aura_ui.agent.llm.ProviderProfile
import com.aura.aura_ui.agent.llm.ReasoningLevel
import com.aura.aura_ui.agent.llm.ReasoningRequestStyle
import com.aura.aura_ui.agent.llm.VisionInference
import com.aura.aura_ui.agent.mcpbridge.client.McpUrlValidator
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.mcp.bridge.CustomEndpointStore
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import com.aura.aura_ui.presentation.components.MonoSegmented
import com.aura.aura_ui.presentation.components.MonoSwitch
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Capsule
import com.aura.aura_ui.ui.theme.CapsuleScheme
import com.aura.aura_ui.ui.theme.MonoScheme
import kotlinx.coroutines.launch
import java.net.URI

private const val TAG = "ProviderSettings"

/**
 * Settings screen for the on-device agent's **LLM brain** (D2): which endpoint drives the
 * perceive→act→verify loop, the API key for it, and which model to run. Endpoints are now data —
 * built-in presets ([CustomEndpointStore.allEndpoints]) plus user-added OpenAI-compatible custom
 * endpoints — so the picker is a dropdown, not a fixed 3-chip row.
 *
 * Flow: pick an endpoint → paste its key → **Fetch models** (live `/models` via [ModelCatalog]) →
 * choose a model → **Save**. Everything persists per-endpoint-id in [ProviderKeyStore]. The vision
 * warning stays: AURA reads SoM-annotated screenshots, so a non-vision model is surfaced loudly.
 *
 * Styling is the Capsule design language (greige canvas, white sheet cards, one vermilion accent),
 * matching the rest of Settings — see [Capsule] and the sibling `SkillsScreen`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenTrustedDevices: () -> Unit = {},
    onOpenRestrictedApps: () -> Unit = {},
    onOpenActivityLog: () -> Unit = {},
    onOpenRetention: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val cs = Capsule.scheme()
    val keyStore = remember { ProviderKeyStore(context.applicationContext) }
    val customStore = remember {
        CustomEndpointStore(EncryptedJsonStore(context.applicationContext, CustomEndpointStore.STORE_NAME))
    }

    var allEndpoints by remember { mutableStateOf(customStore.allEndpoints()) }
    var endpoint by remember {
        val id = keyStore.getSelectedEndpointId()
        mutableStateOf(allEndpoints.firstOrNull { it.id == id } ?: allEndpoints.first())
    }
    var keyText by remember { mutableStateOf(keyStore.getKey(endpoint.id).orEmpty()) }
    var revealed by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(keyStore.getSelectedModel(endpoint.id)) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var savedAt by remember { mutableStateOf<Long?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val monoScheme = rememberMonoScheme()

    // Generation controls (D2 piece 2): thinking level + temperature, per endpoint.
    var reasoning by remember { mutableStateOf(keyStore.getGeneration(endpoint.id).reasoning) }
    var temperature by remember { mutableStateOf(keyStore.getGeneration(endpoint.id).temperature) }
    // Opt-in per-run request cap. Held as text so a half-typed number is not silently a cap.
    var runCapText by remember { mutableStateOf(keyStore.getRunMaxRequests(endpoint.id)?.toString().orEmpty()) }

    // Model-list filters (D2 truthful picker): vision-only by default, with escape hatches.
    var showAllModels by remember { mutableStateOf(false) }
    var freeOnly by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }

    // Switch the active endpoint: reload ITS saved key + model + generation, drop the fetched list
    // (model ids are endpoint-specific), and clear transient banners.
    fun switchEndpoint(next: LlmEndpoint) {
        if (next.id == endpoint.id) return
        endpoint = next
        keyText = keyStore.getKey(next.id).orEmpty()
        selectedModel = keyStore.getSelectedModel(next.id)
        val gen = keyStore.getGeneration(next.id)
        reasoning = gen.reasoning
        temperature = gen.temperature
        runCapText = keyStore.getRunMaxRequests(next.id)?.toString().orEmpty()
        models = emptyList()
        fetchError = null
        savedAt = null
        revealed = false
        showAllModels = false
        freeOnly = false
        modelQuery = ""
    }

    val selectedModelInfo = models.firstOrNull { it.id == selectedModel }
    val selectedIsNonVision = selectedModelInfo != null && !selectedModelInfo.visionCapable

    // Per-model thinking gate: the control shows only when the SELECTED model actually reasons AND
    // the endpoint's wire can carry a thinking level. "Off" is offered only when it can truly disable.
    val showThinking = selectedModelInfo?.reasoningCapable == true &&
        endpoint.profile.reasoningRequestStyle != ReasoningRequestStyle.NONE
    val canDisableThinking = selectedModelInfo?.canDisableThinking == true

    // Model-list filtering: vision-first by default, free-only where pricing is known, plus search.
    val anyPricingKnown = models.any { it.isFree != null }
    val displayedModels = models
        .filter { showAllModels || it.visionCapable }
        .filter { !freeOnly || it.isFree == true }
        .filter { modelQuery.isBlank() || it.id.contains(modelQuery.trim(), ignoreCase = true) }

    // Keep the saved thinking level truthful to the selected model: a non-reasoning model can't carry
    // a level, and a model that can't be disabled can't stay "Off". Runs on both dropdown-select and
    // fetch auto-select.
    LaunchedEffect(selectedModel, models) {
        val info = models.firstOrNull { it.id == selectedModel } ?: return@LaunchedEffect
        if (!info.reasoningCapable && reasoning != ReasoningLevel.DEFAULT) {
            reasoning = ReasoningLevel.DEFAULT
        } else if (info.reasoningCapable && reasoning == ReasoningLevel.OFF && !info.canDisableThinking) {
            reasoning = ReasoningLevel.DEFAULT
        }
    }
    val keyRequired = endpoint.requiresKey
    val canSave = (!keyRequired || keyText.isNotBlank()) && !selectedModel.isNullOrBlank()
    val host = remember(endpoint.baseUrl) {
        runCatching { URI(endpoint.baseUrl).host }.getOrNull() ?: endpoint.baseUrl
    }

    AuraScreenScaffold(
        title = "Brain",
        subtitle = "Provider, API key & model",
        onBack = onNavigateBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusBanner(
                cs = cs,
                providerName = endpoint.displayName,
                isConfigured = canSave && keyStore.getSelectedModel(endpoint.id) == selectedModel,
            )

            // B2 — the Android Keystore was unavailable, so keys are stored UNENCRYPTED. Don't report
            // silent success: warn, so the user can decide whether to trust this device with a real key.
            if (keyRequired && !keyStore.isEncrypted) {
                AttentionBanner(
                    message = "Secure storage is unavailable on this device — API keys are saved " +
                        "unencrypted. Avoid storing a sensitive key here.",
                    cs = cs,
                )
            }

            EyebrowLabel("PROVIDER", cs)
            EndpointDropdown(
                cs = cs,
                endpoints = allEndpoints,
                selected = endpoint,
                onSelect = ::switchEndpoint,
                onAddCustom = { showEditor = true },
            )

            if (!endpoint.isBuiltIn) {
                DestructivePill(
                    text = "Remove this endpoint",
                    cs = cs,
                    onClick = {
                        customStore.remove(endpoint.id)
                        keyStore.clearKey(endpoint.id)
                        allEndpoints = customStore.allEndpoints()
                        switchEndpoint(allEndpoints.first())
                    },
                )
            }

            if (keyRequired) {
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it; savedAt = null },
                    label = { Text("${endpoint.displayName} API key") },
                    singleLine = true,
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealed) "Hide key" else "Show key",
                                tint = cs.sub,
                            )
                        }
                    },
                    placeholder = { Text(endpoint.keyHint) },
                    colors = capsuleFieldColors(cs),
                    shape = Capsule.ShapeChipWell,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Your key is sent only inside the HTTPS request to $host.",
                    fontSize = 12.sp,
                    color = cs.sub,
                )
            }

            // Fetch models — uses the LIVE field text (not the stored key) so the user can fetch
            // before saving. /models is auth-gated, so a 401 here is a free key check.
            PrimaryPill(
                text = if (models.isEmpty()) "Fetch models" else "Refresh models (${models.size})",
                cs = cs,
                enabled = (!keyRequired || keyText.isNotBlank()) && !fetching,
                loading = fetching,
                loadingText = "Fetching…",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        fetching = true
                        fetchError = null
                        ModelCatalog.fetch(endpoint, keyText.trim())
                            .onSuccess { list ->
                                models = list
                                Log.i(TAG, "Fetched ${list.size} models for ${endpoint.id} (${list.count { it.visionCapable }} vision)")
                                if (list.isEmpty()) {
                                    // 200 but nothing parsed — usually a wrong models URL for this endpoint.
                                    fetchError = "The provider returned no models. Double-check the models URL for this endpoint."
                                } else if (selectedModel == null || list.none { it.id == selectedModel }) {
                                    selectedModel = list.firstOrNull()?.id
                                }
                            }
                            .onFailure { t ->
                                models = emptyList()
                                fetchError = (t as? ModelCatalog.ModelFetchError)?.userMessage
                                    ?: "Couldn't fetch models. Try again."
                                Log.w(TAG, "Model fetch failed for ${endpoint.id}", t)
                            }
                        fetching = false
                    }
                },
            )

            fetchError?.let { AttentionBanner(message = it, cs = cs) }

            if (models.isNotEmpty()) {
                ModelFilterBar(
                    cs = cs,
                    scheme = monoScheme,
                    visionOnly = !showAllModels,
                    onVisionOnly = { showAllModels = !it },
                    freeOnly = freeOnly,
                    onFreeOnly = { freeOnly = it },
                    showFreeFilter = anyPricingKnown,
                    query = modelQuery,
                    onQuery = { modelQuery = it },
                    shown = displayedModels.size,
                    total = models.size,
                )
                if (displayedModels.isEmpty()) {
                    Text(
                        text = "No models match these filters. Turn off a filter or clear the search.",
                        fontSize = 13.sp,
                        color = cs.sub,
                    )
                } else {
                    ModelDropdown(
                        cs = cs,
                        models = displayedModels,
                        selectedModel = selectedModel,
                        onSelect = { selectedModel = it; savedAt = null },
                    )
                }
                if (selectedIsNonVision) {
                    AttentionBanner(
                        message = "This model has no vision support. AURA can't read the " +
                            "screen with it — pick a model marked with the eye icon.",
                        cs = cs,
                    )
                }
            }

            GenerationControls(
                cs = cs,
                monoScheme = monoScheme,
                showThinking = showThinking,
                canDisableThinking = canDisableThinking,
                reasoning = reasoning,
                onReasoning = { reasoning = it; savedAt = null },
                temperature = temperature,
                onTemperature = { temperature = it; savedAt = null },
                runCapText = runCapText,
                onRunCapText = { runCapText = it.filter(Char::isDigit).take(4); savedAt = null },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryPill(
                    text = "Save",
                    cs = cs,
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (keyRequired) keyStore.setKey(endpoint.id, keyText.trim())
                        keyStore.setSelectedEndpointId(endpoint.id)
                        selectedModel?.let { keyStore.setSelectedModel(endpoint.id, it) }
                        // Capture the model's context window while we still have it: this is the
                        // only moment it is in hand (it came from the provider's /models listing),
                        // and the agent scales its compaction threshold to it. Writing null when
                        // the list was never fetched is deliberate — "unknown" falls back to the
                        // historical fixed threshold, whereas a stale number would mis-tune the run.
                        keyStore.setSelectedModelContextWindow(endpoint.id, selectedModelInfo?.contextWindow)
                        // P1: same capture, same reason — this is the only moment the model's
                        // vision capability is in hand, and the agent now gates the declared
                        // capability (and whether it re-injects screenshots) on it. Null when the
                        // listing was never fetched, which means "declare vision anyway".
                        keyStore.setSelectedModelVisionCapable(endpoint.id, selectedModelInfo?.visionCapable)
                        keyStore.setGeneration(endpoint.id, GenerationConfig(reasoning = reasoning, temperature = temperature))
                        // Blank (or an unparseable leftover) means no cap, which is the default and
                        // the right answer for a paid key. Only a real number becomes a ceiling.
                        keyStore.setRunMaxRequests(endpoint.id, runCapText.toIntOrNull())
                        savedAt = System.currentTimeMillis()
                        Log.i(TAG, "Saved ${endpoint.id} / $selectedModel / reasoning=$reasoning temp=$temperature cap=${runCapText.ifBlank { "none" }}")
                    },
                )
                SecondaryPill(
                    text = "Clear",
                    cs = cs,
                    enabled = keyText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        keyStore.clearKey(endpoint.id)
                        keyText = ""
                        selectedModel = null
                        models = emptyList()
                        fetchError = null
                        savedAt = null
                    },
                )
            }

            if (savedAt != null) {
                Text(
                    text = "Saved — agent will use ${endpoint.displayName} / $selectedModel.",
                    color = cs.ok,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (endpoint.keyUrl.isNotBlank()) {
                TextButton(
                    onClick = { uriHandler.openUri(endpoint.keyUrl) },
                    colors = ButtonDefaults.textButtonColors(contentColor = cs.accent),
                ) {
                    Text("Get a ${endpoint.displayName} key →")
                }
            }

            Text(
                text = "Storage: encrypted with the Android Keystore (AES-256 GCM). Keys " +
                    "never leave this device except inside the HTTPS request to the provider.",
                fontSize = 12.sp,
                color = cs.sub,
            )

            EyebrowLabel("MORE", cs)
            Surface(
                shape = Capsule.ShapeCard,
                color = cs.card,
                border = BorderStroke(1.dp, cs.line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    CapsuleNavRow(
                        icon = Icons.Filled.Security,
                        title = "Trusted Devices",
                        subtitle = "Computers allowed to connect — revoke access",
                        cs = cs,
                        onClick = onOpenTrustedDevices,
                    )
                    HorizontalDivider(color = cs.line, modifier = Modifier.padding(start = 56.dp))
                    CapsuleNavRow(
                        icon = Icons.Filled.Block,
                        title = "Restricted Apps",
                        subtitle = "Apps AURA declines to act in — banking, payments, auth",
                        cs = cs,
                        onClick = onOpenRestrictedApps,
                    )
                    HorizontalDivider(color = cs.line, modifier = Modifier.padding(start = 56.dp))
                    CapsuleNavRow(
                        icon = Icons.Filled.History,
                        title = "Activity Log",
                        subtitle = "Tool calls, connections, screenshots",
                        cs = cs,
                        onClick = onOpenActivityLog,
                    )
                    HorizontalDivider(color = cs.line, modifier = Modifier.padding(start = 56.dp))
                    CapsuleNavRow(
                        icon = Icons.Filled.Schedule,
                        title = "Log Retention",
                        subtitle = "How long to keep session logs",
                        cs = cs,
                        onClick = onOpenRetention,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showEditor) {
        CustomEndpointEditor(
            cs = cs,
            onDismiss = { showEditor = false },
            onSave = { newEndpoint ->
                customStore.upsert(newEndpoint)
                allEndpoints = customStore.allEndpoints()
                showEditor = false
                switchEndpoint(allEndpoints.first { it.id == newEndpoint.id })
            },
        )
    }
}

/** Field colors that make OutlinedTextFields speak the Capsule language (ink border, no purple). */
@Composable
private fun capsuleFieldColors(cs: CapsuleScheme) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = cs.ink,
    unfocusedBorderColor = cs.line,
    cursorColor = cs.accent,
    focusedLabelColor = cs.ink,
    unfocusedLabelColor = cs.sub,
    focusedTextColor = cs.ink,
    unfocusedTextColor = cs.ink,
    focusedContainerColor = cs.card,
    unfocusedContainerColor = cs.card,
)

/** Small uppercase eyebrow above a control, matching the Capsule section labels. */
@Composable
private fun EyebrowLabel(text: String, cs: CapsuleScheme) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold),
        color = cs.sub,
    )
}

/** The ink pill — Capsule's primary action button (charcoal capsule + light content). */
@Composable
private fun PrimaryPill(
    text: String,
    cs: CapsuleScheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = Capsule.ShapePill,
        color = if (enabled) cs.capsule else cs.line,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = cs.onCapsule,
                )
            }
            Text(
                text = if (loading) (loadingText ?: text) else text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) cs.onCapsule else cs.sub,
            )
        }
    }
}

/** Outlined pill — Capsule's secondary/neutral action (hairline on canvas). */
@Composable
private fun SecondaryPill(
    text: String,
    cs: CapsuleScheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = Capsule.ShapePill,
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, if (enabled) cs.line else cs.line.copy(alpha = 0.5f)),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) cs.ink else cs.sub,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 15.dp),
        )
    }
}

/** Destructive pill — soft accent field with accent content (remove/delete rows). */
@Composable
private fun DestructivePill(
    text: String,
    cs: CapsuleScheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = Capsule.ShapePill,
        color = cs.accentSoft,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = cs.accent, modifier = Modifier.size(18.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = cs.accent,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointDropdown(
    cs: CapsuleScheme,
    endpoints: List<LlmEndpoint>,
    selected: LlmEndpoint,
    onSelect: (LlmEndpoint) -> Unit,
    onAddCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Endpoint") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = capsuleFieldColors(cs),
            shape = Capsule.ShapeChipWell,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            endpoints.forEach { e ->
                DropdownMenuItem(
                    text = {
                        Text(
                            e.displayName,
                            color = cs.ink,
                            fontWeight = if (e.id == selected.id) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = if (e.id == selected.id) {
                        { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = cs.accent, modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = { onSelect(e); expanded = false },
                )
            }
            HorizontalDivider(color = cs.line)
            DropdownMenuItem(
                text = { Text("Add custom endpoint", color = cs.ink) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = cs.accent, modifier = Modifier.size(18.dp)) },
                onClick = { onAddCustom(); expanded = false },
            )
        }
    }
}

@Composable
private fun CustomEndpointEditor(
    cs: CapsuleScheme,
    onDismiss: () -> Unit,
    onSave: (LlmEndpoint) -> Unit,
) {
    val scheme = rememberMonoScheme()
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var chatPath by remember { mutableStateOf("/v1/chat/completions") }
    var modelsUrl by remember { mutableStateOf("") }
    var localNoAuth by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val fieldColors = capsuleFieldColors(cs)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.card,
        titleContentColor = cs.ink,
        textContentColor = cs.sub,
        shape = Capsule.ShapeCard,
        title = { Text("Custom endpoint", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Any OpenAI-compatible endpoint (Together, Fireworks, a local server, …). " +
                        "Must be a vision-capable model to drive the screen.",
                    fontSize = 12.sp,
                    color = cs.sub,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, colors = fieldColors, shape = Capsule.ShapeChipWell, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL (host only)") }, placeholder = { Text("https://api.example.com") }, singleLine = true, colors = fieldColors, shape = Capsule.ShapeChipWell, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(chatPath, { chatPath = it }, label = { Text("Chat completions path") }, singleLine = true, colors = fieldColors, shape = Capsule.ShapeChipWell, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(modelsUrl, { modelsUrl = it }, label = { Text("Models URL") }, placeholder = { Text("https://api.example.com/v1/models") }, singleLine = true, colors = fieldColors, shape = Capsule.ShapeChipWell, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoSwitch(checked = localNoAuth, onCheckedChange = { localNoAuth = it }, scheme = scheme)
                    Spacer(Modifier.width(8.dp))
                    Text("Local endpoint (no API key)", fontSize = 13.sp, color = cs.ink)
                }
                error?.let { Text(it, color = cs.accent, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = cs.accent),
                onClick = {
                    if (name.isBlank() || baseUrl.isBlank() || modelsUrl.isBlank()) {
                        error = "Name, base URL, and models URL are required."; return@TextButton
                    }
                    // https-only, with the localhost carve-out for local endpoints (Ollama/LM Studio).
                    val baseOk = McpUrlValidator.validate(baseUrl.trim(), allowLocalhostDebug = true)
                    val modelsOk = McpUrlValidator.validate(modelsUrl.trim(), allowLocalhostDebug = true)
                    val failure = baseOk.exceptionOrNull() ?: modelsOk.exceptionOrNull()
                    if (failure != null) { error = failure.message ?: "Invalid URL"; return@TextButton }
                    onSave(
                        LlmEndpoint(
                            id = CustomEndpointStore.newCustomId(name),
                            displayName = name.trim(),
                            baseUrl = baseUrl.trim(),
                            chatCompletionsPath = chatPath.trim().ifBlank { "/v1/chat/completions" },
                            modelsUrl = modelsUrl.trim(),
                            authScheme = if (localNoAuth) AuthScheme.NONE else AuthScheme.BEARER,
                            modelListStyle = ModelListStyle.OPENAI_DATA,
                            visionInference = VisionInference.GENERIC,
                            profile = ProviderProfile(),
                            isBuiltIn = false,
                            requiresKey = !localNoAuth,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = cs.sub),
            ) { Text("Cancel") }
        },
    )
}

/**
 * Filter row above the model picker: a "Vision only" switch (on by default — this agent always reads
 * the screen), an optional "Free only" switch (only where pricing is known, i.e. OpenRouter), a search
 * field, and a shown/total counter so a filtered-down list is never mistaken for an empty catalog.
 */
@Composable
private fun ModelFilterBar(
    cs: CapsuleScheme,
    scheme: MonoScheme,
    visionOnly: Boolean,
    onVisionOnly: (Boolean) -> Unit,
    freeOnly: Boolean,
    onFreeOnly: (Boolean) -> Unit,
    showFreeFilter: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    shown: Int,
    total: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search models") },
            singleLine = true,
            colors = capsuleFieldColors(cs),
            shape = Capsule.ShapeChipWell,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MonoSwitch(checked = visionOnly, onCheckedChange = onVisionOnly, scheme = scheme)
            Text("Vision only", fontSize = 13.sp, color = cs.ink)
            if (showFreeFilter) {
                Spacer(Modifier.width(12.dp))
                MonoSwitch(checked = freeOnly, onCheckedChange = onFreeOnly, scheme = scheme)
                Text("Free only", fontSize = 13.sp, color = cs.ink)
            }
            Spacer(Modifier.weight(1f))
            Text("$shown / $total", fontSize = 12.sp, color = cs.sub)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    cs: CapsuleScheme,
    models: List<ModelInfo>,
    selectedModel: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedModel ?: "Select a model",
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = capsuleFieldColors(cs),
            shape = Capsule.ShapeChipWell,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                model.id,
                                color = cs.ink,
                                fontWeight = if (model.id == selectedModel) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!model.visionCapable) Text("no vision", fontSize = 11.sp, color = cs.accent)
                                if (model.reasoningCapable) Text("thinks", fontSize = 11.sp, color = cs.sub)
                                if (model.isFree == true) Text("free", fontSize = 11.sp, color = cs.ok)
                            }
                        }
                    },
                    trailingIcon = if (model.visionCapable) {
                        {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Vision-capable",
                                tint = cs.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                    onClick = { onSelect(model.id); expanded = false },
                )
            }
        }
    }
}

/** Quiet status card at the top of the screen (white sheet, accent only in the icon). */
@Composable
private fun StatusBanner(cs: CapsuleScheme, providerName: String, isConfigured: Boolean) {
    Surface(
        shape = Capsule.ShapeCard,
        color = cs.card,
        border = BorderStroke(1.dp, cs.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(Capsule.ShapeChipWell)
                    .background(cs.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isConfigured) cs.ok else cs.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = if (isConfigured) "$providerName is configured and ready." else "Paste a key, fetch models, and pick one to enable the agent.",
                color = cs.ink,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Soft-accent inline banner for real problems (fetch failed, non-vision model). */
@Composable
private fun AttentionBanner(message: String, cs: CapsuleScheme) {
    Surface(
        shape = Capsule.ShapeChipWell,
        color = cs.accentSoft,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = cs.accent, modifier = Modifier.size(20.dp))
            Text(message, color = cs.ink, fontSize = 13.sp)
        }
    }
}

/** Capsule-styled navigation row for the "MORE" section (mirrors MCP Center's NavRow). */
@Composable
private fun CapsuleNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    cs: CapsuleScheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = cs.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.sub)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.sub)
    }
}

/** Slider shows this until the user first moves it; temperature stays null (Auto) until then. */
private const val TEMP_DEFAULT = 0.2

/**
 * Thinking level (when the provider supports it) + sampling temperature (D2 piece 2).
 * The segmented order maps 1:1 to [ReasoningLevel]'s ordinal (Auto/Off/Low/Med/High). Temperature
 * stays null ("Auto") until the user moves the slider — and is cautioned low, because this agent
 * taps real UI and randomness means mis-taps.
 */
@Composable
private fun GenerationControls(
    cs: CapsuleScheme,
    monoScheme: MonoScheme,
    showThinking: Boolean,
    canDisableThinking: Boolean,
    reasoning: ReasoningLevel,
    onReasoning: (ReasoningLevel) -> Unit,
    temperature: Double?,
    onTemperature: (Double) -> Unit,
    runCapText: String,
    onRunCapText: (String) -> Unit,
) {
    if (showThinking) {
        // Per-model option set: "Off" appears only when this model can truly disable thinking, so no
        // segment is ever a lie. The index↔level map is explicit (not ReasoningLevel.ordinal) because
        // the option list is variable-length.
        val options = buildList {
            add("Auto" to ReasoningLevel.DEFAULT)
            if (canDisableThinking) add("Off" to ReasoningLevel.OFF)
            add("Low" to ReasoningLevel.LOW)
            add("Med" to ReasoningLevel.MEDIUM)
            add("High" to ReasoningLevel.HIGH)
        }
        val selectedIndex = options.indexOfFirst { it.second == reasoning }.coerceAtLeast(0)
        EyebrowLabel("THINKING", cs)
        MonoSegmented(
            options = options.map { it.first },
            selectedIndex = selectedIndex,
            onSelect = { onReasoning(options[it].second) },
            scheme = monoScheme,
        )
        Text(
            text = if (canDisableThinking) {
                "Auto uses the model's default. Off turns thinking off; Low/Med/High set how hard it thinks."
            } else {
                "This model always thinks — you can only set how hard (Auto uses its default)."
            },
            fontSize = 12.sp,
            color = cs.sub,
        )
    }

    EyebrowLabel("TEMPERATURE", cs)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Slider(
            value = (temperature ?: TEMP_DEFAULT).toFloat(),
            onValueChange = { onTemperature(it.toDouble()) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = cs.ink,
                activeTrackColor = cs.ink,
                inactiveTrackColor = cs.line,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (temperature == null) "Auto" else "%.2f".format(temperature),
            color = cs.ink,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    Text(
        text = "Lower is steadier. Higher makes the agent's taps more random — keep it low for reliable automation.",
        fontSize = 12.sp,
        color = cs.sub,
    )

    EyebrowLabel("STEP LIMIT PER TASK", cs)
    OutlinedTextField(
        value = runCapText,
        onValueChange = onRunCapText,
        singleLine = true,
        placeholder = { Text("No limit", color = cs.sub) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = capsuleFieldColors(cs),
        shape = Capsule.ShapeChipWell,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = "Leave blank unless your key has a daily request allowance. A free tier that gives " +
            "you, say, 500 requests a day is spent by roughly six 80-step tasks — a limit here " +
            "stops one confused run from using the lot. Paid keys don't need it.",
        fontSize = 12.sp,
        color = cs.sub,
    )
}
