package com.nemoclaw.chat.features.bots

import android.content.Context
import androidx.core.content.edit
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.executeHttpGet
import com.nemoclaw.chat.executeJsonRequest
import com.nemoclaw.chat.httpGetResponse
import com.nemoclaw.chat.loadGatewayConnectionSecret
import com.nemoclaw.chat.loadGatewaySecret
import com.nemoclaw.chat.normalizeHermesProfileName
import com.nemoclaw.chat.postJson
import com.nemoclaw.chat.resolveHermesUrl
import com.nemoclaw.chat.core.WorkLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class HermesBotConnection(
    val id: String,
    val label: String,
    val endpoint: String,
    val enabled: Boolean = true,
    val isPrimary: Boolean = false
)

internal data class HermesBotConnectionRegistry(
    val items: List<HermesBotConnection>,
    val warnings: List<String>
)

internal data class HermesBotGroupMember(
    val connectionId: String,
    val profile: String,
    val displayName: String,
    val handle: String,
    val identityKey: String
)

internal data class HermesBotGroup(
    val name: String,
    val members: List<HermesBotGroupMember>
)

private const val CONNECTIONS_PREFS = "hermes_bot_connections"
private const val CONNECTIONS_JSON_KEY = "items"
private const val GROUPS_PREFS = "hermes_bot_groups"
private const val GROUPS_JSON_KEY = "items"
private const val BOT_GROUPS_PENDING_PUSH_KEY = "pending_push"
private const val BOT_GROUPS_PENDING_DELETE_KEY = "pending_delete"
private const val BOT_GROUPS_MIGRATED_KEY = "migrated_v1"

private fun botGroupsMigrated(context: Context): Boolean =
    context.applicationContext.getSharedPreferences(GROUPS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(BOT_GROUPS_MIGRATED_KEY, false)

private fun markBotGroupsMigrated(context: Context) {
    context.applicationContext.getSharedPreferences(GROUPS_PREFS, Context.MODE_PRIVATE)
        .edit { putBoolean(BOT_GROUPS_MIGRATED_KEY, true) }
}

/** 4xx definitivi (validazione/rifiuto) escono dai pending; 0/5xx restano. */
private fun keepPendingPush(status: Int): Boolean = status == 0 || status >= 500

internal fun loadHermesBotConnections(context: Context, settings: AppSettings): HermesBotConnectionRegistry {
    val warnings = mutableListOf<String>()
    val primaryEndpoint = normalizeBotEndpoint(settings.gatewayUrl, allowEmpty = true)
    val result = mutableListOf(
        HermesBotConnection("primary", "Gateway principale", primaryEndpoint, primaryEndpoint.isNotBlank(), true)
    )
    val raw = context.applicationContext.getSharedPreferences(CONNECTIONS_PREFS, Context.MODE_PRIVATE)
        .getString(CONNECTIONS_JSON_KEY, null)
        ?: return HermesBotConnectionRegistry(result, warnings)
    runCatching {
        val array = JSONArray(raw)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                warnings += "Riga connessione ${index + 1} ignorata: dati non validi."
                continue
            }
            runCatching {
                val id = item.optString("id").trim()
                val label = item.optString("label").trim()
                val endpoint = normalizeBotEndpoint(item.optString("endpoint"), allowEmpty = false)
                require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")))
                require(!id.equals("primary", true))
                require(label.isNotBlank() && label.length <= 120)
                result += HermesBotConnection(id, label, endpoint, item.optBoolean("enabled", true), false)
            }.onFailure {
                warnings += "Riga connessione ${index + 1} ignorata: dati non validi."
            }
        }
    }.onFailure {
        warnings += "Registry connessioni illeggibile: righe custom ignorate."
    }
    val unique = mutableListOf<HermesBotConnection>()
    val ids = mutableSetOf<String>()
    val labels = mutableSetOf<String>()
    val endpoints = mutableSetOf<String>()
    result.forEach { connection ->
        val duplicate = !ids.add(connection.id.lowercase()) ||
            !labels.add(connection.label.lowercase()) ||
            (connection.endpoint.isNotBlank() && !endpoints.add(connection.endpoint.lowercase()))
        if (duplicate) warnings += "Connessione ${connection.id} ignorata: label o endpoint duplicato."
        else unique += connection
    }
    return HermesBotConnectionRegistry(unique, warnings)
}

