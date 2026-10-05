package com.nemoclaw.chat.features.bots

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.LocalConversation
import com.nemoclaw.chat.isBotConversationId
import com.nemoclaw.chat.loadConversations
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Menu contestuale bot stile Hermes desktop (tasto destro -> long-press / ⋮).
 * Il server non espone pin/hide/duplicate/sessioni/sezioni/auto-screen:
 * tutto cio che non ha backend e persistito in LOCALE (stesso pattern di
 * gruppi e auto-approve), cosi la UX resta fedele senza inventare API.
 */

private const val BOT_DISPLAY_PREFS = "hermes_bot_display"

// ---------------------------------------------------------------- store ---

private fun botDisplayPrefs(context: Context) =
    context.getSharedPreferences(BOT_DISPLAY_PREFS, Context.MODE_PRIVATE)

private fun loadStringSet(context: Context, key: String): MutableSet<String> =
    botDisplayPrefs(context).getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()

private fun saveStringSet(context: Context, key: String, values: Set<String>) {
    // apply(): niente I/O sincrono su UI thread (era commit=true).
    botDisplayPrefs(context).edit { putStringSet(key, values.toSet()) }
}

/** Fissa in alto: identityKey pinnati. */
internal fun loadBotPins(context: Context): Set<String> = loadStringSet(context, "pins")
internal fun setBotPinned(context: Context, identityKey: String, pinned: Boolean) {
    val next = loadBotPins(context).toMutableSet()
    if (pinned) next.add(identityKey) else next.remove(identityKey)
    saveStringSet(context, "pins", next)
}

/** Nascondi (locale): identityKey nascosti dal roster. */
internal fun loadBotHiddenLocal(context: Context): Set<String> = loadStringSet(context, "hidden")
internal fun setBotHiddenLocal(context: Context, identityKey: String, hidden: Boolean) {
    val next = loadBotHiddenLocal(context).toMutableSet()
    if (hidden) next.add(identityKey) else next.remove(identityKey)
    saveStringSet(context, "hidden", next)
}

/** "Apri schermo quando il bot lo usa": identityKey con auto-open attivo. */
internal fun loadBotAutoScreen(context: Context): Set<String> = loadStringSet(context, "screen_auto")
internal fun setBotAutoScreen(context: Context, identityKey: String, enabled: Boolean) {
    val next = loadBotAutoScreen(context).toMutableSet()
    if (enabled) next.add(identityKey) else next.remove(identityKey)
    saveStringSet(context, "screen_auto", next)
}

// ------------------------------------------------------------- sezioni ---

internal data class BotSections(
    val order: List<String> = emptyList(),
    val assign: Map<String, String> = emptyMap()
)

internal fun loadBotSections(context: Context): BotSections {
    val raw = botDisplayPrefs(context).getString("sections_json", null) ?: return BotSections()
    return runCatching {
        val root = JSONObject(raw)
        val order = mutableListOf<String>()
        root.optJSONArray("order")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(order::add)
        }
        val assign = mutableMapOf<String, String>()
        root.optJSONObject("assign")?.let { obj ->
            obj.keys().forEach { key -> obj.optString(key).takeIf { it.isNotBlank() }?.let { assign[key] = it } }
        }
        BotSections(order.distinct(), assign)
    }.getOrDefault(BotSections())
}

internal fun saveBotSections(context: Context, sections: BotSections) {
    val root = JSONObject()
    root.put("order", JSONArray(sections.order))
    val assign = JSONObject()
    sections.assign.forEach { (k, v) -> assign.put(k, v) }
    root.put("assign", assign)
    botDisplayPrefs(context).edit { putString("sections_json", root.toString()) }
}

internal fun sanitizeSectionName(raw: String): String =
    raw.trim().replace(Regex("\\s+"), " ").take(40)

// ------------------------------------------------------------- sessioni ---

internal data class BotSessionEntry(
    val sessionId: String,
    val multiplexEnabled: Boolean,
    val openedAt: Long
)

internal data class BotSessionBinding(
    val current: BotSessionEntry?,
    val recent: List<BotSessionEntry> = emptyList()
)

private const val BOT_SESSIONS_MAX_RECENT = 10

