package com.nemoclaw.chat

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleType
import com.nemoclaw.chat.features.bots.loadAllHermesBotRosters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Dashboard home: stato generale a colpo d'occhio + numeri live,
 * senza la muraglia di widget. Navigazione via chips compatti.
 * Sottotitoli statici (niente IO); i numeri arrivano in IO una volta sola.
 */
internal data class DashboardEntry(val tab: Tab, val subtitle: String)

internal fun dashboardEntries(): List<DashboardEntry> = listOf(
    DashboardEntry(Tab.Bots, "Gestisci i bot"),
    DashboardEntry(Tab.Voice, "Parla con Hermes"),
    DashboardEntry(Tab.Jarvis, "Vista e automazioni"),
    DashboardEntry(Tab.Video, "Libreria video"),
    DashboardEntry(Tab.Images, "Foto e media"),
    DashboardEntry(Tab.News, "Notizie"),
    DashboardEntry(Tab.Hardware, "CPU, GPU, dischi"),
    DashboardEntry(Tab.Health, "Benessere"),
    DashboardEntry(Tab.Cron, "Attività pianificate"),
    DashboardEntry(Tab.Archive, "Chat salvate"),
    DashboardEntry(Tab.Artifacts, "File generati"),
    DashboardEntry(Tab.Projects, "Spazi di lavoro"),
    DashboardEntry(Tab.Characters, "Voci e identità"),
    DashboardEntry(Tab.Search, "Cerca ovunque"),
    DashboardEntry(Tab.Notifications, "Avvisi"),
    DashboardEntry(Tab.Continuity, "Riprendi il filo"),
    DashboardEntry(Tab.Audit, "Registro attività"),
    DashboardEntry(Tab.Profile, "Profilo e dispositivi"),
    DashboardEntry(Tab.Settings, "Impostazioni")
)

internal data class BoardLive(
    val bots: Int? = null,
    val cronActive: Int? = null,
    val chats: Int? = null,
    val gpuMode: String? = null,
    val gpuJobs: Int? = null,
    val loaded: Boolean = false
)

/** Widget creato dall'utente: link (target = Tab.name), probe (target = URL),
 *  note (detail = testo). Id stabile "c:<millis>" per ordine/nascondi. */
internal data class CustomWidget(
    val id: String,
    val kind: String,
    val title: String,
    val target: String,
    val detail: String
)

internal fun defaultDashboardOrder(): List<String> =
    listOf("status", "bots", "cron", "chats", "gpu", "quick")

internal fun parseIdList(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()

internal fun parseCustomWidgets(raw: String): List<CustomWidget> {
    return try {
        val arr = org.json.JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val kind = o.optString("kind").trim()
                if (id.isBlank() || kind !in setOf("link", "probe", "note")) continue
                add(
                    CustomWidget(
                        id = id,
                        kind = kind,
                        title = o.optString("title").trim().take(40),
                        target = o.optString("target").trim().take(300),
                        detail = o.optString("detail").trim().take(300)
                    )
                )
            }
        }.take(20)
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun encodeCustomWidgets(widgets: List<CustomWidget>): String {
    val arr = org.json.JSONArray()
    widgets.take(20).forEach {
        arr.put(
            org.json.JSONObject()
                .put("id", it.id)
                .put("kind", it.kind)
                .put("title", it.title)
                .put("target", it.target)
                .put("detail", it.detail)
        )
    }
    return arr.toString()
}

internal sealed interface DashboardItem {
    data class Builtin(val id: String) : DashboardItem
    data class Custom(val widget: CustomWidget) : DashboardItem
}

/** Ordine + visibilita risolti: ignoti scartati, mancanti accodati in default,
 *  nascosti tolti (built-in e custom). Puro/testabile. */
internal fun resolveDashboardItems(
    orderRaw: String,
    hiddenRaw: String,
    custom: List<CustomWidget>
): List<DashboardItem> {
    val defaults = defaultDashboardOrder()
    val hidden = parseIdList(hiddenRaw).toSet()
    val ordered = parseIdList(orderRaw).filter { it in defaults || it == "quick" || custom.any { w -> w.id == it } }
    val missing = defaults.filter { it !in ordered }
    return (ordered + missing)
        .filter { it !in hidden }
        .mapNotNull { id ->
            when {
                id in defaults || id == "quick" -> DashboardItem.Builtin(id)
                else -> custom.firstOrNull { it.id == id }?.let { DashboardItem.Custom(it) }
            }
        }
}

/** Sonda URL leggera (solo codice): IO, mai solleva. */
internal suspend fun probeUrl(url: String, timeoutMs: Int = 8000): Int? {
    return try {
        withContext(Dispatchers.IO) {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.connect()
                conn.responseCode.takeIf { it > 0 }
            } finally {
                conn.disconnect()
            }
        }
    } catch (_: Exception) {
        null
    }
}

