package com.aura.mcp.tools

import com.aura.mcp.bridge.DeepLinkBridge
import com.aura.mcp.bridge.DeepLinkCatalog
import com.aura.mcp.bridge.DeepLinkEntry
import com.aura.mcp.bridge.OpenUriResult
import com.aura.mcp.bridge.UriResolution
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LaunchDirectLinksTest {

    private fun entry(label: String, source: String, confirm: Boolean = false, template: String? = null) =
        DeepLinkEntry(
            exampleUri = "app://$label",
            uriTemplate = template,
            scheme = "app",
            host = null,
            label = label,
            source = source,
            requiresConfirmation = confirm,
        )

    private class FakeBridge(private val list: suspend () -> DeepLinkCatalog) : DeepLinkBridge {
        override suspend fun listDeepLinks(packageName: String): DeepLinkCatalog = list()
        override fun resolveUri(uri: String): UriResolution = error("unused")
        override fun openUri(uri: String, packageName: String?): OpenUriResult = error("unused")
    }

    private val launched = CallToolResult(content = listOf(TextContent("""{"success":true}""")), isError = false)

    @Test
    fun `only vouched-for links that need no confirmation are offered`() {
        val picked = LaunchDirectLinks.pick(
            DeepLinkCatalog(
                "pkg", "App",
                listOf(
                    entry("Open host", "verified"),
                    entry("New chat", "shortcut"),
                    entry("Send money", "shortcut", confirm = true),
                    entry("Search", "app_action"),
                    entry("Directions", "resolved"),
                    entry("Unprobed", "catalog"),
                    entry("Guessed", "discovered"),
                ),
            ),
        )
        assertEquals(listOf("New chat", "Search", "Directions"), picked.map { it.label })
    }

    @Test
    fun `links are appended as a separate block and templates win over examples`() = runBlocking<Unit> {
        val bridge = FakeBridge {
            DeepLinkCatalog("pkg", "App", listOf(entry("Search", "app_action", template = "app://search/{q}")))
        }
        val out = LaunchDirectLinks.appendTo(launched, bridge, "pkg")

        assertEquals(2, out.content.size, "original reply must be kept intact")
        val block = Json.parseToJsonElement((out.content[1] as TextContent).text.orEmpty()).jsonObject
        val first = block.getValue("direct_links").jsonArray.single().jsonObject
        assertEquals("app://search/{q}", first.getValue("uri").jsonPrimitive.content)
    }

    @Test
    fun `a slow lookup never delays the launch reply`() = runBlocking<Unit> {
        val bridge = FakeBridge {
            delay(LaunchDirectLinks.TIMEOUT_MS * 10)
            DeepLinkCatalog("pkg", "App", listOf(entry("Late", "shortcut")))
        }
        assertSame(launched, LaunchDirectLinks.appendTo(launched, bridge, "pkg"))
    }

    @Test
    fun `a failing lookup leaves the reply unchanged`() = runBlocking<Unit> {
        val bridge = FakeBridge { error("package manager died") }
        assertSame(launched, LaunchDirectLinks.appendTo(launched, bridge, "pkg"))
    }
}
