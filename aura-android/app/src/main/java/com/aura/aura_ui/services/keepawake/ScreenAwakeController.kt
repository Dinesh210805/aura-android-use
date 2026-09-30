package com.aura.aura_ui.services.keepawake

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * How the screen is physically kept awake. Split from the controller so the
 * lease/ref-count logic is unit-testable with a fake, and so the mechanism can
 * be swapped (accessibility overlay vs. wake lock) without touching callers.
 *
 * Implementations must never throw — a keep-awake failure must not kill a run.
 */
interface ScreenAwakeMechanism {
    /** Keep the display interactive (and wake it if it is currently off). */
    fun engage()

    /** Let the display sleep normally again. */
    fun disengage()
}

/**
 * Single shared authority over "does something need the screen right now".
 *
 * Both automation planes hold the screen through this one object:
 *  - the on-device agent brackets each run with a lease (AuraAgent's try/finally);
 *  - the MCP plane holds a session-scoped lease driven by tool activity
 *    ([KeepAwakeToolPhaseSink]).
 *
 * Ref-counted: the mechanism engages when the first lease is acquired and
 * disengages when the last one closes, so an agent run inside an MCP session
 * (drive_phone) can never release the other plane's hold. A connection alone
 * never acquires — only tool/run activity does.
 *
 * Each lease carries a [maxHoldMs] backstop: if a release is ever missed the
 * controller drops the lease itself instead of pinning the screen (and the
 * battery) forever. Normal callers release in a finally / on end-of-session.
 */
class ScreenAwakeController(
    private val mechanism: ScreenAwakeMechanism,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val maxHoldMs: Long = MAX_HOLD_MS,
) {

    private val lock = Any()
    private val active = mutableSetOf<Lease>()

    /** Handle for one keep-awake hold. Closing twice (or after backstop) is safe. */
    inner class Lease internal constructor(val reason: String) : AutoCloseable {
        internal val backstop = Runnable {
            Log.w(TAG, "Keep-awake backstop hit for '$reason' — releasing leaked lease")
            close()
        }

        override fun close() = release(this)
    }

    /** Keep the screen awake until the returned lease is closed. Never throws. */
    fun acquire(reason: String): Lease {
        val lease = Lease(reason)
        val isFirst: Boolean
        synchronized(lock) {
            isFirst = active.isEmpty()
            active.add(lease)
        }
        handler.postDelayed(lease.backstop, maxHoldMs)
        if (isFirst) {
            runCatching { mechanism.engage() }
                .onFailure { Log.w(TAG, "Keep-awake engage failed — screen may sleep mid-run", it) }
        }
        Log.i(TAG, "Screen keep-awake acquired ($reason)")
        return lease
    }

    private fun release(lease: Lease) {
        val wasLast: Boolean
        synchronized(lock) {
            if (!active.remove(lease)) return // already closed (double-close or backstop race)
            wasLast = active.isEmpty()
        }
        handler.removeCallbacks(lease.backstop)
        if (wasLast) {
            runCatching { mechanism.disengage() }
                .onFailure { Log.w(TAG, "Keep-awake disengage failed", it) }
        }
        Log.i(TAG, "Screen keep-awake released (${lease.reason})")
    }

    /** True while at least one lease is held — used by tests and diagnostics. */
    fun isHeld(): Boolean = synchronized(lock) { active.isNotEmpty() }

    companion object {
        private const val TAG = "ScreenAwake"

        // Backstop only — normal holds release via close(). 30 min comfortably
        // exceeds the longest plausible run without risking an all-night pin.
        const val MAX_HOLD_MS = 30L * 60L * 1000L
    }
}

/** Process-wide singleton accessor — both planes must share one controller. */
object ScreenAwake {
    @Volatile private var shared: ScreenAwakeController? = null

    fun controller(context: Context): ScreenAwakeController =
        shared ?: synchronized(this) {
            shared ?: ScreenAwakeController(
                DefaultScreenAwakeMechanism(context.applicationContext),
            ).also { shared = it }
        }
}
