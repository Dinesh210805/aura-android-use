package com.aura.aura_ui.remote

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Stable, hashed identifiers for this handset and this account, used as [Blocklist] keys.
 *
 * - Contract: both hashes are `sha256(SALT + value)` in lowercase hex, so the phone and the owner
 *   compute the same key from the same input. The raw `ANDROID_ID` (SSAID) never leaves the phone.
 * - Why SSAID: it's the only identifier an ordinary app can read without a permission that
 *   survives both a reinstall and a new sign-in. The anonymous Firebase uid changes whenever app
 *   data is cleared.
 * - Limits (don't treat this as a security boundary; it's a deterrent):
 *   - A factory reset, another Android user or work profile, or a new release signing key all
 *     produce a new SSAID, and so a new hash.
 *   - Debug and release builds are signed differently, so they get **different** hashes on the
 *     same phone. Blocking one doesn't block the other.
 *   - A patched APK can skip the check entirely.
 * - Why a salt: the salt is public (it's in the APK). It only makes the digest AURA-specific, so
 *   it can't be joined with another app's hash of the same SSAID.
 */
object DeviceIdentity {

    /**
     * Domain separator for every hash here.
     *
     * Change together: nothing. Changing this value silently invalidates every existing blocklist
     * entry. Treat it as permanent.
     */
    private const val SALT = "aura.device.v1"

    /** Placeholder some platforms return instead of a real id. Treated as "no id". */
    internal const val UNKNOWN = "unknown"

    /**
     * The blocklist key for this handset, or null when the platform has no usable id.
     *
     * - Contract: null means "skip the device check". Callers must never substitute a default,
     *   because a shared fallback value would block every device that shares it.
     */
    @SuppressLint("HardwareIds")
    fun deviceHash(context: Context): String? {
        val ssaid = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return hashOf(ssaid)
    }

    /**
     * The pure half of [deviceHash]; covered by `DeviceIdentityTest`.
     *
     * - Returns null for null, blank, [UNKNOWN] and [KNOWN_BAD_SSAID], because each of those is
     *   shared by many unrelated devices.
     */
    internal fun hashOf(ssaid: String?): String? {
        val clean = ssaid?.trim()?.lowercase().orEmpty()
        if (clean.isEmpty() || clean == UNKNOWN || clean == KNOWN_BAD_SSAID) return null
        return sha256Hex(SALT + clean)
    }

    /** The blocklist key for a Firebase uid, hashed the same way so both key types look alike. */
    fun accountHash(uid: String?): String? {
        val clean = uid?.trim().orEmpty()
        return if (clean.isEmpty()) null else sha256Hex(SALT + clean)
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** A hardcoded SSAID shipped by many old devices and emulators. Must never key a block. */
    private const val KNOWN_BAD_SSAID = "9774d56d682e549c"
}
