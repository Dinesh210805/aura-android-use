package com.aura.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Proof-of-life tool. Returns the supplied text verbatim.
 *
 * Phase 1 deliverable: verifies the Ktor + MCP SDK stack actually runs on Android
 * before we invest in porting the real 31 tools.
 */
internal fun Server.registerEchoTool() {
    scopedTool(
        name = "echo",
        description = "Returns the supplied text verbatim. Used to verify the MCP transport is alive.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "text" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Text to echo back to the caller."),
                        )
                    ),
                )
            ),
            required = listOf("text"),
        ),
    ) { request ->
        val text = request.arguments?.get("text")?.jsonPrimitive?.content.orEmpty()
        CallToolResult(content = listOf(TextContent("echo: $text")))
    }
}
