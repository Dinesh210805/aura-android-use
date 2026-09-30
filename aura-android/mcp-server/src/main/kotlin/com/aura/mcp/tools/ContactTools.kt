package com.aura.mcp.tools

import com.aura.mcp.bridge.ContactResolution
import com.aura.mcp.bridge.ContactsBridge
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
 * `resolve_contact` — turn a spoken/typed name into a phone number, on-device.
 *
 * This closes the loop that `system_intent` opens: dial/SMS demand a real
 * number and tell the agent to "resolve contact names to a number first" —
 * this is the tool that does it. READ scope: pure inspection, no device
 * mutation. The name arg and the resulting number are PII — the tool is
 * read-only in PathStepSanitizer so neither ever enters learnings.
 */
internal fun Server.registerContactTools(bridge: ContactsBridge) {
    scopedTool(
        name = "resolve_contact",
        description = "Find a contact's phone number by name, on the phone. Handles misspellings and partial " +
            "names. status: auto — one match, use it; disambiguate — 2 to 4 candidates, ask the user " +
            "which; none — ask the user for the number; permission_denied — ask the user to allow " +
            "Contacts access. Use numbers only as tool arguments; never say or save them.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "name" to stringSchema(
                        "The contact name as the user said it (e.g. 'Mom', 'Dinesh').",
                    ),
                ),
            ),
            required = listOf("name"),
        ),
    ) { request ->
        val name = request.arguments?.stringArg("name")?.trim()
        if (name.isNullOrEmpty()) {
            return@scopedTool errorResult("resolve_contact requires a non-empty 'name'")
        }

        val r = bridge.resolveContact(name)
        val payload = buildJsonObject {
            put("status", r.status)
            put(
                "candidates",
                buildJsonArray {
                    for (c in r.candidates) {
                        add(
                            buildJsonObject {
                                put("contact_id", c.contactId)
                                put("display_name", c.displayName)
                                put("phone_number", c.phoneNumber)
                                put("score", c.score)
                            },
                        )
                    }
                },
            )
            put(
                "next_step",
                when (r.status) {
                    ContactResolution.STATUS_AUTO ->
                        "Use candidates[0].phone_number directly in system_intent " +
                            "or a deep-link template. Do not read it aloud."
                    ContactResolution.STATUS_DISAMBIGUATE ->
                        "Ask the user which of these contacts they meant (say the " +
                            "names only, never the numbers), then use that " +
                            "candidate's phone_number."
                    ContactResolution.STATUS_PERMISSION_DENIED ->
                        "Tell the user AURA needs Contacts permission (Settings > " +
                            "Apps > AURA > Permissions > Contacts), then stop."
                    else ->
                        "No contact matched. Ask the user for the number or the " +
                            "exact name, then retry."
                },
            )
        }
        CallToolResult(
            content = listOf(TextContent(payload.toString())),
            isError = r.status == ContactResolution.STATUS_PERMISSION_DENIED,
        )
    }
}
