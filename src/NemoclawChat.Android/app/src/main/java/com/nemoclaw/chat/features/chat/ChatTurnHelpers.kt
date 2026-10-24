package com.nemoclaw.chat

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nemoclaw.chat.core.WorkLimits
import org.json.JSONObject
import kotlin.math.abs

/**
 * Id stabile riga transcript per dedup live: prova id/message_id/seq nel raw
 * (int diretto o stringa numerica), poi created_at + hash del contenuto,
 * infine fallback deterministico positivo da indice. Mai 0.
 */
internal fun stableMessageId(raw: JSONObject?, fallbackIndex: Int): Int {
    if (raw != null) {
        for (key in arrayOf("id", "message_id", "seq")) {
            if (raw.isNull(key)) continue
            val value = raw.opt(key) ?: continue
            val asInt: Int? = when (value) {
                is Number -> value.toInt().takeIf { it != 0 }
                is String -> value.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it != 0 }
                else -> null
            }
            if (asInt != null) return asInt
        }
        val created = raw.optString("created_at", "").orEmpty().trim()
        if (created.isNotEmpty()) {
            val hash = (created + "|" + raw.optString("role", "") + "|" + raw.optString("content", "").take(256)).hashCode()
            if (hash != 0) {
                if (hash == Int.MIN_VALUE) return Int.MAX_VALUE
                val abs = abs(hash)
                if (abs != 0) return abs
            }
        }
    }
    return 1_000_000 + fallbackIndex.coerceAtLeast(0)
}

/** Finestra freschezza live bot: righe nuove o fresche = in esecuzione. */
internal const val BOT_LIVE_FRESH_WINDOW_MS = 240_000L
/** Tolleranza skew orologi telefono/server nel confronto eco. */
internal const val BOT_LIVE_ECHO_TOLERANCE_MS = 30_000L

/**
 * La riga piu nuova indica VERO lavoro esterno solo se e fresca E piu
 * nuova della fine del nostro ultimo turno locale (tolleranza skew).
 * Senza questo, la nostra risposta appena arrivata (freschissima) tiene
 * banner e stop accesi per minuti dopo ogni turno. Puro e testabile.
 */
internal fun isExternalBotWork(freshTsMs: Long, nowMs: Long, localTurnEndMs: Long): Boolean {
    if (freshTsMs <= 0L) return false
    if (nowMs - freshTsMs >= BOT_LIVE_FRESH_WINDOW_MS) return false
    if (localTurnEndMs > 0L && freshTsMs <= localTurnEndMs + BOT_LIVE_ECHO_TOLERANCE_MS) return false
    return true
}

/**
 * Morte di rete contro errore logico: solo la prima merita il messaggio
 * "stream scollegato, il gateway potrebbe continuare". Pura e testabile.
 */
internal fun isTransportFailure(ex: Exception): Boolean {
    if (ex is java.io.IOException) return true
    val message = ex.message.orEmpty()
    return message.contains("connection abort", ignoreCase = true) ||
        message.contains("software caused connection abort", ignoreCase = true) ||
        message.contains("connection reset", ignoreCase = true) ||
        message.contains("broken pipe", ignoreCase = true) ||
        message.contains("unexpected end of stream", ignoreCase = true) ||
        message.contains("unable to resolve host", ignoreCase = true) ||
        message.contains("timeout", ignoreCase = true) ||
        message.contains("timed out", ignoreCase = true)
}

/**
 * Testo finale onesto quando il turno muore senza contenuto: mai silenzio,
 * mai il placeholder "sta lavorando" lasciato appeso. Pura e testabile.
 */
internal fun silentTurnEndMessage(transportDetached: Boolean): String =
    if (transportDetached) {
        "Connessione persa senza risposta. Il lavoro potrebbe continuare sul gateway: riapri la chat o tocca Riprova."
    } else {
        "Turno finito senza risposta né errore. Tocca Riprova."
    }

/**
 * Stats parziali oneste a fine anomala (timeout/stop/disconnect senza Done):
 * solo durata reale sopra 1s, mai token inventati. Pura e testabile.
 */
