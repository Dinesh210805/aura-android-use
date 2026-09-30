package com.aura.aura_ui.mcp.log

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Phase 10B — user setting: how many days to keep session logs.
 *
 * Stored in [EncryptedSharedPreferences] to keep the surface coherent with the
 * other auth/key preferences, but the value (a day count) is NOT sensitive, so
 * this NEVER crashes on a keystore failure: if the encrypted file's keyset is
 * corrupt (a known AndroidX Security failure that used to crash the app the
 * moment the Retention screen opened), it is wiped and re-created once, and if
 * the keystore is unavailable entirely we fall back to plain prefs.
 */
class LogRetentionPrefs(context: Context) {

    private val prefs: SharedPreferences = openPrefs(context.applicationContext)

    /** Days the user wants to retain session logs. Default 30, must be ≥ 1. */
    fun retentionDays(): Int = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_DAYS).coerceAtLeast(1)

    fun setRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, days.coerceAtLeast(1)).apply()
    }

    companion object {
        private const val TAG = "LogRetentionPrefs"
        private const val FILE_NAME = "aura_mcp_log_prefs"
        private const val FALLBACK_FILE_NAME = "aura_mcp_log_prefs_plain"
        private const val KEY_RETENTION_DAYS = "retention_days"
        const val DEFAULT_DAYS = 30
        /** Preset chips offered in the settings screen. "custom" path lets the user type any int. */
        val PRESETS = listOf(7, 15, 30, 60, 90)

        /**
         * Open the encrypted store, self-healing on corruption so opening the
         * Retention screen can never crash the app:
         *  1. Try [EncryptedSharedPreferences].
         *  2. On failure, delete the (corrupt) keyset file and try once more —
         *     fixes the common "encrypted file corrupt, master key fine" case.
         *  3. If it still fails (keystore unavailable), fall back to plain prefs.
         *     A retention-day count is not sensitive, so this is acceptable.
         */
        private fun openPrefs(ctx: Context): SharedPreferences {
            fun encrypted(): SharedPreferences {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    ctx,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return runCatching { encrypted() }
                .recoverCatching {
                    Log.w(TAG, "Encrypted retention prefs corrupt — wiping and retrying", it)
                    ctx.deleteSharedPreferences(FILE_NAME)
                    encrypted()
                }
                .getOrElse {
                    Log.e(TAG, "Keystore unavailable — falling back to plain retention prefs", it)
                    ctx.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
                }
        }
    }
}
