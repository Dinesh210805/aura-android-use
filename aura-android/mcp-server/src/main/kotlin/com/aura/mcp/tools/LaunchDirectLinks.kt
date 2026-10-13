package com.aura.mcp.tools

import com.aura.mcp.bridge.DeepLinkBridge
import com.aura.mcp.bridge.DeepLinkCatalog
import com.aura.mcp.bridge.DeepLinkEntry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Adds the launched app's direct links to a `launch_app` reply, so an agent learns about one-call
 * deep links at the moment it enters the app instead of tapping through it.
 *
 * - Why here: agents rarely call `list_app_deeplinks` on their own; every agent reads the reply
 *   of the call it just made.
 * - Contract: appends at most one `{"direct_links":[...]}` text block; the original content is
 *   untouched. Only links the app or AURA vouches for and that resolve on this phone, never one
 *   flagged `requires_confirmation` (those can send, pay or delete).
 * - Fails: open. A slow (> [TIMEOUT_MS]) or failing lookup returns the reply unchanged; a launch
 *   is never delayed or broken by this.
 */
internal object LaunchDirectLinks {

    const val MAX_LINKS = 5
    const val TIMEOUT_MS = 400L

    /** `shortcut`/`app_action` are the app's own entry points; `resolved` is a catalog link probed on this phone. */
    private val TRUSTED_SOURCES = setOf("shortcut", "app_action", "resolved")

    fun pick(catalog: DeepLinkCatalog): List<DeepLinkEntry> =
        catalog.entries
            .filter { it.source in TRUSTED_SOURCES && !it.requiresConfirmation }
            .take(MAX_LINKS)

    suspend fun appendTo(result: CallToolResult, bridge: DeepLinkBridge, packageName: String): CallToolResult {
        val links = withTimeoutOrNull(TIMEOUT_MS) { lookup(bridge, packageName) }?.let(::pick).orEmpty()
        if (links.isEmpty()) return result
        val block = buildJsonObject {
            putJsonArray("direct_links") {
                links.forEach { link ->
                    add(
                        buildJsonObject {
                            put("label", link.label)
                            put("uri", link.uriTemplate ?: link.exampleUri)
                        },
                    )
                }
            }
            put(
                "hint",
                "To reach one of these screens, call open_deeplink(uri, package_name) instead of " +
                    "tapping through the app. Fill any {slot} first.",
            )
        }
        return CallToolResult(content = result.content + TextContent(block.toString()), isError = result.isError)
    }

    private suspend fun lookup(bridge: DeepLinkBridge, packageName: String): DeepLinkCatalog? =
        try {
            bridge.listDeepLinks(packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}
