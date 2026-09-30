package com.aura.aura_ui.uistream

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Continuous UI-tree stream, straight out of the accessibility service.
 *
 * Loopback TCP, newline-delimited JSON, one line per frame. Reach it from a computer
 * with:
 *
 * ```
 * adb forward tcp:8127 tcp:8127
 * ```
 *
 * ### Why a raw socket and not the MCP server
 *
 * `get_ui_tree` over the aura-mcp daemon is request/response across a WebRTC hop: each
 * read costs ~0.3-0.6 s and a caller that wants a live view has to poll, which is
 * exactly what saturates that link. This is the opposite shape — one reader on the
 * device, many subscribers, push. It also adds no dependency: `ServerSocket` is in the
 * JDK, whereas the app carries only a Ktor *client*.
 *
 * ### Its relationship to the agent (updated 2026-08-18)
 *
 * The agent does **not** consume this server, and must not: it is gated behind
 * [UiStreamStore], which is off by default and user-toggled, so an agent subscribing
 * here would lose perception whenever the user had not flipped that switch.
 *
 * What the agent took instead is the *format* and the *idea*. `read_screen`
 * (`mcp-server/.../tools/ReadScreenTool.kt`) is the agent-facing sibling: same
 * positional encoding, ported to `:mcp-server` as `ScreenFrame`, sourced from an
 * in-process tree read rather than a socket, and settled via `ScreenSettle` before it
 * answers. It replaced `get_ui_tree` as the agent's default look.
 *
 * This server stays exactly what it was: a debug channel for the desktop
 * `screenview.py` viewer.
 *
 * ### Loop shape
 *
 * One sampler at [intervalMs], broadcasting to every subscriber. A frame goes out when
 * the tree actually changed, when the idle flag flipped, or on a [heartbeatMs] tick —
 * so a still screen still produces traffic (a consumer can tell "nothing is moving"
 * from "the stream died") without re-sending an unchanged tree four times a second.
 *
 * Bind is loopback-only: a stream of everything on the user's screen must not be
 * reachable from the network. `adb forward` is the only way in, which requires USB
 * debugging and an authorised host.
 */
class UiStreamServer(
    private val port: Int = DEFAULT_PORT,
    private val intervalMs: Long = 250L,
    private val heartbeatMs: Long = 1_000L,
    private val quietMs: Long = ScreenIdle.DEFAULT_QUIET_MS,
    private val now: () -> Long = System::currentTimeMillis,
    /** Injected so the sampler is testable without an accessibility service. */
    private val readTree: () -> Map<*, *>?,
) {

    private val clients: MutableSet<BufferedWriter> =
        Collections.synchronizedSet(LinkedHashSet())
    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var sampleJob: Job? = null

    @Volatile
    var running: Boolean = false
        private set

    /** Frames emitted since start — surfaced in the UI so "is it alive" is answerable. */
    @Volatile
    var framesSent: Long = 0L
        private set

    val clientCount: Int get() = clients.size

    fun start() {
        if (running) return
        val s = try {
            // Loopback only. Backlog of 4: this is a debug channel, not a service.
            ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
        } catch (t: Throwable) {
            Log.e(TAG, "cannot bind 127.0.0.1:$port — ${t.message}")
            return
        }
        server = s
        running = true
        framesSent = 0
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        acceptJob = sc.launch { acceptLoop(s) }
        sampleJob = sc.launch { sampleLoop() }
        Log.i(TAG, "streaming on 127.0.0.1:$port (adb forward tcp:$port tcp:$port)")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        scope?.cancel()
        scope = null
        acceptJob = null
        sampleJob = null
        Log.i(TAG, "stopped")
    }

    private suspend fun acceptLoop(s: ServerSocket) {
        while (running) {
            val sock: Socket = try {
                s.accept()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "accept failed: ${t.message}")
                return
            }
            runCatching {
                sock.tcpNoDelay = true
                val w = sock.getOutputStream().bufferedWriter()
                w.appendLine(UiStreamFrame.hello(quietMs, intervalMs))
                w.flush()
                clients.add(w)
                Log.i(TAG, "client attached (${clients.size} total)")
            }.onFailure { runCatching { sock.close() } }
        }
    }

    private suspend fun sampleLoop() {
        var seq = 0L
        var lastDigest = ""
        var lastIdle: Boolean? = null
        var lastEmit = 0L
        while (scope?.isActive == true && running) {
            val t0 = now()
            if (clients.isNotEmpty()) {
                val idle = ScreenIdle.isIdle(t0, quietMs)
                val quiet = ScreenIdle.quietForMs(t0)
                val tree = runCatching { readTree() }.getOrNull()
                val line: String?
                if (tree == null || tree["elements"] == null) {
                    // Only report a gap once, not four times a second.
                    line = if (lastDigest != STALE) {
                        lastDigest = STALE
                        UiStreamFrame.stale(++seq, t0, idle, quiet, "tree unavailable or empty")
                    } else {
                        null
                    }
                } else {
                    val digest = digestOf(tree)
                    val changed = digest != lastDigest
                    val idleFlipped = idle != lastIdle
                    val due = t0 - lastEmit >= heartbeatMs
                    line = if (changed || idleFlipped || due) {
                        lastDigest = digest
                        UiStreamFrame.of(tree, ++seq, t0, idle, quiet, changed)
                    } else {
                        null
                    }
                }
                if (line != null) {
                    lastIdle = idle
                    lastEmit = t0
                    broadcast(line)
                    framesSent++
                }
            }
            val slack = intervalMs - (now() - t0)
            if (slack > 0) delay(slack)
        }
    }

    /**
     * Cheap change key. Element COUNT plus the bounds of the first and last element is
     * enough to catch a navigation or a scroll without hashing the whole payload every
     * 250 ms; the heartbeat covers anything subtler, and `changed` is advisory — the
     * frame itself is always the truth.
     */
    private fun digestOf(tree: Map<*, *>): String {
        val els = tree["elements"] as? List<*> ?: return "0"
        fun edge(i: Int): String {
            val b = (els.getOrNull(i) as? Map<*, *>)?.get("bounds") as? Map<*, *> ?: return "-"
            return "${b["left"]},${b["top"]},${b["right"]},${b["bottom"]}"
        }
        return "${tree["package_name"]}|${els.size}|${edge(0)}|${edge(els.size - 1)}"
    }

    private fun broadcast(line: String) {
        val dead = mutableListOf<BufferedWriter>()
        synchronized(clients) {
            for (w in clients) {
                try {
                    w.appendLine(line)
                    w.flush()
                } catch (_: Throwable) {
                    dead += w
                }
            }
            dead.forEach {
                runCatching { it.close() }
                clients.remove(it)
            }
        }
        if (dead.isNotEmpty()) Log.i(TAG, "${dead.size} client(s) went away")
    }

    companion object {
        private const val TAG = "UiStream"
        private const val STALE = " stale"
        const val DEFAULT_PORT = 8127

        /** The one instance the accessibility service owns. */
        @Volatile
        var shared: UiStreamServer? = null
            private set

        fun install(server: UiStreamServer) {
            shared?.stop()
            shared = server
        }

        fun shutdown() {
            shared?.stop()
            shared = null
        }
    }
}
