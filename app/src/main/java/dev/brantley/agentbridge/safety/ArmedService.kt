package dev.brantley.agentbridge.safety

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.brantley.agentbridge.BridgeApp
import dev.brantley.agentbridge.R
import dev.brantley.agentbridge.data.toSafety
import dev.brantley.agentbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ArmedService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null
    private var linkStarted = false
    private val screenOff =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                val app = application as BridgeApp
                if (app.core.armed.value && app.core.config.disarmOnLock) shutdown()
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground()
                if (!linkStarted) {
                    linkStarted = true
                    connect()
                    watchIdle()
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        idleJob?.cancel()
        runCatching { unregisterReceiver(screenOff) }
        scope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        val app = application as BridgeApp
        scope.launch {
            val settings = app.settings.settings.first()
            app.core.config = settings.toSafety()
        }
        app.session.listen()
    }

    private fun watchIdle() {
        idleJob =
            scope.launch {
                while (isActive) {
                    delay(5_000)
                    val app = application as BridgeApp
                    if (app.core.armed.value && app.core.isExpired()) shutdown()
                }
            }
    }

    private fun shutdown() {
        val app = application as BridgeApp
        app.core.disarm()
        app.port.service?.cancelConfirm()
        stopForegroundAndSelf()
    }

    private fun startInForeground() {
        val type =
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ArmedService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notif_channel_desc)
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "agent_bridge_armed"
        private const val NOTIFICATION_ID = 41
        const val ACTION_STOP = "dev.brantley.agentbridge.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ArmedService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ArmedService::class.java).setAction(ACTION_STOP))
        }
    }
}
