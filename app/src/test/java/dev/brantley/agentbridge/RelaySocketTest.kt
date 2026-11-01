package dev.brantley.agentbridge

import dev.brantley.agentbridge.net.RelaySocket
import dev.brantley.agentbridge.net.SocketStatus
import dev.brantley.agentbridge.net.backoffDelayMs
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
}
