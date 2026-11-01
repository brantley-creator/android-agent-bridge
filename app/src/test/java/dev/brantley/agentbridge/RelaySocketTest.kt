package dev.brantley.agentbridge

import dev.brantley.agentbridge.net.RelaySocket
import dev.brantley.agentbridge.net.SocketStatus
import dev.brantley.agentbridge.net.backoffDelayMs
import dev.brantley.agentbridge.net.closeCodeToSend
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySocketTest {
    @Test
    fun backoffStaysCappedPastFiveAttempts() {
        val random = Random(1)
        val delays = (0..12).map { backoffDelayMs(it, random) }
        assertTrue(delays.all { it in 1..30_000 })
        assertEquals(20_000L, RelaySocket.PING_INTERVAL_MS)
    }

    @Test
    fun refusesEmptyCredentialsAndQuerySecrets() {
        val statuses = mutableListOf<SocketStatus>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val socket = RelaySocket(scope, isArmed = { true }, sleeper = { })
            socket.onStatus = { statuses += it }
            socket.connect("", "token")
            socket.connect("ws://127.0.0.1:9/", " ")
            socket.connect("ws://127.0.0.1:9/?token=abc", "abc")
            assertEquals(listOf(SocketStatus.Error, SocketStatus.Error, SocketStatus.Error), statuses)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun bearerHeaderAndRetriesWhileArmed() {
        val server = MockWebServer()
        val attempts = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    attempts.incrementAndGet()
                    assertEquals("Bearer secret", request.getHeader("Authorization"))
                    assertFalse(request.path!!.contains("token"))
                    return MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                                webSocket.close(1000, "bye")
                            }
                        },
                    )
                }
            }
        server.start()
        var armed = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socket = RelaySocket(scope, isArmed = { armed }, sleeper = { delay(5) })
        try {
            socket.connect("ws://${server.hostName}:${server.port}/v1/room/r/device", "secret")
            val deadline = System.currentTimeMillis() + 5_000
            while (attempts.get() < 6 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue("saw ${attempts.get()} connects", attempts.get() >= 6)
            armed = false
            socket.disconnect()
            val settled = attempts.get()
            Thread.sleep(80)
            assertTrue(attempts.get() <= settled + 1)
        } finally {
            socket.disconnect()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun reservedCloseCodesAreNotEchoed() {
        assertEquals(1000, closeCodeToSend(1005))
        assertEquals(1000, closeCodeToSend(1006))
        assertEquals(1000, closeCodeToSend(1015))
        assertEquals(1000, closeCodeToSend(1004))
        assertEquals(1000, closeCodeToSend(999))
        assertEquals(1000, closeCodeToSend(5000))
        assertEquals(1000, closeCodeToSend(1000))
        assertEquals(1001, closeCodeToSend(1001))
        assertEquals(4001, closeCodeToSend(4001))
    }

    @Test
    fun closeWithNoStatusDoesNotCrashAndSchedulesReconnect() {
        val server = MockWebServer()
        val attempts = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val attempt = attempts.incrementAndGet()
                    return MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                                if (attempt == 1) {
                                    sendCloseWithNoStatus(webSocket)
                                } else {
                                    webSocket.close(1000, "ok")
                                }
                            }
                        },
                    )
                }
            }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socket = RelaySocket(scope, isArmed = { true }, sleeper = { delay(5) })
        try {
            socket.connect("ws://${server.hostName}:${server.port}/v1/room/r/device", "secret")
            val deadline = System.currentTimeMillis() + 5_000
            while (attempts.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue("saw ${attempts.get()} connects", attempts.get() >= 2)
        } finally {
            socket.disconnect()
            scope.cancel()
            server.shutdown()
        }
    }
}

private fun sendCloseWithNoStatus(webSocket: WebSocket) {
    // MockWebServer calls onOpen before initReaderAndWriter, so the writer is still null.
    // An empty close payload is what the client reports as 1005 (no status).
    Thread {
        val writer = waitForField(webSocket, "writer")
        val writeClose = writer.javaClass.declaredMethods.first { it.name == "writeClose" }
        writeClose.isAccessible = true
        writeClose.invoke(writer, 0, null)
        (waitForField(webSocket, "streams") as java.io.Closeable).close()
    }.start()
}

private fun waitForField(target: Any, name: String): Any {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    val deadline = System.currentTimeMillis() + 2_000
    var value = field.get(target)
    while (value == null && System.currentTimeMillis() < deadline) {
        Thread.sleep(5)
        value = field.get(target)
    }
    return value ?: error("$name was not ready")
}
