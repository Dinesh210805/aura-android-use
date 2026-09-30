package com.aura.aura_ui.remote

import android.content.Context
import android.os.Build
import android.util.Log
import com.aura.aura_ui.BuildConfig
import com.aura.aura_ui.agent.conversation.PersonaOwner
import com.aura.aura_ui.data.preferences.DiagnosticsStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.installations.FirebaseInstallations
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes one row per install to Firestore `devices/{uid}`, so the owner can see who runs AURA,
 * on which devices, and on which version.
 *
 * - Writes: Firestore `devices/{uid}` (merge), keyed on the Firebase Auth uid (anonymous until the
 *   user signs in with Google; [AuraSignIn] keeps the same uid when upgrading). Prefs
 *   `aura_device_registry` for the throttle, the launch counter, the row fingerprint and the uid
 *   whose document is known to exist.
 * - Contract: the row holds **only** the fields in [reportLaunch]'s `row` map. It never contains
 *   screen content, task content, tool arguments, the app list, location, IMEI, serial,
 *   advertising id, or the raw `ANDROID_ID`.
 * - Cost: at most one write per install per day plus one per real change (see [shouldReport]),
 *   and no reads. Why: the free Firestore tier is shared by every install.
 * - Change together: adding or removing a field means updating the Firestore security rules
 *   (which whitelist the fields) and the privacy policy (`screens/legal/LegalCopy.kt`) in the
 *   same commit.
 * - Fails: silently. Every error is logged and swallowed. This runs at process start and must
 *   never delay or crash a launch; a failed write is retried at the next launch.
 * - Only runs when diagnostics are on ([DiagnosticsStore]).
 */
@Singleton
class DeviceRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Writes the registry row if [shouldReport] allows it.
     *
     * - Contract: safe to call on every process start, with no network, auth, or Firestore.
     *   Signs in anonymously if no Firebase user exists yet.
     * - The launch counter increments even when the write is throttled, so `launches` counts
     *   launches, not writes.
     */
    suspend fun reportLaunch() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val versionCode = BuildConfig.VERSION_CODE.toLong()

            val launches = prefs.getLong(KEY_LAUNCHES, 0L) + 1L
            prefs.edit().putLong(KEY_LAUNCHES, launches).apply()
            if (!DiagnosticsStore.isEnabled(context)) return

            // FirebaseAuth persists the account, so the uid is stable for the life of the install
            // and the same document is updated on every report.
            val auth = FirebaseAuth.getInstance()
            val uid = (auth.currentUser ?: auth.signInAnonymously().await().user)?.uid
                ?: return

            val installId = runCatching { FirebaseInstallations.getInstance().id.await() }
                .getOrDefault("")

            // Everything except the counters, so a real change is reported at once.
            val stable = linkedMapOf<String, Any>(
                // Same value as the Crashlytics custom key set in AuraApplication, so a row can be
                // matched to its crash reports.
                "installId" to installId,
                // Blocklist key for this handset (see DeviceIdentity). Empty string, not null, when
                // there's no usable id: the security rules require this field to be a string.
                "deviceHash" to (DeviceIdentity.deviceHash(context) ?: ""),
                // The verified email from Google sign-in, never a typed value. Empty until the
                // user signs in.
                "email" to (auth.currentUser?.email.orEmpty().take(128)),
                // The name the user gave AURA during onboarding, not their Google display name.
                "displayName" to (PersonaOwner.get(context).orEmpty().take(48)),
                "manufacturer" to Build.MANUFACTURER.take(64),
                "model" to Build.MODEL.take(64),
                "device" to Build.DEVICE.take(64),
                "androidSdk" to Build.VERSION.SDK_INT,
                "androidRelease" to (Build.VERSION.RELEASE ?: "").take(32),
                "versionCode" to versionCode,
                "versionName" to BuildConfig.VERSION_NAME.take(48),
                "buildType" to if (BuildConfig.DEBUG) "debug" else "release",
                "locale" to Locale.getDefault().toString().take(32),
                "timeZone" to TimeZone.getDefault().id.take(64),
            )
            val fingerprint = "$uid|$stable"

            val allowed = shouldReport(
                diagnosticsEnabled = true,
                nowMs = System.currentTimeMillis(),
                lastReportMs = prefs.getLong(KEY_LAST_REPORT_MS, 0L),
                lastReportedVersionCode = prefs.getLong(KEY_LAST_VERSION_CODE, -1L),
                currentVersionCode = versionCode,
                rowChanged = prefs.getString(KEY_FINGERPRINT, null) != fingerprint,
            )
            if (!allowed) return

            val row = HashMap<String, Any>(stable).apply {
                put("launches", launches)
                // Server time, not the phone's clock, so a wrong device clock can't mis-sort rows.
                put("lastSeen", FieldValue.serverTimestamp())
            }
            val docRef = FirebaseFirestore.getInstance().collection(COLLECTION).document(uid)

            // `firstSeen` may only be written when the document is created (the rules reject a
            // change). Instead of paying a read to find out, remember which uid we created and,
            // if a create is refused because the document already exists (the same Google account
            // on a reinstall), write again without it.
            if (prefs.getString(KEY_CREATED_UID, null) == uid) {
                docRef.set(row, SetOptions.merge()).await()
            } else {
                try {
                    docRef.set(row + ("firstSeen" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
                } catch (e: FirebaseFirestoreException) {
                    if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
                    docRef.set(row, SetOptions.merge()).await()
                }
                prefs.edit().putString(KEY_CREATED_UID, uid).apply()
            }
            Log.i(TAG, "Device registry row written (launches=$launches)")

            prefs.edit()
                .putLong(KEY_LAST_REPORT_MS, System.currentTimeMillis())
                .putLong(KEY_LAST_VERSION_CODE, versionCode)
                .putString(KEY_FINGERPRINT, fingerprint)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Device registry write failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DeviceRegistry"
        private const val COLLECTION = "devices"
        private const val PREFS_NAME = "aura_device_registry"
        private const val KEY_LAST_REPORT_MS = "last_report_ms"
        private const val KEY_LAST_VERSION_CODE = "last_version_code"
        private const val KEY_LAUNCHES = "launches"
        private const val KEY_FINGERPRINT = "row_fingerprint"
        private const val KEY_CREATED_UID = "created_uid"
    }
}
