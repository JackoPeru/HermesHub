package com.nemoclaw.chat.features.bots

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.httpGetResponse
import com.nemoclaw.chat.HermesSession
import com.nemoclaw.chat.HermesSessionClient
import com.nemoclaw.chat.normalizeHermesProfileName
import com.nemoclaw.chat.resolveHermesUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sessioni recenti del bot (non canoniche), dalle piu attive: la
 * forever-chat "Bot Chat" si apre da "Apri Bot Chat", qui non compare.
 * Puro e testabile.
 */
internal fun selectRecentBotSessions(rows: List<HermesSession>, limit: Int = 20): List<HermesSession> {
    if (limit <= 0) return emptyList()
    return rows.filterNot { isCanonicalBotRow(it) }
        .sortedWith(
            compareByDescending<HermesSession> { it.raw?.optDouble("last_active", 0.0) ?: 0.0 }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.createdAt }
                .thenBy { it.id }
        )
        .take(limit)
}

/** Client sessioni con lo stesso scope della risoluzione canonica (profilo/multiplex). */
private suspend fun botSessionClient(
    context: Context,
    settings: AppSettings,
    bot: HermesBotItem,
    rosterMultiplex: Boolean
): HermesSessionClient {
    val connection = withContext(Dispatchers.IO) { connectionForBot(context.applicationContext, settings, bot) }
    val effective = settingsForBotConnection(settings, connection)
    val secret = secretForBotConnection(context, connection)
    return HermesSessionClient(effective, secret, bot.profile, rosterMultiplex, null)
}

/** Nuova chat pulita col bot: sessione vuota (mai fork della forever-chat). */
internal suspend fun createBotChatSession(
    context: Context,
    settings: AppSettings,
    bot: HermesBotItem,
    rosterMultiplex: Boolean
): Result<HermesSession> = runCatching {
    val client = botSessionClient(context, settings, bot, rosterMultiplex)
    val (code, created) = client.create(title = newBotChatTitle(bot), source = "hermes-hub-android")
    if (code !in 200..299 || created == null) error("Nuova chat non creata (HTTP $code).")
    created
}

/** Sessioni recenti del bot dal server (scope canonico). */
internal suspend fun listBotRecentSessions(
    context: Context,
    settings: AppSettings,
    bot: HermesBotItem,
    rosterMultiplex: Boolean,
    limit: Int = 20
): Result<List<HermesSession>> = runCatching {
    val client = botSessionClient(context, settings, bot, rosterMultiplex)
    val all = mutableListOf<HermesSession>()
    var offset = 0
    var code = 200
    for (page in 0 until 3) {
        val (pageCode, pageRows) = client.list(limit = 100, offset = offset, includeHidden = true)
        code = pageCode
        if (code !in 200..299) error("Sessioni non leggibili (HTTP $code).")
        if (pageRows.isEmpty()) break
        all.addAll(pageRows)
        if (pageRows.size < 100) break
        offset += 100
    }
    selectRecentBotSessions(all, limit)
}

/** Contesto chat per una sessione arbitraria del bot (nuova o recente). */
internal suspend fun botSessionContext(
    context: Context,
    settings: AppSettings,
    bot: HermesBotItem,
    rosterMultiplex: Boolean,
    session: HermesSession
): Result<BotChatContext> = runCatching {
    val connection = withContext(Dispatchers.IO) { connectionForBot(context.applicationContext, settings, bot) }
    BotChatContext(
        profile = bot.profile,
        sessionId = session.id,
        displayName = bot.displayName,
        localConversationId = stableBotSessionConversationId(bot, session.id),
        multiplexEnabled = rosterMultiplex,
        connectionId = connection.id,
        endpoint = connection.endpoint
    )
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

