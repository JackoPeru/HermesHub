package com.nemoclaw.chat.features.bots

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import android.os.Parcel
import android.os.Parcelable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import com.nemoclaw.chat.features.screen.ScreenScreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.AutoApproveChip
import com.nemoclaw.chat.BitmapImageLoader
import com.nemoclaw.chat.PollWhileStarted
import com.nemoclaw.chat.ScreenStatusInfo
import com.nemoclaw.chat.decodeScreenFrame
import com.nemoclaw.chat.fetchScreenFrameBytes
import com.nemoclaw.chat.getScreenStatus
import com.nemoclaw.chat.loadBotAutoApproveMap
import com.nemoclaw.chat.loadGatewaySecret
import com.nemoclaw.chat.saveBotAutoApprove
import com.nemoclaw.chat.core.WorkLimits
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.nemoclaw.chat.loadBotAutoApproveMap
import com.nemoclaw.chat.saveBotAutoApprove
import com.nemoclaw.chat.httpGetResponse
import com.nemoclaw.chat.HermesSession
import com.nemoclaw.chat.HermesSessionClient
import com.nemoclaw.chat.normalizeHermesProfileName
import com.nemoclaw.chat.postJson
import com.nemoclaw.chat.resolveHermesUrl
import com.nemoclaw.chat.saveGatewayConnectionSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private const val CONNECTION_ROSTER_TIMEOUT_MILLIS = 8_000L

internal data class HermesBotItem(
    val profile: String,
    val displayName: String,
    val description: String,
    val hidden: Boolean,
    val chatId: String?,
    val isDefault: Boolean,
    val connectionId: String = "primary",
    val connectionLabel: String = "Gateway principale",
    val identityKey: String = "$connectionId::$profile",
    val handle: String = displayName
)

internal data class HermesBotRoster(
    val items: List<HermesBotItem>,
    val botModeProtocol: Boolean,
    val multiplexEnabled: Boolean,
    val chatSupported: Boolean,
    val status: String,
    val sourceFailures: List<String> = emptyList()
)

internal data class BotChatContext(
    val profile: String,
    val sessionId: String,
    val displayName: String,
    val localConversationId: String,
    val multiplexEnabled: Boolean,
    val connectionId: String = "primary",
    val endpoint: String = ""
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readByte() != 0.toByte(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(profile)
        parcel.writeString(sessionId)
        parcel.writeString(displayName)
        parcel.writeString(localConversationId)
        parcel.writeByte(if (multiplexEnabled) 1 else 0)
        parcel.writeString(connectionId)
        parcel.writeString(endpoint)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BotChatContext> = object : Parcelable.Creator<BotChatContext> {
            override fun createFromParcel(parcel: Parcel): BotChatContext = BotChatContext(parcel)
            override fun newArray(size: Int): Array<BotChatContext?> = arrayOfNulls(size)
        }
    }
}

internal suspend fun loadHermesBotRoster(
    settings: AppSettings,
    apiKey: String?,
    connection: HermesBotConnection? = null
): HermesBotRoster = withContext(Dispatchers.IO) {
    return@withContext try {
        val response = if (connection == null) {
            httpGetResponse(resolveHermesUrl(settings, "/v1/hub/bots"), apiKey)
        } else {
            botGetForConnection(settings, connection, apiKey, "/v1/hub/bots")
        }
        if (response.first !in 200..299) {
            HermesBotRoster(emptyList(), false, false, false, safeBotError(response.first, response.second, "Bot non disponibili"), listOf(connection?.label ?: "Gateway principale"))
        } else {
            val root = JSONObject(response.second)
            val array = root.optJSONArray("items") ?: root.optJSONArray("profiles") ?: JSONArray()
            val items = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val profile = item.optString("profile", item.optString("name")).trim()
                    if (profile.isBlank()) continue
                    add(HermesBotItem(
                        profile = profile,
                        displayName = item.optString("display_name", item.optString("displayName", item.optString("title", profile))),
                        description = item.optString("description"),
                        hidden = item.optBoolean("hidden", false),
                        chatId = item.optString("chat_id", item.optString("chatId")).takeIf { it.isNotBlank() },
                        isDefault = item.optBoolean("is_default", item.optBoolean("isDefault", profile.equals("default", true))),
                        connectionId = connection?.id ?: "primary",
                        connectionLabel = connection?.label ?: "Gateway principale",
                        identityKey = "${connection?.id ?: "primary"}::$profile"
                    ))
                }
            }
            val multiplex = root.optBoolean("multiplex_enabled", root.optBoolean("profile_multiplexing", false))
            val chat = root.optBoolean("chat_supported", false)
        HermesBotRoster(items, root.optBoolean("bot_mode_protocol", false), multiplex, chat,
                if (chat) "${items.size} profili Hermes disponibili." else "Roster disponibile; chat bot bloccata finché il multiplexing non è attivo.")
        }
    } catch (ex: Exception) {
        HermesBotRoster(emptyList(), false, false, false, "Bot non disponibili: ${ex.message ?: ex.javaClass.simpleName}".take(360), listOf(connection?.label ?: "Gateway principale"))
    }
}

internal suspend fun loadAllHermesBotRosters(
    context: Context,
    settings: AppSettings
): HermesBotRoster = coroutineScope {
    val registry = loadHermesBotConnections(context, settings)
    val sources = registry.items.filter { it.enabled && it.endpoint.isNotBlank() }
    if (sources.isEmpty()) {
        return@coroutineScope HermesBotRoster(
            emptyList(),
            false,
            false,
            false,
            if (registry.warnings.isEmpty()) "Nessuna connessione Hermes esplicita configurata." else registry.warnings.joinToString(" "),
            registry.warnings
        )
    }
    val results = sources.map { connection ->
        async(Dispatchers.IO) {
            runCatching {
                withTimeout(CONNECTION_ROSTER_TIMEOUT_MILLIS) {
                    loadHermesBotRoster(settings, secretForBotConnection(context, connection), connection)
                }
            }.getOrElse { error ->
                HermesBotRoster(
                    emptyList(),
                    false,
                    false,
                    false,
                    "${connection.label}: ${error.message ?: error.javaClass.simpleName}".take(320),
                    listOf(connection.label)
                )
            }
        }
    }.awaitAll()
    val items = assignStableBotHandles(results.flatMap { it.items }.distinctBy { it.identityKey })
    val failures = (registry.warnings + results.flatMap { it.sourceFailures }).distinct()
    val online = results.any { it.chatSupported }
    HermesBotRoster(
        items = items,
        botModeProtocol = results.any { it.botModeProtocol },
        multiplexEnabled = results.any { it.multiplexEnabled },
        chatSupported = online,
        status = if (failures.isEmpty()) {
            "${items.size} profili Hermes disponibili."
        } else {
            "${items.size} profili Hermes disponibili; fonti con errore: ${failures.joinToString(", ")}."
        },
        sourceFailures = failures
    )
}

internal fun assignStableBotHandles(items: List<HermesBotItem>): List<HermesBotItem> {
    fun slug(value: String, fallback: String): String {
        val normalized = value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return normalized.ifBlank { fallback }.take(48)
    }

    val used = mutableSetOf<String>()
    val handles = mutableMapOf<String, String>()
    items.groupBy { slug(it.displayName, slug(it.profile, "bot")) }
        .forEach { (base, rows) ->
            rows.sortedBy { it.identityKey.lowercase() }.forEach { row ->
                val candidateBase = if (rows.size == 1) {
                    base
                } else {
                    "$base-${slug(row.connectionLabel, slug(row.connectionId, "device"))}"
                }
                var candidate = candidateBase
                var suffix = 0
                while (!used.add(candidate)) {
                    suffix++
                    candidate = "$candidateBase-${slug(row.profile, "bot")}${if (suffix == 1) "" else "-$suffix"}"
                }
                handles[row.identityKey] = candidate
            }
        }
    return items.map { item -> item.copy(handle = handles[item.identityKey] ?: slug(item.displayName, item.profile)) }
}

