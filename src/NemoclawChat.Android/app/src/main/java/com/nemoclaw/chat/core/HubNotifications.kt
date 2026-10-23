package com.nemoclaw.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun notificationChatPrompt(item: HubNotification): String {
    return item.conversationPrompt.ifBlank {
        "Riprendiamo da questa notifica Hermes:\n\nTitolo: ${item.title}\nMessaggio: ${item.message}\n\nVoglio chiederti una cosa su questa notifica."
    }
}



internal fun ensureHermesNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        HERMES_NOTIFICATION_CHANNEL,
        "Hermes Hub",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Avvisi da cron e agenti Hermes."
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

class HermesNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            if (applicationContext.getSharedPreferences("notification_settings", Context.MODE_PRIVATE).getBoolean("dnd", false)) return Result.success()
            // App aperta: l'utente vede tutto dal vivo in chat; notificare
            // ora significa solo l'eco dei propri prompt appena inviati.
            if (isAppForeground()) return Result.success()
            ensureHermesNotificationChannel(applicationContext)
            val settings = loadSettings(applicationContext)
            val apiKey = loadGatewaySecret(applicationContext)
            val result = loadHubNotifications(settings, apiKey, unreadOnly = true)
            val prefs = applicationContext.getSharedPreferences(CURRENT_SETTINGS_PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet("seenHubNotifications", emptySet())?.toMutableSet() ?: mutableSetOf()
            var changed = false
            result.first.sortedBy { it.createdAt }.forEach { item ->
                if (item.archived || item.snoozedUntil > System.currentTimeMillis()) return@forEach
                if (!seen.contains(item.id) && showHermesSystemNotification(applicationContext, item)) {
                    seen.add(item.id)
                    changed = true
                }
            }
            if (changed) {
                prefs.edit { putStringSet("seenHubNotifications", seen.toList().takeLast(300).toSet()) }
            }
            // Check run indipendente dal service: se il processo e morto in Doze,
            // questo e l'unico a poter avvisare del completamento. Il service fa
            // lo stesso quando e vivo; il marker evita il doppio avviso.
            runCatching { notifyCompletedRuns(applicationContext, settings, apiKey) }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** Run terminali gia notificate (service o worker): una sola volta per runId. */
internal fun wasRunNotified(context: Context, runId: String): Boolean {
    val prefs = context.applicationContext.getSharedPreferences(CURRENT_SETTINGS_PREFS, Context.MODE_PRIVATE)
    return prefs.getStringSet("notifiedRunIds", emptySet())?.contains(runId) == true
}

internal fun markRunNotified(context: Context, runId: String) {
    runCatching {
        val prefs = context.applicationContext.getSharedPreferences(CURRENT_SETTINGS_PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("notifiedRunIds", emptySet())?.toMutableSet() ?: mutableSetOf()
        seen.add(runId)
        while (seen.size > 100) seen.remove(seen.first())
        prefs.edit { putStringSet("notifiedRunIds", seen) }
    }
}

internal suspend fun notifyCompletedRuns(context: Context, settings: AppSettings, apiKey: String?) =
    withContext(Dispatchers.IO) {
        val bindings = runCatching { loadActiveWorkBindings(context) }.getOrDefault(emptyMap())
        for ((_, binding) in bindings) {
            if (binding.runId.isBlank()) continue
            if (wasRunNotified(context, binding.runId)) continue
            val client = HermesRunClient(settings, apiKey, null, false)
            val (code, info) = runCatching { client.status(binding.runId) }.getOrElse { 0 to null }
            if (code !in 200..299 || info == null) continue
            val state = backgroundWorkStateFromRun(info, false)
            if (state != BackgroundWorkState.DONE_COMPLETED && state != BackgroundWorkState.DONE_FAILED) continue
            val done = state == BackgroundWorkState.DONE_COMPLETED
            val text = (if (done) info.output else info.error)?.take(220)?.ifBlank {
                if (done) "Risultato pronto in chat." else "Vedi dettagli in chat."
            } ?: if (done) "Risultato pronto in chat." else "Vedi dettagli in chat."
            val item = HubNotification(
                id = "run:${binding.runId}",
                title = if (done) "Risposta pronta" else "Lavoro fallito",
                message = text,
                kind = "run",
                severity = if (done) "info" else "high",
                source = "hermes-run",
                conversationPrompt = "",
                createdAt = System.currentTimeMillis(),
                readAt = 0L,
                category = "Run",
                priority = if (done) "Normale" else "Alta"
            )
            if (showHermesSystemNotification(context, item)) markRunNotified(context, binding.runId)
        }
    }

internal fun showHermesSystemNotification(context: Context, item: HubNotification): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(
        context,
        item.id.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val replyIntent = Intent(context, HermesNotificationReplyReceiver::class.java).putExtra("notification_id", item.id)
    val replyPending = PendingIntent.getBroadcast(context, item.id.hashCode() xor 0x4862, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    val remoteInput = androidx.core.app.RemoteInput.Builder("hermes_reply").setLabel("Rispondi a Hermes").build()
    val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_launcher_monochrome, "Rispondi", replyPending).addRemoteInput(remoteInput).build()
    val notification = NotificationCompat.Builder(context, HERMES_NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(item.title.ifBlank { "Hermes" })
        .setContentText(item.message.take(180))
        .setStyle(NotificationCompat.BigTextStyle().bigText(item.message.take(1200)))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setContentIntent(pending)
        .addAction(replyAction)
        .setAutoCancel(true)
        .setGroup(item.automationId.ifBlank { "hermes-${item.category}" })
        .setOngoing(item.kind.equals("long_run", true) && item.readAt <= 0L)
        .setPriority(when (notificationPriorityRank(item.priority)) { 4 -> NotificationCompat.PRIORITY_MAX; 3 -> NotificationCompat.PRIORITY_HIGH; 1 -> NotificationCompat.PRIORITY_LOW; else -> NotificationCompat.PRIORITY_DEFAULT })
        .build()
    NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification)
    return true
}

internal fun appVersion(context: Context): String {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "debug"
    } catch (_: Exception) {
        "debug"
    }
}

internal fun openAndroidIntent(context: Context, intent: Intent): Boolean {
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}

