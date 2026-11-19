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
    fun `a link elsewhere in the reply does not make a connected app a sign-in`() {
        val reply = """{"successful":true,"data":{"message":"Done","results":{"gmail":{"connected_account":{"status":"ACTIVE","id":"ca_1"}}},
            "session":{"id":"s1","instructions":"Read https://docs.composio.dev/tool-router before calling tools"}}}"""
        assertEquals(ComposioLink.Active, ComposioAccounts.parse(reply, "gmail"))
    }

    @Test
    fun `the summary decides a one-app reply whose entry has no status`() {
        val reply = """{"successful":true,"data":{"message":"https://docs.composio.dev","results":{"gmail":{"logo":"https://logos.composio.dev/api/gmail"}},
            "summary":{"total_toolkits":1,"active_connections":1,"initiated_connections":0,"failed_connections":0}}}"""
        assertEquals(ComposioLink.Active, ComposioAccounts.parse(reply, "gmail"))
    }

    @Test
    fun `a logo is never taken for a sign-in link`() {
        val reply = """{"successful":true,"data":{"results":{"gmail":{"logo":"https://logos.composio.dev/api/gmail"}}}}"""
        assertEquals(ComposioLink.NotConnected, ComposioAccounts.parse(reply, "gmail"))
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
    fun `list and add name each toolkit as an object with its action`() {
        assertEquals(
            """{"toolkits":[{"name":"gmail","action":"list"},{"name":"github","action":"list"}]}""",
            ComposioAccounts.listArgs(linkedSetOf("gmail", "github")).toString(),
        )
        assertEquals("""{"toolkits":[{"name":"gmail","action":"add"}]}""", ComposioAccounts.addArgs("gmail").toString())
    }

    // The replies below are real `COMPOSIO_MANAGE_CONNECTIONS` answers from connect.composio.dev, trimmed.

    @Test
    fun `a list of a connected app is Active`() {
        val reply = """{"successful":true,"data":{"message":"All connections are active","results":{"github":{"toolkit":"github","status":"active",
            "accounts":[{"id":"github_a","status":"initializing","is_default":false},{"id":"github_b","status":"active","is_default":true}]}},
            "summary":{"total_toolkits":1,"active_connections":1,"initiated_connections":0,"failed_connections":0}},"error":null}"""
        assertEquals(ComposioLink.Active, ComposioAccounts.parse(reply, "github"))
    }

    @Test
    fun `a list of an app with no account is NotConnected, not a sign-in`() {
        val reply = """{"successful":true,"data":{"message":"All connections have been initiated and are pending completion",
            "results":{"notion":{"toolkit":"notion","status":"initiated","accounts":[]}},
            "summary":{"total_toolkits":1,"active_connections":0,"initiated_connections":1,"failed_connections":0}},"error":null}"""
        assertEquals(ComposioLink.NotConnected, ComposioAccounts.parse(reply, "notion"))
    }

    @Test
    fun `an add at the account limit is Failed with Composio's reason`() {
        val reply = """{"successful":true,"data":{"message":"All connection attempts failed","results":{"github":{"toolkit":"github","status":"failed",
            "error_message":"Maximum of 10 accounts per toolkit reached. Remove an existing account before adding a new one.","accounts":[]}}},"error":null}"""
        assertEquals(
            ComposioLink.Failed("Maximum of 10 accounts per toolkit reached. Remove an existing account before adding a new one."),
            ComposioAccounts.parse(reply, "github"),
        )
    }

    @Test
    fun `a list of several apps is read per app`() {
        val reply = """{"successful":true,"data":{"results":{"github":{"toolkit":"github","status":"active","accounts":[]},
            "notion":{"toolkit":"notion","status":"initiated","accounts":[]}},
            "summary":{"total_toolkits":2,"active_connections":1,"initiated_connections":1,"failed_connections":0}}}"""
        assertEquals(ComposioLink.Active, ComposioAccounts.parse(reply, "github"))
        assertEquals(ComposioLink.NotConnected, ComposioAccounts.parse(reply, "notion"))
    }

    @Test
    fun `apps in a call are the toolkits of a connection call and the app part of each slug`() {
        val manage = Json.parseToJsonElement("""{"toolkits":["Gmail","slack"]}""").jsonObject
        assertEquals(setOf("gmail", "slack"), ComposioAccounts.appsIn(ComposioAccounts.MANAGE, manage))
        val objects = Json.parseToJsonElement("""{"toolkits":[{"name":"GitHub","action":"list"}]}""").jsonObject
        assertEquals(setOf("github"), ComposioAccounts.appsIn(ComposioAccounts.MANAGE, objects))
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
        assertEquals("slack", ComposioAccounts.blockedApp(ComposioAccounts.MANAGE, args("""{"toolkits":[{"name":"slack","action":"add"}]}"""), setOf("slack")))
        assertEquals("gmail", ComposioAccounts.blockedApp(exec, args("""{"tools":[{"name":"x"}]}"""), setOf("gmail")))
        assertNull(ComposioAccounts.blockedApp(exec, args("""{"tools":[]}"""), emptySet()))
    }
}