/**
 * Id conversazione STABILE per bot: ogni bot ha un'unica chat persistente,
 * la stessa su HermesHub e Hermes desktop (l'autosync archivia sotto lo
 * stesso id). Preferisce il chat_id canonico del server; fallback
 * connessione+profilo normalizzati. Sanitizzato [A-Za-z0-9-_].
 */
internal fun stableBotConversationId(bot: HermesBotItem): String {
    val conn = bot.connectionId
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(48).ifBlank { "primary" }
    bot.chatId?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        val safe = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        // Connessione inclusa: stesso chat_id su primary e remoto non deve
        // mai mescolare le storie (la sanitizzazione e lossy).
        if (safe.isNotEmpty()) return "botchat-$conn-$safe"
    }
    val profile = (
        runCatching { normalizeHermesProfileName(bot.profile) }.getOrNull()
            // Profili non slug dal server: mai crashare l'id, sanitizza grezzo.
            ?: bot.profile.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        )
        .take(64).ifBlank { "bot" }
    return "bot-$conn-$profile"
}

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

private fun safeBotError(status: Int, body: String, fallback: String): String {
    val message = runCatching {
        val root = JSONObject(body)
        val error = root.optJSONObject("error")
        (error?.optString("message") ?: root.optString("message")).trim()
    }.getOrDefault("")
    return if (message.isBlank()) "$fallback (HTTP $status)." else "$fallback (HTTP $status): ${message.take(320)}"
}

private fun parseBotItem(root: JSONObject, fallbackProfile: String = "", connection: HermesBotConnection? = null): HermesBotItem {
    val item = root.optJSONObject("bot") ?: root
    val profile = item.optString("profile", item.optString("name", fallbackProfile)).trim()
    return HermesBotItem(
        profile = profile,
        displayName = item.optString("display_name", item.optString("displayName", item.optString("title", profile))),
        description = item.optString("description"),
        hidden = item.optBoolean("hidden", false),
        chatId = item.optString("chat_id", item.optString("chatId")).takeIf { it.isNotBlank() },
        isDefault = item.optBoolean("is_default", item.optBoolean("isDefault", profile.equals("default", true))),
        connectionId = connection?.id ?: "primary",
        connectionLabel = connection?.label ?: "Gateway principale",
        identityKey = "${connection?.id ?: "primary"}::$profile"
    )
}

internal suspend fun createHermesBot(
    settings: AppSettings,
    apiKey: String?,
    profile: String,
    displayName: String,
    description: String,
    soul: String,
    connection: HermesBotConnection? = null
): Result<HermesBotItem> = withContext(Dispatchers.IO) {
    runCatching {
        val normalized = normalizeHermesProfileName(profile)
        val payload = JSONObject()
            .put("name", normalized)
            .put("profile", normalized)
            .put("display_name", displayName.trim())
            .put("description", description.trim())
            .put("no_skills", false)
        if (soul.isNotBlank()) payload.put("soul", soul)
        val response = if (connection == null) {
            postJson(resolveHermesUrl(settings, "/v1/hub/bots"), payload, apiKey)
        } else {
            botPostForConnection(settings, connection, apiKey, "/v1/hub/bots", payload)
        }
        if (response.first !in 200..299) error(safeBotError(response.first, response.second, "Bot non creato"))
        parseBotItem(JSONObject(response.second), normalized, connection)
    }
}

internal suspend fun updateHermesBot(
    settings: AppSettings,
    apiKey: String?,
    bot: HermesBotItem,
    displayName: String,
    description: String,
    soul: String?,
    connection: HermesBotConnection? = null
): Result<HermesBotItem> = withContext(Dispatchers.IO) {
    runCatching {
        val profile = normalizeHermesProfileName(bot.profile)
        val payload = JSONObject()
            .put("display_name", displayName.trim())
            .put("description", description.trim())
        if (!soul.isNullOrBlank()) payload.put("soul", soul)
        val encoded = URLEncoder.encode(profile, "UTF-8")
        val response = if (connection == null) {
            postJson(
                resolveHermesUrl(settings, "/v1/hub/bots/$encoded"),
                payload,
                apiKey,
                method = "PATCH"
            )
        } else {
            botPostForConnection(
                settings,
                connection,
                apiKey,
                "/v1/hub/bots/$encoded",
                payload,
                method = "PATCH"
            )
        }
        if (response.first !in 200..299) error(safeBotError(response.first, response.second, "Modifiche bot non salvate"))
        parseBotItem(JSONObject(response.second), profile, connection)
    }
}

internal suspend fun deleteHermesBot(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    bot: HermesBotItem,
    confirmation: String
): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val profile = normalizeHermesProfileName(bot.profile)
        check(!bot.isDefault && !profile.equals("default", true)) { "Il profilo predefinito non può essere eliminato." }
        check(confirmation == profile) { "La conferma deve corrispondere esattamente al nome del bot." }
        val encoded = URLEncoder.encode(profile, "UTF-8")
        val response = if (bot.connectionId.equals("primary", true)) {
            postJson(
                resolveHermesUrl(settings, "/v1/hub/bots/$encoded"),
                JSONObject().put("confirm_name", confirmation),
                apiKey,
                method = "DELETE"
            )
        } else {
            val connection = connectionForBot(context, settings, bot)
            botPostForConnection(
                settings,
                connection,
                secretForBotConnection(context, connection),
                "/v1/hub/bots/$encoded",
                JSONObject().put("confirm_name", confirmation),
                method = "DELETE"
            )
        }
        if (response.first !in 200..299) error(safeBotError(response.first, response.second, "Bot non eliminato"))
    }
}

