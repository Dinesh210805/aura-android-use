package com.aura.aura_ui.agentcore

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.aura.aura_ui.agent.mcpbridge.McpToolSchemaParser
import com.aura.aura_ui.data.deeplink.DeepLinkManager
import com.aura.aura_ui.mcp.bridge.AppBrowserBridge
import com.aura.aura_ui.mcp.bridge.AppContactsBridge
import com.aura.aura_ui.mcp.bridge.AppDeepLinkBridge
import com.aura.aura_ui.mcp.bridge.AppDeviceBridge
import com.aura.aura_ui.mcp.bridge.AppFilesBridge
import com.aura.aura_ui.mcp.bridge.AppMediaBridge
import com.aura.aura_ui.mcp.bridge.AppNotificationBridge
import com.aura.aura_ui.mcp.bridge.AppPerceptionBridge
import com.aura.aura_ui.mcp.bridge.AppScreenshotBridge
import com.aura.aura_ui.mcp.bridge.AppSystemIntentBridge
import com.aura.aura_ui.mcp.bridge.AppUiTreeBridge
import com.aura.aura_ui.mcp.bridge.AppWebSearchBridge
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.mcp.bridge.TavilyKeyStore
import com.aura.aura_ui.services.ControlLockStore
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.server.ActionGuard
import com.aura.mcp.server.InProcessMcpHandle
import com.aura.mcp.server.InProcessMcpServer
import com.aura.mcp.server.McpToolScopes
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Where tools come from: the phone (the in-process MCP server in `:mcp-server`) and connectors
 * (remote MCP servers the user added, such as GitHub).
 *
 * - Contract: a source only lists and calls. Classifying risk is this file's job; deciding what
 *   to do about the risk is [SafetyGate]'s.
 * - Change together: a new phone tool needs a line in [PhoneToolSource] only when it belongs in
 *   [ToolHub.CORE] or [ToolHub.SCREEN], commits the user, or sends data out. Otherwise it lands in
 *   the `phone` toolset with its `McpToolScopes` risk and needs nothing here.
 * - Why `McpToolSchemaParser` is imported from `agent/`: it is the one piece of the current agent
 *   this package borrows. Move it here when `agent/` is deleted.
 */
class PhoneToolSource(context: Context) : ToolSource {

    override val id: String = "phone"

    private val app = context.applicationContext
    private val guard = ActionGuard()
    private val connecting = Mutex()
    private var handle: InProcessMcpHandle? = null
    private var client: Client? = null

    @Volatile
    private var labelOf: (Int) -> String? = { null }

    /**
     * The visible label of the element a gesture is about to press, from the latest screen read.
     * - Contract: null when the call names no `som_id` or the element has no label. Give this
     *   to `ToolHub` as `targetLabel`; it is how a tap on "Send" or "Pay" becomes a commit.
     */
    fun targetLabel(tool: String, args: JsonObject): String? =
        (args["som_id"] as? JsonPrimitive)?.intOrNull?.takeIf { tool in PRESS_TOOLS }?.let(labelOf)

    override suspend fun list(): List<ToolSpec> =
        connected().listTools()?.tools.orEmpty().filterNot { it.name in HIDDEN }.map { tool ->
            ToolSpec(
                descriptor = McpToolSchemaParser.parse(tool),
                risk = riskOf(tool.name),
                origin = ToolOrigin.PHONE,
                toolset = toolsetOf(tool.name),
                resultOrigin = resultOriginOf(tool.name),
                egress = tool.name in EGRESS_TOOLS || tool.name.startsWith("browser_"),
            )
        }

