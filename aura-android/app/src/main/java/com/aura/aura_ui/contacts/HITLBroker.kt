package com.aura.aura_ui.contacts

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedList
import java.util.Queue

/**
 * Client-side HITL signal broker.
 *
 * Agents emit [HITLSignal]s here; the UI layer (MainActivity / composable host)
 * observes [activeSignal] and renders the appropriate sheet. When the user
 * resolves or cancels, the UI calls [resolve] or [cancel] — the broker invokes
 * the original callback and advances the queue.
 *
 * This object is safe to call from any thread; internal state is protected by
 * the @Synchronized annotation on mutation methods.
 */
object HITLBroker {

    private const val TAG = "HITLBroker"

    private val _activeSignal = MutableStateFlow<HITLSignal?>(null)

    /**
     * Observed by the composable host (see ContactBrokerHost in
     * ContactDisambiguationSheet.kt). Emits the currently-pending signal,
     * or null when no HITL interaction is in progress.
     */
    val activeSignal: StateFlow<HITLSignal?> = _activeSignal.asStateFlow()

    /** Signals queued while another sheet is already visible. */
    private val pending: Queue<HITLSignal> = LinkedList()

    // ─────────────────────────────────────────────────────────────────────────
    // Agent-facing API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by agents/services to request user input.
     * If another signal is already active, this one is enqueued and shown next.
     */
    @Synchronized
    fun emit(signal: HITLSignal) {
        Log.i(TAG, "📥 Signal received: ${signal::class.simpleName} [${signal.requestId}]")
        if (_activeSignal.value == null) {
            _activeSignal.value = signal
        } else {
            Log.d(TAG, "Queuing signal — another is already active")
            pending.add(signal)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI-facing API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by [ContactDisambiguationSheet] when the user selects a contact.
     */
    @Synchronized
    fun resolve(requestId: String, contactId: String) {
        val signal = _activeSignal.value ?: return
        if (signal.requestId != requestId) {
            Log.w(TAG, "resolve() called with stale requestId — ignoring")
            return
        }
        Log.i(TAG, "✅ Resolved [${signal.requestId}] → contactId=$contactId")
        when (signal) {
            is HITLSignal.ContactDisambiguation -> signal.onResolved(contactId)
            is HITLSignal.PermissionRequired -> { /* not applicable */ }
        }
        advance()
    }

    /**
     * Called by the sheet's "Skip" button or backdrop dismiss.
     */
    @Synchronized
    fun cancel(requestId: String) {
        val signal = _activeSignal.value ?: return
        if (signal.requestId != requestId) return
        Log.i(TAG, "❌ Cancelled [${signal.requestId}]")
        when (signal) {
            is HITLSignal.ContactDisambiguation -> signal.onCancelled()
            is HITLSignal.PermissionRequired -> signal.onCancelled()
        }
        advance()
    }

    /**
     * Called when user taps "Open Settings" in a PermissionRequired sheet.
     */
    @Synchronized
    fun openSettings(requestId: String) {
        val signal = _activeSignal.value as? HITLSignal.PermissionRequired ?: return
        if (signal.requestId != requestId) return
        Log.i(TAG, "⚙️ Open Settings requested [${signal.requestId}]")
        signal.onOpenSettings()
        advance()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun advance() {
        _activeSignal.value = pending.poll()
        if (_activeSignal.value != null) {
            Log.d(TAG, "Advancing queue → ${_activeSignal.value!!::class.simpleName}")
        }
    }
}