@Composable
internal fun BotsScreen(
    context: Context,
    settings: AppSettings,
    onOpenBot: (BotChatContext) -> Unit,
    onOpenScreen: () -> Unit = {},
    onOpenCron: () -> Unit = {},
    // Apre la sidebar in modalita bot (lista bot stile desktop).
    onOpenSidebar: () -> Unit = {},
    // Vieta apertura a turno attivo (reset ammazzerebbe stream/binding/coda).
    canOpenBotChat: () -> Boolean = { true }
) {
    var roster by remember(settings.gatewayUrl) { mutableStateOf<HermesBotRoster?>(null) }
    var connections by remember(settings.gatewayUrl) {
        mutableStateOf(HermesBotConnectionRegistry(emptyList(), emptyList()))
    }
    var status by remember(settings.gatewayUrl) { mutableStateOf("Carico il roster reale Hermes...") }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf<String?>(null) }
    var editorBot by remember { mutableStateOf<HermesBotItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var profileInput by remember { mutableStateOf("") }
    var displayNameInput by remember { mutableStateOf("") }
    var descriptionInput by remember { mutableStateOf("") }
    var soulInput by remember { mutableStateOf("") }
    var editorError by remember { mutableStateOf("") }
    var deleteBot by remember { mutableStateOf<HermesBotItem?>(null) }
    var deleteConfirmation by remember { mutableStateOf("") }
    var removeConnection by remember { mutableStateOf<HermesBotConnection?>(null) }
    var mutating by remember { mutableStateOf(false) }
    var showConnectionEditor by remember { mutableStateOf(false) }
    var connectionLabelInput by remember { mutableStateOf("") }
    var connectionEndpointInput by remember { mutableStateOf("") }
    var connectionTokenInput by remember { mutableStateOf("") }
    var selectedConnectionId by remember { mutableStateOf("primary") }
    var groups by remember(settings.gatewayUrl) { mutableStateOf(emptyList<HermesBotGroup>()) }
    var groupNameInput by remember { mutableStateOf("") }
    var selectedGroupMemberKeys by remember { mutableStateOf(emptySet<String>()) }
    var openGroup by remember { mutableStateOf<HermesBotGroup?>(null) }
    var groupPrompt by remember { mutableStateOf("") }
    var groupResult by remember { mutableStateOf<HermesBotGroupTurnResult?>(null) }
    var groupRunning by remember { mutableStateOf(false) }
    var groupJob by remember { mutableStateOf<Job?>(null) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    val detailBot = remember(roster, detailKey) { roster?.items?.firstOrNull { it.identityKey == detailKey } }
    val scope = rememberCoroutineScope()
    val botListState = rememberLazyListState()
    // Menu contestuale stile desktop: preferenze locali (il server non ha
    // pin/hide/sezioni/sessioni/auto-screen). Ricaricate col roster.
    var botPins by remember { mutableStateOf(setOf<String>()) }
    var botHiddenLocal by remember { mutableStateOf(setOf<String>()) }
    var botAutoScreen by remember { mutableStateOf(setOf<String>()) }
    var botSections by remember { mutableStateOf(BotSections()) }
    var showHiddenBots by remember { mutableStateOf(false) }
    var showSectionScreen by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var menuPage by remember { mutableStateOf(0) }
    var newSectionFor by remember { mutableStateOf<HermesBotItem?>(null) }
    var lastScreenHolder by remember { mutableStateOf("human") }

    // applicationContext: i polling screen non trattengono mai l'Activity.
    val appContext = context.applicationContext

    fun reloadBotDisplayPrefs() {
        botPins = loadBotPins(appContext)
        botHiddenLocal = loadBotHiddenLocal(appContext)
        botAutoScreen = loadBotAutoScreen(appContext)
        botSections = loadBotSections(appContext)
    }
    // Stato schermo condiviso per roster e dettaglio (poll leggero, anteprima solo se acceso).
    var screenStatus by remember(settings.gatewayUrl) { mutableStateOf<ScreenStatusInfo?>(null) }
    var screenPreview by remember { mutableStateOf<Bitmap?>(null) }
    var lastScreenSignature by remember { mutableStateOf<BitmapImageLoader.FrameSignature?>(null) }
    // Schermo gia aperto in sezione: niente doppio polling (ScreenScreen
    // polla da se): invocazione condizionale, non early-return.
    if (!showSectionScreen) {
    PollWhileStarted(settings.gatewayUrl, baseIntervalMs = 8_000L) {
        val key = withContext(Dispatchers.IO) { loadGatewaySecret(appContext) }
        val next = runCatching { withContext(Dispatchers.IO) { getScreenStatus(settings, key) } }.getOrNull()
        screenStatus = next
        // "Apri schermo quando il bot lo usa": fronte di un bot che prende
        // lo schermo (holder: human -> altro). Solo se UN solo bot e flaggato
        // (holder e generico, non attribuibile: con piu flag niente auto-open,
        // solo status). Mai mentre l'utente compila dialoghi.
        val holderNow = next?.holder ?: "human"
        val flagged = roster?.items.orEmpty().filter { it.identityKey in botAutoScreen }
        if (next?.running == true && holderNow != "human" && lastScreenHolder == "human" &&
            flagged.size == 1 && !showEditor && deleteBot == null && openGroup == null &&
            newSectionFor == null && !showConnectionEditor &&
            removeConnection == null) {
            showSectionScreen = true
            status = "Un bot sta usando lo schermo: aperto automaticamente."
        }
        lastScreenHolder = holderNow
        next != null
    }
    }
    if (!showSectionScreen) {
    PollWhileStarted(screenStatus?.running, baseIntervalMs = 6_000L) {
        if (screenStatus?.running != true) {
            screenPreview = null
            lastScreenSignature = null
            return@PollWhileStarted true
        }
        val key = withContext(Dispatchers.IO) { loadGatewaySecret(appContext) }
        val bytes = withContext(Dispatchers.IO) { fetchScreenFrameBytes(settings, key, 480) }
        // Skip re-decode se i byte sono identici ai precedenti (hash/lunghezza).
        val signature = BitmapImageLoader.frameSignature(bytes)
        if (signature != null && signature == lastScreenSignature) {
            return@PollWhileStarted bytes != null
        }
        lastScreenSignature = signature
        // reqWidth = larghezza view anteprima (loader: sampling + cache).
        screenPreview = withContext(Dispatchers.IO) { decodeScreenFrame(bytes, 480) }
        bytes != null
    }
    }

    // Back dal dettaglio torna al roster, non fuori dalla sezione
    // (l'handler AppRoot consumerebbe il back altrimenti).
    BackHandler(enabled = detailBot != null) { detailKey = null }

    // Funnel unico di apertura canonica: guard SINCRONA anti doppio-tap
    // (lo state write e immediato, la ricomposizione che disabilita i
    // bottoni no) + finally (mai opening appeso su cancel). Risolve la
    // forever-chat condivisa col desktop (mai fork, mai sessioni per-tap).
    // Ritorna false se non acquisisce la guard (chiamante: non chiudere menu).
    fun openBotSession(bot: HermesBotItem): Boolean {
        if (opening != null) {
            Toast.makeText(context, "Apertura già in corso.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!canOpenBotChat()) return false
        opening = bot.identityKey
        scope.launch {
            try {
                // Niente lock esterno qui: il lock e dentro
                // resolveCanonicalBotChat (stessa chiave = deadlock,
                // Mutex non rientrante). La guard `opening` sopra copre
                // gia il doppio-tap.
                when (val resolved = resolveCanonicalBotChat(appContext, settings, bot, roster?.multiplexEnabled == true)) {
                    is CanonicalBotResolve.Found -> {
                        if (resolved.duplicates > 0) {
                            status = "Attenzione: ${resolved.duplicates + 1} Bot Chat per ${bot.displayName}, uso la prima."
                        }
                        val connection = withContext(Dispatchers.IO) { connectionForBot(appContext, settings, bot) }
                        onOpenBot(
                            BotChatContext(
                                profile = bot.profile,
                                sessionId = resolved.chat.sessionId,
                                displayName = bot.displayName,
                                localConversationId = stableBotConversationId(bot),
                                multiplexEnabled = roster?.multiplexEnabled == true,
                                connectionId = connection.id,
                                endpoint = connection.endpoint
                            )
                        )
                    }
                    is CanonicalBotResolve.Empty ->
                        status = "Nessuna Bot Chat per ${bot.displayName}: riprova."
                    is CanonicalBotResolve.Failed ->
                        status = resolved.message
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                status = e.message ?: "Apertura bot fallita."
            } finally {
                opening = null
            }
        }
        return true
    }

    fun openPersistent(bot: HermesBotItem): Boolean = openBotSession(bot)
    // Anteprime roster stile desktop (ultimo messaggio + tempo per bot):
    // scan canonico senza mintare, best-effort e silenzioso.
    var botPreviews by remember { mutableStateOf(mapOf<String, CanonicalBotChat>()) }
    LaunchedEffect(roster?.items) {
        val items = roster?.items.orEmpty()
        if (items.isEmpty()) {
            botPreviews = emptyMap()
            return@LaunchedEffect
        }
        fun markStale() {
            botPreviews = emptyMap()
            if (!status.contains("anteprime non aggiornate")) status += " (anteprime non aggiornate)"
        }
        try {
            val result = withTimeoutOrNull(8_000) {
                items.take(20).map { bot ->
                    async(Dispatchers.IO) {
                        val chat: CanonicalBotChat? = withTimeoutOrNull(8_000) {
                            runCatching {
                                // Il lock e dentro resolveCanonicalBotChat: qui niente
                                // lock esterno (stessa chiave = deadlock, Mutex non rientrante).
                                when (val resolved = resolveCanonicalBotChat(appContext, settings, bot, roster?.multiplexEnabled == true, createIfMissing = false)) {
                                    is CanonicalBotResolve.Found -> resolved.chat
                                    else -> null
                                }
                            }.getOrNull()
                        }
                        bot.identityKey to chat
                    }
                }.awaitAll().mapNotNull { (key, chat) -> chat?.let { key to it } }.toMap()
            }
            if (result == null) {
                markStale()
            } else {
                botPreviews = result
            }
        } catch (_: Exception) {
            markStale()
        }
    }

    val detail = detailBot
    if (detail != null) {
        BotDetailScreen(
            bot = detail,
            context = context,
            settings = settings,
            screenStatus = screenStatus,
            screenPreview = screenPreview,
            onBack = { detailKey = null },
            // Singola POST via funnel (guard + finally): il dettaglio non
            // posta mai da solo, delega sempre qui.
            onOpenChat = { bot -> openPersistent(bot) },
            // Schermo sempre interno alla sezione (il tab Screen non esiste
            // piu): "Apri schermo live" torna al roster con schermo aperto.
            onOpenScreen = { showSectionScreen = true; detailKey = null },
            onOpenCron = onOpenCron,
            busy = opening != null
        )
        return
    }

    fun openEditor(bot: HermesBotItem?) {
        editorBot = bot
        profileInput = bot?.profile.orEmpty()
        displayNameInput = bot?.displayName.orEmpty()
        descriptionInput = bot?.description.orEmpty()
        soulInput = ""
        selectedConnectionId = bot?.connectionId ?: "primary"
        editorError = ""
        showEditor = true
    }

    LaunchedEffect(settings.gatewayUrl, refreshNonce) {
        connections = loadHermesBotConnections(context, settings)
        groups = loadHermesBotGroups(context)
        reloadBotDisplayPrefs()
        val result = loadAllHermesBotRosters(context, settings)
        roster = result
        status = result.status
        if (!result.chatSupported) status += " Apri Bot Chat richiede multiplexing profili attivo."
        // Pota orfani solo a roster valido: con fallimenti di sorgente il
        // roster puo essere vuoto per errore rete, mai potare allora.
        if (result.sourceFailures.isEmpty()) {
            botSections = pruneBotDisplayPrefs(
                appContext,
                result.items.map { it.identityKey }.toSet()
            )
            reloadBotDisplayPrefs()
        }
    }

    // Roster da mostrare: via i nascosti (server o locali) salvo toggle,
    // fissati in alto, resto in ordine server raggruppato per sezione.
    val displayBots = remember(roster, botPins, botHiddenLocal, showHiddenBots) {
        val items = roster?.items.orEmpty()
        val filtered = if (showHiddenBots) items
        else items.filter { !it.hidden && it.identityKey !in botHiddenLocal }
        sortBotsForRoster(filtered, botPins)
    }
    val hiddenCount = remember(roster, botHiddenLocal) {
        roster?.items.orEmpty().count { it.hidden || it.identityKey in botHiddenLocal }
    }
    val pinnedBots = remember(displayBots, botPins) {
        displayBots.filter { it.identityKey in botPins }
    }
    val groupedBots = remember(displayBots, botPins, botSections) {
        groupBotsBySection(displayBots.filter { it.identityKey !in botPins }, botSections)
    }
    // Anteprime stale: scan best-effort fallito per alcuni bot ma con cache
    // precedente (il LaunchedEffect con timeout conserva la mappa vecchia).
    // UI-only: non tocca timeout/resolve, aggiunge solo etichetta.
    val previewsStale = remember(roster?.items, botPreviews) {
        val current = roster?.items.orEmpty()
        current.isNotEmpty() && botPreviews.isNotEmpty() &&
            current.any { it.identityKey !in botPreviews }
    }
    // Limite bitmap: anteprima schermo live solo ai primi 2 in roster
    // (il dettaglio la riceve sempre). Evita N decode/view contemporanei.
    val botOrderIndex = remember(displayBots) {
        displayBots.mapIndexed { index, bot -> bot.identityKey to index }.toMap()
    }

    // Schermo dentro la sezione Bot (non piu tab sidebar): copre roster e
    // dettaglio, back torna al roster.
    @Composable
    fun BotCardWithMenu(bot: HermesBotItem, cardIndex: Int = Int.MAX_VALUE) {
        val isPinned = bot.identityKey in botPins
        val isHidden = bot.hidden || bot.identityKey in botHiddenLocal
        val currentSection = botSections.assign[bot.identityKey]?.takeIf { it in botSections.order }
        val preview = botPreviews[bot.identityKey]
        // Anteprima testo o conteggio (chat vuota e scan fallito restano
        // distinti: solo la prima mostra riga). Plurale + stale label.
        val basePreviewLine = preview?.preview?.takeIf { it.isNotBlank() }
            ?: preview?.takeIf { it.messageCount > 0 }?.let {
                if (it.messageCount == 1) "1 messaggio" else "${it.messageCount} messaggi"
            }
        val previewLine = when {
            basePreviewLine != null && previewsStale -> "$basePreviewLine (anteprime non aggiornate)"
            basePreviewLine != null -> basePreviewLine
            // Stale senza dato per questo bot: riga esplicita invece di vuoto.
            previewsStale && roster != null -> "(anteprime non aggiornate)"
            else -> null
        }
        // Bitmap limit: solo primi 2 in roster condividono la preview live.
        val limitedPreview = if (cardIndex < 2) screenPreview else null
        // Dropdown hoist: host costruito solo per la card con menu aperto,
        // la card chiusa non compone alcun DropdownMenu.
        val menuHost = if (menuFor == bot.identityKey) BotMenuHost(
            expanded = true,
            page = menuPage,
            busy = opening != null,
            pinned = isPinned,
            hidden = bot.identityKey in botHiddenLocal,
            autoScreen = bot.identityKey in botAutoScreen,
            sections = botSections.order,
            currentSection = currentSection,
                onDismiss = { menuFor = null; menuPage = 0 },
                onPage = { menuPage = it },
                onOpenChat = { if (openPersistent(bot)) menuFor = null },
                onOpenScreen = { menuFor = null; showSectionScreen = true },
                onToggleAutoScreen = {
                    // Checkable: il menu resta aperto (come desktop).
                    val next = botAutoScreen.toMutableSet()
                    if (bot.identityKey in next) next.remove(bot.identityKey) else next.add(bot.identityKey)
                    botAutoScreen = next
                    setBotAutoScreen(appContext, bot.identityKey, bot.identityKey in next)
                },
                onTogglePin = {
                    menuFor = null
                    val next = botPins.toMutableSet()
                    if (bot.identityKey in next) next.remove(bot.identityKey) else next.add(bot.identityKey)
                    botPins = next
                    setBotPinned(appContext, bot.identityKey, bot.identityKey in next)
                },
                onToggleHide = {
                    // Nascosto dal server: il flag locale e inutile, spiega e resta.
                    if (bot.hidden && bot.identityKey !in botHiddenLocal) {
                        Toast.makeText(context, "Nascosto dal server.", Toast.LENGTH_SHORT).show()
                    } else {
                        menuFor = null
                        val next = botHiddenLocal.toMutableSet()
                        if (bot.identityKey in next) next.remove(bot.identityKey) else next.add(bot.identityKey)
                        botHiddenLocal = next
                        setBotHiddenLocal(appContext, bot.identityKey, bot.identityKey in next)
                    }
                },
                onEdit = { menuFor = null; openEditor(bot) },
                onManageGroups = {
                    menuFor = null
                    status = "Gruppi qui sotto: creali e lanciali da questa scheda."
                    scope.launch { botListState.animateScrollToItem(2) }
                },
                onDuplicate = {
                    menuFor = null
                    // Il server non espone la soul in lettura: copia
                    // profilo/nome/descrizione, soul da ricompilare.
                    editorBot = null
                    profileInput = (bot.profile + "-copy").take(64)
                    displayNameInput = "${bot.displayName} (copia)"
                    descriptionInput = bot.description
                    soulInput = ""
                    selectedConnectionId = bot.connectionId
                    showEditor = true
                    Toast.makeText(context, "Soul non copiata: il server non la espone, ricompilala.", Toast.LENGTH_LONG).show()
                },
                onMoveToSection = { name ->
                    menuFor = null
                    val assign = botSections.assign.toMutableMap()
                    if (name == null) assign.remove(bot.identityKey) else assign[bot.identityKey] = name
                    val next = botSections.copy(assign = assign)
                    botSections = next
                    saveBotSections(appContext, next)
                },
                onNewSection = { menuFor = null; newSectionFor = bot }
            ) else null
        BotRosterCard(
            bot = bot,
            screenRunning = screenStatus?.running == true,
            screenPreview = limitedPreview,
            chatSupported = roster?.chatSupported == true,
            busy = opening != null,
            pinned = isPinned,
            hiddenBadge = showHiddenBots && isHidden,
            previewText = previewLine,
            previewTime = preview?.let { relativeTimeLabel(System.currentTimeMillis(), it.lastActiveMs) }?.takeIf { it.isNotBlank() },
            menu = menuHost,
            onOpenDetail = { detailKey = bot.identityKey },
            onOpenChat = { openPersistent(bot) },
            onOpenScreen = { showSectionScreen = true },
            onEdit = { openEditor(bot) },
            onDelete = {
                deleteBot = bot
                deleteConfirmation = ""
            },
            onMenuRequest = { menuFor = bot.identityKey; menuPage = 0 },
            canDelete = !bot.isDefault && !bot.profile.equals("default", true) && mutating == false
        )
    }

    if (showSectionScreen) {
        BackHandler(enabled = showSectionScreen) { showSectionScreen = false }
        ScreenScreen(
            context = context,
            settings = settings,
            onBack = { showSectionScreen = false }
        )
        return
    }

    LazyColumn(
        state = botListState,
        modifier = Modifier.fillMaxSize().padding(20.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bot Hermes", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("Profili reali con configurazione, memoria, skill e credenziali separate. Le routine bot richiedono multiplexing attivo.", color = AppColors.Muted, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Sidebar in modalita bot (come Hermes desktop).
                    IconButton(onClick = onOpenSidebar) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Apri lista bot", tint = Color.White)
                    }
                    // Schermo dentro la sezione (non piu tab sidebar).
                    IconButton(onClick = { showSectionScreen = true }) {
                        Icon(
                            Icons.Rounded.Computer,
                            contentDescription = "Apri schermo bot",
                            tint = if (screenStatus?.running == true) Color(0xFF4CAF50) else Color.White
                        )
                    }
                    IconButton(onClick = { openEditor(null) }) { Icon(Icons.Rounded.Add, contentDescription = "Nuovo bot", tint = Color.White) }
                    IconButton(onClick = {
                        connectionLabelInput = ""
                        connectionEndpointInput = ""
                        connectionTokenInput = ""
                        showConnectionEditor = true
                    }) { Icon(Icons.Rounded.Link, contentDescription = "Gestisci connessioni", tint = Color.White) }
                    IconButton(onClick = { refreshNonce++ }) { Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna bot", tint = Color.White) }
                }
            }
            Text(status, color = AppColors.Muted, modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        // Loading: roster null = primo fetch in corso (skeleton/progress +
        // liveRegion per screen reader). Distinto da empty/error.
        if (roster == null) {
            item(key = "loading") {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Carico il roster reale Hermes…",
                            color = AppColors.Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            }
        }
        // Errori sorgente: retry esplicito con refreshNonce++ (mai auto-prune).
        val sourceFailures = roster?.sourceFailures.orEmpty()
        if (sourceFailures.isNotEmpty()) {
            item(key = "sources-error") {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Alcune fonti Hermes non rispondono.", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Fonti con errore: ${sourceFailures.joinToString(", ")}.",
                            color = AppColors.Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                        )
                        TextButton(
                            onClick = { refreshNonce++ },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Riprova", color = AppColors.Accent) }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Endpoint Hermes", color = Color.White, fontWeight = FontWeight.SemiBold)
                    connections.items.forEach { connection ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(connection.label, color = Color.White)
                                Text(
                                    if (connection.endpoint.isBlank()) "Endpoint non configurato" else connection.endpoint,
                                    color = AppColors.Muted,
                                    fontSize = 12.sp
                                )
                            }
                            if (!connection.isPrimary) {
                                IconButton(onClick = { removeConnection = connection }) { Icon(Icons.Rounded.Delete, contentDescription = "Rimuovi connessione", tint = Color(0xFFFF7B8E)) }
                            }
                        }
                    }
                    connections.warnings.forEach { warning ->
                        Text(warning, color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gruppi Hermes", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Seleziona da 2 a 6 bot anche su connessioni diverse. Max 3 turni e 10 risposte per turno.", color = AppColors.Muted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = groupNameInput,
                        onValueChange = { groupNameInput = it },
                        label = { Text("Nome gruppo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    roster?.items.orEmpty().forEach { bot ->
                        val selected = bot.identityKey in selectedGroupMemberKeys
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = {
                                    selectedGroupMemberKeys = if (selected) {
                                        selectedGroupMemberKeys - bot.identityKey
                                    } else if (selectedGroupMemberKeys.size < 6) {
                                        selectedGroupMemberKeys + bot.identityKey
                                    } else {
                                        selectedGroupMemberKeys
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("@${bot.handle} · ${bot.displayName}", color = Color.White, fontSize = 13.sp)
                                Text(bot.connectionLabel, color = AppColors.Muted, fontSize = 11.sp)
                            }
                        }
                    }
                    if (roster?.items.isNullOrEmpty()) {
                        Text("Crea prima almeno 2 bot qui sopra per formare un gruppo.", color = AppColors.Muted, fontSize = 12.sp)
                    }
                    Text("Selezionati: ${selectedGroupMemberKeys.size}/6", color = AppColors.Muted, fontSize = 12.sp)
                    IconButton(
                        enabled = groupNameInput.isNotBlank() && selectedGroupMemberKeys.size in 2..6,
                        onClick = {
                            runCatching {
                                val selected = roster?.items.orEmpty().filter { it.identityKey in selectedGroupMemberKeys }
                                upsertHermesBotGroup(
                                    context,
                                    HermesBotGroup(
                                        groupNameInput.trim(),
                                        selected.map { bot -> HermesBotGroupMember(bot.connectionId, bot.profile, bot.displayName, bot.handle, bot.identityKey) }
                                    )
                                )
                            }.onSuccess {
                                groups = loadHermesBotGroups(context)
                                groupNameInput = ""
                                selectedGroupMemberKeys = emptySet()
                                status = "Gruppo Hermes salvato."
                            }.onFailure { status = it.message ?: "Gruppo non salvato." }
                        }
                    ) { Icon(Icons.Rounded.Check, contentDescription = "Salva gruppo", tint = Color.White) }
                    groups.forEach { group ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(group.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (group.members.size == 1) "1 membro · sessioni Group persistenti" else "${group.members.size} membri · sessioni Group persistenti",
                                    color = AppColors.Muted,
                                    fontSize = 11.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = {
                                    openGroup = group
                                    groupPrompt = ""
                                    groupResult = null
                                }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Apri gruppo", tint = Color.White) }
                                IconButton(onClick = {
                                    runCatching { deleteHermesBotGroup(context, group.name) }
                                        .onSuccess { groups = loadHermesBotGroups(context); status = "Gruppo rimosso." }
                                        .onFailure { status = it.message ?: "Gruppo non rimosso." }
                                }) { Icon(Icons.Rounded.Delete, contentDescription = "Elimina gruppo", tint = Color(0xFFFF7B8E)) }
                            }
                        }
                    }
                }
            }
        }
        // Empty distinta da loading/error: roster caricato ma zero bot visibili.
        // Nascosta quando ci sono sourceFailures (mostra solo errore sopra).
        val emptyRoster = roster
        if (emptyRoster != null && emptyRoster.items.isEmpty() && emptyRoster.sourceFailures.isEmpty()) {
            item(key = "empty") {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nessun bot disponibile.", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Crea il primo bot oppure collega un altro endpoint Hermes.",
                            color = AppColors.Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { openEditor(null) }) { Icon(Icons.Rounded.Add, contentDescription = "Crea bot", tint = Color.White) }
                            IconButton(onClick = {
                                connectionLabelInput = ""
                                connectionEndpointInput = ""
                                connectionTokenInput = ""
                                showConnectionEditor = true
                            }) { Icon(Icons.Rounded.Link, contentDescription = "Verifica connessioni", tint = Color.White) }
                        }
                        TextButton(
                            onClick = { openEditor(null) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Crea primo bot", color = AppColors.Accent) }
                    }
                }
            }
        }
        if (hiddenCount > 0) {
            item(key = "hidden-row") {
                BotHiddenRow(
                    hiddenCount = hiddenCount,
                    showing = showHiddenBots,
                    onToggle = { showHiddenBots = !showHiddenBots }
                )
            }
        }
        // Fissati sempre in alto, poi sezioni utente, poi non assegnati.
        if (pinnedBots.isNotEmpty()) {
            item(key = "header-pinned") { BotSectionHeader("Fissati") }
            items(pinnedBots, key = { "pin-${it.identityKey}" }) { bot ->
                BotCardWithMenu(bot = bot, cardIndex = botOrderIndex[bot.identityKey] ?: Int.MAX_VALUE)
            }
        }
        groupedBots.forEach { (section, bots) ->
            if (section != null) {
                item(key = "sec-$section") { BotSectionHeader(section) }
            }
            items(bots, key = { it.identityKey }) { bot ->
                BotCardWithMenu(bot = bot, cardIndex = botOrderIndex[bot.identityKey] ?: Int.MAX_VALUE)
            }
        }
    }

    newSectionFor?.let { bot ->
        BotSectionNameDialog(
            onConfirm = { name ->
                if (name.isNotEmpty()) {
                    // Dedup case-insensitive: "Lavoro" == "lavoro".
                    val existing = botSections.order.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
                    val order = (botSections.order + existing).distinct()
                    val assign = botSections.assign.toMutableMap()
                    assign[bot.identityKey] = existing
                    val next = BotSections(order, assign)
                    botSections = next
                    saveBotSections(appContext, next)
                }
                newSectionFor = null
            },
            onDismiss = { newSectionFor = null }
        )
    }

    openGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { if (!groupRunning) openGroup = null },
            title = { Text("Turno · ${group.name}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (group.members.size == 1) "1 membro · ogni sessione è persistente." else "${group.members.size} membri · ogni sessione è persistente.",
                        color = AppColors.Muted,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = groupPrompt,
                        onValueChange = { groupPrompt = it.take(20_000) },
                        label = { Text("Messaggio per il gruppo") },
                        minLines = 3,
                        enabled = !groupRunning,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (groupRunning) Text("Esecuzione in corso...", color = AppColors.Muted)
                    groupResult?.let { result ->
                        Text("Esito: ${groupOutcomeLabel(result.outcome)} · turni: ${result.rounds} · risposte: ${result.botMessages}", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Silenziosi: ${result.memberResults.count { it.silent }}", color = AppColors.Muted, fontSize = 12.sp)
                        result.memberResults.filterNot { it.silent }.forEach { memberResult ->
                            val source = connections.items.firstOrNull { it.id.equals(memberResult.member.connectionId, true) }?.label ?: "Connessione Hermes"
                            Text(
                                "@${memberResult.member.handle} · ${memberResult.member.displayName} · $source: ${memberResult.reply}",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                        if (result.failures.isNotEmpty()) {
                            Text("Fallimenti parziali:", color = Color.White, fontWeight = FontWeight.SemiBold)
                            result.failures.forEach { failure -> Text(failure, color = AppColors.Muted, fontSize = 12.sp) }
                        }
                    }
                }
            },
            confirmButton = {
                if (groupRunning) {
                    IconButton(onClick = { groupJob?.cancel() }) { Icon(Icons.Rounded.Stop, contentDescription = "Annulla turno", tint = Color.White) }
                } else {
                    IconButton(
                        enabled = groupPrompt.isNotBlank(),
                        onClick = {
                            groupRunning = true
                            groupJob = scope.launch {
                                try {
                                    val result = runHermesBotGroupTurn(context, settings, group, groupPrompt)
                                    currentCoroutineContext().ensureActive()
                                    groupResult = result
                                    status = "Turno ${group.name}: ${result.outcome}."
                                } catch (error: CancellationException) {
                                    status = "Turno gruppo annullato; le risposte già completate non sono state sostituite."
                                    throw error
                                } catch (error: Exception) {
                                    status = error.message ?: "Turno gruppo fallito."
                                } finally {
                                    groupRunning = false
                                    groupJob = null
                                }
                            }
                        }
                    ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Esegui turno", tint = Color.White) }
                }
            },
            dismissButton = { IconButton(onClick = { openGroup = null }, enabled = !groupRunning) { Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = Color.White) } }
        )
    }

    if (showEditor) {
        val editing = editorBot
        val selectedConnection = connections.items.firstOrNull { it.id.equals(selectedConnectionId, true) }
        // remember map: niente I/O SharedPreferences a ogni ricomposizione.
        val autoApproveMap = remember(editing?.profile, showEditor) { loadBotAutoApproveMap(appContext) }
        AlertDialog(
            onDismissRequest = { if (!mutating) showEditor = false },
            title = { Text(if (editing == null) "Nuovo bot Hermes" else "Modifica ${editing.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(profileInput, { profileInput = it; editorError = "" }, label = { Text("Nome profilo") }, enabled = editing == null, singleLine = true)
                    OutlinedTextField(displayNameInput, { displayNameInput = it; editorError = "" }, label = { Text("Nome visualizzato") }, singleLine = true)
                    OutlinedTextField(descriptionInput, { descriptionInput = it; editorError = "" }, label = { Text("Descrizione (${descriptionInput.length}/$MAX_BOT_DESCRIPTION)") }, minLines = 2)
                    OutlinedTextField(soulInput, { soulInput = it; editorError = "" }, label = { Text("SOUL.md (opzionale, ${soulInput.length}/$MAX_BOT_SOUL)") }, minLines = 4)
                    val editorValidation = validateBotEditor(profileInput, displayNameInput, descriptionInput, soulInput, editing == null)
                    if (editorValidation != null) {
                        Text(editorValidation, color = Color(0xFFFF7B8E), fontSize = 12.sp)
                    }
                    if (editorError.isNotBlank()) {
                        Text(editorError, color = Color(0xFFFF7B8E), fontSize = 12.sp)
                    }
                    Text("Auto-approvazione run di questo bot (solo client, mai deny automatico)", color = AppColors.Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, value) in listOf("Chiedi" to WorkLimits.AUTO_APPROVE_OFF, "Sessione" to WorkLimits.AUTO_APPROVE_SESSION, "Sempre" to WorkLimits.AUTO_APPROVE_ALWAYS)) {
                            val selected = autoApproveMap[editing?.profile.orEmpty()]?.let { it == value }
                                ?: (value == WorkLimits.AUTO_APPROVE_OFF)
                            TextButton(
                                onClick = {
                                    val profile = (if (editing == null) profileInput else editing?.profile).orEmpty()
                                    if (profile.isNotBlank()) {
                                        saveBotAutoApprove(context, profile, value)
                                        refreshNonce++
                                    }
                                }
                            ) {
                                Text(if (selected) "✓ $label" else label)
                            }
                        }
                    }
                    Text("Connessione", color = AppColors.Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        connections.items.filter { it.enabled }.forEach { connection ->
                            TextButton(onClick = { selectedConnectionId = connection.id }, enabled = editing == null) {
                                Text(if (connection.id.equals(selectedConnectionId, true)) "✓ ${connection.label}" else connection.label)
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(1.dp))
                }
            },
            confirmButton = {
                // Stessa validazione del server: mai un 400 criptico.
                val editorValidation = validateBotEditor(profileInput, displayNameInput, descriptionInput, soulInput, editing == null)
                IconButton(
                    enabled = profileInput.isNotBlank() && !mutating && selectedConnection?.enabled == true && editorValidation == null,
                    onClick = {
                        mutating = true
                        editorError = ""
                        scope.launch {
                            val connection = if (editing == null) {
                                selectedConnection ?: error("Connessione Hermes non disponibile.")
                            } else {
                                connectionForBot(context, settings, editing)
                            }
                            val secret = secretForBotConnection(context, connection)
                            val result = if (editing == null) {
                                createHermesBot(settings, secret, profileInput, displayNameInput, descriptionInput, soulInput, connection)
                            } else {
                                updateHermesBot(settings, secret, editing, displayNameInput, descriptionInput, soulInput.takeIf { it.isNotBlank() }, connection)
                            }
                            result.onSuccess {
                                status = if (editing == null) "Bot creato." else "Bot aggiornato."
                                showEditor = false
                                refreshNonce++
                            }.onFailure {
                                editorError = it.message ?: "Operazione bot fallita."
                                status = editorError
                            }
                            mutating = false
                        }
                    }
                ) { Icon(Icons.Rounded.Save, contentDescription = "Salva bot", tint = Color.White) }
            },
            dismissButton = { IconButton(onClick = { showEditor = false }, enabled = !mutating) { Icon(Icons.Rounded.Close, contentDescription = "Annulla", tint = Color.White) } }
        )
    }

    deleteBot?.let { bot ->
        AlertDialog(
            onDismissRequest = { if (!mutating) deleteBot = null },
            title = { Text("Elimina ${bot.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("L'eliminazione rimuove il profilo Hermes e i suoi dati dal server. Scrivi esattamente ${bot.profile} per confermare.")
                    OutlinedTextField(deleteConfirmation, { deleteConfirmation = it }, label = { Text("Conferma nome") }, singleLine = true)
                }
            },
            confirmButton = {
                IconButton(
                    enabled = deleteConfirmation == bot.profile && !mutating,
                    onClick = {
                        mutating = true
                        scope.launch {
                            deleteHermesBot(context, settings, secretForBotConnection(context, connectionForBot(context, settings, bot)), bot, deleteConfirmation)
                                .onSuccess {
                                    status = "Bot eliminato."
                                    deleteBot = null
                                    // Pulisci pin/hide/sezioni/sessioni/link locali del bot.
                                    withContext(Dispatchers.IO) {
                                        removeBotDisplayPrefs(appContext, bot.identityKey)
                                        clearLastBotIf(appContext, bot.identityKey)
                                    }
                                    reloadBotDisplayPrefs()
                                    refreshNonce++
                                }
                                .onFailure { status = it.message ?: "Eliminazione bot fallita." }
                            mutating = false
                        }
                    }
                ) { Icon(Icons.Rounded.Delete, contentDescription = "Conferma eliminazione bot", tint = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { IconButton(onClick = { deleteBot = null }, enabled = !mutating) { Icon(Icons.Rounded.Close, contentDescription = "Annulla", tint = Color.White) } }
        )
    }

    removeConnection?.let { connection ->
        AlertDialog(
            onDismissRequest = { removeConnection = null },
            title = { Text("Rimuovi ${connection.label}") },
            text = { Text("La connessione endpoint verrà eliminata dal dispositivo. I bot su questa connessione smetteranno di funzionare.") },
            confirmButton = {
                IconButton(
                    onClick = {
                        removeConnection = null
                        scope.launch {
                            runCatching { deleteHermesBotConnection(context, settings, connection.id) }
                                .onSuccess { status = "Connessione rimossa."; refreshNonce++ }
                                .onFailure { status = it.message ?: "Connessione non rimossa." }
                        }
                    }
                ) { Icon(Icons.Rounded.Delete, contentDescription = "Conferma rimozione connessione", tint = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { IconButton(onClick = { removeConnection = null }) { Icon(Icons.Rounded.Close, contentDescription = "Annulla", tint = Color.White) } }
        )
    }

    if (showConnectionEditor) {
        AlertDialog(
            onDismissRequest = { if (!mutating) showConnectionEditor = false },
            title = { Text("Nuova connessione Hermes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(connectionLabelInput, { connectionLabelInput = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(connectionEndpointInput, { connectionEndpointInput = it }, label = { Text("Endpoint HTTPS/HTTP") }, singleLine = true)
                    OutlinedTextField(
                        connectionTokenInput,
                        { connectionTokenInput = it },
                        label = { Text("Token API (opzionale)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Text("Il token viene salvato solo nello storage sicuro del dispositivo.", color = AppColors.Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                IconButton(
                    enabled = connectionLabelInput.isNotBlank() && connectionEndpointInput.isNotBlank() && !mutating,
                    onClick = {
                        mutating = true
                        scope.launch {
                            var created: HermesBotConnection? = null
                            runCatching {
                                val added = addHermesBotConnection(context, settings, connectionLabelInput, connectionEndpointInput)
                                created = added
                                check(saveGatewayConnectionSecret(context, added.id, connectionTokenInput)) { "Token non salvato nello storage sicuro." }
                            }.onSuccess {
                                status = "Connessione salvata."
                                showConnectionEditor = false
                                refreshNonce++
                            }.onFailure { error ->
                                created?.let { runCatching { deleteHermesBotConnection(context, settings, it.id) } }
                                status = error.message ?: "Connessione non salvata."
                            }
                            mutating = false
                        }
                    }
                ) { Icon(Icons.Rounded.Save, contentDescription = "Salva connessione", tint = Color.White) }
            },
            dismissButton = { IconButton(onClick = { showConnectionEditor = false }, enabled = !mutating) { Icon(Icons.Rounded.Close, contentDescription = "Annulla", tint = Color.White) } }
        )
    }
}

private fun groupOutcomeLabel(outcome: String): String = when (outcome.lowercase()) {
    "reply" -> "Risposte ricevute"
    "bounded" -> "Limitato a 3 turni e 10 risposte"
    "pass" -> "Nessun intervento"
    "partial" -> "Parziale: alcuni membri falliti"
    "error" -> "Errore"
    "escalation" -> "Escalation"
    else -> outcome
}

/** Colore avatar deterministico dal profilo. Puro, testabile. */
internal fun botAvatarColor(profile: String): androidx.compose.ui.graphics.Color {
    val hue = kotlin.math.abs(profile.hashCode() % 360).toFloat()
    return androidx.compose.ui.graphics.Color.hsl(hue, 0.45f, 0.42f)
}

@Composable
internal fun BotAvatar(name: String, profile: String, size: androidx.compose.ui.unit.Dp = 52.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(botAvatarColor(profile)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            (name.trim().firstOrNull()?.uppercase() ?: "?"),
            color = Color.White,
            fontSize = (size.value / 2.4f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun BotRosterCard(
    bot: HermesBotItem,
    screenRunning: Boolean,
    screenPreview: Bitmap?,
    chatSupported: Boolean,
    busy: Boolean,
    onOpenDetail: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenScreen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    // Menu contestuale stile desktop (long-press / ⋮).
    pinned: Boolean = false,
    hiddenBadge: Boolean = false,
    // Anteprima ultimo messaggio stile desktop (dal registro canonico).
    previewText: String? = null,
    previewTime: String? = null,
    menu: BotMenuHost? = null,
    onMenuRequest: () -> Unit = {}
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClickLabel = "Apri dettaglio bot",
                    onLongClickLabel = "Opzioni bot",
                    onClick = onOpenDetail,
                    onLongClick = onMenuRequest
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    BotAvatar(bot.displayName, bot.profile)
                    Box(
                        modifier = Modifier.size(14.dp).clip(CircleShape)
                            .background(if (screenRunning) Color(0xFF4CAF50) else AppColors.Faint)
                            .align(Alignment.BottomEnd)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(bot.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f, fill = false))
                        if (pinned) Text("FISSATO", color = AppColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        if (hiddenBadge) Text("NASCOSTO", color = AppColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                        Text("@${bot.handle} · ${bot.connectionLabel}", color = AppColors.Muted, fontSize = 12.sp)
                    if (!previewText.isNullOrBlank()) {
                        Text(
                            (if (!previewTime.isNullOrBlank()) "$previewTime · " else "") + previewText,
                            color = AppColors.Muted,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        if (screenRunning) "Schermo live" else if (bot.isDefault) "Profilo predefinito" else bot.profile,
                        color = if (screenRunning) Color(0xFF4CAF50) else AppColors.Faint,
                        fontSize = 11.sp
                    )
                }
                Box {
                    IconButton(onClick = onMenuRequest) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Opzioni bot", tint = Color.White)
                    }
                    // Solo la card con menuFor == key compone il DropdownMenu
                    // (hoist in BotsScreen: le altre passano menu = null).
                    if (menu != null && menu.expanded) {
                        BotCardMenu(bot = bot, host = menu)
                    }
                }
                IconButton(onClick = onOpenChat, enabled = !busy && chatSupported) {
                    Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "Apri Bot Chat", tint = Color.White)
                }
            }
            if (screenRunning && screenPreview != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color.Black)
                        .clickable(onClick = onOpenScreen)
                ) {
                    Image(
                        bitmap = screenPreview.asImageBitmap(),
                        contentDescription = "Anteprima schermo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            if (!bot.description.isBlank()) Text(bot.description, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onOpenScreen, enabled = screenRunning) { Text("Apri live") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Modifica bot", tint = Color.White) }
                if (canDelete) {
                    IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Elimina bot", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private enum class BotDetailTab(val label: String) { Chat("Chat"), Screen("Screen"), Info("Info") }

@Composable
internal fun BotDetailScreen(
    bot: HermesBotItem,
    context: Context,
    settings: AppSettings,
    screenStatus: ScreenStatusInfo?,
    screenPreview: Bitmap?,
    onBack: () -> Unit,
    onOpenChat: (HermesBotItem) -> Unit,
    onOpenScreen: () -> Unit,
    onOpenCron: () -> Unit,
    // Disabilita "Apri" mentre l'open esterno e in corso (anti doppio tap).
    busy: Boolean = false
) {
    // applicationContext: remember/static non trattengono mai l'Activity.
    val appContext = context.applicationContext
    var tab by remember(bot.identityKey) { mutableStateOf(BotDetailTab.Chat) }
    var autoMode by remember(bot.identityKey) {
        mutableStateOf(loadBotAutoApproveMap(appContext)[bot.profile] ?: "off")
    }
    Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = Color.White)
            }
            BotAvatar(bot.displayName, bot.profile, 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(bot.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text(
                    "@${bot.handle} · " + if (screenStatus?.running == true) "schermo live" else "schermo spento",
                    color = AppColors.Muted, fontSize = 12.sp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BotDetailTab.values().forEach { entry ->
                val selected = tab == entry
                TextButton(
                    onClick = { tab = entry },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        entry.label,
                        color = if (selected) AppColors.Accent else Color.White,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        when (tab) {
            BotDetailTab.Chat -> {
                if (bot.description.isNotBlank()) Text(bot.description, color = Color.White, fontSize = 14.sp)
                Button(
                    // Delega all'handler esterno (singola POST persistente):
                    // prima faceva POST qui + POST fuori (doppia sessione).
                    onClick = { onOpenChat(bot) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
                ) { Text("Apri Bot Chat", color = Color(0xFF171009)) }
                Text("Auto-approvazione run di ${bot.displayName}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, value) in listOf("Chiedi" to WorkLimits.AUTO_APPROVE_OFF, "Sessione" to WorkLimits.AUTO_APPROVE_SESSION, "Sempre" to WorkLimits.AUTO_APPROVE_ALWAYS)) {
                        val selected = autoMode == value
                        TextButton(onClick = {
                            saveBotAutoApprove(appContext, bot.profile, value)
                            autoMode = value
                        }) { Text(if (selected) "✓ $label" else label) }
                    }
                }
                Text("Mai deny automatico; ogni auto-approvazione resta visibile.", color = AppColors.Faint, fontSize = 11.sp)
            }
            BotDetailTab.Screen -> {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    ScreenScreen(context = context, settings = settings)
                }
            }
            BotDetailTab.Info -> {
                Text("Profilo: ${bot.profile}", color = Color.White, fontSize = 13.sp)
                Text("Connessione: ${bot.connectionLabel}", color = AppColors.Muted, fontSize = 12.sp)
                if (bot.description.isNotBlank()) Text(bot.description, color = Color.White, fontSize = 13.sp)
                OutlinedButton(onClick = onOpenCron) { Text("Routine e Cron") }
                OutlinedButton(onClick = onOpenScreen) { Text("Apri schermo live") }
            }
        }
    }
}
