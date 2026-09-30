package com.aura.mcp.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TrustedClientsStore — the trust ledger behind WebRTC auto-approve and the
 * Trusted Devices screen. Persistence is injected as load/save lambdas so the
 * store is pure JVM here and SharedPreferences-backed on device.
 */
class TrustedClientsStoreTest {

    private var persisted: String? = null
    private var legacyTokens: Set<String> = emptySet()

    private fun store() = TrustedClientsStore(
        load = { persisted },
        save = { persisted = it },
        loadLegacyTokens = { legacyTokens },
    )

    @Test
    fun `approve records metadata and trusts the token`() {
        val s = store()
        s.approve("token-1", "AURA MCP Bridge", "DINESH-PC", "win32", nowMillis = 1000L)

        assertTrue(s.isTrusted("token-1"))
        val client = s.clients.value.single()
        assertEquals("AURA MCP Bridge", client.label)
        assertEquals("DINESH-PC", client.host)
        assertEquals("win32", client.platform)
        assertEquals(1000L, client.firstApprovedAt)
        assertEquals(1000L, client.lastConnectedAt)
    }

    @Test
    fun `touch updates last-connected and refreshes self-declared fields, keeps first-approved`() {
        val s = store()
        s.approve("token-1", "AURA MCP Bridge", "OLD-PC", "win32", nowMillis = 1000L)
        s.touch("token-1", label = "AURA MCP Bridge", host = "NEW-PC", platform = "darwin", nowMillis = 5000L)

        val client = s.clients.value.single()
        assertEquals(1000L, client.firstApprovedAt)
        assertEquals(5000L, client.lastConnectedAt)
        assertEquals("NEW-PC", client.host)
        assertEquals("darwin", client.platform)
    }

    @Test
    fun `touch on unknown token is a no-op`() {
        val s = store()
        s.touch("ghost", label = null, host = null, platform = null, nowMillis = 1L)
        assertTrue(s.clients.value.isEmpty())
        assertFalse(s.isTrusted("ghost"))
    }

    @Test
    fun `revoke removes one, revokeAll removes everything`() {
        val s = store()
        s.approve("token-1", "Bridge A", null, null, nowMillis = 1L)
        s.approve("token-2", "Bridge B", null, null, nowMillis = 2L)

        s.revoke("token-1")
        assertFalse(s.isTrusted("token-1"))
        assertTrue(s.isTrusted("token-2"))
        assertEquals(1, s.clients.value.size)

        s.revokeAll()
        assertTrue(s.clients.value.isEmpty())
        assertFalse(s.isTrusted("token-2"))
    }

    @Test
    fun `persists across instances`() {
        store().approve("token-1", "Bridge", "PC", "linux", nowMillis = 42L)

        val reloaded = store()
        assertTrue(reloaded.isTrusted("token-1"))
        assertEquals("PC", reloaded.clients.value.single().host)
        assertEquals(42L, reloaded.clients.value.single().firstApprovedAt)
    }

    @Test
    fun `migrates legacy bare-token set once, with unknown metadata`() {
        legacyTokens = setOf("legacy-token")
        val s = store()

        assertTrue(s.isTrusted("legacy-token"))
        val client = s.clients.value.single()
        assertNull(client.host)
        assertEquals(0L, client.firstApprovedAt)

        // Migration persisted — a fresh instance with the legacy set gone still trusts.
        legacyTokens = emptySet()
        assertTrue(store().isTrusted("legacy-token"))
    }

    @Test
    fun `corrupt persisted json is treated as empty, not a crash`() {
        persisted = "{not json"
        val s = store()
        assertTrue(s.clients.value.isEmpty())
        s.approve("token-1", "Bridge", null, null, nowMillis = 1L)
        assertTrue(store().isTrusted("token-1"))
    }

    @Test
    fun `tokenId is a short prefix, never the full secret`() {
        val s = store()
        s.approve("abcdefgh-full-secret-token", "Bridge", null, null, nowMillis = 1L)
        assertEquals("abcdefgh", s.clients.value.single().tokenId)
    }
}
