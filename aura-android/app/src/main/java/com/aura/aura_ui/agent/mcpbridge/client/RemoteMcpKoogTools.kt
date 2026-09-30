package com.aura.aura_ui.agent.mcpbridge.client

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKoogJSONElement
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.serialization.typeToken
import android.content.Context
import com.aura.aura_ui.agent.mcpbridge.InProcessMcpToolSource
import com.aura.aura_ui.agent.mcpbridge.capToolResultText
import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.ToolHookChain
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Koog-tool glue that exposes connected remote MCP servers to the agent through exactly two
 * tools — `list_mcp_tools` and `use_mcp_tool` — both routed through [McpProxyTools] (whose
 * gating/parse logic is unit-tested). This is thin, mechanical adaptation of [McpProxyTools] to
 * Koog's [Tool] interface, mirroring `McpTool`'s serialization round-trip; the load-bearing
 * behaviour lives in the unit-tested classes, and this file is compile-/device-verified.
 */

/**
 * T1: remote tool results are UNTRUSTED input — cap what reaches the model at the same
 * budget the trusted in-process path enforces ([com.aura.aura_ui.agent.mcpbridge.ToolMeta]).
 * An uncapped remote result is context blowout, token-budget burn, and maximal
 * prompt-injection real estate.
 */
internal const val REMOTE_RESULT_MAX_CHARS = 30_000

/**
 * Pure text rendering of a remote [CallToolResult] for the LLM: joins text content,
 * elides everything else, prefixes errors, and enforces [REMOTE_RESULT_MAX_CHARS].
 * Unit-tested in `RemoteResultEncodingTest`.
 */
internal fun encodeRemoteResultText(result: CallToolResult?): String {
    val text = result?.content?.filterIsInstance<TextContent>()?.joinToString("\n") { it.text }.orEmpty()
    val capped = capToolResultText(text, REMOTE_RESULT_MAX_CHARS)
    return if (result?.isError == true) "Error: ${capped.ifBlank { "tool call blocked or failed" }}"
    else capped.ifBlank { "(no content)" }
}

/** Shared serialization round-trip for proxy tools that yield a [CallToolResult]. */
@OptIn(InternalAgentsApi::class)
internal abstract class CallToolResultKoogTool(descriptor: ToolDescriptor) : Tool<JSONObject, CallToolResult?>(
    argsType = typeToken<JSONObject>(),
    resultType = typeToken<CallToolResult?>(),
    descriptor = descriptor,
    metadata = emptyMap(),
) {
    protected val json: Json = Json.Default
    private val resultSerializer = CallToolResult.serializer().nullable

    override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): CallToolResult? =
        json.decodeFromJsonElement(resultSerializer, rawResult.toKotlinxJsonElement())

    override fun encodeResult(result: CallToolResult?, serializer: JSONSerializer): JSONElement =
        json.encodeToJsonElement(resultSerializer, result).toKoogJSONElement()

    override fun encodeResultToString(result: CallToolResult?, serializer: JSONSerializer): String =
        encodeRemoteResultText(result)
}

@OptIn(InternalAgentsApi::class)
internal class ListMcpToolsKoogTool(private val proxy: McpProxyTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "list_mcp_tools",
        description = "List tools available on connected third-party MCP servers. " +
            "Optional 'query' filters by name or description. Call this before use_mcp_tool.",
        requiredParameters = emptyList(),
        optionalParameters = listOf(
            ToolParameterDescriptor("query", "Substring filter over tool name/description.", ToolParameterType.String),
        ),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult {
        val query = args.toKotlinxJsonObject()["query"]?.jsonPrimitive?.contentOrNull
        return CallToolResult(content = listOf(TextContent(proxy.listMcpTools(query))), isError = false)
    }
}

@OptIn(InternalAgentsApi::class)
internal class UseMcpToolKoogTool(private val proxy: McpProxyTools) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "use_mcp_tool",
        description = "Invoke a tool on a connected third-party MCP server. Provide the server id and " +
            "tool name (from list_mcp_tools) plus 'args_json': a JSON object of the tool's arguments, as a string.",
        requiredParameters = listOf(
            ToolParameterDescriptor("server", "The MCP server id, as shown by list_mcp_tools.", ToolParameterType.String),
            ToolParameterDescriptor("tool", "The tool name to invoke.", ToolParameterType.String),
            ToolParameterDescriptor("args_json", "The tool's arguments as a JSON object string, e.g. {\"title\":\"x\"}.", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult {
        val obj = args.toKotlinxJsonObject()
        val server = obj["server"]?.jsonPrimitive?.contentOrNull ?: return err("missing 'server'")
        val tool = obj["tool"]?.jsonPrimitive?.contentOrNull ?: return err("missing 'tool'")
        val argsJson = obj["args_json"]?.jsonPrimitive?.contentOrNull ?: "{}"
        return proxy.useMcpTool(server, tool, argsJson)
    }

    private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)
}

/**
 * The result of attaching enabled remote MCP servers to one agent run: the [registry] (base
 * device tools + the two proxy tools) and the advisory [instructions] tail. [close] releases the
 * HTTP client. Returned only when ≥1 enabled server actually reached Connected.
 */
internal class RemoteMcpAttachment(
    val registry: ToolRegistry,
    val instructions: String,
    private val closer: () -> Unit,
) {
    fun close() {
        runCatching { closer() }
    }
}

/**
 * Gated entry point called from AuraAgent. Returns null (preserving today's exact path) when no
 * server is enabled or none reaches Connected. Fail-closed: any error degrades to "no remote
 * tools this run". Shares the run's ONE [chain] so remote calls obey the same loop/perceive
 * invariants as in-process gestures AND reach the same post-hooks (learnings, budgets) — a
 * separately-constructed chain here silently dropped `extraPostHooks` (T5).
 */
@OptIn(InternalAgentsApi::class)
internal suspend fun attachRemoteMcpIfEnabled(
    context: Context,
    inProcessClient: Client,
    baseRegistry: ToolRegistry,
    chain: ToolHookChain,
): RemoteMcpAttachment? {
    val store = McpServerStore(context)
    if (store.list().none { it.enabled }) return null // no enabled remote → unchanged path

    return runCatching {
        val secretStore = McpSecretStore(context)
        val httpClient = HttpClient(OkHttp)
        val connector = KtorMcpConnector(McpClientTransportFactory(httpClient), secretStore)
        val manager = RemoteMcpManager.forApp(store, secretStore, connector)
        val connections = manager.connectEnabled()
        val inProcess = InProcessMcpToolSource(inProcessClient)
        val composite = manager.connectedSources(connections, inProcess)
        if (composite == null) {
            httpClient.close()
            return null // nothing connected → unchanged path
        }
        val ctx = HookContext(confirm = { true })
        val proxy = McpProxyTools(composite, chain, ctx)
        val proxyRegistry = ToolRegistry {
            tool(ListMcpToolsKoogTool(proxy))
            tool(UseMcpToolKoogTool(proxy))
        }
        RemoteMcpAttachment(
            registry = baseRegistry + proxyRegistry,
            instructions = manager.connectedInstructions(connections).orEmpty(),
            closer = { httpClient.close() },
        )
    }.getOrNull()
}
