package com.aura.aura_ui.agentcore

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aura.aura_ui.presentation.components.AuraButton
import com.aura.aura_ui.presentation.components.AuraDivider
import com.aura.aura_ui.presentation.components.AuraHint
import com.aura.aura_ui.presentation.components.AuraPage
import com.aura.aura_ui.presentation.components.AuraRow
import com.aura.aura_ui.presentation.components.AuraSwitch
import com.aura.aura_ui.presentation.components.AuraSection
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura

/**
 * The Connectors page: sign in to Composio once, then every app one below the other with its
 * own switch; any other MCP server below.
 *
 * - Contract: Sign in runs OAuth (`McpOAuth`), no key to paste. Turning an app on asks Composio
 *   about that one app and opens its sign-in page when it needs one; the row, and every
 *   connected app, re-checks when AURA comes back to the front (`AgentPagesViewModel.recheck`).
 *   Turning one off makes AURA refuse its calls; revoking it at Composio is under Manage.
 * - Change together: `ComposioApps.kt` (catalog, sign-in calls), `McpOAuth.kt`, `AgentPagesViewModel`.
 */
@Composable
fun ConnectorsPage(vm: AgentPagesViewModel = viewModel()) {
    val connectors by vm.connectors.collectAsState()
    val status by vm.connectorStatus.collectAsState()
    val oauthStatus by vm.oauthStatus.collectAsState()
    val apps by vm.apps.collectAsState()
    val changed by vm.changedTools.collectAsState()
    val uri = LocalUriHandler.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.recheck() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val composio = connectors.firstOrNull { it.url == COMPOSIO_MCP_URL }
    val open: (String) -> Unit = { url -> runCatching { uri.openUri(url) } }

    AuraPage("Connectors", subtitle = "Accounts AURA can use for you") {
        if (composio == null) {
            ComposioSignIn(oauthStatus) { vm.signInComposio(open) }
        } else {
            changed[composio.id]?.let { n -> ChangedTools(n) { vm.allowChangedTools(composio.id) } }
            AppList(apps, { slug, on -> vm.toggleApp(slug, on, open) }, open)
            AuraSection(footer = "Turning an app off stops AURA using it. To remove its access completely, use Manage. What AURA sends an app and reads back passes through Composio, and AURA treats what comes back as untrusted.") {
                AuraRow("Manage at Composio", onClick = { open(COMPOSIO_DASHBOARD_URL) })
                AuraDivider(16.dp)
                AuraRow("Sign out of Composio", destructive = true, onClick = { vm.removeConnector(composio.id) })
            }
        }
        connectors.filterNot { it.url == COMPOSIO_MCP_URL }.forEach { server ->
            changed[server.id]?.let { n -> ChangedTools(n, server.displayName) { vm.allowChangedTools(server.id) } }
        }
        OtherServers(connectors.filterNot { it.url == COMPOSIO_MCP_URL }, status, vm)
    }
}

/**
 * [count] of a connector's tools changed since the user approved them, so AURA hides them; Allow
 * approves them as they are now.
 * - Why it asks: a connector changing what its tools say is how a server could slip new
 *   instructions to the agent, so the change waits for the user.
 */
@Composable
private fun ChangedTools(count: Int, name: String = "Composio", onAllow: () -> Unit) {
    val aura = LocalAura.current
    AuraSection {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$name updated ${if (count == 1) "a tool" else "$count tools"}", style = AuraType.headline, color = aura.label)
                Text("AURA can't use ${if (count == 1) "it" else "them"} until you allow the new version.", style = AuraType.footnote, color = aura.secondary)
            }
            Spacer(Modifier.width(12.dp))
            AuraButton("Allow", onAllow, primary = false)
        }
    }
    Spacer(Modifier.height(14.dp))
}

