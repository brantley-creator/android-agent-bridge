package dev.brantley.agentbridge.net

import dev.brantley.agentbridge.BridgeApp
import dev.brantley.agentbridge.data.toSafety
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stays connected after disarm so a later command can be answered with PAUSED.
 * The phone still refuses every action while disarmed. Unpair closes the socket.
 */
class SessionLink(private val app: BridgeApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val lock = Any()
    private var socket: RelaySocket? = null
    private var listening = false

    fun listen() {
        synchronized(lock) {
            if (listening && socket != null) return
            listening = true
        }
        scope.launch { open() }
    }

    fun hangUp() {
        val current: RelaySocket?
        synchronized(lock) {
            listening = false
            current = socket
            socket = null
        }
        current?.disconnect()
        app.link.value = SocketStatus.Disconnected
    }

    private suspend fun open() {
        val settings = app.settings.settings.first()
        app.core.config = settings.toSafety()
        val token = app.tokens.get().orEmpty()
        if (settings.relayUrl.isBlank() || settings.room.isBlank() || token.isBlank()) {
            app.link.value = SocketStatus.Error
            return
        }
        val relay = RelaySocket(scope, isArmed = { listening })
        synchronized(lock) {
            if (!listening || socket != null) {
                relay.disconnect()
                return
            }
            socket = relay
        }
        relay.onStatus = { status -> app.link.value = status }
        relay.onFrame = { frame ->
            scope.launch {
                gate.withLock {
                    if (!listening) return@withLock
                    relay.send(app.rpc.handle(frame))
                }
            }
        }
        relay.connect(deviceWebSocketUrl(settings.relayUrl, settings.room), token)
    }
}
