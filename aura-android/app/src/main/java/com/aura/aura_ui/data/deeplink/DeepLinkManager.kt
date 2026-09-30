
package com.aura.aura_ui.data.deeplink

import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import androidx.annotation.RequiresApi
import com.aura.aura_ui.utils.AgentLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verified-domain lookup for deep-link discovery.
 *
 * Historical note: this class used to carry a whole discover-and-cache
 * pipeline (`discoverAndCacheDeepLinks` + `AppMetadataCache`). It was dead
 * code — never called — and structurally broken: it probed with a data-less
 * `ACTION_VIEW` intent, which Android's intent data-test rule matches only
 * against filters that declare **no** `<data>`, so every scheme/host filter
 * was silently dropped. Runtime discovery now lives in
 * [ManifestIntentFilterReader] + [DiscoveredLinkSynthesizer] (manifest
 * parsing, not resolution probing), and static shortcuts in
 * [StaticShortcutReader] + [ShortcutEntrySynthesizer].
 */
@Singleton
class DeepLinkManager
    @Inject
    constructor(
        private val context: Context,
    ) {
        /**
         * Verified + user-selected web (http/https) domains an app owns, via
         * [DomainVerificationManager] (API 31+). Returns host strings such as
         * "open.spotify.com". This is the docs-correct source for https App
         * Links — the highest-trust tier of `list_app_deeplinks`.
         *
         * Caller owns the `SDK_INT >= S` guard; on older devices, fall back to
         * the curated catalog.
         */
        @RequiresApi(Build.VERSION_CODES.S)
        fun getVerifiedDomains(packageName: String): List<String> =
            runCatching {
                val manager = context.getSystemService(DomainVerificationManager::class.java)
                    ?: return emptyList()
                val state = manager.getDomainVerificationUserState(packageName)
                    ?: return emptyList()
                state.hostToStateMap
                    .filterValues {
                        it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED ||
                            it == DomainVerificationUserState.DOMAIN_STATE_SELECTED
                    }
                    .keys
                    .toList()
            }.getOrElse {
                AgentLogger.Deeplink.e("getVerifiedDomains failed for $packageName", it)
                emptyList()
            }
    }
