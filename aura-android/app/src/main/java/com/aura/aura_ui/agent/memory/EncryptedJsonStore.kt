package com.aura.aura_ui.agent.memory

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Generic key->List<T> JSON store over EncryptedSharedPreferences (AES-256 at rest, Keystore master
 * key). V-3 decided this over SQLCipher/Room (not on classpath; shared AuraDatabase stays untouched).
 * Resilient init copied from McpSecretStore: a Keystore failure falls back to MODE_PRIVATE so memory
 * never crashes the agent. All reads are fail-soft (corrupt/absent JSON -> empty list).
 *
 * ## B2/M8 — encrypt-or-warn
 *
 * The Keystore fallback used to be **silent** (only a `Log.w`), so on a ROM with a broken keystore
 * user memories were written to plaintext with no signal. Now:
 *  - [isEncrypted] reports whether the at-rest cipher is actually active, so callers/UI can warn.
 *  - [failClosedWhenUnencrypted] makes writes **refuse** to persist plaintext (fail-closed). Stores
 *    holding user-derived PII (`aura_memory`, `aura_learnings`) opt in; durability-critical stores
 *    that carry less sensitive data (the run ledger) stay warn-only so they keep working.
 */
class EncryptedJsonStore internal constructor(
    private val prefs: SharedPreferences,
    /** True when the at-rest AES cipher is active; false when we fell back to plaintext prefs. */
    val isEncrypted: Boolean,
    private val failClosedWhenUnencrypted: Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun <T> read(key: String, serializer: KSerializer<T>): List<T> =
        prefs.getString(key, null)?.let {
            runCatching { json.decodeFromString(ListSerializer(serializer), it) }.getOrNull()
        } ?: emptyList()

    fun <T> write(key: String, list: List<T>, serializer: KSerializer<T>) {
        if (!persistAllowed(key)) return
        prefs.edit().putString(key, json.encodeToString(ListSerializer(serializer), list)).apply()
    }

    /**
     * Synchronous (`commit`) variant for data that must survive an imminent process death — e.g. the
     * run ledger, whose whole point is to persist across a force-stop, where async `apply()` may not
     * flush. Call off the main thread (blocking disk I/O).
     */
    fun <T> writeBlocking(key: String, list: List<T>, serializer: KSerializer<T>) {
        if (!persistAllowed(key)) return
        prefs.edit().putString(key, json.encodeToString(ListSerializer(serializer), list)).commit()
    }

    /** Test/diagnostic hook: write a raw string under a key (used to inject corrupt JSON). */
    fun writeRaw(key: String, raw: String) {
        if (!persistAllowed(key)) return
        prefs.edit().putString(key, raw).apply()
    }

    fun keys(): Set<String> = prefs.all.keys
    fun clearAll() = prefs.edit().clear().apply()

    /**
     * Whether a write may proceed. Fail-closed stores refuse to write plaintext when the cipher is
     * unavailable — better to lose an untrusted-ROM memory than persist PII in the clear.
     */
    private fun persistAllowed(key: String): Boolean {
        if (isEncrypted || !failClosedWhenUnencrypted) return true
        Log.w(TAG, "refusing plaintext write to '$key' — keystore unavailable, store is fail-closed")
        return false
    }

    companion object {
        private const val TAG = "EncryptedJsonStore"

        /**
         * Build the store, transparently falling back to plaintext prefs if the Keystore is broken.
         * Kept as an `invoke` operator so existing `EncryptedJsonStore(context, name)` call sites are
         * unchanged; [failClosedWhenUnencrypted] is opt-in for PII stores.
         */
        operator fun invoke(
            context: Context,
            storeName: String,
            failClosedWhenUnencrypted: Boolean = false,
        ): EncryptedJsonStore {
            var encrypted = true
            val prefs: SharedPreferences = runCatching {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context, storeName, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse {
                encrypted = false
                Log.w(TAG, "EncryptedSharedPreferences unavailable for '$storeName', plain fallback", it)
                context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
            }
            return EncryptedJsonStore(prefs, encrypted, failClosedWhenUnencrypted)
        }
    }
}
