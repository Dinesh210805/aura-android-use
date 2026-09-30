package com.aura.aura_ui.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.mcp.bridge.TavilyKeyStore

/**
 * Settings screen for the Tavily API key used by the on-device `web_search` MCP tool.
 *
 * The key is persisted in [TavilyKeyStore] (EncryptedSharedPreferences). The
 * field stays masked by default; users can toggle visibility to confirm what
 * they pasted. There is no inline validation request — Tavily charges per call,
 * so we don't want to burn the user's quota probing — instead we let the first
 * real `web_search` invocation surface auth errors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TavilyKeyScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val keyStore = remember { TavilyKeyStore(context.applicationContext) }

    var keyText by remember { mutableStateOf(keyStore.getApiKey().orEmpty()) }
    var revealed by remember { mutableStateOf(false) }
    var savedAt by remember { mutableStateOf<Long?>(null) }

    val isConfigured = keyText.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web Search (Tavily)") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusBanner(isConfigured = isConfigured)

            Text(
                text = "Why this exists",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = "AURA's web_search tool fetches live documentation from the open " +
                    "web — for example, when you ask 'create a WhatsApp group', it looks " +
                    "up the official steps before planning the on-screen taps. This needs " +
                    "a free Tavily API key.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                label = { Text("Tavily API key") },
                singleLine = true,
                visualTransformation = if (revealed) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            imageVector = if (revealed) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = if (revealed) "Hide key" else "Show key",
                        )
                    }
                },
                placeholder = { Text("tvly-...") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        keyStore.setApiKey(keyText.trim())
                        savedAt = System.currentTimeMillis()
                    },
                    enabled = keyText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = {
                        keyStore.clear()
                        keyText = ""
                        savedAt = null
                    },
                    enabled = isConfigured,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }
            }

            if (savedAt != null) {
                Text(
                    text = "Saved.",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = { uriHandler.openUri("https://tavily.com/") },
            ) {
                Text("Get a free key at tavily.com →")
            }

            Text(
                text = "Storage: encrypted with the Android Keystore (AES-256 GCM). " +
                    "The key never leaves this device except inside the HTTPS POST body " +
                    "to api.tavily.com.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusBanner(isConfigured: Boolean) {
    val bg = if (isConfigured) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val fg = if (isConfigured) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = fg,
        )
        Text(
            text = if (isConfigured) {
                "web_search is enabled."
            } else {
                "web_search is disabled — paste a Tavily key below."
            },
            color = fg,
            fontWeight = FontWeight.Medium,
        )
    }
}