internal fun addHermesBotConnection(context: Context, settings: AppSettings, label: String, endpoint: String): HermesBotConnection {
    val connection = HermesBotConnection(
        id = "connection-${UUID.randomUUID().toString().replace("-", "").take(20)}",
        label = label.trim().take(WorkLimits.TRUNC_120).ifBlank { error("Nome connessione obbligatorio.") },
        endpoint = normalizeBotEndpoint(endpoint, allowEmpty = false)
    )
    upsertHermesBotConnection(context, settings, connection)
    return connection
}

internal fun upsertHermesBotConnection(context: Context, settings: AppSettings, connection: HermesBotConnection) {
    val normalized = connection.copy(
        id = connection.id.trim(),
        label = connection.label.trim().take(WorkLimits.TRUNC_120).ifBlank { error("Nome connessione obbligatorio.") },
        endpoint = normalizeBotEndpoint(connection.endpoint, allowEmpty = false),
        isPrimary = false
    )
    require(normalized.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")))
    require(!normalized.id.equals("primary", true))
    val registry = loadHermesBotConnections(context, settings)
    val existing = registry.items.filterNot { it.id.equals(normalized.id, true) }
    require(existing.none { it.label.equals(normalized.label, true) || it.endpoint.equals(normalized.endpoint, true) }) {
        "Label o endpoint già usato da un'altra connessione Hermes."
    }
    val array = JSONArray()
    (existing.filterNot { it.isPrimary } + normalized).forEach { item ->
        array.put(JSONObject().put("id", item.id).put("label", item.label).put("endpoint", item.endpoint).put("enabled", item.enabled))
    }
    context.applicationContext.getSharedPreferences(CONNECTIONS_PREFS, Context.MODE_PRIVATE).edit { putString(CONNECTIONS_JSON_KEY, array.toString()) }
}

internal fun deleteHermesBotConnection(context: Context, settings: AppSettings, connectionId: String) {
    require(!connectionId.equals("primary", true))
    val registry = loadHermesBotConnections(context, settings)
    val array = JSONArray()
    registry.items.filterNot { it.isPrimary || it.id.equals(connectionId, true) }.forEach { item ->
        array.put(JSONObject().put("id", item.id).put("label", item.label).put("endpoint", item.endpoint).put("enabled", item.enabled))
    }
    context.applicationContext.getSharedPreferences(CONNECTIONS_PREFS, Context.MODE_PRIVATE).edit { putString(CONNECTIONS_JSON_KEY, array.toString()) }
    com.nemoclaw.chat.deleteGatewayConnectionSecret(context, connectionId)
}

internal fun loadHermesBotGroups(context: Context): List<HermesBotGroup> {
    val raw = context.applicationContext.getSharedPreferences(GROUPS_PREFS, Context.MODE_PRIVATE)
        .getString(GROUPS_JSON_KEY, null) ?: return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return parseHermesBotGroupsJson(array)
}

private fun parseHermesBotGroupsJson(array: JSONArray): List<HermesBotGroup> {
    val result = mutableListOf<HermesBotGroup>()
    for (index in 0 until array.length()) {
        val parsed = runCatching {
            val item = array.getJSONObject(index)
            val name = item.optString("name").trim()
            val membersArray = item.optJSONArray("members") ?: error("membri assenti")
            val members = buildList {
                for (memberIndex in 0 until membersArray.length()) {
                    val member = membersArray.getJSONObject(memberIndex)
                    val connectionId = member.optString("connection_id").trim()
                    val profile = normalizeHermesProfileName(member.optString("profile"))
                    val identityKey = "$connectionId::$profile"
                    add(HermesBotGroupMember(
                        connectionId = connectionId,
                        profile = profile,
                        displayName = member.optString("display_name", profile),
                        handle = member.optString("handle", profile),
                        identityKey = identityKey
                    ))
                }
            }
            normalizeHermesBotGroup(HermesBotGroup(name, members))
        }.getOrNull()
        if (parsed != null) result += parsed
    }
    return result
}

private fun hermesBotGroupJson(item: HermesBotGroup): JSONObject = JSONObject().put("name", item.name).put("members", JSONArray().apply {
    item.members.forEach { member ->
        put(JSONObject()
            .put("connection_id", member.connectionId)
            .put("profile", member.profile)
            .put("display_name", member.displayName)
            .put("handle", member.handle))
    }
})

private fun saveHermesBotGroupsCache(context: Context, groups: List<HermesBotGroup>) {
    val array = JSONArray()
    groups.forEach { group -> array.put(hermesBotGroupJson(group)) }
    context.applicationContext.getSharedPreferences(GROUPS_PREFS, Context.MODE_PRIVATE)
        .edit { putString(GROUPS_JSON_KEY, array.toString()) }
}

private fun loadPendingGroupNames(context: Context, key: String): List<String> {
    val raw = context.applicationContext.getSharedPreferences(GROUPS_PREFS, Context.MODE_PRIVATE)
        .getString(key, null) ?: return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val name = array.optString(index).trim()
            if (name.isNotBlank()) add(name)
        }
    }
}