internal fun loadBotSessionBinding(context: Context, identityKey: String): BotSessionBinding {
    val raw = botDisplayPrefs(context).getString("sessions_json", null) ?: return BotSessionBinding(null)
    return runCatching {
        val entry = JSONObject(raw).optJSONObject(identityKey) ?: return BotSessionBinding(null)
        fun read(obj: JSONObject?): BotSessionEntry? {
            if (obj == null) return null
            val sid = obj.optString("sid").trim()
            if (sid.isEmpty()) return null
            return BotSessionEntry(sid, obj.optBoolean("mpx", false), obj.optLong("ts", 0L))
        }
        val recent = mutableListOf<BotSessionEntry>()
        entry.optJSONArray("recent")?.let { arr ->
            for (i in 0 until arr.length()) read(arr.optJSONObject(i))?.let(recent::add)
        }
        BotSessionBinding(read(entry.optJSONObject("current")), recent)
    }.getOrDefault(BotSessionBinding(null))
}

internal fun saveBotSessionBinding(
    context: Context,
    identityKey: String,
    sessionId: String,
    multiplexEnabled: Boolean
) {
    val prefs = botDisplayPrefs(context)
    val all = runCatching { JSONObject(prefs.getString("sessions_json", null) ?: "{}") }.getOrDefault(JSONObject())
    val prev = loadBotSessionBinding(context, identityKey)
    val now = System.currentTimeMillis()
    val entry = BotSessionEntry(sessionId, multiplexEnabled, now)
    val recent = (listOf(entry) + prev.recent.filter { it.sessionId != sessionId })
        .take(BOT_SESSIONS_MAX_RECENT)
    val obj = JSONObject()
    obj.put("current", JSONObject().put("sid", entry.sessionId).put("mpx", entry.multiplexEnabled).put("ts", now))
    val arr = JSONArray()
    recent.forEach { arr.put(JSONObject().put("sid", it.sessionId).put("mpx", it.multiplexEnabled).put("ts", it.openedAt)) }
    obj.put("recent", arr)
    all.put(identityKey, obj)
    prefs.edit { putString("sessions_json", all.toString()) }
}

/** Rimuove ogni traccia locale di un bot eliminato (niente orfani). */internal fun removeBotDisplayPrefs(context: Context, identityKey: String) {
    setBotPinned(context, identityKey, false)
    setBotHiddenLocal(context, identityKey, false)
    setBotAutoScreen(context, identityKey, false)
    clearBotLink(context, identityKey)
    val sections = loadBotSections(context)
    val assign = sections.assign.toMutableMap()
    assign.remove(identityKey)
    saveBotSections(context, sections.copy(assign = assign))
    val prefs = botDisplayPrefs(context)
    val all = runCatching { JSONObject(prefs.getString("sessions_json", null) ?: "{}") }.getOrDefault(JSONObject())
    all.remove(identityKey)
    prefs.edit { putString("sessions_json", all.toString()) }
}

/**
 * Pota preferenze orfane (bot spariti dal roster) e sezioni svuotate.
 * Chiamare solo a roster valido (niente sourceFailures): a roster vuoto
 * per errore rete NON si pota mai.
 */
internal fun pruneBotDisplayPrefs(context: Context, validKeys: Set<String>): BotSections {
    saveStringSet(context, "pins", loadBotPins(context).intersect(validKeys))
    saveStringSet(context, "hidden", loadBotHiddenLocal(context).intersect(validKeys))
    saveStringSet(context, "screen_auto", loadBotAutoScreen(context).intersect(validKeys))
    val links = loadBotLinks(context).filterKeys { it in validKeys }
    val linksRoot = JSONObject()
    links.forEach { (k, v) -> linksRoot.put(k, v) }
    botDisplayPrefs(context).edit { putString("links_json", linksRoot.toString()) }
    saveBotLinkSkipped(context, loadBotLinkSkipped(context).intersect(validKeys))
    val sections = loadBotSections(context)
    val assign = sections.assign.filterKeys { it in validKeys }
    val order = sections.order.filter { name -> assign.values.any { it == name } }
    val pruned = BotSections(order, assign)
    saveBotSections(context, pruned)
    val prefs = botDisplayPrefs(context)
    val all = runCatching { JSONObject(prefs.getString("sessions_json", null) ?: "{}") }.getOrDefault(JSONObject())
    val stale = mutableListOf<String>()
    all.keys().forEach { key -> if (key !in validKeys) stale.add(key) }
    stale.forEach(all::remove)
    prefs.edit { putString("sessions_json", all.toString()) }
    return pruned
}

// -------------------------------------------------------- ultimo bot ---
/**
 * Ultimo bot usato: la "pagina principale" della sezione Bot e la sua
 * chat persistente, non il roster. Solo riferimento leggero (connessione,
 * profilo, nome): la chat si riapre riusando il binding di sessione,
 * con una sola POST se il binding manca.
 */
