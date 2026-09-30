package com.aura.aura_ui.data.preferences

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's "share diagnostics" switch: controls every report this app sends to its Firebase
 * project.
 *
 * - Contract: when off, nothing is sent. `DeviceRegistry` skips its row, and Firebase Analytics
 *   and Crashlytics collection are disabled (both SDKs persist that setting across launches).
 *   Defaults to on.
 * - Change together: the privacy policy (`presentation/screens/legal/LegalCopy.kt`) describes what
 *   this switch covers.
 * - Turning it off doesn't delete data already sent. Clients can't delete registry rows under
 *   the Firestore rules; removal is by request, as the privacy policy says.
 */
object DiagnosticsStore {
    private const val PREFS_NAME = "aura_settings"
    private const val KEY_ENABLED = "diagnostics_enabled"

    private val _enabled = MutableStateFlow(true)

    /** Observed by the Settings row; read directly by the registry writer. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var hydrated = false

    fun hydrate(context: Context) {
        if (hydrated) return
        hydrated = true
        _enabled.value = prefs(context).getBoolean(KEY_ENABLED, true)
        applyToFirebase(context, _enabled.value)
    }

    /**
     * Synchronous read for callers outside composition (the registry runs at
     * process start, before any screen exists). Hydrates on first use so it is
     * never wrong just because no UI has been shown yet.
     */
    fun isEnabled(context: Context): Boolean {
        hydrate(context)
        return _enabled.value
    }

    fun setEnabled(context: Context, value: Boolean) {
        hydrated = true
        _enabled.value = value
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        applyToFirebase(context, value)
    }

    /** Turns Firebase Analytics and Crashlytics collection on or off. Never throws. */
    private fun applyToFirebase(context: Context, enabled: Boolean) {
        runCatching { FirebaseAnalytics.getInstance(context.applicationContext).setAnalyticsCollectionEnabled(enabled) }
        runCatching { FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
