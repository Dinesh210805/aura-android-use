package com.aura.aura_ui.agent.conversation

/**
 * Reconnect discipline for the Live session. The server resets the WebSocket periodically by
 * design (session resumption exists for exactly this), so the FIRST drop reconnects immediately;
 * only repeated consecutive drops back off, and a dead network eventually gives up instead of
 * storming. Born from a field incident where close-callback feedback created ~9 reconnects/sec
 * (626 sessions in one minute) and the user's speech reached the server 30-40 s late.
 */
object ReconnectPolicy {
    const val MAX_CONSECUTIVE_DROPS = 8
    private const val BASE_DELAY_MS = 500L
    private const val MAX_DELAY_MS = 8_000L

    /** Delay before reconnect attempt given how many times the link has dropped in a row. */
    fun delayForMs(consecutiveDrops: Int): Long = when {
        consecutiveDrops <= 0 -> 0L
        else -> (BASE_DELAY_MS shl minOf(consecutiveDrops - 1, 30)).coerceAtMost(MAX_DELAY_MS)
    }

    /** True when the link has dropped so often in a row that we stop and surface an error. */
    fun shouldGiveUp(consecutiveDrops: Int): Boolean = consecutiveDrops >= MAX_CONSECUTIVE_DROPS
}
