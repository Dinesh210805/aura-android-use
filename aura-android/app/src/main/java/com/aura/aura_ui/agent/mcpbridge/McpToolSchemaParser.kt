package com.aura.aura_ui.agent.mcpbridge

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.modelcontextprotocol.kotlin.sdk.types.Tool as SdkTool

/**
 * Converts an MCP SDK (0.8.3) [SdkTool] definition into a Koog [ToolDescriptor].
 *
 * This is a focused re-implementation of Koog's `DefaultMcpToolDescriptorParser`,
 * tailored to MCP SDK **0.8.3** (whose `ToolSchema` exposes flat
 * `properties` + `required`, with no `$defs`/`EmptyJsonObject` that the newer
 * SDK — and Koog's own JVM-only agents-mcp — depend on). It covers the JSON-Schema
 * vocabulary our 36 server tools actually use: string, integer, number, boolean,
 * array, object, and enum. Unknown types fall back to String so a tool is never
 * dropped from the registry.
 */
internal object McpToolSchemaParser {

    fun parse(sdkTool: SdkTool): ToolDescriptor {
        val schema = sdkTool.inputSchema
        val properties: JsonObject = schema.properties ?: JsonObject(emptyMap())
        val required: List<String> = schema.required ?: emptyList()

        val params = properties.mapNotNull { (name, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ToolParameterDescriptor(
                name = name,
                description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
                type = parseType(obj),
            )
        }

        return ToolDescriptor(
            name = sdkTool.name,
            description = sdkTool.description.orEmpty(),
            requiredParameters = params.filter { it.name in required },
            optionalParameters = params.filter { it.name !in required },
        )
    }

    private fun parseType(element: JsonObject): ToolParameterType {
        // Enum (regardless of declared type)
        element["enum"]?.jsonArray?.let { enum ->
            if (enum.isNotEmpty()) {
                return ToolParameterType.Enum(
                    enum.map { it.jsonPrimitive.content }.toTypedArray(),
                )
            }
        }

        return when (element["type"]?.jsonPrimitive?.content?.lowercase()) {
            "string" -> ToolParameterType.String
            "integer" -> ToolParameterType.Integer
            "number" -> ToolParameterType.Float
            "boolean" -> ToolParameterType.Boolean
            "null" -> ToolParameterType.Null
            "array" -> {
                val items = element["items"]?.jsonObject
                if (items != null) {
                    ToolParameterType.List(itemsType = parseType(items))
                } else {
                    ToolParameterType.List(itemsType = ToolParameterType.String)
                }
            }
            "object" -> {
                val props = element["properties"]?.jsonObject
                val nested = props?.mapNotNull { (n, p) ->
                    val pObj = p as? JsonObject ?: return@mapNotNull null
                    ToolParameterDescriptor(
                        name = n,
                        description = pObj["description"]?.jsonPrimitive?.content.orEmpty(),
                        type = parseType(pObj),
                    )
                } ?: emptyList()
                val req = element["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                ToolParameterType.Object(
                    properties = nested,
                    requiredProperties = req,
                )
            }
            // Unknown / missing type → safest default so the tool stays usable.
            else -> ToolParameterType.String
        }
    }
}