    override suspend fun call(name: String, args: JsonObject, turn: TurnState): SourceResult {
        val result = connected().callTool(name = name, arguments = args)
        val content = result?.content.orEmpty()
        return SourceResult(
            text = content.filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() },
            isError = result?.isError == true,
            imagePng = content.filterIsInstance<ImageContent>().firstOrNull()
                ?.let { runCatching { Base64.decode(it.data, Base64.DEFAULT) }.getOrNull() },
        )
    }

    /** Forget the last task's screen state. Call at the start of every turn. */
    suspend fun startTask() = guard.reset()

    override suspend fun close() {
        runCatching { client?.close() }
        runCatching { handle?.close() }
        client = null
        handle = null
    }

    /**
     * Connect to a dedicated in-process server on first use.
     * - Reads/Writes: the same app bridges the LAN server uses; nothing is paired or exposed.
     * - Fails: throws when the server cannot be built, which [ToolHub.refresh] turns into "no phone
     *   tools this turn". Once connected, tools fail one by one with the server's own
     *   `resource_required` errors (accessibility off, capture not granted).
     */
    private suspend fun connected(): Client = connecting.withLock {
        client ?: run {
            val server = InProcessMcpServer.connect(
                deviceBridge = AppDeviceBridge(app),
                screenshotBridge = AppScreenshotBridge(app),
                uiTreeBridge = AppUiTreeBridge(),
                perceptionBridge = AppPerceptionBridge(app),
                webSearchBridge = AppWebSearchBridge(TavilyKeyStore(app)),
                deepLinkBridge = AppDeepLinkBridge(app, DeepLinkManager(app)),
                notificationBridge = AppNotificationBridge(app),
                mediaBridge = AppMediaBridge(app),
                systemIntentBridge = AppSystemIntentBridge(app),
                contactsBridge = AppContactsBridge(app),
                filesBridge = AppFilesBridge(app),
                browserBridge = AppBrowserBridge.shared(app),
                ownPackageName = app.packageName,
                pausedByHuman = { ControlLockStore.shared.isPausedByHuman },
                actionGuard = guard,
                labelLookup = { labelOf = it },
            )
            Client(clientInfo = Implementation(name = "aura-agentcore", version = "1")).also {
                it.connect(server.clientTransport)
                handle = server
                client = it
            }
        }
    }

    companion object {
        /** Gestures that press the element they name. A swipe or scroll across one does not. */
        private val PRESS_TOOLS = setOf("tap", "long_press", "double_tap", "press_enter")

        /** Not offered to the model: a chat turn ends by replying, and `run_steps` is off. */
        private val HIDDEN = setOf("end_session", "run_steps")

        /** Direct verbs a chat needs without loading anything. */
        private val CORE_TOOLS = setOf(
            "system_intent", "web_search", "launch_app", "media_control", "read_notifications", "get_device_status",
        )

        private val SCREEN_TOOLS = setOf(
            "read_screen", "perceive_screen", "get_screenshot", "request_screen_capture_permission",
            "tap", "type_text", "set_value", "scroll", "swipe", "press_key", "press_enter",
            "wait_for", "verify_action", "watch_device_events",
        )

        /** Take a URL, an intent or outgoing text, so their arguments can leave the phone. */
        private val EGRESS_TOOLS = setOf(
            "web_search", "open_deeplink", "resolve_deeplink", "system_intent", "open_file", "notification_action",
        )

        /** Acts on the world the moment it is called, with no screen the user confirms on. */
        private val COMMIT_TOOLS = setOf("notification_action")

        /** Results the harness or the OS wrote; everything else from the phone is private content. */
        private val TRUSTED_RESULTS = setOf("get_device_status", "volume", "request_screen_capture_permission")

        internal fun riskOf(tool: String): ToolRisk = when {
            tool in COMMIT_TOOLS -> ToolRisk.COMMIT
            McpToolScopes.toolScopeMap[tool] == McpScope.READ -> ToolRisk.READ
            else -> ToolRisk.ACT
        }

        internal fun toolsetOf(tool: String): String = when {
            tool in CORE_TOOLS -> ToolHub.CORE
            tool in SCREEN_TOOLS -> ToolHub.SCREEN
            tool.startsWith("browser_") -> "browser"
            else -> "phone"
        }

        internal fun resultOriginOf(tool: String): Origin? = when {
            tool in TRUSTED_RESULTS -> null
            tool == "web_search" || tool.startsWith("browser_") -> Origin.WEB
            tool.contains("notification") || tool.contains("media") -> Origin.NOTIFICATION
            tool.contains("file") || tool.contains("contact") || tool == "clipboard" -> Origin.FILE
            else -> Origin.SCREEN
        }
    }
}

