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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
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
        // Rehydrate: ricarica i binding persistiti così il polling riparte anche
        // dopo kill del processo (il lavoro vive sul gateway, qui solo riaggancio).
        runCatching {
            val bindings = loadActiveWorkBindings(this)
            for (binding in bindings.values) {
                val restoredRunId = binding.runId.trim()
                val restoredCid = binding.conversationId.trim()
                if (restoredRunId.isBlank() || restoredCid.isBlank()) continue
                val already = tracked[restoredRunId]
                if (already == null) {
                    tracked[restoredRunId] = Tracked(
                        restoredCid,
                        binding.goal,
                        null,
                        false
                    )
                }
            }
            if (tracked.isNotEmpty()) {
                val ids = tracked.keys.toList()
                for (restoredId in ids) startPoller(restoredId)
                Log.i(TAG, "rehydrate: ${tracked.size} binding ripristinati")
            }
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
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        runCatching { cancelAllPerRunNotifications() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun clientFor(runId: String): HermesRunClient? {
        val metaSnapshot = tracked[runId] ?: return null
        val settingsSnapshot = runCatching { loadSettings(this) }.getOrNull() ?: return null
        val keySnapshot = runCatching { loadGatewaySecret(this) }.getOrNull()
        return HermesRunClient(settingsSnapshot, keySnapshot, metaSnapshot.profile, metaSnapshot.multiplex)
    }

    private fun clientForMeta(meta: Tracked): HermesRunClient? {
        val settingsSnapshot = runCatching { loadSettings(this) }.getOrNull() ?: return null
        val keySnapshot = runCatching { loadGatewaySecret(this) }.getOrNull()
        return HermesRunClient(settingsSnapshot, keySnapshot, meta.profile, meta.multiplex)
    }

    private fun startPoller(runId: String) {
        if (pollers.containsKey(runId)) return
        pollers[runId] = scope.launch {
            var backoffMs = POLL_BASE_MS
            var consecutiveErrors = 0
            while (true) {
                val clientSnapshot = clientFor(runId)
                if (clientSnapshot == null) {
                    Log.w(TAG, "poll runId=$runId code=noclient nessun binding, esco")
                    break
                }
                try {
                    val (code, info) = clientSnapshot.status(runId)
                    val infoSnapshot = info
                    when {
                        code == 404 -> {
                            val cidSnapshot = tracked[runId]?.conversationId
                            notifyFinished(runId, cidSnapshot, "Run non trovato", "Il gateway non la conosce più.")
                            untrack(runId, clearBinding = true)
                            break
                        }
                        code !in 200..299 || infoSnapshot == null -> {
                            consecutiveErrors++
                            Log.w(TAG, "poll runId=$runId code=$code gateway non pronto, backoff=${backoffMs}ms")
                            maybeScheduleDozeFallback(runId)
                            delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceAtMost(POLL_MAX_MS)
                            if (consecutiveErrors > 40) {
                                Log.w(TAG, "poll runId=$runId code=$code troppi errori, esco")
                                break
                            }
                            continue
                        }
                        else -> {
                            consecutiveErrors = 0
                            backoffMs = POLL_BASE_MS
                            val approval = parseRunApprovalPayload(infoSnapshot.raw, runId)
                            when (backgroundWorkStateFromRun(infoSnapshot, approval != null)) {
                                BackgroundWorkState.ACTIVE -> updateProgress(runId, "Elaborazione nel gateway…")
                                BackgroundWorkState.WAITING_FOR_APPROVAL -> {
                                    updateProgress(runId, "In attesa di approvazione…")
                                    if (approval != null) {
                                        autoApproveIfEnabled(runId, approval)
                                        val cidForApproval = tracked[runId]?.conversationId
                                        notifyApproval(runId, cidForApproval, approval)
                                    }
                                }                                BackgroundWorkState.DONE_COMPLETED -> {
                                    val conversationId = tracked[runId]?.conversationId
                                    val outputSnapshot = infoSnapshot.output?.take(220)?.ifBlank { "Risultato pronto in chat." } ?: "Risultato pronto in chat."
                                    notifyFinished(runId, conversationId, "Lavoro completato", outputSnapshot)
                                    untrack(runId, clearBinding = false)
                                    break
                                }
                                BackgroundWorkState.DONE_FAILED -> {
                                    val conversationId = tracked[runId]?.conversationId
                                    val errorSnapshot = infoSnapshot.error?.take(220)?.ifBlank { "Vedi dettagli in chat." } ?: "Vedi dettagli in chat."
                                    notifyFinished(runId, conversationId, "Lavoro fallito", errorSnapshot)
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
                } catch (error: Exception) {
                    consecutiveErrors++
                    Log.w(TAG, "poll runId=$runId code=exc err=${error.message} backoff=${backoffMs}ms")
                    maybeScheduleDozeFallback(runId)
                    if (consecutiveErrors > 40) {
                        Log.w(TAG, "poll runId=$runId code=abort troppi errori consecutivi")
                        break
                    }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(POLL_MAX_MS)
                    continue
                }
                delay(POLL_BASE_MS)
            }
            pollers.remove(runId)
        }
    }

    private fun maybeScheduleDozeFallback(runId: String) {
        runCatching {
            val powerManager = getSystemService(PowerManager::class.java) ?: return
            if (powerManager.isDeviceIdleMode) {
                Log.i(TAG, "poll runId=$runId code=doze pianifico fallback WorkManager")
                scheduleHermesRunFallbackWorker(applicationContext, runId)
            }
        }
    }

    private fun stopRun(runId: String) {
        val cleanRunId = runId.trim()
        if (cleanRunId.isBlank()) return
        // Idempotente per runId: il secondo stop è no-op (anti-race da doppie notifiche/azioni).
        if (!tryClaimStopRun(cleanRunId)) {
            Log.w(TAG, "stop runId=$cleanRunId code=skip già in arresto")
            return
        }
        // Rimozione SINCRONA prima del POST: untrack + cancellazione binding subito,
        // così un poller concorrente non può ri-creare stato dopo lo stop.
        pollers.remove(cleanRunId)?.cancel()
        notifiedApprovals.remove(cleanRunId)
        val metaSnapshot = tracked.remove(cleanRunId)
        if (metaSnapshot != null) {
            runCatching { clearActiveWorkBinding(this@HermesWorkService, metaSnapshot.conversationId) }
        }
        cancelNotification(tag(cleanRunId))
        val metaForStop = metaSnapshot
        if (metaForStop == null) {
            stopIfIdle()
            return
        }
        val clientSnapshot = clientForMeta(metaForStop)
        if (clientSnapshot == null) {
            stopIfIdle()
            return
        }
        // Stop retryabile in background; stopSelf solo dopo conferma o retry esauriti.
        scope.launch {
            var lastCode = 0
            var confirmed = false
            for (attempt in 1..STOP_MAX_ATTEMPTS) {
                try {
                    val (code, _) = clientSnapshot.stop(cleanRunId)
                    lastCode = code
                    Log.i(TAG, "stop runId=$cleanRunId code=$code tentativo=$attempt")
                    if (code in 200..299 || code == 404) {
                        confirmed = true
                        break
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "stop runId=$cleanRunId code=exc tentativo=$attempt err=${error.message}")
                }
                if (attempt < STOP_MAX_ATTEMPTS) delay(2000L * attempt)
            }
            if (!confirmed) {
                Log.w(TAG, "stop runId=$cleanRunId code=$lastCode retry esauriti")
            }
            stopIfIdle()
        }
    }

    private fun stopAll() {
        pollers.values.forEach { it.cancel() }
        pollers.clear()
        val idsSnapshot = tracked.keys.toList()
        tracked.clear()
        notifiedApprovals.clear()
        for (idSnapshot in idsSnapshot) cancelNotification(tag(idSnapshot))
        runCatching { cancelAllPerRunNotifications() }
        stopSelf()
    }

    private fun untrack(runId: String, clearBinding: Boolean) {
        pollers.remove(runId)?.cancel()
        notifiedApprovals.remove(runId)
        val metaSnapshot = tracked.remove(runId)
        if (clearBinding) {
            val cidSnapshot = metaSnapshot?.conversationId
            if (cidSnapshot != null) {
                runCatching { clearActiveWorkBinding(this, cidSnapshot) }
            }
        }
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

    private fun openAppIntent(conversationId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!conversationId.isNullOrBlank()) {
                data = "hermes-hub://chat?conversation=$conversationId".toUri()
            }
        }
        return PendingIntent.getActivity(
            this, conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

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
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateProgress(runId: String, text: String) {
        val metaSnapshot = tracked[runId] ?: return
        val goalTitle = metaSnapshot.goal.take(80).ifBlank { "Hermes al lavoro" }
        notifyPerRun(runId, metaSnapshot.conversationId, goalTitle, text, ongoing = true)
    }

    private fun notifyFinished(runId: String, conversationId: String?, title: String, text: String) {
        notifyPerRun(runId, conversationId, title, text, ongoing = false)
    }

    /**
     * UN SOLO helper per-run: stesso tag `work_<runId>`, stesso id per progress,
     * finish e approval (deeplink identico). Evita notifiche ongoing orfane.
     */
    private fun notifyPerRun(
        runId: String,
        conversationId: String?,
        title: String,
        text: String,
        ongoing: Boolean,
        highPriority: Boolean = false
    ) {
        val cidSnapshot = conversationId?.trim().orEmpty()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openAppIntent(cidSnapshot.ifBlank { null }))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (ongoing) {
            builder.addAction(stopAction(runId))
        }
        if (highPriority) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        }
        getSystemService(NotificationManager::class.java).notify(tag(runId), NOTIFICATION_ID, builder.build())
    }

    /**
     * Auto-approvazione in background (solo opt-in da impostazioni, mai deny).
     * Single-flight per approvalId + no-downgrade: se il server ha gia' risolto
     * o offre solo un livello inferiore, il POST non parte (409 o skip).
     */
    private fun autoApproveIfEnabled(runId: String, approval: HermesRunApprovalRequest) {
        val settingsSnapshot = runCatching { loadSettings(this) }.getOrNull() ?: return
        val metaSnapshot = tracked[runId] ?: return
        val mode = resolveAutoApproveMode(
            metaSnapshot.profile,
            runCatching { loadBotAutoApproveMap(this) }.getOrDefault(emptyMap()),
            settingsSnapshot.autoApprove
        )
        if (mode == "off" || mode.isBlank()) return
        val choice = pickAutoApprovalChoice(approval.choices, mode)
        if (choice == null) {
            Log.w(TAG, "auto-approve runId=$runId code=nodowngrade mode=$mode offered=${approval.choices} nessuna auto-approvazione")
            return
        }
        val claimId = approval.approvalId.ifBlank { approval.requestId }.trim()
        if (claimId.isBlank()) {
            Log.w(TAG, "auto-approve runId=$runId code=noid senza id, skip")
            return
        }
        if (!tryClaimApproval(claimId)) {
            Log.w(TAG, "auto-approve runId=$runId code=skip già reclamata id=$claimId")
            return
        }
        val keySnapshot = runCatching { loadGatewaySecret(this) }.getOrNull()
        val clientSnapshot = HermesRunClient(settingsSnapshot, keySnapshot, metaSnapshot.profile, metaSnapshot.multiplex)
        val requestIdSnapshot = approval.requestId.takeIf { it.isNotBlank() }
        val toolSnapshot = approval.tool.take(60)
        scope.launch {
            val (code, _) = runCatching {
                clientSnapshot.approval(runId, choice, requestIdSnapshot)
            }.getOrElse { 0 to "" }
            if (code in 200..299) {
                updateProgress(runId, "Auto-approvato ($choice): $toolSnapshot")
            } else {
                Log.w(TAG, "auto-approve runId=$runId code=$code fallita per id=$claimId")
                releaseApprovalClaim(claimId)
            }
        }
    }

    private fun notifyApproval(runId: String, conversationId: String?, approval: HermesRunApprovalRequest) {
        val key = "${approval.approvalId}::${approval.requestId}"
        if (notifiedApprovals[runId] == key) return
        notifiedApprovals[runId] = key
        val detailParts = listOf(approval.tool, approval.command, approval.description)
        val detail = detailParts
            .filter { it.isNotBlank() }.joinToString(" — ").take(220)
            .ifBlank { "Decisione richiesta." }
        notifyPerRun(runId, conversationId, "Hermes chiede approvazione", detail, ongoing = false, highPriority = true)
    }

    private fun cancelNotification(tag: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.cancel(tag, NOTIFICATION_ID)
            // Pulizia legacy: id/tag separati usati in passato (nessuna orfana).
            manager.cancel(tag, NOTIFICATION_ID + 1)
            manager.cancel(tag + "_approval", NOTIFICATION_ID + 2)
        }
    }

    private fun cancelAllPerRunNotifications() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            val idsSnapshot = tracked.keys.toList()
            for (idSnapshot in idsSnapshot) {
                manager.cancel(tag(idSnapshot), NOTIFICATION_ID)
                manager.cancel(tag(idSnapshot), NOTIFICATION_ID + 1)
                manager.cancel(tag(idSnapshot) + "_approval", NOTIFICATION_ID + 2)
            }
            // Niente ongoing orfane alla distruzione: rimuovi tutto.
            manager.cancelAll()
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
        private const val TAG = "HermesWorkService"
        private const val CHANNEL_ID = "hermes_work"
        private const val NOTIFICATION_ID = 8644
        private const val POLL_BASE_MS = 10_000L
        private const val POLL_MAX_MS = 60_000L
        private const val STOP_MAX_ATTEMPTS = 3

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
