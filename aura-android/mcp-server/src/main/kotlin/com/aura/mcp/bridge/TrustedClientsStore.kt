package com.aura.mcp.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One computer the user has approved on the device.
 *
 * Doctrine (TODO.md, settled 2026-07-14): [label]/[host]/[platform] are
 * SELF-DECLARED display data from the bridge's init packet — they are shown
 * to the user but every trust decision keys off [token] alone.
 */
@Serializable
data class TrustedClient(
    val token: String,
    val label: String = "Unknown client",
    val host: String? = null,
    val platform: String? = null,
    /** 0 = approved before metadata existed (migrated legacy token). */
    val firstApprovedAt: Long = 0L,
    val lastConnectedAt: Long = 0L,
) {
    /** Short display id — matches the audit log's tokenId, never the secret. */
    val tokenId: String get() = token.take(8)
}

/**
 * The trust ledger behind WebRTC auto-approve and the Trusted Devices screen:
 * token → metadata, persisted as one JSON blob. Persistence is injected
 * (load/save lambdas) so the store is JVM-testable; on device it's backed by
 * the same SharedPreferences the legacy bare-token StringSet lived in, and
 * that legacy set is migrated on first read (metadata unknown, epoch 0).
 *
 * Exposes a [StateFlow] snapshot for the Compose screen. All mutations are
 * synchronized — callers are the DataChannel callback thread and the UI.
 */
class TrustedClientsStore(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val loadLegacyTokens: () -> Set<String> = { emptySet() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val _clients = MutableStateFlow(readInitial())
    val clients: StateFlow<List<TrustedClient>> = _clients.asStateFlow()

    fun isTrusted(token: String): Boolean =
        token.isNotEmpty() && _clients.value.any { it.token == token }

    /** First-time approval from the on-device dialog. */
    fun approve(token: String, label: String?, host: String?, platform: String?, nowMillis: Long) {
        if (token.isEmpty()) return
        synchronized(lock) {
            val others = _clients.value.filterNot { it.token == token }
            persist(
                others + TrustedClient(
                    token = token,
                    label = label?.takeIf { it.isNotBlank() } ?: "Unknown client",
                    host = host,
                    platform = platform,
                    firstApprovedAt = nowMillis,
                    lastConnectedAt = nowMillis,
                ),
            )
        }
    }

    /**
     * Silent reconnect of an already-trusted token: bump last-connected and
     * refresh the self-declared display fields (hostname/OS can change).
     */
    fun touch(token: String, label: String?, host: String?, platform: String?, nowMillis: Long) {
        synchronized(lock) {
            val existing = _clients.value.firstOrNull { it.token == token } ?: return
            persist(
                _clients.value.map { client ->
                    if (client.token != token) client
                    else client.copy(
                        label = label?.takeIf { it.isNotBlank() } ?: client.label,
                        host = host ?: client.host,
                        platform = platform ?: client.platform,
                        lastConnectedAt = nowMillis,
                    )
                },
            )
        }
    }

    /** Un-trust one computer — its next connect shows the approval dialog again. */
    fun revoke(token: String) {
        synchronized(lock) {
            persist(_clients.value.filterNot { it.token == token })
        }
    }

    /** Un-trust every computer. */
    fun revokeAll() {
        synchronized(lock) { persist(emptyList()) }
    }

    private fun persist(clients: List<TrustedClient>) {
        _clients.value = clients
        runCatching { save(json.encodeToString(clients)) }
    }

    private fun readInitial(): List<TrustedClient> {
        val raw = load()
        if (raw != null) {
            return runCatching { json.decodeFromString<List<TrustedClient>>(raw) }
                .getOrElse { emptyList() }
        }
        // First run on the new format: import legacy bare tokens (trusted
        // before metadata existed), and persist so migration happens once.
        val legacy = runCatching { loadLegacyTokens() }.getOrElse { emptySet() }
        if (legacy.isEmpty()) return emptyList()
        val migrated = legacy.map { TrustedClient(token = it) }
        runCatching { save(json.encodeToString(migrated)) }
        return migrated
    }
}
