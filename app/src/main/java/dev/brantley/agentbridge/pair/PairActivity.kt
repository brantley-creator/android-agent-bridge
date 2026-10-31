package dev.brantley.agentbridge.pair

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.brantley.agentbridge.BridgeApp
import dev.brantley.agentbridge.net.relayHost
import dev.brantley.agentbridge.ui.AgentBridgeTheme
import kotlinx.coroutines.launch

class PairActivity : ComponentActivity() {
    private var status by mutableStateOf("Check the relay host and room, then tap Pair. The phone stays disarmed.")
    private var busy by mutableStateOf(false)
    private var relay by mutableStateOf("")
    private var room by mutableStateOf("")
    private var code by mutableStateOf("")
    private var host by mutableStateOf("")
    private var ready by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF101614.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF101614.toInt()),
        )
        applyIntent(intent)
        setContent {
            AgentBridgeTheme {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Pair this phone", style = MaterialTheme.typography.headlineMedium)
                    Text("Relay host")
                    Text(host.ifBlank { "(missing)" }, style = MaterialTheme.typography.titleLarge)
                    Text("Room")
                    Text(room.ifBlank { "(missing)" }, style = MaterialTheme.typography.titleLarge)
                    Text("Nothing is saved until you tap Pair.")
                    Text(status)
                    Button(
                        enabled = ready && !busy,
                        onClick = { pair(relay, room, code, host) },
                    ) { Text("Pair") }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        val data = intent?.data
        relay = data?.getQueryParameter("relay").orEmpty().trim()
        room = data?.getQueryParameter("room").orEmpty().trim()
        code = data?.getQueryParameter("code").orEmpty().trim()
        host = relayHost(relay)
        ready = looksLikeRelay(relay) && ROOM.matches(room) && code.isNotEmpty()
        busy = false
        status = "Check the relay host and room, then tap Pair. The phone stays disarmed."
    }

    private fun pair(relay: String, room: String, code: String, host: String) {
        busy = true
        status = "Pairing…"
        val app = application as BridgeApp
        lifecycleScope.launch {
            try {
                val token = Pairing.claim(relay, room, code, deviceName())
                app.tokens.put(token)
                app.settings.update { it.copy(relayUrl = relay, room = room, paired = true) }
                status = "Paired with $host. Open Agent Bridge and tap Arm session when you want control."
            } catch (_: Exception) {
                status = "Pairing failed. The code may already be used or expired."
                busy = false
            }
        }
    }

    private fun deviceName(): String {
        val raw = Build.MODEL.orEmpty()
        val cleaned = raw.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim()
        return cleaned.ifBlank { "phone" }.take(64)
    }

    private fun looksLikeRelay(relay: String): Boolean =
        relay.startsWith("https://") ||
            relay.startsWith("http://") ||
            relay.startsWith("wss://") ||
            relay.startsWith("ws://")

    companion object {
        private val ROOM = Regex("^[A-Za-z0-9]{8,32}$")
    }
}
