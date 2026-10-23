package com.nemoclaw.chat.features.bots

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.HermesSession
import com.nemoclaw.chat.HermesSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Chat canonica del bot, desktop-parity: UN bot, UNA chat per sempre,
 * identificata per NOME (sessione titled esattamente "Bot Chat" sul
 * profilo), mai per puntatore. Stesse regole del desktop: lookup fallito
 * = fail-closed con "riprova" (mai aprire/mintare al buio: si forkerebbe
 * la forever-chat); scan vuoto = crea la canonica titled una volta sola.
 */
internal const val CANONICAL_BOT_CHAT_TITLE = "Bot Chat"
internal const val CANONICAL_SESSION_LIST_LIMIT = 200

internal data class CanonicalBotChat(
    val sessionId: String,
    val messageCount: Int,
    val preview: String,
    val lastActiveMs: Long
)

internal sealed interface CanonicalBotResolve {
    data class Found(val chat: CanonicalBotChat, val duplicates: Int = 0) : CanonicalBotResolve
    data object Empty : CanonicalBotResolve
    data class Failed(val message: String) : CanonicalBotResolve
}

/** Singleflight cross-path: un solo resolve/create per bot alla volta
 *  (funnel roster/detail + sidebar + last-bot condividono la guard UI
 *  locale, ma corse tra path diversi mintavano due forever-chat).
 *  MAI annidare: il lock vive solo dentro resolveCanonicalBotChat, i
 *  chiamanti non lo prendono (Mutex non rientrante = deadlock).
 *  La mappa e limitata: a fine uso pota i lucchetti liberi. */
internal object CanonicalBotOpenLocks {
    internal const val MAX_LOCKS = 64
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    suspend fun <T> withBotLock(key: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(key) { kotlinx.coroutines.sync.Mutex() }
        try {
            return mutex.withLock { block() }
        } finally {
            pruneIdleLocks()
        }
    }

    internal fun pruneIdleLocks() {
        if (locks.size <= MAX_LOCKS) return
        // Solo lucchetti liberi e ancora gli stessi in mappa: mai rimuovere
        // uno in uso o appena ricreato (nel dubbio resta, riprova dopo).
        locks.entries.removeIf { (key, mutex) -> !mutex.isLocked && locks[key] === mutex && locks.size > MAX_LOCKS }
    }

    internal fun lockCountForTest(): Int = locks.size
}

/** Match normalizzato canonico: trim().lowercase() == "bot chat".
 *  root_title vince se non-blank (come desktop), altrimenti title. */
internal fun isCanonicalBotRow(row: HermesSession): Boolean {
    val rootTitle = row.raw?.optString("root_title").orEmpty()
    val effective = if (rootTitle.trim().isNotEmpty()) rootTitle else row.title
    return effective.trim().lowercase() == CANONICAL_BOT_CHAT_TITLE.lowercase()
}

private fun canonicalRowSortKey(row: HermesSession): String = row.createdAt

/** Registry lookup: righe con titolo normalizzato "bot chat",
 *  deterministico ordinando per created_at/last_active/id. Puro e testabile. */
internal fun pickCanonicalRow(rows: List<HermesSession>): HermesSession? {
    return rows.filter { isCanonicalBotRow(it) }
        .sortedWith(
            compareBy(
                { canonicalRowSortKey(it) },
                { it.raw?.optDouble("last_active", 0.0) ?: 0.0 },
                { it.id }
            )
        )
        .firstOrNull()
}

internal fun canonicalPreviewOf(row: HermesSession): CanonicalBotChat {
    val raw = row.raw
    val count = raw?.optInt("live_message_count", -1)?.takeIf { it >= 0 }
        ?: raw?.optInt("message_count", 0) ?: 0
    val preview = raw?.optString("preview").orEmpty()
    val lastActiveMs = ((raw?.optDouble("last_active", 0.0) ?: 0.0) * 1000).toLong()
    return CanonicalBotChat(row.id, count, preview, lastActiveMs)
}

/** Etichetta relativa stile desktop (ora/5m/3h/2g). Puro e testabile. */
internal fun relativeTimeLabel(nowMs: Long, tsMs: Long): String {
    if (tsMs <= 0) return ""
    val seconds = ((nowMs - tsMs).coerceAtLeast(0)) / 1000
    return when {
        seconds < 60 -> "ora"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}g"
    }
}

