package com.aura.aura_ui

import android.app.Application
import com.aura.aura_ui.audio.RecordableVoiceStore
import com.aura.aura_ui.remote.DeviceRegistry
import com.aura.aura_ui.remote.RemoteGateManager
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.installations.FirebaseInstallations
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AuraApplication : Application() {

    @Inject lateinit var remoteGateManager: RemoteGateManager
    @Inject lateinit var deviceRegistry: DeviceRegistry

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Before anything can log an event: applies the diagnostics switch to Analytics/Crashlytics.
        com.aura.aura_ui.data.preferences.DiagnosticsStore.hydrate(this)
        // Before anything starts: the last kill switch / block / version verdict, from disk, so a
        // blocked install stays blocked from its first instant (see GateEnforcer).
        RemoteGateManager.hydrate(this)
        val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        com.aura.aura_ui.remote.GateEnforcer.watch(this, appScope)
        // Hydrated here rather than lazily in composition: the audio players read it statically,
        // and a service-started process (boot, quick-settings tile) never runs a Compose screen.
        RecordableVoiceStore.hydrate(this)
        com.aura.aura_ui.data.preferences.ReadScreenImageStore.hydrate(this)
        // After a swipe from recents (ColorOS kills the whole process), Android restarts our sticky
        // services one at a time ~20 s apart, with backoff — the wake word was measured coming back
        // after 81–480 s. Whichever component brings the process back up restarts it right away.
        if (getSharedPreferences("aura_settings", MODE_PRIVATE).getBoolean("wake_word_enabled", false)) {
            com.aura.aura_ui.services.WakeWordListeningService.start(this)
        }
        // First remote-gate fetch at process start; the foreground service keeps it
        // fresh on a 15-min ticker. refresh() never throws.
        appScope.launch {
            remoteGateManager.refresh()
        }
        appScope.launch {
            deviceRegistry.reportLaunch()
        }
        // Firebase's own anonymous per-install ID, attached as a custom key so it's
        // easy to eyeball alongside Analytics data. No new identifier is invented —
        // it resets on uninstall and is already attached to every crash report.
        FirebaseInstallations.getInstance().id.addOnSuccessListener { id ->
            FirebaseCrashlytics.getInstance().setCustomKey("installation_id", id)
        }
    }

    companion object {
        lateinit var instance: AuraApplication
            private set
    }
}