/** One remote MCP server the user connected. The token itself lives in the secret store. */
@Serializable
data class ConnectorConfig(
    /** Short stable id; becomes the toolset name and the tool-name prefix. */
    val id: String,
    val displayName: String,
    /** Streamable HTTP endpoint. Must be https. */
    val url: String,
    /** Tool name to the [ConnectorPins.fingerprint] the user approved. */
    val approvedTools: Map<String, String> = emptyMap(),
)

/**
 * A remote MCP server as a tool source.
 *
 * - Contract: only tools whose current definition matches what the user approved are listed
 *   ([ConnectorPins]); the rest stay hidden until re-approved in Settings. Tool names are
 *   prefixed with the connector id, so two connectors cannot collide.
 * - Fails: [list] throws when the server is unreachable or rejects the token; `ToolHub.refresh`
 *   treats that as no tools and tries again next turn.
 * - Reads/Writes: network host [ConnectorConfig.url] only.
 */
class ConnectorToolSource(
    private val config: ConnectorConfig,
    private val token: () -> String?,
) : ToolSource {

    override val id: String = config.id

    private val http by lazy { HttpClient(OkHttp) }
    private var client: Client? = null
    private var specs: List<ToolSpec>? = null

    /** Every tool the server lists now, name to fingerprint. Throws when it cannot be reached. */
    suspend fun fingerprints(): Map<String, String> = connected().listTools()?.tools.orEmpty()
        .associate { it.name to ConnectorPins.fingerprint(it.name, it.description.orEmpty(), it.inputSchema.toString()) }

    /**
     * - Contract: asks the server once per process and keeps the answer, so a slow or dead
     *   connector costs one turn, not every turn.
     */
    override suspend fun list(): List<ToolSpec> = specs ?: fetch().also { specs = it }

    private suspend fun fetch(): List<ToolSpec> {
        require(config.url.startsWith("https://")) { "Connector ${config.id} must use https." }
        val tools = connected().listTools()?.tools.orEmpty()
        val current = tools.associate { it.name to ConnectorPins.fingerprint(it.name, it.description.orEmpty(), it.inputSchema.toString()) }
        val approved = ConnectorPins.approved(current, config.approvedTools)
        return tools.filter { it.name in approved }.map { tool ->
            val parsed = McpToolSchemaParser.parse(tool)
            ToolSpec(
                descriptor = ai.koog.agents.core.tools.ToolDescriptor(
                    name = "${config.id}__${tool.name}",
                    description = ContextAssembler.clamp(parsed.description, MAX_DESCRIPTION_CHARS),
                    requiredParameters = parsed.requiredParameters,
                    optionalParameters = parsed.optionalParameters,
                ),
                risk = if (ConnectorPins.looksReadOnly(tool.name)) ToolRisk.READ else ToolRisk.COMMIT,
                origin = ToolOrigin.CONNECTOR,
                toolset = config.id,
                resultOrigin = Origin.CONNECTOR,
                egress = true,
            )
        }
    }

    override suspend fun call(name: String, args: JsonObject, turn: TurnState): SourceResult {
        val result = connected().callTool(name = name.removePrefix("${config.id}__"), arguments = args)
        return SourceResult(
            text = result?.content.orEmpty().filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() },
            isError = result?.isError == true,
        )
    }

    private suspend fun connected(): Client = client ?: run {
        val bearer = token()
        val transport = http.mcpStreamableHttpTransport(config.url, requestBuilder = {
            if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
        })
        Client(clientInfo = Implementation(name = "aura-agentcore", version = "1")).also {
            it.connect(transport)
            client = it
        }
    }

    override suspend fun close() {
        runCatching { client?.close() }
        runCatching { http.close() }
    }

    private companion object {
        /** A connector's description is third-party text in the prompt; keep it short. */
        const val MAX_DESCRIPTION_CHARS = 600
    }
}

