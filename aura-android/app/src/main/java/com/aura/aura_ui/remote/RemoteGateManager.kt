package com.aura.aura_ui.remote

import android.content.Context
import android.util.Log
import com.aura.aura_ui.BuildConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes and publishes the app's [GateState]: kill switch, minimum version, update nudge, and
 * per-device blocks.
 *
 * - Reads, combined in [refresh]:
 *   1. Remote Config keys `KEY_*` (below): kill switch, version floor, latest version, update URL.
 *      Free and unlimited, so this is where every global control lives.
 *   2. [Blocklist]: `blocklist/{hash}` and this install's `devices/{uid}.blocked` in Firestore,
 *      at most once a day.
 *   Either can turn the kill switch on. Neither can turn it off once the other has.
 * - Fails: **open** for 1. On a fetch error, cached or default values apply, and the defaults
 *   resolve to [GateState.Allowed], so being offline never locks out a fresh install. 2 fails
 *   closed once a block has been seen (see [Blocklist.decide]).
 * - Callers: `AuraApplication` (process start), `AssistantForegroundService` (every 15 min),
 *   `RemoteGateViewModel` (app UI opened).
 * - Change together: the Remote Config keys here and the parameters in the owner's Firebase
 *   project. `KEY_LATEST_VERSION_CODE` must be bumped there after every release, or existing
 *   installs never see the update prompt.
 */
