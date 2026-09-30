package com.aura.mcp.tools

import com.aura.mcp.bridge.FileSearchResult
import com.aura.mcp.bridge.FilesBridge
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * File tools — deterministic file access instead of Files-app gesture safari.
 *
 *  - `find_files` (READ) — search the device media index by name/kind/recency.
 *  - `open_file` (WRITE) — fire a returned `content://media/…` URI in its
 *    default viewer. Screen changes → observed like any WRITE tool.
 */
internal fun Server.registerFileTools(bridge: FilesBridge) {
    registerFindFiles(bridge)
    registerOpenFile(bridge)
}

private const val DEFAULT_LIMIT = 10
private const val MAX_LIMIT = 25

private fun Server.registerFindFiles(bridge: FilesBridge) {
    scopedTool(
        name = "find_files",
        description = "Find files on the phone by name and kind, newest first — images, videos, audio, " +
            "documents, downloads. Returns content:// URIs for open_file. Files private to an app are " +
            "not visible. permission_denied: ask the user to allow photos and media access.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "query" to stringSchema(
                        "Display-name substring to match (e.g. 'resume'). Omit to list newest.",
                    ),
                    "kind" to stringSchema(
                        "Filter: image | video | audio | document | any (default any).",
                    ),
                    "limit" to numberSchema("Max results, default $DEFAULT_LIMIT, cap $MAX_LIMIT."),
                ),
            ),
            required = emptyList(),
        ),
    ) { request ->
        val args = request.arguments
        val query = args?.stringArg("query")?.trim()?.takeIf { it.isNotEmpty() }
        val kind = args?.stringArg("kind")?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (args?.intArg("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val r = bridge.findFiles(query, kind, limit)
        val payload = buildJsonObject {
            put("status", r.status)
            put("count", r.files.size)
            put(
                "files",
                buildJsonArray {
                    for (f in r.files) {
                        add(
                            buildJsonObject {
                                put("uri", f.uri)
                                put("display_name", f.displayName)
                                put("mime_type", f.mimeType)
                                put("size_bytes", f.sizeBytes)
                                put("modified_epoch_ms", f.modifiedEpochMs)
                            },
                        )
                    }
                },
            )
            if (r.note != null) put("note", r.note)
            put(
                "next_step",
                when (r.status) {
                    FileSearchResult.STATUS_PERMISSION_DENIED ->
                        "Tell the user AURA needs Photos & media permission " +
                            "(Settings > Apps > AURA > Permissions), then stop."
                    FileSearchResult.STATUS_INVALID_KIND ->
                        "Retry with kind = image, video, audio, document, or any."
                    else ->
                        if (r.files.isEmpty()) {
                            "No match. Broaden the query, or ask the user where the file is."
                        } else {
                            "Pass the chosen file's uri to open_file verbatim."
                        }
                },
            )
        }
        CallToolResult(
            content = listOf(TextContent(payload.toString())),
            isError = r.status == FileSearchResult.STATUS_PERMISSION_DENIED,
        )
    }
}

private fun Server.registerOpenFile(bridge: FilesBridge) {
    scopedTool(
        name = "open_file",
        description = "Open a file from find_files in its default app. Only URIs returned by find_files work.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "uri" to stringSchema("A content://media/… URI returned by find_files."),
                ),
            ),
            required = listOf("uri"),
        ),
    ) { request ->
        val uri = request.arguments?.stringArg("uri")?.trim()
        if (uri.isNullOrEmpty()) {
            return@scopedTool errorResult("open_file requires a non-empty 'uri'")
        }
        val r = bridge.openFile(uri)
        jsonOkPayload(
            buildJsonObject {
                put("success", r.success)
                put("resolved_package", r.resolvedPackage)
                put("error", r.error)
                if (r.success) {
                    put("next_step", "Screen changed — check the post_action_observation.")
                }
            },
            success = r.success,
        )
    }
}
