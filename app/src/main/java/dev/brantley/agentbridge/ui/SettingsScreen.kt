package dev.brantley.agentbridge.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.brantley.agentbridge.BridgeApp
import dev.brantley.agentbridge.data.AppSettings
import dev.brantley.agentbridge.data.toSafety
import dev.brantley.agentbridge.pair.Pairing
import dev.brantley.agentbridge.safety.ArmedService
import dev.brantley.agentbridge.safety.Policy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BridgeApp
    val settings by app.settings.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var extra by remember { mutableStateOf("") }
    var idle by remember(settings.idleTimeoutMin) { mutableFloatStateOf(settings.idleTimeoutMin.toFloat()) }
    var session by remember(settings.sessionTimeoutMin) { mutableFloatStateOf(settings.sessionTimeoutMin.toFloat()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Confirm mode")
        ModeButton("Off", settings.confirmMode == "off") { scope.launch { save(app, settings.copy(confirmMode = "off")) } }
        ModeButton("Writes", settings.confirmMode == "writes") { scope.launch { save(app, settings.copy(confirmMode = "writes")) } }
        ModeButton("Every action", settings.confirmMode == "every") { scope.launch { save(app, settings.copy(confirmMode = "every")) } }
        Text("Settings always asks, and a five-minute approval does not cover it.")
        Text("Idle timeout ${idle.toInt()} min")
        Slider(value = idle, onValueChange = { idle = it }, valueRange = 1f..60f, steps = 58, onValueChangeFinished = {
            scope.launch { save(app, settings.copy(idleTimeoutMin = idle.toInt())) }
        })
        Text("Session cap ${session.toInt()} min")
        Slider(value = session, onValueChange = { session = it }, valueRange = 5f..240f, onValueChangeFinished = {
            scope.launch { save(app, settings.copy(sessionTimeoutMin = session.toInt())) }
        })
        RowSwitch("Disarm when the screen locks", settings.disarmOnLock) {
            scope.launch { save(app, settings.copy(disarmOnLock = it)) }
        }
        RowSwitch("Allow screenshots", settings.screenshotsEnabled) {
            scope.launch { save(app, settings.copy(screenshotsEnabled = it)) }
        }
        Text("Screenshots stay off until you enable them. Secure screens still fail.")
        Text("Denylist", style = MaterialTheme.typography.titleMedium)
        Text("Locked rows stay blocked. Deny wins over anything the agent asks.")
        Policy.displayDeny(settings.toSafety()).forEach { (pkg, locked) ->
            Text(if (locked) "$pkg  · locked" else pkg)
            if (!locked) {
                TextButton(onClick = {
                    scope.launch {
                        val next =
                            if (pkg in Policy.STARTER_DENY) {
                                settings.copy(removedDefaults = settings.removedDefaults + pkg, extraDeny = settings.extraDeny - pkg)
                            } else {
                                settings.copy(extraDeny = settings.extraDeny - pkg)
                            }
                        save(app, next)
                    }
                }) { Text("Remove $pkg") }
            }
        }
        OutlinedTextField(value = extra, onValueChange = { extra = it }, label = { Text("Add package") })
        Button(onClick = {
            val pkg = extra.trim()
            if (pkg.isNotEmpty()) {
                scope.launch {
                    save(app, settings.copy(extraDeny = settings.extraDeny + pkg, removedDefaults = settings.removedDefaults - pkg))
                    extra = ""
                }
            }
        }) { Text("Add to denylist") }
        OutlinedButton(onClick = {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
        }) { Text("Ask to ignore battery optimizations") }
        Button(onClick = {
            ArmedService.stop(context)
            app.port.service?.disableSelf()
        }) { Text("Stop and turn off accessibility") }
        OutlinedButton(onClick = {
            scope.launch {
                val token = app.tokens.get()
                val current = app.settings.settings.first()
                if (!token.isNullOrBlank() && current.relayUrl.isNotBlank() && current.room.isNotBlank()) {
                    Pairing.revoke(current.relayUrl, current.room, token)
                }
                app.tokens.clear()
                app.audit.clear()
                app.core.disarm()
                ArmedService.stop(context)
                app.settings.update { it.copy(relayUrl = "", room = "", paired = false) }
            }
        }) { Text("Unpair and clear the audit log") }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Text(label)
    Switch(checked = checked, onCheckedChange = onChecked)
}

private suspend fun save(app: BridgeApp, settings: AppSettings) {
    app.settings.update { settings }
    app.core.config = settings.toSafety()
}
