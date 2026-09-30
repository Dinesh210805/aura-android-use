package com.aura.aura_ui.services

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-writer state holder for [AuraStatus] transitions.
 *
 * Exposes a [StateFlow] that any subscriber (typically the status-bar chip
 * collector in [AssistantForegroundService]) can observe. Does **not** post
 * the notification itself — keeping posting in one place (the existing
 * `combine(health, status)` collector) avoids two writers fighting over the
 * notification id.
 *
 * Dedupes equal transitions so duplicate events (common during fast back-
 * to-back tool calls) don't churn the StateFlow downstream.
 */
class StatusController {
    private val _state = MutableStateFlow<AuraStatus>(AuraStatus.Idle)
    val state: StateFlow<AuraStatus> = _state.asStateFlow()

    fun set(next: AuraStatus) {
        if (_state.value == next) return
        Log.d(TAG, "Status: ${_state.value} -> $next")
        _state.value = next
    }

    private companion object {
        const val TAG = "StatusController"
    }
}