internal data class SavedBotRef(
    val connectionId: String,
    val profile: String,
    val displayName: String
) {
    fun toItem(): HermesBotItem {
        val key = "$connectionId::$profile"
        return HermesBotItem(
            profile = profile,
            displayName = displayName.ifBlank { profile },
            description = "",
            hidden = false,
            chatId = null,
            isDefault = false,
            connectionId = connectionId,
            connectionLabel = connectionId,
            identityKey = key,
            handle = displayName.ifBlank { profile }
        )
    }
}

internal fun saveLastBot(context: Context, connectionId: String, profile: String, displayName: String) {
    if (profile.isBlank()) return
    val root = JSONObject()
    root.put("conn", connectionId.ifBlank { "primary" })
    root.put("profile", profile)
    root.put("name", displayName)
    botDisplayPrefs(context).edit { putString("last_bot_json", root.toString()) }
}

internal fun loadLastBot(context: Context): SavedBotRef? {
    val raw = botDisplayPrefs(context).getString("last_bot_json", null) ?: return null
    return runCatching {
        val root = JSONObject(raw)
        val profile = root.optString("profile").trim()
        if (profile.isEmpty()) return null
        SavedBotRef(
            connectionId = root.optString("conn", "primary").ifBlank { "primary" },
            profile = profile,
            displayName = root.optString("name")
        )
    }.getOrNull()
}

internal fun clearLastBotIf(context: Context, identityKey: String) {
    val ref = loadLastBot(context) ?: return
    if ("${ref.connectionId}::${ref.profile}" == identityKey) {
        botDisplayPrefs(context).edit { remove("last_bot_json") }
    }
}

// ------------------------------------------- collegamento chat desktop ---

/**
 * Collega la chat persistente del bot a una conversazione desktop esistente
 * (stesso id Hub): telefono e desktop leggono/scrivono lo STESSO oggetto
 * (merge hub per id, vince updatedAt piu recente; uso sequenziale).
 * Senza link si usa l'id stabile locale.
 */