/** Stato generale puro/testabile: ok, degraded (N controlli ko), offline. */
internal fun boardOverallStatus(gatewayOnline: Boolean, failedChecks: Int): String =
    when {
        !gatewayOnline -> "offline"
        failedChecks > 0 -> "degraded"
        else -> "ok"
    }

private suspend fun loadGpuBoard(settings: AppSettings, appKey: String?): Pair<String?, Int?> {
    val base = gpuManagerBase(settings.gatewayUrl)
    val (code, body) = httpGetResponse("$base/status", appKey)
    if (code !in 200..299) return null to null
    val mode = JSONObject(body).optString("desired_mode").ifBlank { null }
    val (jobCode, jobBody) = httpGetResponse("$base/jobs?limit=300", appKey)
    if (jobCode !in 200..299) return mode to null
    val arr = runCatching { JSONObject(jobBody).optJSONArray("jobs") }.getOrNull()
    var running = 0
    for (i in 0 until (arr?.length() ?: 0)) {
        if (arr?.optJSONObject(i)?.optString("status") == "running") running++
    }
    return mode to running
}

@Composable
private fun LiveNumberCard(
    label: String,
    value: String?,
    loaded: Boolean,
    detail: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppleGlass(shape = RoundedCornerShape(18.dp), onClick = onOpen) {
        Column(
            modifier = modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                value ?: if (loaded) "—" else "…",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                label,
                color = AppColors.Accent,
                fontSize = AppleType.Footnote,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                color = AppColors.Faint,
                fontSize = AppleType.Footnote,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun ServerStatusBoard(
    context: Context,
    settings: AppSettings,
    snapshot: ServerSnapshot,
    gatewayOnline: Boolean,
    failedChecks: Int,
    onOpenTab: (Tab) -> Unit,
    onSettingsChanged: (AppSettings) -> Unit = {}
) {
    var live by remember { mutableStateOf(BoardLive()) }
    LaunchedEffect(settings.gatewayUrl) {
        val secret = withContext(Dispatchers.IO) { loadGatewaySecret(context) }
        val appKey = withContext(Dispatchers.IO) { loadGpuAppSecret(context) }
        coroutineScope {
            val botsDef = async {
                runCatching { loadAllHermesBotRosters(context, settings).items.size }.getOrNull()
            }
            val cronDef = async {
                runCatching { loadCronJobs(settings, secret).first.count { it.enabled } }.getOrNull()
            }
            val chatsDef = async {
                withContext(Dispatchers.IO) {
                    runCatching { loadConversations(context).size }.getOrNull()
                }
            }
            val gpuDef = async {
                runCatching { loadGpuBoard(settings, appKey) }.getOrNull()
            }
            live = BoardLive(
                bots = botsDef.await(),
                cronActive = cronDef.await(),
                chats = chatsDef.await(),
                gpuMode = gpuDef.await()?.first,
                gpuJobs = gpuDef.await()?.second,
                loaded = true
            )
        }
    }
    var showCustomize by rememberSaveable { mutableStateOf(false) }
    val custom = remember(settings.dashboardCustom) { parseCustomWidgets(settings.dashboardCustom) }
    val items = remember(settings.dashboardOrder, settings.dashboardHidden, custom) {
        resolveDashboardItems(settings.dashboardOrder, settings.dashboardHidden, custom)
    }
    // Righe: le live si accoppiano a due a due in ordine, il resto da solo.
    val liveIds = setOf("bots", "cron", "chats", "gpu")
    val rows = remember(items) {
        val out = mutableListOf<List<DashboardItem>>()
        var pending = mutableListOf<DashboardItem>()
        fun flush() {
            pending.chunked(2).forEach { out.add(it) }
            pending = mutableListOf()
        }
        items.forEach {
            if (it is DashboardItem.Builtin && it.id in liveIds) pending.add(it)
            else {
                flush()
                out.add(listOf(it))
            }
        }
        flush()
        out
    }
    fun persist(order: List<String>, hidden: Set<String>, customs: List<CustomWidget>) {
        onSettingsChanged(
            settings.copy(
                dashboardOrder = order.joinToString(","),
                dashboardHidden = hidden.joinToString(","),
                dashboardCustom = encodeCustomWidgets(customs)
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "LA MIA DASHBOARD",
                color = AppColors.Accent,
                fontSize = AppleType.Footnote,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showCustomize = !showCustomize }) {
                Text(if (showCustomize) "Fine" else "Personalizza", color = AppColors.Accent)
            }
        }
        if (showCustomize) {
            CustomizePanel(
                order = items.map {
                    when (it) {
                        is DashboardItem.Builtin -> it.id
                        is DashboardItem.Custom -> it.widget.id
                    }
                },
                hidden = parseIdList(settings.dashboardHidden).toSet(),
                custom = custom,
                onChange = { order, hidden, customs -> persist(order, hidden, customs) },
                onDismiss = { showCustomize = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
        rows.forEach { row ->
            val singles = row.size == 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { item ->
                    when (item) {
                        is DashboardItem.Builtin -> when (item.id) {
                            "status" -> DashboardHero(
                                snapshot = snapshot,
                                gatewayOnline = gatewayOnline,
                                failedChecks = failedChecks,
                                modifier = Modifier.weight(1f)
                            )
                            "bots" -> LiveNumberCard(
                                label = "BOT", value = live.bots?.toString(), loaded = live.loaded,
                                detail = "in gestione", onOpen = { onOpenTab(Tab.Bots) },
                                modifier = Modifier.weight(1f)
                            )
                            "cron" -> LiveNumberCard(
                                label = "CRON", value = live.cronActive?.toString(), loaded = live.loaded,
                                detail = "attive", onOpen = { onOpenTab(Tab.Cron) },
                                modifier = Modifier.weight(1f)
                            )
                            "chats" -> LiveNumberCard(
                                label = "CHAT", value = live.chats?.toString(), loaded = live.loaded,
                                detail = "salvate", onOpen = { onOpenTab(Tab.Archive) },
                                modifier = Modifier.weight(1f)
                            )
                            "gpu" -> LiveNumberCard(
                                label = "GPU", value = live.gpuMode, loaded = live.loaded,
                                detail = live.gpuJobs?.let { "$it job" } ?: "manager",
                                onOpen = { onOpenTab(Tab.Hardware) },
                                modifier = Modifier.weight(1f)
                            )
                            "quick" -> QuickChips(onOpenTab = onOpenTab, modifier = Modifier.weight(1f))
                            else -> Box(modifier = Modifier.weight(1f))
                        }
                        is DashboardItem.Custom -> CustomWidgetCard(
                            widget = item.widget,
                            onOpenTab = onOpenTab,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (singles) {
                    val only = row.first()
                    val isHalf = (only is DashboardItem.Builtin && only.id in liveIds) ||
                        only is DashboardItem.Custom
                    if (isHalf) Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DashboardHero(
    snapshot: ServerSnapshot,
    gatewayOnline: Boolean,
    failedChecks: Int,
    modifier: Modifier = Modifier
) {
    AppleGlass(shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when (boardOverallStatus(gatewayOnline, failedChecks)) {
                            "ok" -> AppColors.Success
                            "degraded" -> AppColors.Warning
                            else -> AppColors.Error
                        }
                    )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (boardOverallStatus(gatewayOnline, failedChecks)) {
                        "ok" -> "Tutto operativo"
                        "degraded" -> "Da guardare ($failedChecks)"
                        else -> "Server offline"
                    },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    snapshot.model.ifBlank { snapshot.gateway },
                    color = AppColors.Faint,
                    fontSize = AppleType.Footnote,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun QuickChips(onOpenTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "ACCESSO RAPIDO",
            color = AppColors.Accent,
            fontSize = AppleType.Footnote,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            dashboardEntries().forEach { entry ->
                AppleGlass(shape = RoundedCornerShape(14.dp)) {
                    Row(
                        modifier = Modifier
                            .clickable { onOpenTab(entry.tab) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            entry.tab.icon,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            entry.tab.label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomWidgetCard(
    widget: CustomWidget,
    onOpenTab: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    when (widget.kind) {
        "link" -> {
            val tab = runCatching { Tab.valueOf(widget.target) }.getOrNull() ?: return
            AppleGlass(shape = RoundedCornerShape(18.dp), onClick = { onOpenTab(tab) }) {
                Row(
                    modifier = modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.Accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(21.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(widget.title.ifBlank { tab.label }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Collegamento · ${tab.label}", color = AppColors.Faint, fontSize = AppleType.Footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        "probe" -> {
            var nonce by remember(widget.target) { mutableStateOf(0) }
            var code by remember(widget.target) { mutableStateOf<Int?>(null) }
            var checking by remember(widget.target) { mutableStateOf(true) }
            LaunchedEffect(widget.target, nonce) {
                checking = true
                code = probeUrl(widget.target)
                checking = false
            }
            val dot = when {
                checking && code == null -> AppColors.Faint
                code != null && code in 200..399 -> AppColors.Success
                else -> AppColors.Error
            }
            val sub = when {
                checking && code == null -> "controllo…"
                code != null -> "HTTP $code"
                else -> "non raggiungibile"
            }
            AppleGlass(shape = RoundedCornerShape(18.dp), onClick = { nonce++ }) {
                Row(
                    modifier = modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(dot)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(widget.title.ifBlank { widget.target }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, color = AppColors.Faint, fontSize = AppleType.Footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        "note" -> {
            AppleGlass(shape = RoundedCornerShape(18.dp)) {
                Column(
                    modifier = modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(widget.title.ifBlank { "Nota" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(widget.detail.ifBlank { "—" }, color = AppColors.Faint, fontSize = AppleType.Footnote)
                }
            }
        }
    }
}

private fun builtinLabel(id: String): String = when (id) {
    "status" -> "Stato generale"
    "bots" -> "Bot"
    "cron" -> "Cron"
    "chats" -> "Chat"
    "gpu" -> "GPU"
    "quick" -> "Accesso rapido"
    else -> id
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomizePanel(
    order: List<String>,
    hidden: Set<String>,
    custom: List<CustomWidget>,
    onChange: (List<String>, Set<String>, List<CustomWidget>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val orderState = remember { order.toMutableStateList() }
    var hiddenState by remember { mutableStateOf(hidden) }
    var customsState by remember { mutableStateOf(custom) }
    var newKind by remember { mutableStateOf("link") }
    var newTitle by remember { mutableStateOf("") }
    var newTarget by remember { mutableStateOf("") }
    var newDetail by remember { mutableStateOf("") }
    var newTab by remember { mutableStateOf(Tab.Video) }
    fun emit() = onChange(orderState.toList(), hiddenState, customsState)
    fun labelFor(id: String): String {
        customsState.firstOrNull { it.id == id }?.let { return "${it.title.ifBlank { it.kind }} (${it.kind})" }
        return builtinLabel(id)
    }

    AppleGlass(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PERSONALIZZA", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = AppColors.Faint)
                }
            }
                orderState.forEachIndexed { index, id ->
                    val visible = id !in hiddenState
                    val isCustom = customsState.any { it.id == id }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(labelFor(id), color = if (visible) Color.White else AppColors.Faint, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        androidx.compose.material3.IconButton(
                            onClick = {
                                if (index > 0) {
                                    val tmp = orderState[index - 1]
                                    orderState[index - 1] = id
                                    orderState[index] = tmp
                                    emit()
                                }
                            },
                            enabled = index > 0
                        ) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Su", tint = AppColors.Accent) }
                        androidx.compose.material3.IconButton(
                            onClick = {
                                if (index < orderState.size - 1) {
                                    val tmp = orderState[index + 1]
                                    orderState[index + 1] = id
                                    orderState[index] = tmp
                                    emit()
                                }
                            },
                            enabled = index < orderState.size - 1
                        ) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Giù", tint = AppColors.Accent) }
                        androidx.compose.material3.Switch(
                            checked = visible,
                            onCheckedChange = {
                                hiddenState = if (it) hiddenState - id else hiddenState + id
                                emit()
                            }
                        )
                        if (isCustom) {
                            androidx.compose.material3.IconButton(onClick = {
                                customsState = customsState.filterNot { it.id == id }
                                orderState.remove(id)
                                hiddenState = hiddenState - id
                                emit()
                            }) { Icon(Icons.Rounded.Delete, contentDescription = "Elimina", tint = AppColors.Error) }
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                Text("AGGIUNGI WIDGET", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("link" to "Collegamento", "probe" to "Sonda", "note" to "Promemoria").forEach { (kind, label) ->
                        TextButton(onClick = { newKind = kind }) {
                            Text(label, color = if (newKind == kind) AppColors.Accent else AppColors.Faint, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                SettingsField("Titolo", newTitle, { newTitle = it })
                when (newKind) {
                    "link" -> {
                        Text("Sezione", color = AppColors.Muted, fontSize = 13.sp)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tab.entries.filter { it != Tab.Chat }.forEach { tab ->
                                TextButton(onClick = { newTab = tab }) {
                                    Text(tab.label, color = if (newTab == tab) AppColors.Accent else AppColors.Faint)
                                }
                            }
                        }
                    }
                    "probe" -> SettingsField("URL (http/https)", newTarget, { newTarget = it })
                    "note" -> SettingsField("Testo", newDetail, { newDetail = it })
                }
                val urlError = if (newKind == "probe") validateHttpUrl(newTarget.trim(), "URL") else null
                val canAdd = newTitle.isNotBlank() && (newKind != "probe" || urlError == null) && (newKind != "note" || newDetail.isNotBlank())
                androidx.compose.material3.Button(
                    onClick = {
                        val widget = CustomWidget(
                            id = "c:${System.currentTimeMillis()}",
                            kind = newKind,
                            title = newTitle.trim(),
                            target = when (newKind) {
                                "link" -> newTab.name
                                "probe" -> newTarget.trim()
                                else -> ""
                            },
                            detail = if (newKind == "note") newDetail.trim() else ""
                        )
                        customsState = customsState + widget
                        orderState.add(widget.id)
                        newTitle = ""
                        newTarget = ""
                        newDetail = ""
                        emit()
                    },
                    enabled = canAdd,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Aggiungi", fontWeight = FontWeight.SemiBold) }
                if (newKind == "probe" && urlError != null && newTarget.isNotBlank()) {
                    Text(urlError, color = AppColors.Error, fontSize = AppleType.Footnote)
                }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Chiudi", color = AppColors.Accent) }
                TextButton(onClick = {
                    orderState.clear()
                    orderState.addAll(defaultDashboardOrder())
                    hiddenState = emptySet()
                    customsState = emptyList()
                    emit()
                }) { Text("Ripristina", color = AppColors.Error) }
            }
        }
    }
}
