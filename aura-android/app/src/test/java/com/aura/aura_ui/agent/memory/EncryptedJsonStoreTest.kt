package com.aura.aura_ui.agent.memory

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@Serializable
data class Row(val a: String, val b: Int)

@RunWith(RobolectricTestRunner::class)
class EncryptedJsonStoreTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test fun `write then read round-trips`() {
        val store = EncryptedJsonStore(ctx, "test_store_rt")
        store.write("k", listOf(Row("x", 1), Row("y", 2)), serializer<Row>())
        assertEquals(listOf(Row("x", 1), Row("y", 2)), store.read("k", serializer<Row>()))
    }

    @Test fun `missing key reads empty`() {
        val store = EncryptedJsonStore(ctx, "test_store_missing")
        assertTrue(store.read("nope", serializer<Row>()).isEmpty())
    }

    @Test fun `corrupt json reads empty (fail-soft)`() {
        val store = EncryptedJsonStore(ctx, "test_store_corrupt")
        store.writeRaw("bad", "{not valid json")
        assertTrue(store.read("bad", serializer<Row>()).isEmpty())
    }

    @Test fun `clearAll empties the store`() {
        val store = EncryptedJsonStore(ctx, "test_store_clear")
        store.write("k", listOf(Row("x", 1)), serializer<Row>())
        store.clearAll()
        assertTrue(store.keys().isEmpty())
    }
}
