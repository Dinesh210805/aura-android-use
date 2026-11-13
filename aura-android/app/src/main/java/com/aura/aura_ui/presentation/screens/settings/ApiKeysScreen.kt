package com.aura.aura_ui.presentation.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.mcp.bridge.CustomEndpointStore
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.mcp.bridge.TavilyKeyStore
import com.aura.aura_ui.presentation.components.AuraButton
import com.aura.aura_ui.presentation.components.AuraDivider
import com.aura.aura_ui.presentation.components.AuraHint
import com.aura.aura_ui.presentation.components.AuraPage
import com.aura.aura_ui.presentation.components.AuraRow
import com.aura.aura_ui.presentation.components.AuraSection
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura

/** The id the page uses for the Tavily web-search key, which lives in [TavilyKeyStore]. */
const val TAVILY_KEY_ID = "tavily"

/** One service AURA can hold a key for. */
private data class KeyProvider(val id: String, val name: String, val hint: String, val url: String)

/**
 * Where every API key in the app is added, viewed, changed and removed. Onboarding is the only
 * other place a key can be entered, and it saves to the same store, so its key shows up here.
 *
 * - Contract: lists every provider that needs a key (built-in and custom model providers, and
 *   Tavily web search). Keys are masked to their last four characters until "Show" is tapped.
 *   Screens that need a key link here with [preselect] set instead of asking for it themselves.
 * - Fails: closed. When the phone cannot encrypt, saving is refused and the page says so.
 * - Reads/Writes: `ProviderKeyStore` and `TavilyKeyStore` (encrypted `aura_secure_keys`). The
 *   window is `FLAG_SECURE` while the page is open, so keys never land in screenshots or Recents.
 */
