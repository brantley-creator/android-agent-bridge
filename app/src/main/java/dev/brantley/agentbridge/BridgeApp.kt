package dev.brantley.agentbridge

import android.app.Application
import android.util.Log
import dev.brantley.agentbridge.a11y.SwitchingPort
import dev.brantley.agentbridge.data.SecureTokenStore
import dev.brantley.agentbridge.data.SettingsStore
import dev.brantley.agentbridge.net.BridgeLog
import dev.brantley.agentbridge.net.Rpc
import dev.brantley.agentbridge.net.SocketStatus
import dev.brantley.agentbridge.safety.AuditLog
import dev.brantley.agentbridge.safety.ConfirmAnswer
import dev.brantley.agentbridge.safety.ConfirmPrompter
import dev.brantley.agentbridge.safety.PhoneCore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

class BridgeApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var tokens: SecureTokenStore
        private set
    lateinit var audit: AuditLog
        private set
    lateinit var port: SwitchingPort
        private set
    lateinit var core: PhoneCore
        private set
    lateinit var rpc: Rpc
        private set
    val link = MutableStateFlow(SocketStatus.Disconnected)

    override fun onCreate() {
        super.onCreate()
        BridgeLog.sink = { priority, tag, message ->
            if (priority >= 6) Log.e(tag, message) else Log.d(tag, message)
        }
        settings = SettingsStore(this)
        tokens = SecureTokenStore(this)
        audit = AuditLog(File(filesDir, "audit.jsonl"))
        audit.load()
        port = SwitchingPort()
        core =
            PhoneCore(
                port = port,
                audit = audit,
                prompter =
                    ConfirmPrompter(
                        ask = { description ->
                            port.service?.confirm(description) ?: ConfirmAnswer.DENY
                        },
                    ),
            )
        rpc = Rpc(core)
    }
}