/**
 * Pins a connector's tool definitions to what the user approved.
 *
 * - Why: a server can change a tool's description after approval and so put new instructions in
 *   front of the model. A changed fingerprint hides the tool until the user looks again.
 * - Contract: pure.
 */
object ConnectorPins {
    private val READ_PREFIXES = listOf("get_", "list_", "search_", "read_", "find_", "fetch_", "describe_")

    fun fingerprint(name: String, description: String, schema: String): String =
        MessageDigest.getInstance("SHA-256").digest("$name\n$description\n$schema".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Names in [current] whose fingerprint equals the approved one. */
    fun approved(current: Map<String, String>, approved: Map<String, String>): Set<String> =
        current.filter { (name, print) -> approved[name] == print }.keys

    /** Names that are new or changed since approval, for the Settings screen to show. */
    fun needsReview(current: Map<String, String>, approved: Map<String, String>): Set<String> =
        current.keys - approved(current, approved)

    /**
     * ponytail: a name heuristic. A server that lies about a tool's name gets a write past the
     * confirmation prompt, though not past the egress and secret checks. Upgrade path: read MCP
     * tool annotations once the MCP SDK in `:mcp-server` is upgraded past 0.8.3, and let the user
     * mark tools read-only per connector.
     */
    fun looksReadOnly(tool: String): Boolean = READ_PREFIXES.any { tool.startsWith(it) }
}

/**
 * The connectors the user added, and the live tool sources built from them.
 *
 * - Contract: [add] connects once and pins every tool the server lists at that moment; that is
 *   the user's approval. A tool that is new or changed afterwards stays hidden until the
 *   connector is added again.
 * - Reads/Writes: key [KEY] in the prefs file passed in (a JSON list of [ConnectorConfig]);
 *   tokens in `ProviderKeyStore` under `connector_<id>`, never in the JSON.
 * - Fails: [add] returns the reason as a failure and saves nothing.
 */
class ConnectorStore(private val prefs: SharedPreferences, private val keys: ProviderKeyStore) {
    private val serializer = ListSerializer(ConnectorConfig.serializer())

    @Volatile
    private var live: List<ConnectorToolSource> = configs().map(::source)

    fun configs(): List<ConnectorConfig> =
        runCatching { Json.decodeFromString(serializer, prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())

    fun sources(): List<ToolSource> = live

    /** Returns how many tools were approved. */
    suspend fun add(name: String, url: String, token: String?): Result<Int> = try {
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(MAX_ID)
        require(id.isNotEmpty() && id !in RESERVED) { "Pick a different name." }
        require(url.startsWith("https://")) { "The address must start with https://" }
        val probe = ConnectorToolSource(ConnectorConfig(id, name.trim(), url.trim())) { token }
        val pins = try {
            probe.fingerprints()
        } finally {
            probe.close()
        }
        require(pins.isNotEmpty()) { "The server listed no tools. Check the address and the token." }
        if (token.isNullOrBlank()) keys.clearKey(keyFor(id)) else keys.setKey(keyFor(id), token)
        save(configs().filterNot { it.id == id } + ConnectorConfig(id, name.trim(), url.trim(), pins))
        Result.success(pins.size)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun remove(id: String) {
        keys.clearKey(keyFor(id))
        save(configs().filterNot { it.id == id })
    }

    private suspend fun save(configs: List<ConnectorConfig>) {
        prefs.edit().putString(KEY, Json.encodeToString(serializer, configs)).apply()
        val old = live
        live = configs.map(::source)
        old.forEach { it.close() }
    }

    private fun source(config: ConnectorConfig) = ConnectorToolSource(config) { keys.getKey(keyFor(config.id)) }

    private fun keyFor(id: String) = "connector_$id"

    private companion object {
        const val KEY = "connectors"
        const val MAX_ID = 20

        /** Toolset and source names a connector must not take. */
        val RESERVED = setOf(ToolHub.CORE, ToolHub.SCREEN, "phone", "browser", "native", "control")
    }
}
