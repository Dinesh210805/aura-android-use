package com.aura.aura_ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exposes [RemoteGateManager.gateState] to `AuraAppShell`, which swaps in `BlockScreen` while the
 * state is blocking.
 *
 * - Contract: triggers one [RemoteGateManager.refresh] when created, so opening the app picks up
 *   a new kill switch or version floor without waiting for the background refresh.
 */
@HiltViewModel
class RemoteGateViewModel @Inject constructor(
    private val gateManager: RemoteGateManager,
) : ViewModel() {
    val gateState = gateManager.gateState

    init {
        viewModelScope.launch { gateManager.refresh() }
    }
}
