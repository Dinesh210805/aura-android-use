package com.aura.mcp.tools

import com.aura.mcp.bridge.NotificationActionSpec
import com.aura.mcp.bridge.NotificationSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NotificationDigest is the pure selection/shaping layer for
 * `read_notifications`: sensitive-package filtering, ongoing exclusion,
 * newest-first ordering, caps, and length truncation — everything that must
 * hold BEFORE untrusted notification text reaches the model.
 */
class NotificationDigestTest {

    private fun snap(
        key: String,
        pkg: String = "com.whatsapp",
        title: String? = "Alice",
        text: String? = "hey",
        postedAtMs: Long = 1_000L,
        isOngoing: Boolean = false,
        actions: List<NotificationActionSpec> = emptyList(),
    ) = NotificationSnapshot(
        key = key,
        packageName = pkg,
        appName = pkg.substringAfterLast('.'),
        title = title,
        text = text,
        subText = null,
        postedAtMs = postedAtMs,
        isOngoing = isOngoing,
        isClearable = true,
        category = "msg",
        actions = actions,
    )

    private val blockBanking: (String) -> Boolean = { it.contains("bank") || it.contains("paypal") }

    @Test
    fun `sensitive packages are filtered out`() {
        val kept = NotificationDigest.select(
            snapshots = listOf(snap("a"), snap("b", pkg = "com.mybank.app"), snap("c", pkg = "com.paypal.android.p2pmobile")),
            includeOngoing = false,
            packageFilter = null,
            limit = 10,
            isBlocked = blockBanking,
        )
        assertEquals(listOf("a"), kept.map { it.key })
    }

    @Test
    fun `ongoing notifications are excluded by default and included on request`() {
        val all = listOf(snap("a"), snap("b", isOngoing = true))
        assertEquals(listOf("a"), NotificationDigest.select(all, false, null, 10, blockBanking).map { it.key })
        assertEquals(
            setOf("a", "b"),
            NotificationDigest.select(all, true, null, 10, blockBanking).map { it.key }.toSet(),
        )
    }

    @Test
    fun `newest first and capped to limit`() {
        val all = listOf(snap("old", postedAtMs = 1L), snap("new", postedAtMs = 3L), snap("mid", postedAtMs = 2L))
        val kept = NotificationDigest.select(all, false, null, 2, blockBanking)
        assertEquals(listOf("new", "mid"), kept.map { it.key })
    }

    @Test
    fun `package filter matches exact package`() {
        val all = listOf(snap("a", pkg = "com.whatsapp"), snap("b", pkg = "com.google.android.gm"))
        val kept = NotificationDigest.select(all, false, "com.google.android.gm", 10, blockBanking)
        assertEquals(listOf("b"), kept.map { it.key })
    }

    @Test
    fun `clip truncates long text and preserves short text`() {
        assertEquals("short", NotificationDigest.clip("short", 10))
        val clipped = NotificationDigest.clip("x".repeat(600), 500)
        assertEquals(501, clipped?.length) // 500 chars + ellipsis
        assertTrue(clipped!!.endsWith("…"))
        assertNull(NotificationDigest.clip(null, 10))
    }

    @Test
    fun `isSensitivePackage blocks a banking package and allows a normal one`() {
        // com.google.android.apps.authenticator2 is on the SensitivePolicy auth block list.
        assertTrue(NotificationDigest.isSensitivePackage("com.google.android.apps.authenticator2"))
        assertFalse(NotificationDigest.isSensitivePackage("com.whatsapp"))
    }

    @Test
    fun `findAction matches title case-insensitively and trimmed`() {
        val s = snap(
            "a",
            actions = listOf(
                NotificationActionSpec("Mark as read", supportsReply = false),
                NotificationActionSpec("Reply", supportsReply = true),
            ),
        )
        assertEquals("Reply", NotificationDigest.findAction(s, " reply ")?.title)
        assertEquals("Mark as read", NotificationDigest.findAction(s, "MARK AS READ")?.title)
        assertNull(NotificationDigest.findAction(s, "Archive"))
    }
}
