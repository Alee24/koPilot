package ke.co.kopilot.safety

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ke.co.kopilot.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dedicated Android foreground service for trip safety monitoring.
 * Remains active across screen switches, camera minimization, OBD drops, and engine stops,
 * subject to Android OS battery/force-stop policies.
 */
class DrivingSafetyMonitoringService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            _serviceStatus.value = "Stopped"
            _isRunning.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(
            title = "KoPilot Safety Monitoring Active",
            body = "Monitoring impact, rollover, swerve, and unexpected engine stops.",
        )
        runCatching {
            startForeground(NOTIFICATION_ID, notification)
            _isRunning.value = true
            _serviceStatus.value = "Active (Foreground Service)"
        }.onFailure { err ->
            _isRunning.value = false
            _serviceStatus.value = "Restricted: ${err.localizedMessage ?: "OS denied foreground service"}"
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        if (_serviceStatus.value.startsWith("Active")) {
            _serviceStatus.value = "Stopped by system or trip end"
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KoPilot Safety Monitoring",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notification while accident monitoring is active during a trip."
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kopilot_safety_monitoring"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_START = "ke.co.kopilot.safety.ACTION_START"
        private const val ACTION_STOP = "ke.co.kopilot.safety.ACTION_STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _serviceStatus = MutableStateFlow("Standby")
        val serviceStatus: StateFlow<String> = _serviceStatus.asStateFlow()

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, DrivingSafetyMonitoringService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { err ->
                _isRunning.value = false
                _serviceStatus.value = "Restricted: ${err.localizedMessage ?: "cannot start"}"
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, DrivingSafetyMonitoringService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            }
            _isRunning.value = false
            _serviceStatus.value = "Stopped"
        }
    }
}
