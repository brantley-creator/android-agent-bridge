package dev.brantley.agentbridge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.brantley.agentbridge.BridgeApp
import java.text.DateFormat
import java.util.Date

@Composable
fun AuditScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as BridgeApp
    var entries by remember { mutableStateOf(app.audit.entries.asReversed()) }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Audit", style = MaterialTheme.typography.headlineMedium)
        Text("Typed text is stored as a length only. Unpair clears this log.")
        TextButton(onClick = { entries = app.audit.entries.asReversed() }) { Text("Refresh") }
        if (entries.isEmpty()) {
            Text("No commands yet.")
        }
        LazyColumn {
            items(entries) { entry ->
                val whenText = DateFormat.getDateTimeInstance().format(Date(entry.ts))
                Text(
                    "$whenText  ${entry.method}  ${entry.pkg}  ${entry.decision}  ${entry.result}" +
                        (entry.typedLength?.let { "  typed $it chars" } ?: ""),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Text(entry.target, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
