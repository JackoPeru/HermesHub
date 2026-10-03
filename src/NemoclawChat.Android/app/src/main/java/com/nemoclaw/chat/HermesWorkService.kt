package com.nemoclaw.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Lavoro Hermes in background.
 *
 * Il lavoro vero vive sul gateway (la run continua anche a client morto grazie a
 * `continue_on_disconnect`): questo service NON esegue il lavoro, tiene solo vivo
 * il polling di avanzamento con notifica visibile, avvisa a completamento/approval
 * e offre lo Stop reale. Se Android lo uccide, alla riapertura l'app riaggancia
 * la run dal binding persistito: niente dipende dalla sopravvivenza del client.
 */
internal class HermesWorkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollers = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private data class Tracked(val conversationId: String, val goal: String, val profile: String?, val multiplex: Boolean)
    private val tracked = ConcurrentHashMap<String, Tracked>()
    private val notifiedApprovals = ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        try {
            startForegroundInternal(progressNotification("Hermes al lavoro…", "Connessione al gateway…"))
        } catch (error: SecurityException) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
                if (runId.isNotBlank() && conversationId.isNotBlank()) {
                    tracked[runId] = Tracked(
                        conversationId,
                        intent.getStringExtra(EXTRA_GOAL).orEmpty(),
                        intent.getStringExtra(EXTRA_PROFILE)?.takeIf { it.isNotBlank() },
                        intent.getBooleanExtra(EXTRA_MULTIPLEX, false)
                    )
                    startForegroundInternal(progressNotification(
                        "Hermes al lavoro",
                        intent.getStringExtra(EXTRA_GOAL)?.takeIf { it.isNotBlank() } ?: "Avanzamento nel gateway…"
                    ))
                    startPoller(runId)
                }
            }
            ACTION_STOP -> {
                val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
                if (runId.isNotBlank()) stopRun(runId) else stopAll()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun clientFor(runId: String): HermesRunClient? {
        val meta = tracked[runId] ?: return null
        val settings = runCatching { loadSettings(this) }.getOrNull() ?: return null
        val key = runCatching { loadGatewaySecret(this) }.getOrNull()
        return HermesRunClient(settings, key, meta.profile, meta.multiplex)
    }

    private fun startPoller(runId: String) {
        if (pollers.containsKey(runId)) return
        pollers[runId] = scope.launch {
            var failures = 0
            while (true) {
                val client = clientFor(runId) ?: break
                try {
                    val (code, info) = client.status(runId)
                    failures = 0
                    when {
                        code == 404 -> {
                            notifyFinished(runId, "Run non trovato", "Il gateway non la conosce più.")
                            untrack(runId, clearBinding = true)
                            break
                        }
                        code !in 200..299 || info == null -> {
                            updateProgress(runId, "In attesa del gateway…")
                        }
                        else -> {
                            val approval = parseRunApprovalPayload(info.raw, runId)
                            when (backgroundWorkStateFromRun(info, approval != null)) {
                                BackgroundWorkState.ACTIVE -> updateProgress(runId, "Elaborazione nel gateway…")
                                BackgroundWorkState.WAITING_FOR_APPROVAL -> {
                                    updateProgress(runId, "In attesa di approvazione…")
                                    if (approval != null) {
                                        autoApproveIfEnabled(runId, approval)
                                        notifyApproval(runId, approval)
                                    }
                                }
                                BackgroundWorkState.DONE_COMPLETED -> {
                                    notifyFinished(runId, "Lavoro completato",
                                        info.output?.take(220)?.ifBlank { "Risultato pronto in chat." } ?: "Risultato pronto in chat.")
                                    untrack(runId, clearBinding = false)
                                    break
                                }
                                BackgroundWorkState.DONE_FAILED -> {
                                    notifyFinished(runId, "Lavoro fallito",
                                        info.error?.take(220)?.ifBlank { "Vedi dettagli in chat." } ?: "Vedi dettagli in chat.")
                                    untrack(runId, clearBinding = false)
                                    break
                                }
                                BackgroundWorkState.DONE_CANCELLED -> {
                                    untrack(runId, clearBinding = true)
                                    break
                                }
                                BackgroundWorkState.GONE, BackgroundWorkState.UNKNOWN -> {
                                    updateProgress(runId, "Stato run incerto, ricontrollo…")
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    failures++
                    if (failures > 40) break
                }
                delay(POLL_MS)
            }
        }
    }

    private fun stopRun(runId: String) {
        pollers.remove(runId)?.cancel()
        notifiedApprovals.remove(runId)
        scope.launch {
            runCatching {
                clientFor(runId)?.stop(runId)
                val meta = tracked[runId]
                if (meta != null) clearActiveWorkBinding(this@HermesWorkService, meta.conversationId)
            }
            cancelNotification(tag(runId))
            tracked.remove(runId)
            stopIfIdle()
        }
    }

    private fun stopAll() {
        pollers.values.forEach { it.cancel() }
        pollers.clear()
        tracked.clear()
        stopSelf()
    }

    private fun untrack(runId: String, clearBinding: Boolean) {
        pollers.remove(runId)?.cancel()
        notifiedApprovals.remove(runId)
        if (clearBinding) {
            tracked[runId]?.let { clearActiveWorkBinding(this, it.conversationId) }
        }
        tracked.remove(runId)
        cancelNotification(tag(runId))
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (tracked.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun tag(runId: String) = "work_$runId"

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopAction(runId: String): NotificationCompat.Action {
        val pending = PendingIntent.getService(
            this, runId.hashCode(),
            Intent(this, HermesWorkService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_RUN_ID, runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(0, "Stop", pending).build()
    }

    private fun progressNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateProgress(runId: String, text: String) {
        val meta = tracked[runId] ?: return
        val goal = meta.goal.take(80).ifBlank { "Hermes al lavoro" }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(goal)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(stopAction(runId))
            .build()
        getSystemService(NotificationManager::class.java).notify(tag(runId), NOTIFICATION_ID, notification)
    }

    private fun notifyFinished(runId: String, title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(tag(runId), NOTIFICATION_ID + 1, notification)
    }

    /**
     * Auto-approvazione in background (solo opt-in da impostazioni, mai deny).
     * Idempotente per approvalId: se il server ha gia' risolto, il POST fallisce
     * con 409 e non succede niente.
     */
    private fun autoApproveIfEnabled(runId: String, approval: HermesRunApprovalRequest) {
        val settings = runCatching { loadSettings(this) }.getOrNull() ?: return
        val mode = settings.autoApprove
        if (mode == "off" || mode.isBlank()) return
        val choice = pickAutoApprovalChoice(approval.choices, mode) ?: return
        val meta = tracked[runId] ?: return
        val key = runCatching { loadGatewaySecret(this) }.getOrNull()
        val client = HermesRunClient(settings, key, meta.profile, meta.multiplex)
        scope.launch {
            val (code, _) = runCatching {
                client.approval(runId, choice, approval.requestId.takeIf { it.isNotBlank() })
            }.getOrElse { 0 to "" }
            if (code in 200..299) {
                updateProgress(runId, "Auto-approvato ($choice): ${approval.tool.take(60)}")
            }
        }
    }

    private fun notifyApproval(runId: String, approval: HermesRunApprovalRequest) {        val key = "${approval.approvalId}::${approval.requestId}"
        if (notifiedApprovals[runId] == key) return
        notifiedApprovals[runId] = key
        val detail = listOf(approval.tool, approval.command, approval.description)
            .filter { it.isNotBlank() }.joinToString(" — ").take(220)
            .ifBlank { "Decisione richiesta." }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Hermes chiede approvazione")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(tag(runId) + "_approval", NOTIFICATION_ID + 2, notification)
    }

    private fun cancelNotification(tag: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(tag, NOTIFICATION_ID)
            getSystemService(NotificationManager::class.java).cancel(tag + "_approval", NOTIFICATION_ID + 2)
        }
    }

    private fun startForegroundInternal(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Hermes al lavoro", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avanzamento dei lavori Hermes in background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.nemoclaw.chat.work.START"
        const val ACTION_STOP = "com.nemoclaw.chat.work.STOP"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_MULTIPLEX = "multiplex"
        private const val CHANNEL_ID = "hermes_work"
        private const val NOTIFICATION_ID = 8644
        private const val POLL_MS = 10_000L

        fun start(context: Context, binding: ActiveWorkBinding, profile: String?, multiplex: Boolean) {
            // Su Android 12+ l'avvio da background puo' essere negato: mai far fallire il chiamante.
            // Il binding resta comunque persistito e la riapertura riaggancia la run.
            runCatching {
                val intent = Intent(context, HermesWorkService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RUN_ID, binding.runId)
                    .putExtra(EXTRA_CONVERSATION_ID, binding.conversationId)
                    .putExtra(EXTRA_GOAL, binding.goal)
                    .putExtra(EXTRA_PROFILE, profile)
                    .putExtra(EXTRA_MULTIPLEX, multiplex)
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context, runId: String) {
            runCatching {
                context.startService(
                    Intent(context, HermesWorkService::class.java)
                        .setAction(ACTION_STOP)
                        .putExtra(EXTRA_RUN_ID, runId)
                )
            }
        }
    }
}

/**
 * Avvia il tracking background per un binding se l'utente lo consente.
 * Non lancia mai eccezioni verso il chiamante (streaming già delicato).
 */
internal fun maybeStartBackgroundWork(
    context: Context,
    settings: AppSettings,
    binding: ActiveWorkBinding,
    profile: String?,
    multiplex: Boolean,
    requestNotificationsPermission: (() -> Unit)? = null
) {
    if (!settings.backgroundWork) return
    if (requestNotificationsPermission != null && Build.VERSION.SDK_INT >= 33) {
        val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) runCatching { requestNotificationsPermission() }
    }
    runCatching { HermesWorkService.start(context, binding, profile, multiplex) }
}
