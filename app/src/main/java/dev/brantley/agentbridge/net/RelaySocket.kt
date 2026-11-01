package dev.brantley.agentbridge.net

import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class SocketStatus {
    Connecting,
    Connected,
    Disconnected,
    Error,
}

internal object BridgeLog {
    var sink: (Int, String, String) -> Unit = { _, _, _ -> }

    fun d(tag: String, message: String) = sink(3, tag, message)

    fun e(tag: String, message: String) = sink(6, tag, message)
}

class RelaySocket(
    private val scope: CoroutineScope,
    private val isArmed: () -> Boolean,
    private val random: Random = Random.Default,
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val client: OkHttpClient = defaultClient(),
) {
    var onStatus: ((SocketStatus) -> Unit)? = null
    var onFrame: ((String) -> Unit)? = null

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var wantConnect = false
    private var attempts = 0
    private var url: String = ""
    private var token: String = ""

    fun connect(url: String, token: String) {
        val cleanUrl = url.trim()
        val cleanToken = token.trim()
        if (cleanUrl.isBlank() || cleanToken.isBlank()) {
            BridgeLog.e(TAG, "URL and auth token are required — refusing to connect.")
            onStatus?.invoke(SocketStatus.Error)
            return
        }
        if (tokenInQuery(cleanUrl)) {
            BridgeLog.e(TAG, "Refusing a URL that carries a secret in the query string.")
            onStatus?.invoke(SocketStatus.Error)
            return
        }
        if (!isArmed()) {
            BridgeLog.e(TAG, "Refusing to connect while disarmed.")
            onStatus?.invoke(SocketStatus.Error)
            return
        }
        this.url = cleanUrl
        this.token = cleanToken
        wantConnect = true
        attempts = 0
        reconnectJob?.cancel()
        open()
    }

    fun send(frame: String): Boolean {
        val current = socket ?: return false
        return current.send(frame)
    }

    fun disconnect() {
        wantConnect = false
        reconnectJob?.cancel()
        val current = socket
        socket = null
        current?.cancel()
        onStatus?.invoke(SocketStatus.Disconnected)
    }

    private fun open() {
        onStatus?.invoke(SocketStatus.Connecting)
        BridgeLog.d(TAG, "Connecting to ${urlHost(url)}")
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
        socket = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        if (!wantConnect || !isArmed()) {
            onStatus?.invoke(SocketStatus.Disconnected)
            return
        }
        reconnectJob?.cancel()
        val wait = backoffDelayMs(attempts, random)
        attempts += 1
        BridgeLog.d(TAG, "Reconnecting in ${wait}ms while armed")
        reconnectJob =
            scope.launch {
                sleeper(wait)
                if (wantConnect && isArmed()) open()
            }
    }

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                onStatus?.invoke(SocketStatus.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val method = frameMethod(text)
                BridgeLog.d(TAG, "frame method=$method bytes=${text.toByteArray().size}")
                onFrame?.invoke(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val echoed = runCatching { webSocket.close(closeCodeToSend(code), reason.take(123)) }
                if (echoed.isFailure) runCatching { webSocket.close(1000, "") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                BridgeLog.e(TAG, "Connection failed (${t.javaClass.simpleName})")
                onStatus?.invoke(SocketStatus.Error)
                if (socket === webSocket) socket = null
                scheduleReconnect()
            }
        }

    companion object {
        private const val TAG = "RelaySocket"
        const val PING_INTERVAL_MS = 20_000L

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
    }
}

internal fun backoffDelayMs(attempt: Int, random: Random, capMs: Long = 30_000L): Long {
    val shift = attempt.coerceIn(0, 8)
    var ceiling = 250L
    repeat(shift) {
        if (ceiling >= capMs) return capMs
        ceiling = (ceiling * 2).coerceAtMost(capMs)
    }
    val jitter = if (ceiling <= 1L) 0L else random.nextLong(ceiling)
    return (ceiling / 2 + jitter / 2).coerceIn(1L, capMs)
}

/**
 * OkHttp rejects reserved close codes (1004–1006 and 1015–2999) and anything
 * outside 1000–4999. Echo the peer only when the code is legal to send.
 */
internal fun closeCodeToSend(peerCode: Int): Int {
    val rejected = peerCode < 1000 || peerCode >= 5000 || peerCode in 1004..1006 || peerCode in 1015..2999
    return if (rejected) 1000 else peerCode
}

internal fun tokenInQuery(url: String): Boolean = QUERY_SECRET.containsMatchIn(url)

private val QUERY_SECRET = Regex("[?&](token|access_token|code|authorization)=", RegexOption.IGNORE_CASE)

private fun urlHost(url: String): String =
    Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)").find(url)?.groupValues?.get(1) ?: "invalid"

private fun frameMethod(text: String): String =
    Regex("\"method\"\\s*:\\s*\"([^\"]{0,64})\"").find(text)?.groupValues?.get(1).orEmpty()
