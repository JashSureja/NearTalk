package com.neartalk.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class NearTalkService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: NearbyVoiceManager
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground(notification("Scanning for nearby devices"))
        acquireLocks()

        manager = NearTalkRuntime.manager(applicationContext)
        serviceScope.launch {
            manager.state
                .map(::notificationText)
                .distinctUntilChanged()
                .collect(::updateNotification)
        }
        manager.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        manager.stop()
        serviceScope.cancel()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(notification: Notification) {
        val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceTypes)
    }

    private fun notificationText(state: NearbyUiState): String = when {
        state.peers.isEmpty() -> "Scanning for nearby devices"
        state.voiceActivationEnabled && state.isTalking -> "Voice detected - talking to ${state.peers.size}"
        state.voiceActivationEnabled -> "Listening - connected to ${state.peers.size}"
        state.isTalking -> "Talking to ${state.peers.size}"
        else -> "Connected to ${state.peers.size} nearby ${if (state.peers.size == 1) "device" else "devices"}"
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, NearTalkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("NearTalk is active")
            .setContentText(text)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopService)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active voice connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps nearby voice communication active when the screen is off"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NearTalk::voice-session",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "NearTalk::nearby-session",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        cpuLock?.takeIf { it.isHeld }?.release()
        cpuLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private companion object {
        const val CHANNEL_ID = "neartalk_active_voice"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.neartalk.app.action.STOP"
    }
}
