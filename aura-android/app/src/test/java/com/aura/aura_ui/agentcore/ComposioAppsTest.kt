package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ComposioAppsTest {

    @Test
    fun `an active toolkit under data results is Active`() {
        val reply = """{"successful":true,"data":{"message":"ok","results":{"gmail":{"status":"ACTIVE","connected_account_id":"ca_1"}}}}"""
        assertEquals(ComposioLink.Active, ComposioAccounts.parse(reply, "gmail"))
    }

    @Test
    fun `a toolkit with a redirect url is SignIn with that url`() {
        val reply = """{"successful":true,"data":{"results":{"slack":{"status":"initiated","redirect_url":"https://connect.composio.dev/link/ln_abc"}}}}"""
        assertEquals(ComposioLink.SignIn("https://connect.composio.dev/link/ln_abc"), ComposioAccounts.parse(reply, "slack"))
    }

    @Test
    fun `results as a list are matched by the toolkit name`() {
        val reply = """{"data":{"results":[
            {"toolkit":"gmail","status":"active"},
            {"toolkit":"notion","status":"initiated","auth_link":"https://connect.composio.dev/link/ln_n"}]}}"""
        assertEquals(ComposioLink.SignIn("https://connect.composio.dev/link/ln_n"), ComposioAccounts.parse(reply, "notion"))
        assertEquals(ComposioLink.Active, ComposioAccounts.parse(reply, "gmail"))
    }

    @Test
    fun `plain text with a link is SignIn`() {
        val link = ComposioAccounts.parse("Sign in here: https://connect.composio.dev/link/ln_x.", "github")
        assertTrue(link is ComposioLink.SignIn && link.url.startsWith("https://connect.composio.dev/link/ln_x"))
    }

    @Test
    fun `an unsuccessful reply is Failed with its error`() {
        assertEquals(ComposioLink.Failed("bad toolkit"), ComposioAccounts.parse("""{"successful":false,"error":"bad toolkit"}""", "nope"))
    }

    @Test
    fun `a typed name becomes a slug and junk does not`() {
        assertEquals("googlesheets", ComposioAccounts.normalizeSlug("Google Sheets"))
        assertEquals("google_tasks", ComposioAccounts.normalizeSlug("google_tasks"))
        assertNull(ComposioAccounts.normalizeSlug("g"))
        assertNull(ComposioAccounts.normalizeSlug("x; drop"))
    }

    @Test
    fun `manage args name one toolkit and never force a reconnect`() {
        assertEquals("""{"toolkits":["gmail"],"reinitiate_all":false}""", ComposioAccounts.manageArgs("gmail").toString())
    }

    @Test
    fun `apps in a call are the toolkits of a connection call and the app part of each slug`() {
        val manage = Json.parseToJsonElement("""{"toolkits":["Gmail","slack"]}""").jsonObject
        assertEquals(setOf("gmail", "slack"), ComposioAccounts.appsIn(ComposioAccounts.MANAGE, manage))
        val exec = Json.parseToJsonElement("""{"tools":[{"tool_slug":"GMAIL_SEND_EMAIL"},{"tool_slug":"GOOGLECALENDAR_LIST_EVENTS"}]}""").jsonObject
        assertEquals(setOf("gmail", "googlecalendar"), ComposioAccounts.appsIn("COMPOSIO_MULTI_EXECUTE_TOOL", exec))
    }

    @Test
    fun `a token answer keeps the old refresh token when it sends none`() {
        val json = Json.parseToJsonElement("""{"access_token":"a2","expires_in":3600}""").jsonObject
        val t = McpOAuth.parseTokens(json, "https://login.example.dev/token", "c1", COMPOSIO_MCP_URL, "r1", now = 1_000)
        assertEquals("a2", t.access)
        assertEquals("r1", t.refresh)
        assertEquals(3_601_000L, t.expiresAt)
    }

    @Test
    fun `well-known documents live at the root of the server's host`() {
        assertEquals("https://connect.composio.dev/.well-known/oauth-protected-resource", McpOAuth.wellKnown(COMPOSIO_MCP_URL, "oauth-protected-resource"))
    }

    @Test
    fun `an off app is matched by whole slug prefix, toolkit arguments, and an unnamed execute fails closed`() {
        fun args(j: String) = Json.parseToJsonElement(j).jsonObject
        val exec = "COMPOSIO_MULTI_EXECUTE_TOOL"
        assertEquals("google_tasks", ComposioAccounts.blockedApp(exec, args("""{"tools":[{"tool_slug":"GOOGLE_TASKS_LIST_TASKS"}]}"""), setOf("google_tasks")))
        assertEquals("gmail", ComposioAccounts.blockedApp(exec, args("""{"tools":[{"tool_slug":"GMAIL_SEND_EMAIL"}]}"""), setOf("gmail")))
        assertNull(ComposioAccounts.blockedApp(exec, args("""{"tools":[{"tool_slug":"GMAILX_SEND"}]}"""), setOf("gmail")))
        assertEquals("slack", ComposioAccounts.blockedApp("COMPOSIO_SEARCH_TOOLS", args("""{"toolkits":["slack"]}"""), setOf("slack")))
        assertEquals("gmail", ComposioAccounts.blockedApp(exec, args("""{"tools":[{"name":"x"}]}"""), setOf("gmail")))
        assertNull(ComposioAccounts.blockedApp(exec, args("""{"tools":[]}"""), emptySet()))
    }
}
