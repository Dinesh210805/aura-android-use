package com.aura.aura_ui.mcp.bridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists the per-device ONNX execution-provider choice so the (expensive)
 * calibration benchmark runs **once** and every later app launch just rebuilds
 * the known-best session.
 *
 * Plain [SharedPreferences] on purpose — the chosen EP name is not a secret, so
 * this deliberately does NOT use the EncryptedSharedPreferences `aura_secure_keys`
 * store that [ProviderKeyStore]/[TavilyKeyStore] use; the keystore round-trip
 * would buy nothing here.
 *
 * The stored choice is bound to a [signature]. When the signature changes the
 * persisted choice is treated as absent, forcing re-calibration. The signature
 * folds in the app version, the OS build fingerprint (an OS update can swap the
 * NNAPI driver and flip which EP is fastest), and the model identity — exactly
 * the things that invalidate a previous measurement.
 */
class ExecutionProviderStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The persisted provider, or null if nothing is stored OR the stored choice
     * was calibrated under a different [signature] (→ caller should recalibrate).
     */
    fun getChoice(signature: String): ExecutionProvider? {
        val storedSig = prefs.getString(KEY_SIGNATURE, null) ?: return null
        if (storedSig != signature) {
            Log.i(TAG, "Calibration signature changed — recalibration required")
            return null
        }
        val name = prefs.getString(KEY_CHOICE, null) ?: return null
        return runCatching { ExecutionProvider.valueOf(name) }
            .getOrElse {
                Log.w(TAG, "Unknown persisted provider '$name' — ignoring")
                null
            }
    }

    /** Persist [ep] as the winner for the current [signature]. */
    fun setChoice(ep: ExecutionProvider, signature: String) {
        prefs.edit()
            .putString(KEY_CHOICE, ep.name)
            .putString(KEY_SIGNATURE, signature)
            .apply()
        Log.i(TAG, "Persisted execution provider=$ep for signature=$signature")
    }

    companion object {
        private const val TAG = "ExecutionProviderStore"
        private const val PREFS_NAME = "aura_perception_prefs"
        private const val KEY_CHOICE = "ep_choice"
        private const val KEY_SIGNATURE = "ep_calib_signature"
    }
}