/** Before sign-in: what Composio gives, and one button. */
@Composable
private fun ComposioSignIn(status: String?, onSignIn: () -> Unit) {
    val aura = LocalAura.current
    AuraSection {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)) {
            Row {
                listOf("gmail", "slack", "notion", "github", "googlecalendar").mapNotNull { s -> COMPOSIO_APPS.firstOrNull { it.slug == s } }.forEachIndexed { i, app ->
                    AppTile(app, Modifier.offset(x = (-10 * i).dp).border(2.dp, aura.surface, RoundedCornerShape(13.dp)))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Connect your apps", style = AuraType.title, color = aura.label)
            Spacer(Modifier.height(6.dp))
            Text(
                "Gmail, Calendar, Drive, Slack, Notion and ${COMPOSIO_APPS.size - 5} more. Sign in to Composio once, then switch on only the apps you want AURA to use.",
                style = AuraType.callout,
                color = aura.secondary,
            )
            if (status != null) {
                Spacer(Modifier.height(10.dp))
                Text(status, style = AuraType.footnote, color = aura.secondary)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    AuraButton("Sign in with Composio", onSignIn, Modifier.fillMaxWidth())
}

/** Every app one below the other, the ones that are on first; the search also takes any Composio app name. */
@Composable
private fun AppList(apps: Map<String, AppState>, toggle: (String, Boolean) -> Unit, open: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val known = COMPOSIO_APPS.associateBy { it.slug }
    val extra = apps.keys.filterNot { it in known }.map { ComposioApp(it, it.replaceFirstChar(Char::uppercase), "Composio app", 0xFF6B7280) }
    val all = (COMPOSIO_APPS + extra).sortedByDescending { apps[it.slug] != null }
    val shown = all.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.slug.contains(query.trim(), true) }
    val typed = ComposioAccounts.normalizeSlug(query)?.takeIf { slug -> slug !in known && shown.isEmpty() }

    SearchField(query) { query = it }
    AuraSection(title = "Apps") {
        shown.forEachIndexed { i, app ->
            if (i > 0) AuraDivider(70.dp)
            AppRow(app, apps[app.slug], toggle, open)
        }
        if (typed != null) {
            AppRow(ComposioApp(typed, query.trim(), "Connect it by its Composio name", 0xFF6B7280), apps[typed], toggle, open)
        } else if (shown.isEmpty()) {
            AuraHint("No app by that name. Type its Composio name, such as \"googletasks\".")
        }
    }
}

@Composable
private fun AppRow(app: ComposioApp, state: AppState?, toggle: (String, Boolean) -> Unit, open: (String) -> Unit) {
    val aura = LocalAura.current
    val detail = when (state) {
        is AppState.Waiting -> if (state.again) "Composio asks you to sign in again. Tap to do it." else "Finish signing in, then come back. Tap to open it again."
        is AppState.Failed -> state.reason
        AppState.Connected -> "On"
        AppState.Working -> "Checking with Composio"
        null -> app.blurb
    }
    val detailColor = when (state) {
        is AppState.Failed -> aura.danger
        AppState.Connected -> aura.success
        else -> aura.secondary
    }
    val on = state == AppState.Connected || state is AppState.Waiting
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Switch) {
                when {
                    state is AppState.Waiting -> state.url?.let(open) ?: toggle(app.slug, true)
                    state != AppState.Working -> toggle(app.slug, !on)
                }
            }
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTile(app)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, style = AuraType.headline, color = aura.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = AuraType.footnote, color = detailColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        if (state == AppState.Working) {
            CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(20.dp), color = aura.accent, strokeWidth = 2.dp)
        } else {
            AuraSwitch(on, { toggle(app.slug, it) })
        }
    }
}

/**
 * The app's logo from Composio on a white tile; its first letter on its colour until the logo
 * loads or when there is none.
 */
@Composable
private fun AppTile(app: ComposioApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(context).data("$COMPOSIO_LOGO_URL${app.slug}").crossfade(true).build(),
        imageLoader = AppLogos.loader(context),
    )
    val loaded = painter.state is AsyncImagePainter.State.Success
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (loaded) Color.White else Color(app.color)),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) Text(app.name.take(1).uppercase(), style = AuraType.headline.copy(fontWeight = FontWeight.Bold), color = Color.White)
        Image(painter, contentDescription = null, modifier = Modifier.size(26.dp))
    }
}

/** One image loader for every logo, with the SVG decoder they need; cached on disk by Coil. */
private object AppLogos {
    @Volatile private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext).components { add(SvgDecoder.Factory()) }.build().also { loader = it }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val aura = LocalAura.current
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(AuraShape.Pill).background(aura.well).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = aura.tertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search apps", style = AuraType.body, color = aura.tertiary)
            BasicTextField(query, onChange, Modifier.fillMaxWidth(), textStyle = AuraType.body.copy(color = aura.label), cursorBrush = SolidColor(aura.label), singleLine = true)
        }
    }
}

/** Any other MCP server (GitHub's, a self-hosted one), behind a fold. */
@Composable
private fun OtherServers(servers: List<ConnectorConfig>, status: String?, vm: AgentPagesViewModel) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var keyHeader by rememberSaveable { mutableStateOf("") }
    AuraSection(title = "Other MCP servers", footer = status ?: "A server you add here lends AURA all its tools at once.") {
        servers.forEachIndexed { i, server ->
            if (i > 0) AuraDivider(16.dp)
            DeletableRow(server.displayName, "${server.approvedTools.size} tools", "Remove ${server.displayName}", { vm.removeConnector(server.id) })
        }
        if (servers.isNotEmpty()) AuraDivider(16.dp)
        if (!adding) {
            Text(
                "Add a server",
                Modifier.fillMaxWidth().clickable(role = Role.Button) { adding = true }.padding(16.dp),
                style = AuraType.body,
                color = LocalAura.current.accent,
            )
        } else {
            Field(name, { name = it }, "Name")
            AuraDivider(16.dp)
            Field(url, { url = it }, "MCP server address (https)")
            AuraDivider(16.dp)
            Field(token, { token = it }, "Access token or API key (optional)", secret = true)
            AuraDivider(16.dp)
            Field(keyHeader, { keyHeader = it }, "Key header, if not Authorization")
        }
    }
    if (adding) {
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuraButton("Use GitHub", {
                name = "GitHub"
                url = GITHUB_MCP_URL
                keyHeader = ""
            }, Modifier.weight(1f), primary = false)
            AuraButton("Connect", {
                vm.addConnector(name, url, token, keyHeader)
                token = ""
            }, Modifier.weight(1f), enabled = name.isNotBlank() && url.isNotBlank())
        }
    }
}

/** GitHub's hosted MCP server; it takes a personal access token as the bearer. */
private const val GITHUB_MCP_URL = "https://api.githubcopilot.com/mcp/"
