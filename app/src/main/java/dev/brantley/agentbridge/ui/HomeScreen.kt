package dev.brantley.agentbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.brantley.agentbridge.BridgeApp
import dev.brantley.agentbridge.net.SocketStatus

@Composable
fun HomeScreen(
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onAudit: () -> Unit,
    onSettings: () -> Unit,
    notice: String? = null,
) {
    val app = LocalContext.current.applicationContext as BridgeApp
    val settings by app.settings.settings.collectAsState(initial = null)
    val armed by app.core.armed.collectAsState()
    val link by app.link.collectAsState()
    val paired = settings?.paired == true
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Agent Bridge", style = MaterialTheme.typography.headlineMedium)
        if (!notice.isNullOrBlank()) {
            Text(notice, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            if (armed) "Armed" else "Disarmed",
            style = MaterialTheme.typography.headlineSmall,
            color = if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(linkLabel(link))
        Text(if (app.port.a11yEnabled()) "Accessibility service is on." else "Accessibility service is off.")
        Text(
            if (paired) {
                "Paired with ${settings?.relayUrl.orEmpty().substringBefore("?").ifBlank { "the relay" }} · room ${settings?.room.orEmpty()}"
            } else {
                "Not paired. Scan the code from abridge pair."
            },
        )
        Text("The agent cannot turn this on. Arming is this button. Stop is the notification or the Quick Settings tile.")
        if (armed) {
            Button(onClick = onDisarm) { Text("Disarm") }
        } else {
            Button(onClick = onArm, enabled = paired) { Text("Arm session") }
        }
        OutlinedButton(onClick = onAudit) { Text("Audit log") }
        OutlinedButton(onClick = onSettings) { Text("Settings") }
    }
}

private fun linkLabel(status: SocketStatus): String =
    when (status) {
        SocketStatus.Connecting -> "Relay: connecting"
        SocketStatus.Connected -> "Relay: connected"
        SocketStatus.Disconnected -> "Relay: disconnected"
        SocketStatus.Error -> "Relay: not connected"
    }