internal fun loadBotLinks(context: Context): Map<String, String> {
    val raw = botDisplayPrefs(context).getString("links_json", null) ?: return emptyMap()
    return runCatching {
        val root = JSONObject(raw)
        buildMap {
            root.keys().forEach { key ->
                root.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
    }.getOrDefault(emptyMap())
}

internal fun saveBotLink(context: Context, identityKey: String, entityId: String) {
    val next = loadBotLinks(context).toMutableMap()
    next[identityKey] = entityId
    val root = JSONObject()
    next.forEach { (k, v) -> root.put(k, v) }
    botDisplayPrefs(context).edit { putString("links_json", root.toString()) }
}

internal fun clearBotLink(context: Context, identityKey: String) {
    val next = loadBotLinks(context).toMutableMap()
    next.remove(identityKey)
    val root = JSONObject()
    next.forEach { (k, v) -> root.put(k, v) }
    botDisplayPrefs(context).edit { putString("links_json", root.toString()) }
    // Via anche lo skip: riproporre il picker al prossimo giro.
    val skipped = loadBotLinkSkipped(context).toMutableSet()
    skipped.remove(identityKey)
    saveBotLinkSkipped(context, skipped)
}

internal fun loadBotLinkSkipped(context: Context): Set<String> = loadStringSet(context, "links_skipped")

internal fun saveBotLinkSkipped(context: Context, values: Set<String>) {
    saveStringSet(context, "links_skipped", values)
}

/**
 * Candidate collegabili: conversazioni non-Android su Hub (desktop e
 * altre surface) non eliminate, escluse quelle gia linkate ad altri bot
 * e gli id stabili bot. Puro I/O locale, niente rete.
 */
internal fun desktopLinkCandidates(context: Context, limit: Int = 30): List<LocalConversation> {
    val linkedIds = loadBotLinks(context).values.toSet()
    return loadConversations(context)
        .filter { conv ->
            conv.deletedAt == null &&
                conv.id !in linkedIds &&
                !isBotConversationId(conv.id) &&
                (conv.serverConversationId ?: "").startsWith("hermes-hub:") &&
                !(conv.serverConversationId ?: "").startsWith("hermes-hub:android-app:")
        }
        .sortedByDescending { it.updatedAt }
        .take(limit.coerceIn(1, 50))
}

private fun candidateSurfaceLabel(item: LocalConversation): String {
    val surface = (item.serverConversationId ?: "").split(":").getOrNull(1).orEmpty()
    return when {
        surface.isBlank() -> "locale"
        surface == "windows-app" -> "desktop"
        else -> surface
    }
}

// ------------------------------------------------------- pure helpers ---

/** Fissati prima (ordine server), poi gli altri. Puro: testabile. */
internal fun sortBotsForRoster(items: List<HermesBotItem>, pins: Set<String>): List<HermesBotItem> {
    if (pins.isEmpty()) return items
    return items.sortedWith(compareByDescending<HermesBotItem> { it.identityKey in pins })
}

/** Raggruppa per sezione (ordine sezioni, poi non assegnati). Puro: testabile. */
internal fun groupBotsBySection(
    items: List<HermesBotItem>,
    sections: BotSections
): List<Pair<String?, List<HermesBotItem>>> {
    if (sections.order.isEmpty()) return listOf(null to items)
    val bySection = items.groupBy { sections.assign[it.identityKey]?.takeIf { s -> s in sections.order } }
    val out = mutableListOf<Pair<String?, List<HermesBotItem>>>()
    sections.order.forEach { name -> bySection[name]?.takeIf { it.isNotEmpty() }?.let { out.add(name to it) } }
    bySection[null]?.takeIf { it.isNotEmpty() }?.let { out.add(null to it) }
    return out
}

// ----------------------------------------------------------------- menu ---

/** Tutto cio che il menu puo fare + stato per le spunte. Costruito in BotsScreen. */
internal data class BotMenuHost(
    val expanded: Boolean,
    val page: Int, // 0 = principale, 1 = sposta in sezione
    val busy: Boolean = false,
    val pinned: Boolean,
    val hidden: Boolean,
    val autoScreen: Boolean,
    val linked: Boolean = false,
    val sections: List<String>,
    val currentSection: String?,
    val onDismiss: () -> Unit,
    val onPage: (Int) -> Unit,
    val onOpenChat: () -> Unit,
    val onOpenScreen: () -> Unit,
    val onToggleAutoScreen: () -> Unit,
    val onTogglePin: () -> Unit,
    val onToggleHide: () -> Unit,
    val onEdit: () -> Unit,
    val onManageGroups: () -> Unit,
    val onDuplicate: () -> Unit,
    val onNewChat: () -> Unit,
    val onRecentSessions: () -> Unit,
    val onLinkDesktop: () -> Unit = {},
    val onUnlinkDesktop: () -> Unit = {},
    val onMoveToSection: (String?) -> Unit,
    val onNewSection: (String) -> Unit
)

@Composable
private fun BotMenuItem(
    label: String,
    icon: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label, color = if (enabled) Color.White else AppColors.Faint, fontSize = 14.sp) },
        leadingIcon = icon,
        trailingIcon = trailing,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
internal fun BotCardMenu(bot: HermesBotItem, host: BotMenuHost) {
    DropdownMenu(
        expanded = host.expanded,
        onDismissRequest = host.onDismiss
    ) {
        if (host.page == 1) {
            BotMenuItem(
                label = "‹ ${bot.displayName}",
                icon = { Icon(Icons.Rounded.ChevronLeft, contentDescription = null, tint = AppColors.Muted) },
                onClick = { host.onPage(0) }
            )
            HorizontalDivider(color = AppColors.Border)
            host.sections.forEach { name ->
                BotMenuItem(
                    label = name,
                    trailing = if (host.currentSection == name) {
                        { Icon(Icons.Rounded.Check, contentDescription = "Sezione attuale", tint = AppColors.Accent) }
                    } else null,
                    onClick = { host.onMoveToSection(name) }
                )
            }
            BotMenuItem(
                label = "Nuova sezione…",
                icon = { Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppColors.Muted) },
                onClick = { host.onNewSection("") }
            )
            BotMenuItem(label = "Nessuna sezione", onClick = { host.onMoveToSection(null) })
            return@DropdownMenu
        }
        BotMenuItem(
            label = "Apri Bot Chat",
            icon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, tint = AppColors.Accent) },
            enabled = !host.busy,
            onClick = host.onOpenChat
        )
        BotMenuItem(
            label = "Apri schermo",
            icon = { Icon(Icons.Rounded.Computer, contentDescription = null, tint = Color.White) },
            onClick = host.onOpenScreen
        )
        BotMenuItem(
            label = "Apri schermo quando il bot lo usa",
            trailing = if (host.autoScreen) {
                { Icon(Icons.Rounded.Check, contentDescription = "Attivo", tint = AppColors.Accent) }
            } else null,
            onClick = host.onToggleAutoScreen
        )
        HorizontalDivider(color = AppColors.Border)
        BotMenuItem(
            label = if (host.pinned) "Togli dai fissati" else "Fissa in alto",
            icon = { Icon(Icons.Rounded.PushPin, contentDescription = null, tint = Color.White) },
            onClick = host.onTogglePin
        )
        BotMenuItem(
            label = if (host.hidden) "Mostra" else "Nascondi",
            icon = {
                Icon(
                    if (host.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = null,
                    tint = Color.White
                )
            },
            onClick = host.onToggleHide
        )
        HorizontalDivider(color = AppColors.Border)
        BotMenuItem(
            label = "Modifica…",
            icon = { Icon(Icons.Rounded.Edit, contentDescription = null, tint = Color.White) },
            onClick = host.onEdit
        )
        BotMenuItem(
            label = "Gestisci gruppi…",
            icon = { Icon(Icons.Rounded.Group, contentDescription = null, tint = Color.White) },
            onClick = host.onManageGroups
        )
        BotMenuItem(
            label = "Duplica",
            icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = Color.White) },
            onClick = host.onDuplicate
        )
        HorizontalDivider(color = AppColors.Border)
        BotMenuItem(
            label = "Nuova chat con questo bot",
            icon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, tint = Color.White) },
            enabled = !host.busy,
            onClick = host.onNewChat
        )
        BotMenuItem(
            label = "Apri sessione recente",
            icon = { Icon(Icons.Rounded.History, contentDescription = null, tint = Color.White) },
            enabled = !host.busy,
            onClick = host.onRecentSessions
        )
        BotMenuItem(
            label = if (host.linked) "Scollega chat desktop" else "Collega chat desktop…",
            icon = { Icon(Icons.Rounded.Link, contentDescription = null, tint = Color.White) },
            onClick = if (host.linked) host.onUnlinkDesktop else host.onLinkDesktop
        )
        BotMenuItem(
            label = "Sposta in sezione",
            icon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null, tint = Color.White) },
            trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = "Sotto menu", tint = AppColors.Muted) },
            onClick = { host.onPage(1) }
        )
    }
}