internal suspend fun resolveCanonicalBotChat(
    context: Context,
    settings: AppSettings,
    bot: HermesBotItem,
    rosterMultiplex: Boolean,
    createIfMissing: Boolean = true
): CanonicalBotResolve = CanonicalBotOpenLocks.withBotLock(bot.identityKey) {
    withContext(Dispatchers.IO) {
        try {
            val connection = connectionForBot(context, settings, bot)
            val effective = settingsForBotConnection(settings, connection)
            val secret = secretForBotConnection(context, connection)
            val client = HermesSessionClient(effective, secret, bot.profile, rosterMultiplex, null)
            suspend fun listAllCanonical(): Pair<Int, List<HermesSession>> {
                val all = mutableListOf<HermesSession>()
                var offset = 0
                var code = 200
                for (page in 0 until 5) {
                    val (pageCode, pageRows) = client.list(
                        limit = CANONICAL_SESSION_LIST_LIMIT,
                        offset = offset,
                        title = CANONICAL_BOT_CHAT_TITLE,
                        includeHidden = true
                    )
                    code = pageCode
                    if (code !in 200..299) return code to all
                    if (pageRows.isEmpty()) break
                    all.addAll(pageRows)
                    if (pageRows.size < CANONICAL_SESSION_LIST_LIMIT) break
                    offset += CANONICAL_SESSION_LIST_LIMIT
                }
                return code to all
            }
            val (listCode, rows) = listAllCanonical()
            if (listCode !in 200..299) {
                return@withContext CanonicalBotResolve.Failed(
                    "Registro Bot Chat non leggibile (HTTP $listCode): riprova."
                )
            }
            pickCanonicalRow(rows)?.let { row ->
                // Fork pregressi: piu righe "Bot Chat" = chat sdoppiata in
                // passato. Si usa la prima deterministica, ma si avvisa
                // (niente repair auto). Conteggio con stesso match normalizzato.
                val dups = rows.count { isCanonicalBotRow(it) } - 1
                return@withContext CanonicalBotResolve.Found(canonicalPreviewOf(row), dups.coerceAtLeast(0))
            }
            if (!createIfMissing) return@withContext CanonicalBotResolve.Empty
            val (createCode, created) = client.create(title = CANONICAL_BOT_CHAT_TITLE, source = "hermes-hub-android")
            if (createCode !in 200..299 || created == null) {
                // Race/create fallito o 400/409 (canonica mintata altrove nel mentre):
                // rifai list e riusa Found se ora presente.
                if (createCode == 400 || createCode == 409 || created == null) {
                    val (reCode, reRows) = listAllCanonical()
                    if (reCode in 200..299) {
                        pickCanonicalRow(reRows)?.let { row ->
                            val dups = reRows.count { isCanonicalBotRow(it) } - 1
                            return@withContext CanonicalBotResolve.Found(canonicalPreviewOf(row), dups.coerceAtLeast(0))
                        }
                    }
                }
                return@withContext CanonicalBotResolve.Failed("Creazione Bot Chat fallita (HTTP $createCode).")
            }
            CanonicalBotResolve.Found(CanonicalBotChat(created.id, 0, "", System.currentTimeMillis()))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            CanonicalBotResolve.Failed(e.message ?: "Bot non raggiungibile.")
        }
    }
}

/**
 * Contesto chat per un bot: sessione CANONICA condivisa col desktop
 * (stessa transcript, stessi turni). localConversationId resta l'id
 * stabile locale (cache/snapshot); la storia autorevole e sul server.
 */
internal suspend fun resolveCanonicalBotContext(
    context: Context,
    settings: AppSettings,
    bot: HermesBotItem,
    rosterMultiplex: Boolean
): Result<BotChatContext> = runCatching {
    val found = when (val resolved = resolveCanonicalBotChat(context, settings, bot, rosterMultiplex)) {
        is CanonicalBotResolve.Found -> resolved.chat
        is CanonicalBotResolve.Empty -> error("Nessuna Bot Chat per ${bot.displayName}: riprova.")
        is CanonicalBotResolve.Failed -> error(resolved.message)
    }
    val connection = withContext(Dispatchers.IO) { connectionForBot(context.applicationContext, settings, bot) }
    BotChatContext(
        profile = bot.profile,
        sessionId = found.sessionId,
        displayName = bot.displayName,
        localConversationId = stableBotConversationId(bot),
        multiplexEnabled = rosterMultiplex,
        connectionId = connection.id,
        endpoint = connection.endpoint
    )
}