private fun savePendingGroupNames(context: Context, key: String, names: Collection<String>) {
    val array = JSONArray()
    names.distinctBy { it.lowercase() }.forEach { array.put(it) }
    context.applicationContext.getSharedPreferences(GROUPS_PREFS, Context.MODE_PRIVATE)
        .edit { putString(key, array.toString()) }
}

private fun addPendingGroupName(context: Context, key: String, name: String) {
    val current = loadPendingGroupNames(context, key).filterNot { it.equals(name, true) }
    savePendingGroupNames(context, key, current + name.trim())
}

private fun removePendingGroupName(context: Context, key: String, name: String) {
    val remaining = loadPendingGroupNames(context, key).filterNot { it.equals(name, true) }
    savePendingGroupNames(context, key, remaining)
}

internal fun upsertHermesBotGroup(context: Context, group: HermesBotGroup) {
    val normalized = normalizeHermesBotGroup(group)
    val existing = loadHermesBotGroups(context).filterNot { it.name.equals(normalized.name, true) }
    saveHermesBotGroupsCache(context, existing + normalized)
}

internal fun deleteHermesBotGroup(context: Context, name: String) {
    val normalized = name.trim()
    val remaining = loadHermesBotGroups(context).filterNot { it.name.equals(normalized, true) }
    saveHermesBotGroupsCache(context, remaining)
}

private fun normalizeHermesBotGroup(group: HermesBotGroup): HermesBotGroup {
    val name = group.name.trim()
    require(name.isNotBlank() && name.length <= 160 && !name.contains('\n') && !name.contains('\r')) { "Nome gruppo non valido." }
    require(group.members.size in 2..6) { "Un gruppo Hermes deve contenere da 2 a 6 bot." }
    val members = group.members.map { member ->
        val connectionId = member.connectionId.trim()
        val profile = normalizeHermesProfileName(member.profile)
        require(connectionId.isNotBlank()) { "Identificativo connessione gruppo assente." }
        member.copy(
            connectionId = connectionId,
            profile = profile,
            displayName = member.displayName.trim().ifBlank { profile },
            handle = member.handle.trim().removePrefix("@").ifBlank { profile },
            identityKey = "$connectionId::$profile"
        )
    }
    require(members.map { it.identityKey.lowercase() }.toSet().size == members.size) { "Identità gruppo duplicata." }
    return HermesBotGroup(name, members)
}

internal fun settingsForBotConnection(settings: AppSettings, connection: HermesBotConnection): AppSettings {
    require(connection.endpoint.isNotBlank()) { "Endpoint Hermes esplicito non configurato." }
    return settings.copy(
        gatewayUrl = connection.endpoint,
        gatewayWsUrl = "",
        inferenceEndpoint = connection.endpoint,
        adminBridgeUrl = connection.endpoint
    )
}

internal fun normalizeBotEndpoint(value: String, allowEmpty: Boolean): String {
    val normalized = value.trim().trimEnd('/')
    if (normalized.isBlank() && allowEmpty) return ""
    val uri = URI(normalized)
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
    require(!uri.host.isNullOrBlank() && uri.userInfo.isNullOrBlank() && uri.query.isNullOrBlank() && uri.fragment.isNullOrBlank())
    return normalized
}

internal suspend fun botGetForConnection(
    settings: AppSettings,
    connection: HermesBotConnection,
    apiKey: String?,
    path: String
): Pair<Int, String> {
    val effective = settingsForBotConnection(settings, connection)
    val url = resolveHermesUrl(effective, path)
    return if (connection.isPrimary) {
        httpGetResponse(url, apiKey)
    } else {
        withContext(Dispatchers.IO) { runCatching { executeHttpGet(url, apiKey) }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) } }
    }
}