// -------------------------------------------------------------- dialoghi ---

@Composable
internal fun BotRecentSessionsDialog(
    bot: HermesBotItem,
    entries: List<BotSessionEntry>,
    onPick: (BotSessionEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sessioni recenti · ${bot.displayName}", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entries.isEmpty()) {
                    Text("Nessuna sessione nota per questo bot.", color = AppColors.Muted, fontSize = 13.sp)
                } else {
                    Text(
                        "Sessioni avviate da questo dispositivo. La chat resta sempre la stessa, cambia solo la sessione server.",
                        color = AppColors.Muted,
                        fontSize = 12.sp
                    )
                    entries.forEach { entry ->
                        TextButton(onClick = { onPick(entry) }) {
                            Text(
                                "${entry.sessionId.take(12)}… · ${dateFormat.format(Date(entry.openedAt))}",
                                color = AppColors.Accent,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi", color = Color.White) } }
    )
}

@Composable
internal fun BotDesktopLinkDialog(
    botDisplayName: String,
    candidates: List<LocalConversation>,
    onPick: (LocalConversation) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collega chat desktop · $botDisplayName", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Scegli la chat desktop da condividere: telefono e desktop leggeranno e scriveranno la STESSA chat (vince l'ultimo che scrive). Senza scelta resta la chat locale.",
                    color = AppColors.Muted,
                    fontSize = 12.sp
                )
                if (candidates.isEmpty()) {
                    Text("Nessuna chat desktop sincronizzata.", color = AppColors.Muted, fontSize = 13.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(candidates, key = { it.id }) { item ->
                            TextButton(onClick = { onPick(item) }) {
                                Text(
                                    "${item.title.ifBlank { "Senza titolo" }.take(38)} · ${candidateSurfaceLabel(item)} · ${item.messages.size} msg · ${dateFormat.format(Date(item.updatedAt))}",
                                    color = AppColors.Accent,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSkip) { Text("Non collegare", color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi", color = AppColors.Muted) } }
    )
}

@Composable
internal fun BotSectionNameDialog(    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuova sezione", color = Color.White) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Nome sezione") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(sanitizeSectionName(name)) },
                enabled = sanitizeSectionName(name).isNotEmpty()
            ) { Text("Crea", color = AppColors.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla", color = Color.White) } }
    )
}

@Composable
internal fun BotSectionHeader(name: String) {
    Text(
        name.uppercase(),
        color = AppColors.Accent,
        fontSize = 12.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
}

@Composable
internal fun BotHiddenRow(hiddenCount: Int, showing: Boolean, onToggle: () -> Unit) {    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            if (showing) "$hiddenCount bot nascosti (visibili)" else "$hiddenCount bot nascosti",
            color = AppColors.Muted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (showing) "Nascondi" else "Mostra", color = AppColors.Accent, fontSize = 13.sp)
        }
    }
}
