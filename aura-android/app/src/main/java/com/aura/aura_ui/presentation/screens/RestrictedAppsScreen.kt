package com.aura.aura_ui.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.agent.llm.RestrictedAppClassifier
import com.aura.aura_ui.data.AppInfo
import com.aura.aura_ui.mcp.AppRestrictedApps
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import com.aura.aura_ui.ui.theme.Capsule
import com.aura.aura_ui.ui.theme.CapsuleScheme
import com.aura.aura_ui.utils.AppInventoryScanner
import com.aura.aura_ui.utils.isLaunchable
import com.aura.mcp.bridge.RestrictedAppCategory
import com.aura.mcp.bridge.RestrictedAppEntry
import com.aura.mcp.bridge.RestrictedAppSource
import com.aura.mcp.bridge.RestrictedTier
import kotlinx.coroutines.launch

/**
 * Restricted Apps — the user-managed list of apps AURA should stay out of.
 *
 * Two tiers per app (see [RestrictedTier]): the softer "Decline" (AURA refuses
 * to act while the app is foreground, accessibility stays on) and "Also disable
 * Accessibility" (for apps confirmed to block AURA at the OS level — see the
 * banking-accessibility-check design discussion this screen implements).
 *
 * "Scan for sensitive apps" runs the device's installed-app inventory through
 * [RestrictedAppClassifier] once and merges suggestions in — the user still has
 * to accept nothing (suggestions land as real, editable/removable entries with
 * `source = LLM_SUGGESTED`), they just review and adjust from there. A manual
 * add path covers anything the scan misses or that the user wants pre-emptively.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestrictedAppsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val cs = Capsule.scheme()
    val scope = rememberCoroutineScope()

    val store = remember { AppRestrictedApps.get(context) }
    val entries by store.entries.collectAsState()

    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<RestrictedAppEntry?>(null) }

    AuraScreenScaffold(title = "Restricted Apps", subtitle = "Apps AURA stays out of") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "AURA will not act inside these apps. \"Decline\" just refuses to help; " +
                    "\"Also disable Accessibility\" additionally turns AURA's accessibility " +
                    "off the moment the app opens, for apps that block AURA at the OS level " +
                    "(most banking/UPI apps). That one requires manually turning Accessibility " +
                    "back on afterward — Android allows no other way.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.sub,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            scanning = true
                            scanError = null
                            try {
                                val apps = AppInventoryScanner(context)
                                    .scanInstalledApps()
                                    .filter { it.isLaunchable() }
                                val suggestions = RestrictedAppClassifier(context).classify(apps)
                                store.mergeSuggestions(suggestions)
                            } catch (t: Throwable) {
                                scanError = t.message ?: "Scan failed"
                            } finally {
                                scanning = false
                            }
                        }
                    },
                    enabled = !scanning,
                    modifier = Modifier.weight(1f),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Scanning…")
                    } else {
                        Text("Scan for sensitive apps")
                    }
                }
                OutlinedButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            }

            scanError?.let { err ->
                Text(
                    "Couldn't scan: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (entries.isEmpty()) {
                EmptyRestrictedAppsCard(cs)
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Column {
                        val sorted = entries.sortedWith(
                            compareBy<RestrictedAppEntry> { it.category.ordinal }.thenBy { it.appName },
                        )
                        sorted.forEachIndexed { i, entry ->
                            RestrictedAppRow(
                                entry = entry,
                                cs = cs,
                                onTierChange = { tier -> store.updateTier(entry.packageName, tier) },
                                onRemove = { pendingRemove = entry },
                            )
                            if (i < sorted.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    pendingRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove ${entry.appName}?") },
            text = { Text("AURA will be free to act inside this app again.") },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(entry.packageName)
                    pendingRemove = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            },
        )
    }

    if (showAddDialog) {
        AddRestrictedAppDialog(
            context = context,
            alreadyRestricted = entries.map { it.packageName }.toSet(),
            onPick = { app ->
                store.upsert(
                    RestrictedAppEntry(
                        packageName = app.packageName,
                        appName = app.appName,
                        category = RestrictedAppCategory.OTHER,
                        tier = RestrictedTier.DECLINE_ONLY,
                        source = RestrictedAppSource.USER_ADDED,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun EmptyRestrictedAppsCard(cs: CapsuleScheme) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Shield, null, tint = cs.sub, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "No restricted apps yet. Run a scan or add one manually — banking, payment, " +
                    "trading, and authenticator apps are good candidates.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.sub,
            )
        }
    }
}

@Composable
private fun RestrictedAppRow(
    entry: RestrictedAppEntry,
    cs: CapsuleScheme,
    onTierChange: (RestrictedTier) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(cs.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(categoryIcon(entry.category), null, tint = cs.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.appName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(
                        categoryLabel(entry.category),
                        if (entry.source == RestrictedAppSource.LLM_SUGGESTED) "suggested" else null,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.sub,
                )
            }
            TextButton(
                onClick = onRemove,
                colors = ButtonDefaults.textButtonColors(contentColor = cs.accent),
            ) {
                Text("Remove", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = entry.tier == RestrictedTier.DECLINE_ONLY,
                onClick = { onTierChange(RestrictedTier.DECLINE_ONLY) },
                label = { Text("Decline") },
            )
            FilterChip(
                selected = entry.tier == RestrictedTier.DISABLE_ACCESSIBILITY,
                onClick = { onTierChange(RestrictedTier.DISABLE_ACCESSIBILITY) },
                label = { Text("Also disable Accessibility") },
            )
        }
    }
}

@Composable
private fun AddRestrictedAppDialog(
    context: android.content.Context,
    alreadyRestricted: Set<String>,
    onPick: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // A short list used to be indistinguishable from a working scan on a bare
    // device, which is how a near-empty picker went unnoticed. Keep the scan's own
    // verdict so the dialog can say so out loud.
    var scanWarning by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { AppInventoryScanner(context).scan() }
            .onSuccess { report ->
                apps = report.apps.filter { it.isLaunchable() }
                scanWarning = when {
                    report.error != null ->
                        "Couldn't read the full app list (${report.error}). Showing ${apps.size} app(s)."
                    report.degraded ->
                        "Your system reports ${report.launcherEntries} openable apps but only " +
                            "${apps.size} could be read. Some apps may be missing from this list."
                    else -> null
                }
            }
            .onFailure { scanWarning = "App scan failed: ${it.message ?: it::class.simpleName}" }
        loading = false
    }

    val filtered = remember(apps, query, alreadyRestricted) {
        apps.filterNot { it.packageName in alreadyRestricted }
            .filter {
                query.isBlank() ||
                    it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .sortedBy { it.appName }
            .take(50)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an app") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search installed apps") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                scanWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                    filtered.isEmpty() -> Text(
                        "No matching apps",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> LazyColumn {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPick(app) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun categoryIcon(category: RestrictedAppCategory): ImageVector = when (category) {
    RestrictedAppCategory.BANKING -> Icons.Filled.AccountBalance
    RestrictedAppCategory.PAYMENT -> Icons.Filled.Payments
    RestrictedAppCategory.TRADING_CRYPTO -> Icons.Filled.TrendingUp
    RestrictedAppCategory.AUTH_PASSWORD -> Icons.Filled.Password
    RestrictedAppCategory.OTHER -> Icons.Filled.Apps
}

private fun categoryLabel(category: RestrictedAppCategory): String = when (category) {
    RestrictedAppCategory.BANKING -> "Banking"
    RestrictedAppCategory.PAYMENT -> "Payment"
    RestrictedAppCategory.TRADING_CRYPTO -> "Trading / Crypto"
    RestrictedAppCategory.AUTH_PASSWORD -> "Authenticator / Password"
    RestrictedAppCategory.OTHER -> "Other"
}
