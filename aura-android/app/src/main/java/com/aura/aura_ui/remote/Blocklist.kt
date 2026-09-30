package com.aura.aura_ui.remote

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Whether the owner has blocked this device or account: an entry in `blocklist/{hash}`, or a
 * suspension flag on this install's own `devices/{uid}` row.
 *
 * - Reads: Firestore `blocklist` (one query for [DeviceIdentity.deviceHash] and
 *   [DeviceIdentity.accountHash]) and `devices/{uid}` fields `blocked` / `blocked_message`.
 *   Caches the verdict in prefs `aura_blocklist_cache`. Never writes to Firestore.
 * - Cost: at most one check per [CHECK_INTERVAL_MS] per install, about 2 document reads. Why:
 *   the free Firestore tier allows 50,000 reads a day for the whole project. A check runs sooner
 *   only when the keys change (the user signed in).
 * - Contract: a `blocklist` match on **either** key, or `blocked == true` on the row, blocks. The
 *   optional `message` / `blocked_message` is shown to the user. Unblocking means deleting the
 *   blocklist document or clearing the flag; it takes effect at the next check.
 * - Why two sources: `devices/{uid}` is keyed on an anonymous uid that changes when app data is
 *   cleared, so a flag there is escaped by a reinstall. The blocklist is keyed on hashes that
 *   survive one and is read-only to clients.
 * - Fails: closed once blocked. A lookup that didn't reach the server (offline, quota used up)
 *   returns the cached verdict (see [decide]) and is retried at the next refresh.
 */
object Blocklist {

    private const val TAG = "Blocklist"
    private const val COLLECTION = "blocklist"
    private const val PREFS = "aura_blocklist_cache"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_MESSAGE = "message"
    private const val KEY_CHECKED_AT = "checked_at_ms"
    private const val KEY_CHECKED_KEYS = "checked_keys"

    const val CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

    const val DEFAULT_MESSAGE: String = "AURA has been disabled on this device."
    private const val SUSPENDED_MESSAGE = "This device has been suspended by the owner."

    /** Whether this device or account is blocked, and the message to show if it is. */
    data class Verdict(val blocked: Boolean, val message: String?)

    /**
     * Returns the verdict to act on, looking it up in Firestore only when [isCheckDue].
     *
     * - Signs in anonymously when no Firebase user exists, because the rules only let signed-in
     *   clients read.
     * - Writes the cache only after a lookup that reached the server.
     */
    suspend fun check(context: Context): Verdict {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = Verdict(prefs.getBoolean(KEY_BLOCKED, false), prefs.getString(KEY_MESSAGE, null))

        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid
            ?: runCatching { auth.signInAnonymously().await().user?.uid }.getOrNull()
        val keys = listOfNotNull(DeviceIdentity.deviceHash(context), DeviceIdentity.accountHash(uid))
        val keyTag = keys.joinToString(",")

        val due = isCheckDue(
            nowMs = System.currentTimeMillis(),
            lastCheckMs = prefs.getLong(KEY_CHECKED_AT, 0L),
            keysChanged = prefs.getString(KEY_CHECKED_KEYS, null) != keyTag,
        )
        if (!due || keys.isEmpty()) return decide(null, cached.blocked, cached.message)

        val remote = runCatching { lookup(keys, uid) }
            .onFailure { Log.w(TAG, "blocklist lookup failed: ${it.message}") }
            .getOrNull()
        val decided = decide(remote, cached.blocked, cached.message)
        if (remote != null) {
            prefs.edit()
                .putBoolean(KEY_BLOCKED, decided.blocked)
                .putString(KEY_MESSAGE, decided.message)
                .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                .putString(KEY_CHECKED_KEYS, keyTag)
                .apply()
        }
        return decided
    }

    /** The last verdict a completed lookup produced, without any network. Not blocked if never checked. */
    fun cached(context: Context): Verdict {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return decide(null, prefs.getBoolean(KEY_BLOCKED, false), prefs.getString(KEY_MESSAGE, null))
    }

    /**
     * Server-only reads: Firestore's offline cache could otherwise answer "no entry" for a device
     * that is offline and blocked.
     */
    private suspend fun lookup(keys: List<String>, uid: String?): Verdict {
        val db = FirebaseFirestore.getInstance()
        val hits = db.collection(COLLECTION)
            .whereIn(FieldPath.documentId(), keys)
            .get(Source.SERVER)
            .await()
        hits.documents.firstOrNull()?.let { doc ->
            Log.w(TAG, "blocked by blocklist entry ${doc.id.take(12)}…")
            return Verdict(true, doc.getString("message")?.takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE)
        }
        if (uid != null) {
            val row = db.collection("devices").document(uid).get(Source.SERVER).await()
            if (row.getBoolean("blocked") == true) {
                return Verdict(true, row.getString("blocked_message")?.takeIf { it.isNotBlank() } ?: SUSPENDED_MESSAGE)
            }
        }
        return Verdict(false, null)
    }

    /**
     * Whether to ask Firestore again. Pure; covered by `BlocklistDecisionTest`.
     *
     * - True when never checked, when [CHECK_INTERVAL_MS] has passed, when the clock went
     *   backwards, or when the keys changed (a sign-in gives a new account key).
     */
    internal fun isCheckDue(nowMs: Long, lastCheckMs: Long, keysChanged: Boolean): Boolean {
        if (lastCheckMs <= 0L || keysChanged) return true
        val elapsed = nowMs - lastCheckMs
        return elapsed < 0L || elapsed >= CHECK_INTERVAL_MS
    }

    /**
     * Combines a lookup result with the cached verdict. Pure; covered by `BlocklistDecisionTest`.
     *
     * - A completed lookup (`remote != null`) always wins, both to block and to unblock.
     * - No lookup (`remote == null`: not due, offline, or failed) keeps the cached verdict.
     * - Why: without the cache, going offline would unblock a blocked device. This is
     *   deliberately the opposite of [RemoteGateManager]'s Remote Config fetch, which fails open
     *   because an install that was never blocked shouldn't be locked out by being offline.
     */
    internal fun decide(remote: Verdict?, cachedBlocked: Boolean, cachedMessage: String?): Verdict =
        remote ?: Verdict(cachedBlocked, cachedMessage?.takeIf { cachedBlocked })
}
