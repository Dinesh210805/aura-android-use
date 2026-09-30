package com.aura.aura_ui.agent.memory

import android.content.Context
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * B2/M8 — the encrypted store used to fall back to plaintext SILENTLY. A fail-closed store must
 * REFUSE to persist when the cipher is unavailable (better to lose an untrusted-ROM memory than
 * write PII in the clear); a warn-only store keeps working. Uses the internal (prefs, isEncrypted,
 * failClosed) constructor so the behaviour is exercised without depending on Robolectric's keystore.
 */
@RunWith(RobolectricTestRunner::class)
class EncryptedJsonStoreFailClosedTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private fun plain(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun `fail-closed store refuses to persist when unencrypted`() {
        val store = EncryptedJsonStore(plain("fc_refuse"), isEncrypted = false, failClosedWhenUnencrypted = true)
        store.write("k", listOf(Row("x", 1)), serializer<Row>())
        assertTrue("nothing should be persisted in the clear", store.read("k", serializer<Row>()).isEmpty())
    }

    @Test fun `warn-only store still persists when unencrypted`() {
        val store = EncryptedJsonStore(plain("fc_warn"), isEncrypted = false, failClosedWhenUnencrypted = false)
        store.write("k", listOf(Row("x", 1)), serializer<Row>())
        assertEquals(listOf(Row("x", 1)), store.read("k", serializer<Row>()))
    }

    @Test fun `encrypted store persists regardless of fail-closed`() {
        val store = EncryptedJsonStore(plain("fc_enc"), isEncrypted = true, failClosedWhenUnencrypted = true)
        store.write("k", listOf(Row("x", 1)), serializer<Row>())
        assertEquals(listOf(Row("x", 1)), store.read("k", serializer<Row>()))
    }

    @Test fun `fail-closed also blocks writeBlocking`() {
        val store = EncryptedJsonStore(plain("fc_block"), isEncrypted = false, failClosedWhenUnencrypted = true)
        store.writeBlocking("k", listOf(Row("y", 2)), serializer<Row>())
        assertTrue(store.read("k", serializer<Row>()).isEmpty())
    }
}
