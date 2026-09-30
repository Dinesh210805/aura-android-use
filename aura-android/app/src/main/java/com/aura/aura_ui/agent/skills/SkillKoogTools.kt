package com.aura.aura_ui.agent.skills

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import android.content.Context
import com.aura.aura_ui.agent.llm.AgentTraceTap
import com.aura.aura_ui.agent.mcpbridge.client.CallToolResultKoogTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Koog-tool glue exposing skills to the agent via two read-only tools that reuse #2's
 * [CallToolResultKoogTool] base. Skills are prompt text only — these tools cannot grant capability,
 * so an action a skill induces still passes the hook chain + SensitivePolicy unchanged (V-5).
 */

@OptIn(InternalAgentsApi::class)
internal class ListSkillsKoogTool(private val proxy: SkillProxy) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "list_skills",
        description = "List available how-to skills for operating apps. Optional 'query' filters by " +
            "name/description. Call use_skill to read one before acting.",
        requiredParameters = emptyList(),
        optionalParameters = listOf(
            ToolParameterDescriptor("query", "Substring filter over skill name/description.", ToolParameterType.String),
        ),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult {
        val query = args.toKotlinxJsonObject()["query"]?.jsonPrimitive?.contentOrNull
        return CallToolResult(content = listOf(TextContent(proxy.listSkills(query))), isError = false)
    }
}

@OptIn(InternalAgentsApi::class)
internal class UseSkillKoogTool(private val proxy: SkillProxy) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "use_skill",
        description = "Load the full how-to body of ONE skill by name (from list_skills) into context.",
        requiredParameters = listOf(
            ToolParameterDescriptor("name", "The skill name to load.", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult {
        val name = args.toKotlinxJsonObject()["name"]?.jsonPrimitive?.contentOrNull
            ?: return CallToolResult(content = listOf(TextContent("missing 'name'")), isError = true)
        val result = proxy.useSkill(name)
        // Phase 2: Report skill load to the trace tap (only on success).
        if (result.isError != true && name.isNotBlank()) {
            val content = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            AgentTraceTap.reportSkillLoaded(name, if (content.isNotBlank()) content else null)
        }
        return result
    }
}

/** The result of attaching skills to one run: base registry + the two skill tools, and the listing. */
internal class SkillAttachment(val registry: ToolRegistry, val listing: String)

/**
 * Gated entry point called from AuraAgent. Returns null (today's exact path) when no skill exists.
 * Fail-closed: any error degrades to "no skills this run".
 */
@OptIn(InternalAgentsApi::class)
internal suspend fun attachSkillsIfAny(context: Context, baseRegistry: ToolRegistry): SkillAttachment? =
    runCatching {
        val repository = SkillRepository(
            listOf(BundledSkillSource.fromAssets(context), UserSkillStore(context)),
        )
        val manager = SkillManager(repository)
        val listing = manager.listingOrNull() ?: return null
        val proxy = manager.proxy()
        val skillRegistry = ToolRegistry {
            tool(ListSkillsKoogTool(proxy))
            tool(UseSkillKoogTool(proxy))
        }
        SkillAttachment(baseRegistry + skillRegistry, listing)
    }.getOrNull()