internal fun partialTurnStats(existing: ChatStreamStats?, startedAtNs: Long, nowNs: Long): ChatStreamStats? {
    if (existing != null) return existing
    if (startedAtNs <= 0L) return null
    val elapsedMs = (nowNs - startedAtNs) / 1_000_000.0
    if (!elapsedMs.isFinite() || elapsedMs < 1000.0) return null
    return ChatStreamStats(totalMs = elapsedMs)
}

/**
 * Fail-closed bot remoto: connessione secondaria senza secret salvato = invio vietato.
 * Pura/testabile: true solo se remota E chiave assente/blank (mai NPE su apiKey null).
 */
internal fun isRemoteBotMissingSecret(botConnectionId: String?, apiKey: String?): Boolean {
    val remote = !botConnectionId.isNullOrBlank() && !botConnectionId.equals("primary", true)
    return remote && apiKey.isNullOrBlank()
}

/** Messaggio onesto per invio bloccato fail-closed (chiave connessione remota assente). */
internal fun remoteBotMissingSecretMessage(): String =
    "Connessione remota senza chiave salvata: invio bloccato. Riconfigura la chiave della connessione e riprova."

/** Durata max nota vocale: 10 minuti (stop nativo MediaRecorder). */
internal const val VOICE_NOTE_MAX_DURATION_MS = 10 * 60 * 1000

/** Dimensione max nota vocale: 100 MB (stop nativo MediaRecorder). */
internal const val VOICE_NOTE_MAX_FILE_BYTES = 100L * 1024L * 1024L


internal fun executeSlashCommand(
    command: SlashCommand,
    setMode: (String) -> Unit,
    clear: () -> Unit,
    setDraft: (String) -> Unit,
    addAction: (String, String) -> Unit,
    onSwitchTab: (Tab) -> Unit
) {
    when (command.action) {
        SlashAction.ModeChat -> {
            setMode("Chat"); addAction("Modalita", "Chat attiva.")
        }
        SlashAction.ModeAgent -> {
            setMode("Agente"); addAction("Modalita", "Agente attivo.")
        }
        SlashAction.Clear -> clear()
        SlashAction.Help -> {
            val lines = slashCommands().distinctBy { it.display }.joinToString("\n") { "${it.display} — ${it.title}" }
            addAction("Comandi", lines)
        }
        SlashAction.PromptSetup -> setDraft("Preparami i passaggi per avviare Hermes Agent API Server su Tailscale/LAN.")
        SlashAction.PromptVisual -> setDraft("Spiega con blocchi visuali (tabella, diagramma, chart o callout) mantenendo output_text completo.")
        SlashAction.PromptResearch -> setDraft("Esegui una ricerca approfondita citando fonti e chiedendo conferma prima di uscire dalla LAN/VPN.")
        SlashAction.PromptWeb -> setDraft("Cerca sul web informazioni aggiornate, chiedendo conferma prima di uscire dalla LAN/VPN.")
        SlashAction.PromptImage -> setDraft("Prepara una richiesta di generazione immagine, chiedendo conferma prima di usare tool esterni.")
        SlashAction.PromptVideo -> setDraft("Crea un job video per la sezione Video di Hermes Hub: ")
        SlashAction.PromptNews -> setDraft("Crea un articolo per la sezione News di Hermes Hub: ")
        SlashAction.Health -> setDraft("Controlla stato Hermes, modello disponibile e capabilities API.")
        SlashAction.OpenServer -> onSwitchTab(Tab.Server)
        SlashAction.OpenHardware -> onSwitchTab(Tab.Hardware)
        SlashAction.OpenCron -> onSwitchTab(Tab.Cron)
        SlashAction.OpenArchive -> onSwitchTab(Tab.Archive)
        SlashAction.OpenVideo -> onSwitchTab(Tab.Video)
        SlashAction.OpenNews -> onSwitchTab(Tab.News)
        SlashAction.OpenSettings -> onSwitchTab(Tab.Settings)
        SlashAction.OpenAbout -> onSwitchTab(Tab.Profile)
    }
}


