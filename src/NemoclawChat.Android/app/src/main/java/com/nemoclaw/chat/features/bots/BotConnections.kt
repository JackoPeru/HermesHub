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
        label = label.trim().take(120).ifBlank { error("Nome connessione obbligatorio.") },
        endpoint = normalizeBotEndpoint(endpoint, allowEmpty = false)
    )
    upsertHermesBotConnection(context, settings, connection)
    return connection
}

internal fun upsertHermesBotConnection(context: Context, settings: AppSettings, connection: HermesBotConnection) {
    val normalized = connection.copy(
        id = connection.id.trim(),
        label = connection.label.trim().take(120).ifBlank { error("Nome connessione obbligatorio.") },
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
    val result = mutableListOf<HermesBotGroup>()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
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

internal fun upsertHermesBotGroup(context: Context, group: HermesBotGroup) {
    val normalized = normalizeHermesBotGroup(group)
    val existing = loadHermesBotGroups(context).filterNot { it.name.equals(normalized.name, true) }
    val array = JSONArray()
    (existing + normalized).forEach { item ->
        array.put(JSONObject().put("name", item.name).put("members", JSONArray().apply {
            item.members.forEach { member ->
                put(JSONObject()
                    .put("connection_id", member.connectionId)
                    .put("profile", member.profile)
                    .put("display_name", member.displayName)
                    .put("handle", member.handle))
            }
        }))
    }
    context.applicationContext.getSharedPreferences(GROUPS_PREFS, Context.MODE_PRIVATE)
        .edit { putString(GROUPS_JSON_KEY, array.toString()) }
}

internal fun deleteHermesBotGroup(context: Context, name: String) {
    val normalized = name.trim()
    val array = JSONArray()
    loadHermesBotGroups(context).filterNot { it.name.equals(normalized, true) }.forEach { group ->
        array.put(JSONObject().put("name", group.name).put("members", JSONArray().apply {
            group.members.forEach { member ->
                put(JSONObject().put("connection_id", member.connectionId).put("profile", member.profile).put("display_name", member.displayName).put("handle", member.handle))
            }
        }))
    }
    context.applicationContext.getSharedPreferences(GROUPS_PREFS, Context.MODE_PRIVATE)
        .edit { putString(GROUPS_JSON_KEY, array.toString()) }
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
