package com.nemoclaw.chat.features.bots

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.httpGetResponse
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private const val CONNECTION_ROSTER_TIMEOUT_MILLIS = 15_000L

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
)

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

internal suspend fun openHermesBotChat(
    context: Context,
    settings: AppSettings,
    bot: HermesBotItem,
    apiKey: String? = null
): Result<BotChatContext> = withContext(Dispatchers.IO) {
    runCatching {
        val connection = connectionForBot(context, settings, bot)
        val effective = settingsForBotConnection(settings, connection)
        val secret = secretForBotConnection(context, connection) ?: apiKey
        val encoded = URLEncoder.encode(normalizeHermesProfileName(bot.profile), "UTF-8")
        val response = botPostForConnection(
            effective,
            connection,
            secret,
            "/v1/hub/bots/$encoded/chat",
            JSONObject(),
            method = "POST"
        )
        if (response.first !in 200..299) error(safeBotError(response.first, response.second, "Chat bot non disponibile"))
        val root = JSONObject(response.second)
        val multiplex = root.optBoolean("multiplex_enabled", false)
        val supported = root.optBoolean("chat_supported", false)
        val sessionId = root.optString("session_id").trim()
        if (!multiplex || !supported || sessionId.isBlank()) error("Chat bot rifiutata: multiplexing Hermes non pronto.")
        BotChatContext(
            profile = root.optString("profile", bot.profile),
            sessionId = sessionId,
            displayName = bot.displayName,
            localConversationId = "bot-${bot.connectionId}-${bot.profile}-${sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}",
            multiplexEnabled = multiplex,
            connectionId = connection.id,
            endpoint = connection.endpoint
        )
    }
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
    onOpenBot: (BotChatContext) -> Unit
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
    var deleteBot by remember { mutableStateOf<HermesBotItem?>(null) }
    var deleteConfirmation by remember { mutableStateOf("") }
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
    val scope = rememberCoroutineScope()

    fun openEditor(bot: HermesBotItem?) {
        editorBot = bot
        profileInput = bot?.profile.orEmpty()
        displayNameInput = bot?.displayName.orEmpty()
        descriptionInput = bot?.description.orEmpty()
        soulInput = ""
        selectedConnectionId = bot?.connectionId ?: "primary"
        showEditor = true
    }

    LaunchedEffect(settings.gatewayUrl, refreshNonce) {
        connections = loadHermesBotConnections(context, settings)
        groups = loadHermesBotGroups(context)
        val result = loadAllHermesBotRosters(context, settings)
        roster = result
        status = result.status
        if (!result.chatSupported) status += " Apri Bot Chat richiede multiplexing profili attivo."
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bot Hermes", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("Profili reali con configurazione, memoria, skill e credenziali separate. Le routine bot richiedono multiplexing attivo.", color = AppColors.Muted, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openEditor(null) }) { Text("Nuovo bot") }
                    Button(onClick = {
                        connectionLabelInput = ""
                        connectionEndpointInput = ""
                        connectionTokenInput = ""
                        showConnectionEditor = true
                    }) { Text("Connessioni") }
                    Button(onClick = { refreshNonce++ }) { Text("Aggiorna") }
                }
            }
            Text(status, color = AppColors.Muted, modifier = Modifier.padding(top = 12.dp))
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
                                TextButton(onClick = {
                                    runCatching { deleteHermesBotConnection(context, settings, connection.id) }
                                        .onSuccess { status = "Connessione rimossa."; refreshNonce++ }
                                        .onFailure { status = it.message ?: "Connessione non rimossa." }
                                }) { Text("Rimuovi") }
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
                    Text("Seleziona da 2 a 6 bot anche su connessioni diverse. Il turno resta bounded a 3 round e 10 risposte.", color = AppColors.Muted, fontSize = 12.sp)
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
                    Text("Selezionati: ${selectedGroupMemberKeys.size}/6", color = AppColors.Muted, fontSize = 12.sp)
                    Button(
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
                    ) { Text("Salva gruppo") }
                    groups.forEach { group ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(group.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("${group.members.size} membri · sessioni Group persistenti", color = AppColors.Muted, fontSize = 11.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    openGroup = group
                                    groupPrompt = ""
                                    groupResult = null
                                }) { Text("Apri") }
                                TextButton(onClick = {
                                    runCatching { deleteHermesBotGroup(context, group.name) }
                                        .onSuccess { groups = loadHermesBotGroups(context); status = "Gruppo rimosso." }
                                        .onFailure { status = it.message ?: "Gruppo non rimosso." }
                                }) { Text("Elimina") }
                            }
                        }
                    }
                }
            }
        }
        if (roster?.items.isNullOrEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble), shape = RoundedCornerShape(18.dp)) {
                    Text("Nessun profilo restituito dal gateway.", color = Color.White, modifier = Modifier.padding(16.dp))
                }
            }
        }
        items(roster?.items.orEmpty(), key = { it.identityKey }) { bot ->
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(18.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(bot.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text("${bot.handle} · ${bot.connectionLabel}", color = AppColors.Muted, fontSize = 12.sp)
                        Text(bot.profile, color = AppColors.Faint, fontSize = 11.sp)
                        if (bot.description.isNotBlank()) Text(bot.description, color = Color.White, fontSize = 13.sp)
                        if (bot.isDefault) Text("Profilo predefinito", color = AppColors.Faint, fontSize = 11.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = opening == null && roster?.chatSupported == true,
                            onClick = {
                                opening = bot.identityKey
                                scope.launch {
                                    openHermesBotChat(context, settings, bot)
                                        .onSuccess { onOpenBot(it) }
                                        .onFailure { status = it.message ?: "Apertura Bot Chat fallita." }
                                    opening = null
                                }
                            }
                        ) { Text(if (opening == bot.identityKey) "Apro..." else "Apri Bot Chat") }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { openEditor(bot) }, enabled = mutating == false) { Text("Modifica") }
                            TextButton(
                                onClick = {
                                    deleteBot = bot
                                    deleteConfirmation = ""
                                },
                                enabled = !bot.isDefault && !bot.profile.equals("default", true) && mutating == false
                            ) { Text("Elimina") }
                        }
                    }
                }
            }
        }
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
                    Text("${group.members.size} membri · ogni sessione è persistente.", color = AppColors.Muted, fontSize = 12.sp)
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
                        Text("Esito: ${result.outcome} · round: ${result.rounds} · risposte: ${result.botMessages}", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Pass/silenzio: ${result.memberResults.count { it.silent }}", color = AppColors.Muted, fontSize = 12.sp)
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
                    TextButton(onClick = { groupJob?.cancel() }) { Text("Annulla turno") }
                } else {
                    TextButton(
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
                    ) { Text("Esegui turno") }
                }
            },
            dismissButton = { TextButton(onClick = { openGroup = null }, enabled = !groupRunning) { Text("Chiudi") } }
        )
    }

    if (showEditor) {
        val editing = editorBot
        val selectedConnection = connections.items.firstOrNull { it.id.equals(selectedConnectionId, true) }
        AlertDialog(
            onDismissRequest = { if (!mutating) showEditor = false },
            title = { Text(if (editing == null) "Nuovo bot Hermes" else "Modifica ${editing.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(profileInput, { profileInput = it }, label = { Text("Nome profilo") }, enabled = editing == null, singleLine = true)
                    OutlinedTextField(displayNameInput, { displayNameInput = it }, label = { Text("Nome visualizzato") }, singleLine = true)
                    OutlinedTextField(descriptionInput, { descriptionInput = it }, label = { Text("Descrizione") }, minLines = 2)
                    OutlinedTextField(soulInput, { soulInput = it }, label = { Text("SOUL.md (opzionale)") }, minLines = 4)
                    Text("Endpoint proprietario", color = AppColors.Muted, fontSize = 12.sp)
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
                TextButton(
                    enabled = profileInput.isNotBlank() && !mutating && selectedConnection?.enabled == true,
                    onClick = {
                        mutating = true
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
                            }.onFailure { status = it.message ?: "Operazione bot fallita." }
                            mutating = false
                        }
                    }
                ) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }, enabled = !mutating) { Text("Annulla") } }
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
                TextButton(
                    enabled = deleteConfirmation == bot.profile && !mutating,
                    onClick = {
                        mutating = true
                        scope.launch {
                            deleteHermesBot(context, settings, secretForBotConnection(context, connectionForBot(context, settings, bot)), bot, deleteConfirmation)
                                .onSuccess {
                                    status = "Bot eliminato."
                                    deleteBot = null
                                    refreshNonce++
                                }
                                .onFailure { status = it.message ?: "Eliminazione bot fallita." }
                            mutating = false
                        }
                    }
                ) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { deleteBot = null }, enabled = !mutating) { Text("Annulla") } }
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
                TextButton(
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
                ) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { showConnectionEditor = false }, enabled = !mutating) { Text("Annulla") } }
        )
    }
}
