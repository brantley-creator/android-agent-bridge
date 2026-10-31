package dev.brantley.agentbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.brantley.agentbridge.BridgeApp
import dev.brantley.agentbridge.data.toSafety
import dev.brantley.agentbridge.safety.ArmedService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private var pendingArm by mutableStateOf(false)

    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingArm) armNow()
            pendingArm = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentBridgeTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onArm = { requestArm() },
                            onDisarm = { ArmedService.stop(this@MainActivity) },
                            onAudit = { nav.navigate("audit") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("audit") { AuditScreen(onBack = { nav.popBackStack() }) }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    private fun requestArm() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingArm = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        armNow()
    }

    private fun armNow() {
        val app = application as BridgeApp
        val settings = runBlocking { app.settings.settings.first() }
        if (!settings.paired) return
        app.core.config = settings.toSafety()
        app.core.arm()
        ArmedService.start(this)
    }
}