@Composable
fun ApiKeysScreen(preselect: String? = null) {
    val context = LocalContext.current
    val aura = LocalAura.current
    val keys = remember { ProviderKeyStore(context.applicationContext) }
    val tavily = remember { TavilyKeyStore(context.applicationContext) }
    val providers = remember { providers(context) }
    var tick by remember { mutableIntStateOf(0) }
    val saved = remember(tick) { providers.mapNotNull { p -> keyOf(p.id, keys, tavily)?.let { p to it } } }
    var shown by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf(preselect?.let { id -> providers.firstOrNull { it.id == id } }) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<KeyProvider?>(null) }

    SecureWindow()

    AuraPage("API keys") {
        if (!keys.isEncrypted) {
            AuraSection {
                Text(
                    "This phone can't encrypt keys, so AURA won't store any. Restart the phone and try again.",
                    Modifier.padding(16.dp),
                    style = AuraType.callout,
                    color = aura.danger,
                )
            }
        }
        AuraSection(title = "Saved") {
            if (saved.isEmpty()) AuraHint("No keys yet.")
            saved.forEachIndexed { i, (provider, key) ->
                if (i > 0) AuraDivider(16.dp)
                SavedKeyRow(
                    name = provider.name,
                    key = key,
                    visible = provider.id in shown,
                    onToggle = { shown = if (provider.id in shown) shown - provider.id else shown + provider.id },
                    onEdit = { editing = provider },
                    onDelete = { deleting = provider },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        AuraButton("Add API key", { picking = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, enabled = keys.isEncrypted)
        Text(
            "Keys are encrypted on this phone and sent only to their own service.",
            Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            style = AuraType.footnote,
            color = aura.onSkyMuted,
        )
    }

    if (picking) {
        ProviderPicker(providers, saved.map { it.first.id }.toSet(), onDismiss = { picking = false }) {
            picking = false
            editing = it
        }
    }
    editing?.let { provider ->
        KeyEditor(provider, onDismiss = { editing = null }) { value ->
            val ok = if (provider.id == TAVILY_KEY_ID) tavily.setApiKey(value) else keys.setKey(provider.id, value)
            if (ok) {
                editing = null
                tick++
            }
            ok
        }
    }
    deleting?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete the ${provider.name} key?") },
            text = { Text("Anything that uses ${provider.name} stops working until you add a key again.") },
            confirmButton = {
                TextButton(onClick = {
                    if (provider.id == TAVILY_KEY_ID) tavily.clear() else keys.clearKey(provider.id)
                    deleting = null
                    tick++
                }) { Text("Delete", color = aura.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = aura.label) } },
        )
    }
}

private fun providers(context: Context): List<KeyProvider> {
    val custom = runCatching {
        CustomEndpointStore(EncryptedJsonStore(context, CustomEndpointStore.STORE_NAME)).allEndpoints()
    }.getOrDefault(emptyList())
    val models = (LlmEndpointCatalog.BUILTINS + custom).distinctBy { it.id }.filter { it.requiresKey }
        .map { KeyProvider(it.id, it.displayName, it.keyHint, it.keyUrl) }
    return models + KeyProvider(TAVILY_KEY_ID, "Tavily web search", "tvly-…", "https://app.tavily.com/home")
}

private fun keyOf(id: String, keys: ProviderKeyStore, tavily: TavilyKeyStore): String? =
    if (id == TAVILY_KEY_ID) tavily.getApiKey() else keys.getKey(id)

/** "••••••••abcd": enough to tell two keys apart, not enough to use one. */
private fun mask(key: String): String = "•".repeat(8) + key.takeLast(4)

@Composable
private fun SavedKeyRow(name: String, key: String, visible: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val aura = LocalAura.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = AuraType.body, color = aura.label)
            Text(if (visible) key else mask(key), style = AuraType.code, color = aura.secondary)
        }
        IconAction(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Hide key" else "Show key", onToggle)
        IconAction(Icons.Outlined.Edit, "Change key", onEdit)
        IconAction(Icons.Outlined.Delete, "Delete key", onDelete)
    }
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(AuraShape.Pill).clickable(onClickLabel = label, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = LocalAura.current.label, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPicker(providers: List<KeyProvider>, saved: Set<String>, onDismiss: () -> Unit, onPick: (KeyProvider) -> Unit) {
    val aura = LocalAura.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = aura.surfaceStrong) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Add API key", Modifier.padding(start = 4.dp, bottom = 12.dp), style = AuraType.title, color = aura.label)
            AuraSection {
                providers.forEachIndexed { i, provider ->
                    if (i > 0) AuraDivider(52.dp)
                    AuraRow(provider.name, icon = Icons.Outlined.Key, value = "Saved".takeIf { provider.id in saved }, onClick = { onPick(provider) })
                }
            }
        }
    }
}

/** Paste a key for [provider]. [onSave] returns false when the key could not be stored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyEditor(provider: KeyProvider, onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    val aura = LocalAura.current
    val context = LocalContext.current
    var value by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = value.isNotBlank() && value.trim().none { it.isWhitespace() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = aura.surfaceStrong) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(provider.name, style = AuraType.title, color = aura.label)
            Row(
                Modifier.fillMaxWidth().clip(AuraShape.Inner).background(aura.well).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (value.isEmpty()) Text(provider.hint.ifBlank { "Paste your API key" }, style = AuraType.body, color = aura.tertiary)
                    BasicTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AuraType.code.copy(color = aura.label, fontSize = AuraType.body.fontSize),
                        cursorBrush = SolidColor(aura.label),
                        singleLine = true,
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    )
                }
                IconAction(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "Hide key" else "Show key") { reveal = !reveal }
            }
            error?.let { Text(it, style = AuraType.footnote, color = aura.danger) }
            if (provider.url.isNotBlank()) {
                Text(
                    "Get a key from ${provider.name}",
                    Modifier.clip(AuraShape.Pill).clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(vertical = 6.dp),
                    style = AuraType.callout,
                    color = aura.label,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuraButton("Cancel", onDismiss, Modifier.weight(1f), primary = false)
                AuraButton("Save", {
                    if (!onSave(value.trim())) error = "This phone can't store keys safely, so the key was not saved."
                }, Modifier.weight(1f), enabled = valid)
            }
        }
    }
}

/** Keeps this page out of screenshots, screen recordings and the Recents preview while open. */
@Composable
private fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