internal suspend fun botPostForConnection(
    settings: AppSettings,
    connection: HermesBotConnection,
    apiKey: String?,
    path: String,
    payload: JSONObject,
    method: String = "POST"
): Pair<Int, String> {
    val effective = settingsForBotConnection(settings, connection)
    val url = resolveHermesUrl(effective, path)
    return if (connection.isPrimary) {
        postJson(url, payload, apiKey, method = method)
    } else {
        withContext(Dispatchers.IO) { runCatching { executeJsonRequest(url, payload, method, apiKey) }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) } }
    }
}

internal fun connectionForBot(context: Context, settings: AppSettings, bot: HermesBotItem): HermesBotConnection =
    loadHermesBotConnections(context, settings).items.firstOrNull { it.id.equals(bot.connectionId, true) }
        ?: error("Connessione Hermes non trovata.")

internal fun secretForBotConnection(context: Context, connection: HermesBotConnection): String? =
    if (connection.isPrimary) loadGatewaySecret(context) else loadGatewayConnectionSecret(context, connection.id)

/** Id secondari da cancellare nel reset (puro/testabile: esclude il primario). */
internal fun connectionIdsForReset(registry: HermesBotConnectionRegistry): List<String> =
    registry.items.filterNot { it.isPrimary }.map { it.id }

/**
 * Reset privacy: cancella i secret delle connessioni secondarie e svuota
 * registry + gruppi locali. Mai backup esterni (file export intatti).
 */
internal fun clearHermesBotConnections(context: Context, settings: AppSettings) {
    val registry = runCatching { loadHermesBotConnections(context, settings) }
        .getOrDefault(HermesBotConnectionRegistry(emptyList(), emptyList()))
    for (id in connectionIdsForReset(registry)) {
        runCatching { com.nemoclaw.chat.deleteGatewayConnectionSecret(context, id) }
    }
    runCatching {
        context.applicationContext
            .getSharedPreferences(CONNECTIONS_PREFS, Context.MODE_PRIVATE)
            .edit { clear() }
    }
    runCatching {
        context.applicationContext
            .getSharedPreferences(GROUPS_PREFS, Context.MODE_PRIVATE)
            .edit { clear() }
    }
}

private fun parseHermesBotGroupsServerPayload(body: String): List<HermesBotGroup> {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
    val array = json.optJSONArray("items") ?: JSONArray()
    return parseHermesBotGroupsJson(array)
}

private suspend fun hermesBotGroupsGet(context: Context, settings: AppSettings): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        runCatching { executeHttpGet(resolveHermesUrl(settings, "/v1/hub/bot-groups"), loadGatewaySecret(context)) }
            .getOrElse { 0 to (it.message ?: it.javaClass.simpleName) }
    }

private suspend fun hermesBotGroupsDelete(context: Context, settings: AppSettings, name: String): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            executeJsonRequest(resolveHermesUrl(settings, "/v1/hub/bot-groups/$encoded"), JSONObject(), "DELETE", loadGatewaySecret(context))
        }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) }
    }

private suspend fun hermesBotGroupsPut(context: Context, settings: AppSettings, item: HermesBotGroup): Pair<Int, String> =
    postJson(resolveHermesUrl(settings, "/v1/hub/bot-groups"), hermesBotGroupJson(item), loadGatewaySecret(context))

/**
 * Elenco gruppi con sincronizzazione gateway: il server è fonte di verità
 * quando raggiungibile, la cache locale fa da fallback offline. I pending
 * push/delete catturati offline vengono riverificati al primo load online
 * prima di sovrascrivere la cache, così nessun'azione utente si perde.
 * Al primo load online i gruppi solo-locali (creati prima del sync) vengono
 * migrati sul server invece di essere cancellati; poi vale il server.
 */
