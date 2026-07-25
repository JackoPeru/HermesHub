package com.nemoclaw.chat.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.nemoclaw.chat.MainActivity
import com.nemoclaw.chat.R

internal class JarvisSessionService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        try {
            startForeground(NOTIFICATION_ID, notification())
        } catch (error: SecurityException) {
            stopSelf()
            JarvisSessionController.serviceStartFailed(this, error)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> JarvisSessionController.pauseView(this)
            ACTION_QUESTIONS_ONLY -> JarvisSessionController.setMode(this, JarvisInitiativeMode.QUESTIONS_ONLY)
            ACTION_RESUME -> JarvisSessionController.resumeView(this)
            ACTION_STOP -> JarvisSessionController.stop(this)
        }
        if (intent?.action != ACTION_STOP) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val state = JarvisSessionController.state.value
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                data = "hermes-hub://jarvis".toUri()
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Hermes Jarvis Mode")
            .setContentText(notificationText(state))
            .setContentIntent(contentIntent)
            .setOngoing(state.active)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(Color.rgb(92, 225, 230))

        if (state.visionActive) builder.addAction(action("Pausa vista", ACTION_PAUSE, 1))
        else if (state.active) builder.addAction(action("Riprendi", ACTION_RESUME, 2))
        if (state.active && state.initiativeMode != JarvisInitiativeMode.QUESTIONS_ONLY) {
            builder.addAction(action("Solo domande", ACTION_QUESTIONS_ONLY, 3))
        }
        builder.addAction(action("Termina", ACTION_STOP, 4))
        return builder.build()
    }

    private fun action(label: String, action: String, requestCode: Int): NotificationCompat.Action {
        val pending = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, JarvisSessionService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    private fun notificationText(state: JarvisUiState): String {
        val vision = if (state.visionActive) "vista attiva" else "vista sospesa"
        val mode = when (state.initiativeMode) {
            JarvisInitiativeMode.QUESTIONS_ONLY -> "solo domande"
            JarvisInitiativeMode.ASSISTIVE -> "assistivo"
            JarvisInitiativeMode.PROACTIVE -> "proattivo"
        }
        return state.error?.let { "Errore: ${it.take(90)}" }
            ?: "${state.deviceStatus ?: "dispositivo"} · $vision · $mode"
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Jarvis Mode", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Sessione vocale e visiva temporanea di Hermes"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PAUSE = "com.nemoclaw.chat.jarvis.PAUSE"
        const val ACTION_QUESTIONS_ONLY = "com.nemoclaw.chat.jarvis.QUESTIONS_ONLY"
        const val ACTION_RESUME = "com.nemoclaw.chat.jarvis.RESUME"
        const val ACTION_STOP = "com.nemoclaw.chat.jarvis.STOP"
        const val ACTION_REFRESH = "com.nemoclaw.chat.jarvis.REFRESH"
        private const val CHANNEL_ID = "hermes_jarvis"
        private const val NOTIFICATION_ID = 8643
    }
}