internal fun persistChatOverrides(context: Context, state: ChatStateHolder, preserveRemoteContinuity: Boolean = false, botIdentityKey: String = "") {
    val cid = state.activeConversationId ?: return
    runCatching {
        val current = loadConversation(context, cid) ?: return
        saveConversationSnapshot(
            context = context,
            conversationId = cid,
            mode = "Chat",
            prompt = current.prompt,
            messages = current.messages,
            source = current.description,
            responseId = current.previousResponseId,
            hermesSessionId = state.hermesSessionId ?: current.hermesSessionId,
            modelOverride = state.chatModelOverride,
            providerOverride = state.chatProviderOverride,
            reasoningEffort = state.chatReasoningEffort,
            preserveRemoteContinuity = preserveRemoteContinuity,
            syncAfterSave = false,
            botIdentityKey = botIdentityKey
        )
    }
}


/**
 * Accumulo output del follow live (Responses via poll): gli snapshot
 * sostituiscono, i delta si fondono sul testo precedente. Puro, testabile.
 */
internal fun foldLiveFollowText(previous: String, events: List<ChatStreamEvent>): String {
    val snaps = events.filterIsInstance<ChatStreamEvent.TextSnapshot>()
    if (snaps.isNotEmpty()) return snaps.last().text
    var acc = previous
    events.filterIsInstance<ChatStreamEvent.TextDelta>().forEach { acc = mergeTextDelta(acc, it.delta) }
    return acc
}

internal fun foldLiveFollowThinking(previous: String, events: List<ChatStreamEvent>): String {
    val snaps = events.filterIsInstance<ChatStreamEvent.ThinkingSnapshot>()
    if (snaps.isNotEmpty()) return snaps.last().text
    var acc = previous
    events.filterIsInstance<ChatStreamEvent.ThinkingDelta>().forEach { acc = mergeTextDelta(acc, it.delta) }
    return acc
}

/**
 * Staging messaggi server: entra solo novita (mai duplicati per id, mai
 * riordini). Il live resta intoccato finche l'utente non tocca Mostra.
 * Puro e testabile.
 */
internal fun stageNewServerMessages(
    staged: List<ChatMessage>,
    visible: List<ChatMessage>,
    incoming: List<ChatMessage>
): List<ChatMessage> {
    if (incoming.isEmpty()) return staged
    val known = HashSet<String>(visible.size + staged.size + incoming.size)
    visible.forEach { known.add(it.id) }
    staged.forEach { known.add(it.id) }
    val fresh = incoming.filter { it.id.isNotBlank() && known.add(it.id) }
    return if (fresh.isEmpty()) staged else staged + fresh
}


internal fun AppSettings.withActiveProject(project: LocalConversation): AppSettings = copy(
    activeProjectId = project.id,
    activeProjectName = project.title,
    activeProjectWorkspacePath = project.workspacePath,
    activeProjectRepositoryUrl = project.repositoryUrl,
    activeProjectInstructions = project.projectInstructions,
    activeProjectMemory = project.projectMemory,
    activeProjectTools = project.authorizedTools.joinToString("\n")
)

internal fun AppSettings.clearActiveProject(): AppSettings = copy(
    activeProjectId = "",
    activeProjectName = "",
    activeProjectWorkspacePath = "",
    activeProjectRepositoryUrl = "",
    activeProjectInstructions = "",
    activeProjectMemory = "",
    activeProjectTools = ""
)


internal fun saveArtifactMetadata(context: Context, id: String, title: String, tags: List<String>) {
    synchronized(localArchiveLock) {
        val items = loadConversations(context, includeDeleted = true).toMutableList()
        val index = items.indexOfFirst { it.id == id && it.kind == "Artifact" && it.deletedAt == null }
        if (index < 0) return
        items[index] = items[index].copy(
            title = title.trim().take(180).ifBlank { items[index].title },
            tags = tags.map { it.take(WorkLimits.TRUNC_80) }.distinctBy { it.lowercase() }.take(30),
            updatedAt = System.currentTimeMillis()
        )
        saveConversations(context, items)
    }
}