internal suspend fun loadHermesBotGroupsSynced(context: Context, settings: AppSettings): List<HermesBotGroup> {
    val local = loadHermesBotGroups(context)
    val (status, body) = hermesBotGroupsGet(context, settings)
    if (status == 0 || status !in 200..299) return local

    val pendingDelete = loadPendingGroupNames(context, BOT_GROUPS_PENDING_DELETE_KEY)
    if (pendingDelete.isNotEmpty()) {
        val remaining = mutableListOf<String>()
        for (name in pendingDelete) {
            val (deleteStatus, _) = hermesBotGroupsDelete(context, settings, name)
            if (deleteStatus == 0) remaining += name
        }
        savePendingGroupNames(context, BOT_GROUPS_PENDING_DELETE_KEY, remaining)
    }

    val pendingPush = loadPendingGroupNames(context, BOT_GROUPS_PENDING_PUSH_KEY)
    if (pendingPush.isNotEmpty()) {
        val remaining = mutableListOf<String>()
        for (name in pendingPush) {
            val group = local.firstOrNull { it.name.equals(name, true) } ?: continue
            val (putStatus, _) = hermesBotGroupsPut(context, settings, group)
            if (keepPendingPush(putStatus)) remaining += name
        }
        savePendingGroupNames(context, BOT_GROUPS_PENDING_PUSH_KEY, remaining)
    }

    val (refreshStatus, refreshBody) = hermesBotGroupsGet(context, settings)
    val items = if (refreshStatus in 200..299) refreshBody else body
    val serverNames = parseHermesBotGroupsServerPayload(items).map { it.name.lowercase() }.toSet()
    if (!botGroupsMigrated(context)) {
        // Prima sincronizzazione: i gruppi locali assenti sul server vengono
        // caricati (migrazione), non cancellati. I rifiuti definitivi (4xx)
        // escono dai pending per non riprovare all'infinito.
        for (group in local) {
            if (serverNames.contains(group.name.lowercase())) continue
            if (loadPendingGroupNames(context, BOT_GROUPS_PENDING_PUSH_KEY)
                    .any { it.equals(group.name, true) }) continue
            val (putStatus, _) = hermesBotGroupsPut(context, settings, group)
            if (keepPendingPush(putStatus)) {
                addPendingGroupName(context, BOT_GROUPS_PENDING_PUSH_KEY, group.name)
            }
        }
        markBotGroupsMigrated(context)
        val (finalStatus, finalBody) = hermesBotGroupsGet(context, settings)
        val finalItems = if (finalStatus in 200..299) finalBody else items
        val parsed = parseHermesBotGroupsServerPayload(finalItems)
        saveHermesBotGroupsCache(context, parsed)
        return parsed
    }
    val parsed = parseHermesBotGroupsServerPayload(items)
    saveHermesBotGroupsCache(context, parsed)
    return parsed
}

/**
 * Upsert con conferma gateway: 2xx aggiorna la cache speculare, offline
 * (trasporto 0) scrive solo in locale con push differito, gli altri stati
 * propaga l'errore senza toccare la cache.
 */
internal suspend fun upsertHermesBotGroupSynced(context: Context, settings: AppSettings, group: HermesBotGroup): String {
    val normalized = normalizeHermesBotGroup(group)
    val (status, _) = hermesBotGroupsPut(context, settings, normalized)
    when {
        status == 0 -> {
            upsertHermesBotGroup(context, normalized)
            addPendingGroupName(context, BOT_GROUPS_PENDING_PUSH_KEY, normalized.name)
            removePendingGroupName(context, BOT_GROUPS_PENDING_DELETE_KEY, normalized.name)
            return "Gruppo salvato in locale; sincronizzazione con il gateway in sospeso."
        }
        status !in 200..299 -> error("Gateway ha rifiutato il gruppo (HTTP $status).")
    }
    upsertHermesBotGroup(context, normalized)
    removePendingGroupName(context, BOT_GROUPS_PENDING_PUSH_KEY, normalized.name)
    removePendingGroupName(context, BOT_GROUPS_PENDING_DELETE_KEY, normalized.name)
    return "Gruppo sincronizzato con il gateway."
}

/**
 * Cancellazione con conferma gateway: 2xx/404 confermano l'assenza, offline
 * rimuove in locale con delete differito, 5xx propaga l'errore mantenendo i dati.
 */
internal suspend fun deleteHermesBotGroupSynced(context: Context, settings: AppSettings, name: String): String {
    val normalized = name.trim()
    val (status, _) = hermesBotGroupsDelete(context, settings, normalized)
    when {
        status != 0 && status != 404 && status !in 200..299 ->
            error("Gateway non ha confermato la cancellazione (HTTP $status).")
        status == 0 -> addPendingGroupName(context, BOT_GROUPS_PENDING_DELETE_KEY, normalized)
        else -> removePendingGroupName(context, BOT_GROUPS_PENDING_DELETE_KEY, normalized)
    }
    removePendingGroupName(context, BOT_GROUPS_PENDING_PUSH_KEY, normalized)
    deleteHermesBotGroup(context, normalized)
    if (status == 0) return "Gruppo rimosso in locale; cancellazione dal gateway in sospeso."
    return "Gruppo rimosso dal gateway."
}
