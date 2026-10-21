package com.nemoclaw.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Push fine-job dal gateway (via FCM): il server manda solo segnali
 * ("job finito", mai contenuti chat). Tap = apri l'app.
 */
class HermesPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        savePendingFcmToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val jobId = data["job_id"].orEmpty()
        val status = data["status"].orEmpty()
        val title = message.notification?.title ?: "HermesHub"
        val body = message.notification?.body
            ?: if (status == "failed") "Lavoro fallito sul server." else "Lavoro completato."
        showJobNotification(title, body, jobId)
    }

    private fun showJobNotification(title: String, body: String, jobId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("hermes_jobs", "Lavori Hermes", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = android.content.Intent.ACTION_MAIN
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            if (jobId.isNotBlank()) putExtra("hermes_job_id", jobId)
        }
        val pending = PendingIntent.getActivity(
            this, jobId.hashCode(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, "hermes_jobs")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(3000 + (jobId.hashCode() and 0x0FFF), notification)
    }
}
