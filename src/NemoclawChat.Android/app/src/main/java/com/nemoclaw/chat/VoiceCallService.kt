package com.nemoclaw.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class VoiceCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val wakeMode = intent?.getStringExtra("mode") == "wake"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("hermes_voice_call", "Voce Hermes", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 8642, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "hermes_voice_call")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (wakeMode) "Wake word Hermes attiva" else "Chiamata Hermes attiva")
            .setContentText(if (wakeMode) "Pronuncia la frase scelta per avviare una chiamata." else "Microfono e risposta vocale continuano in background.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(8642, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(8642, notification)
        return START_STICKY
    }
}
