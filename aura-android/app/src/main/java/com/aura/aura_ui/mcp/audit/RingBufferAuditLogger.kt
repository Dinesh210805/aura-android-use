package com.aura.aura_ui.mcp.audit

import com.aura.mcp.bridge.ConnectionEventType
import com.aura.mcp.bridge.McpAuditLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-memory bounded ring buffer audit log.
 *
 * Recent tool dispatches are kept in a fixed-size array (default 500
 * entries). When full, the oldest entry is overwritten — calls never
 * block on log capacity, so the dispatch hot path stays unaffected.
 *
 * Exposes a [StateFlow] of the current snapshot for Compose subscribers
 * (Activity Log screen). Each `log()` call publishes a new immutable
 * `List<AuditEntry>` snapshot — fine at MCP dispatch rates (~tens/sec
 * peak) and saves the UI from having to manage its own diffing.
 *
 * Concurrency: writes go through a single [ReentrantLock]. We chose a
 * lock over `synchronized` to keep the critical section narrow and
 * inspectable; uncontended acquires on Android cost <100ns.
 */
class RingBufferAuditLogger(
    private val capacity: Int = DEFAULT_CAPACITY,
) : McpAuditLogger {

    private val lock = ReentrantLock()
    private val buffer = arrayOfNulls<AuditEntry>(capacity)
    private var writeIndex = 0
    private var size = 0

    private val _entries = MutableStateFlow<List<AuditEntry>>(emptyList())
    val entries: StateFlow<List<AuditEntry>> = _entries.asStateFlow()

    override fun log(
        toolName: String,
        tokenId: String?,
        success: Boolean,
        scopeDenied: Boolean,
        durationMs: Long,
        errorSummary: String?,
    ) {
        val entry = AuditEntry(
            timestampMillis = System.currentTimeMillis(),
            toolName = toolName,
            tokenId = tokenId,
            success = success,
            scopeDenied = scopeDenied,
            durationMs = durationMs,
            errorSummary = errorSummary,
        )
        lock.withLock {
            buffer[writeIndex] = entry
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size += 1
            _entries.value = snapshotLocked()
        }
    }

    override fun logConnection(
        event: ConnectionEventType,
        tokenId: String?,
        label: String?,
        host: String?,
        platform: String?,
    ) {
        val origin = listOfNotNull(host?.takeIf { it.isNotBlank() }, platform?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        // Connection events reuse the AuditEntry shape: the "toolName" column
        // carries a synthetic verb (e.g. "client_approved") + who, so the
        // existing Activity Log screen renders them with no schema change.
        val verb = when (event) {
            ConnectionEventType.AUTO_APPROVED -> "client_reconnected"
            ConnectionEventType.APPROVED -> "client_approved"
            ConnectionEventType.DENIED -> "client_denied"
            ConnectionEventType.DISCONNECTED -> "client_disconnected"
        }
        when (event) {
            ConnectionEventType.APPROVED -> com.aura.aura_ui.telemetry.AuraAnalytics.mcpPairing(approved = true)
            ConnectionEventType.DENIED -> com.aura.aura_ui.telemetry.AuraAnalytics.mcpPairing(approved = false)
            else -> Unit
        }
        val who = label?.takeIf { it.isNotBlank() } ?: "client"
        val detail = if (origin.isNotBlank()) "$who ($origin)" else who
        val entry = AuditEntry(
            timestampMillis = System.currentTimeMillis(),
            toolName = "$verb: $detail",
            tokenId = tokenId,
            success = event != ConnectionEventType.DENIED,
            scopeDenied = event == ConnectionEventType.DENIED,
            durationMs = 0,
            errorSummary = null,
            isConnectionEvent = true,
        )
        lock.withLock {
            buffer[writeIndex] = entry
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size += 1
            _entries.value = snapshotLocked()
        }
    }

    /** Drop every entry. */
    fun clear() {
        lock.withLock {
            for (i in buffer.indices) buffer[i] = null
            writeIndex = 0
            size = 0
            _entries.value = emptyList()
        }
    }

    /** Number of entries currently buffered. */
    val count: Int get() = lock.withLock { size }

    /**
     * Returns a chronologically-ordered snapshot (oldest → newest). The
     * snapshot is a fresh `ArrayList` — callers can freely iterate without
     * worrying about concurrent mutation.
     */
    private fun snapshotLocked(): List<AuditEntry> {
        if (size == 0) return emptyList()
        val out = ArrayList<AuditEntry>(size)
        if (size < capacity) {
            // Buffer hasn't wrapped yet — read 0..writeIndex
            for (i in 0 until writeIndex) {
                out.add(buffer[i]!!)
            }
        } else {
            // Buffer wrapped — start at writeIndex (the oldest slot)
            for (i in 0 until capacity) {
                val idx = (writeIndex + i) % capacity
                out.add(buffer[idx]!!)
            }
        }
        return out
    }

    companion object {
        private const val DEFAULT_CAPACITY = 500
    }
}

/**
 * Single recorded tool invocation. Immutable so snapshots are safe to
 * pass across threads without copying.
 */
data class AuditEntry(
    val timestampMillis: Long,
    val toolName: String,
    val tokenId: String?,
    val success: Boolean,
    val scopeDenied: Boolean,
    val durationMs: Long,
    val errorSummary: String?,
    /** True for connection lifecycle events (connect/disconnect/approve),
     *  false for ordinary tool dispatches — lets the UI badge them apart. */
    val isConnectionEvent: Boolean = false,
)
