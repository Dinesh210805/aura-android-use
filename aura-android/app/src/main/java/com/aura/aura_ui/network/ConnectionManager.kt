package com.aura.aura_ui.network

import com.aura.aura_ui.utils.AgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val serverUrl: String) : ConnectionState()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : ConnectionState()
    data object Stopped : ConnectionState()
}

@Singleton
class ConnectionManager @Inject constructor() {

    companion object {
        private const val WS_PATH = "/ws/device"
        private const val PING_INTERVAL_MS = 20_000L
        private const val PONG_TIMEOUT_MS = 12_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    var onMessage: ((String) -> Unit)? = null

    private val messageQueue = Channel<String>(capacity = Channel.UNLIMITED)
    private val reconnectAttempt = AtomicInteger(0)

    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var serverUrl: String = ""
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastActivityMs: Long = 0L

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun start(serverUrl: String) {
        this.serverUrl = serverUrl
        reconnectAttempt.set(0)
        if (_state.value == ConnectionState.Stopped) {
            _state.value = ConnectionState.Disconnected
        }
        connect()
    }

    fun updateServerUrl(newUrl: String) {
        serverUrl = newUrl
        AgentLogger.Net.i("Server URL updated to $newUrl — reconnecting")
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        webSocket?.close(1001, "URL changed")
        webSocket = null
        reconnectAttempt.set(0)
        connect()
    }

    fun send(message: String): Boolean {
        val ws = webSocket
        return if (ws != null && _state.value is ConnectionState.Connected) {
            val ok = ws.send(message)
            if (!ok) messageQueue.trySend(message)
            ok
        } else {
            messageQueue.trySend(message).isSuccess
        }
    }

    fun stop() {
        AgentLogger.Net.i("ConnectionManager stopping")
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        _state.value = ConnectionState.Stopped
    }

    private fun connect() {
        if (_state.value == ConnectionState.Stopped) return

        val wsUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + WS_PATH

        val attempt = reconnectAttempt.get()
        AgentLogger.Net.i("Connecting to $wsUrl (attempt $attempt)")
        _state.value = ConnectionState.Connecting

        val request = Request.Builder().url(wsUrl).build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AgentLogger.Net.i("WebSocket connected — $wsUrl")
                reconnectAttempt.set(0)
                lastActivityMs = System.currentTimeMillis()
                _state.value = ConnectionState.Connected(serverUrl)
                internalScope.launch { drainQueue(webSocket) }
                startHeartbeatMonitor(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastActivityMs = System.currentTimeMillis()
                onMessage?.invoke(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AgentLogger.Net.w("Peer closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AgentLogger.Net.w("WebSocket closed: $code $reason")
                heartbeatJob?.cancel()
                if (code == 1000 && _state.value == ConnectionState.Stopped) {
                    _state.value = ConnectionState.Stopped
                } else {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AgentLogger.Net.e("WebSocket failure: ${t.message}")
                heartbeatJob?.cancel()
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (_state.value == ConnectionState.Stopped) return

        val attempt = reconnectAttempt.incrementAndGet()
        val delayMs = min(
            (INITIAL_BACKOFF_MS * 2.0.pow((attempt - 1).toDouble())).toLong(),
            MAX_BACKOFF_MS,
        )

        AgentLogger.Net.i("Reconnecting in ${delayMs}ms (attempt $attempt)")
        _state.value = ConnectionState.Reconnecting(attempt, delayMs)

        reconnectJob?.cancel()
        reconnectJob = internalScope.launch {
            delay(delayMs)
            if (isActive && _state.value !is ConnectionState.Stopped) {
                connect()
            }
        }
    }

    private fun startHeartbeatMonitor(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = internalScope.launch {
            val timeout = PING_INTERVAL_MS + PONG_TIMEOUT_MS
            while (isActive) {
                delay(timeout)
                val silent = System.currentTimeMillis() - lastActivityMs
                if (silent >= timeout) {
                    AgentLogger.Net.w("No server activity for ${silent}ms — forcing reconnect")
                    ws.cancel()
                    break
                }
            }
        }
    }

    private suspend fun drainQueue(ws: WebSocket) {
        var msg = messageQueue.tryReceive().getOrNull()
        while (msg != null) {
            ws.send(msg)
            msg = messageQueue.tryReceive().getOrNull()
        }
    }
}
