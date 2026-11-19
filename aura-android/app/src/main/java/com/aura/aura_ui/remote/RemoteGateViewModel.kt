package com.aura.aura_ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exposes [RemoteGateManager.gateState] and the in-app update to `AuraAppShell`, which swaps in
 * `BlockScreen` while the state is blocking and shows `UpdateDialog` when an update is out.
 *
 * - Contract: triggers one [RemoteGateManager.refresh] when created, so opening the app picks up
 *   a new kill switch or version floor without waiting for the background refresh.
 * - Contract: at most one update job; [startUpdate] while one runs is ignored, and leaving the
 *   app (this ViewModel cleared) cancels it.
 */
@HiltViewModel
class RemoteGateViewModel @Inject constructor(
    private val gateManager: RemoteGateManager,
    private val updater: AppUpdater,
) : ViewModel() {
    val gateState = gateManager.gateState
    val updateProgress = updater.progress

    private var updateJob: Job? = null

    init {
        viewModelScope.launch { gateManager.refresh() }
    }

    fun startUpdate(apkUrl: String) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch { updater.install(apkUrl) }
    }

    fun cancelUpdate() {
        updateJob?.cancel()
        updater.reset()
    }
}