@Singleton
class RemoteGateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val remoteConfig = FirebaseRemoteConfig.getInstance()

    /** Same flow as [state]; here so Hilt consumers don't reach into the companion. */
    val gateState: StateFlow<GateState> = state

    init {
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(if (BuildConfig.DEBUG) 0L else FETCH_INTERVAL_SECONDS)
                .build(),
        )
        remoteConfig.setDefaultsAsync(
            mapOf(
                KEY_KILL_SWITCH_ENABLED to false,
                KEY_KILL_SWITCH_MESSAGE to DEFAULT_KILL_SWITCH_MESSAGE,
                KEY_MIN_SUPPORTED_VERSION_CODE to 0L,
                KEY_VERSION_GATE_MESSAGE to DEFAULT_VERSION_GATE_MESSAGE,
                KEY_LATEST_VERSION_CODE to BuildConfig.VERSION_CODE.toLong(),
                // Empty on purpose: the real URL is set in Remote Config, so a wrong baked-in
                // value can never reach users.
                KEY_UPDATE_URL to "",
            ),
        )
    }

    /**
     * Fetches all sources, recomputes the state, publishes it to [state], and saves the inputs
     * for [hydrate].
     *
     * - Contract: safe to call often. Remote Config throttles its own network fetch
     *   (`FETCH_INTERVAL_SECONDS`, 0 in debug) and [Blocklist] asks Firestore at most once a day.
     * - Fails: never throws. Each source's error is logged and that source is skipped.
     */
    suspend fun refresh() {
        try {
            remoteConfig.fetchAndActivate().await()
        } catch (e: Exception) {
            Log.w(TAG, "Remote Config fetch failed, using cached/default values: ${e.message}")
        }
        // The last activated values load from disk asynchronously. Reading before that finishes
        // would return the defaults and, saved below, briefly forget an active kill switch.
        runCatching { remoteConfig.ensureInitialized().await() }

        val inputs = GateInputs(
            killSwitchEnabled = remoteConfig.getBoolean(KEY_KILL_SWITCH_ENABLED),
            killSwitchMessage = remoteConfig.getString(KEY_KILL_SWITCH_MESSAGE),
            minSupportedVersionCode = remoteConfig.getLong(KEY_MIN_SUPPORTED_VERSION_CODE),
            versionGateMessage = remoteConfig.getString(KEY_VERSION_GATE_MESSAGE),
            latestVersionCode = remoteConfig.getLong(KEY_LATEST_VERSION_CODE),
            updateUrl = remoteConfig.getString(KEY_UPDATE_URL),
        )
        saveInputs(context, inputs)

        // Checked last because it can only tighten the gate.
        val block = runCatching { Blocklist.check(context) }
            .onFailure { Log.w(TAG, "blocklist check failed: ${it.message}") }
            .getOrElse { Blocklist.cached(context) }

        publish(evaluateGate(inputs, block, BuildConfig.VERSION_CODE.toLong()))
    }

    companion object {
        private const val TAG = "RemoteGateManager"
        private const val FETCH_INTERVAL_SECONDS = 900L
        private const val DEFAULT_KILL_SWITCH_MESSAGE = "AURA is temporarily unavailable. Please check back soon."
        private const val DEFAULT_VERSION_GATE_MESSAGE = "This version of AURA is no longer supported. Please update to continue."

        private const val PREFS = "aura_gate_cache"

        private val _state = MutableStateFlow<GateState>(GateState.Allowed)

        /**
         * The gate for the whole process, readable without Hilt.
         *
         * - Why static: the overlay, wake word, accessibility and boot paths can't easily inject
         *   this singleton, and each must refuse to start while blocked. `GateEnforcer` collects
         *   it to shut everything down the moment a refresh turns it blocking.
         * - Contract: restored by [hydrate] at process start, then updated by every [refresh].
         */
        val state: StateFlow<GateState> = _state.asStateFlow()

        val latestState: GateState get() = _state.value

        /** True while the app is kill-switched, blocked, or below the version floor. */
        fun isBlocked(): Boolean = latestState.isBlocking()

        /** The message to show while blocked, or null when not blocked. */
        fun blockMessage(): String? = latestState.blockMessageOrNull()

        /**
         * Restores the last verdict from prefs [PREFS] and the [Blocklist] cache, with no
         * network. Call from `Application.onCreate` before anything else starts.
         *
         * - Contract: an install never refreshed is [GateState.Allowed]. The version floor is
         *   re-checked against this build, so an update lifts a version block at once.
         */
        fun hydrate(context: Context) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val inputs = GateInputs(
                killSwitchEnabled = p.getBoolean(KEY_KILL_SWITCH_ENABLED, false),
                killSwitchMessage = p.getString(KEY_KILL_SWITCH_MESSAGE, null) ?: DEFAULT_KILL_SWITCH_MESSAGE,
                minSupportedVersionCode = p.getLong(KEY_MIN_SUPPORTED_VERSION_CODE, 0L),
                versionGateMessage = p.getString(KEY_VERSION_GATE_MESSAGE, null) ?: DEFAULT_VERSION_GATE_MESSAGE,
                latestVersionCode = p.getLong(KEY_LATEST_VERSION_CODE, 0L),
                updateUrl = p.getString(KEY_UPDATE_URL, null).orEmpty(),
            )
            publish(evaluateGate(inputs, Blocklist.cached(context), BuildConfig.VERSION_CODE.toLong()))
        }

        private fun saveInputs(context: Context, i: GateInputs) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_KILL_SWITCH_ENABLED, i.killSwitchEnabled)
                .putString(KEY_KILL_SWITCH_MESSAGE, i.killSwitchMessage)
                .putLong(KEY_MIN_SUPPORTED_VERSION_CODE, i.minSupportedVersionCode)
                .putString(KEY_VERSION_GATE_MESSAGE, i.versionGateMessage)
                .putLong(KEY_LATEST_VERSION_CODE, i.latestVersionCode)
                .putString(KEY_UPDATE_URL, i.updateUrl)
                .apply()
        }

        private fun publish(newState: GateState) {
            if (_state.value != newState) Log.i(TAG, "Gate: ${newState::class.simpleName}")
            _state.value = newState
        }

        const val KEY_KILL_SWITCH_ENABLED = "kill_switch_enabled"
        const val KEY_KILL_SWITCH_MESSAGE = "kill_switch_message"
        const val KEY_MIN_SUPPORTED_VERSION_CODE = "min_supported_version_code"
        const val KEY_VERSION_GATE_MESSAGE = "version_gate_message"
        const val KEY_LATEST_VERSION_CODE = "latest_version_code"
        const val KEY_UPDATE_URL = "update_url"
    }
}
