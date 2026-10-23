package com.nemoclaw.chat.features.bots

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.nemoclaw.chat.gpuManagerBase
import com.nemoclaw.chat.httpGetResponseQuick
import com.nemoclaw.chat.loadGpuAppSecret
import com.nemoclaw.chat.features.screen.ScreenScreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
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
import com.nemoclaw.chat.BitmapImageLoader
import com.nemoclaw.chat.PollWhileStarted
import com.nemoclaw.chat.ScreenStatusInfo
import com.nemoclaw.chat.decodeScreenFrame
import com.nemoclaw.chat.fetchScreenFrameBytes
import com.nemoclaw.chat.getScreenStatus
import com.nemoclaw.chat.loadBotAutoApproveMap
import com.nemoclaw.chat.loadGpuAppSecret
import com.nemoclaw.chat.saveBotAutoApprove
import com.nemoclaw.chat.core.WorkLimits
import com.nemoclaw.chat.loadBotAutoApproveMap
import com.nemoclaw.chat.saveBotAutoApprove
import com.nemoclaw.chat.saveGatewayConnectionSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    canOpenBotChat: () -> Boolean = { true },
    // Azione accodata dalla sidebar (long-press): consumata al roster pronto.
    pendingAction: BotSidebarAction? = null,
    onPendingActionConsumed: () -> Unit = {}
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
    var turnHistory by remember { mutableStateOf(emptyList<GroupTurnSummary>()) }
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
    var fleetGpu by remember(settings.gatewayUrl) { mutableStateOf("GPU: …") }
    fun refreshFleet() {
        scope.launch {
            turnHistory = loadGroupTurnHistory(appContext)
            fleetGpu = runCatching {
                val base = gpuManagerBase(settings.gatewayUrl)
                val key = loadGpuAppSecret(appContext)
                val (code, body) = httpGetResponseQuick("$base/status", key)
                if (code !in 200..299) {
                    "GPU: non raggiungibile (HTTP $code)"
                } else {
                    val status = JSONObject(body)
                    "GPU: ${status.optString("current_state", "?")} · coda ${status.optInt("queue_length", 0)}"
                }
            }.getOrElse { "GPU: non raggiungibile" }
        }
    }
    LaunchedEffect(settings.gatewayUrl) { refreshFleet() }

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
        val key = withContext(Dispatchers.IO) { loadGpuAppSecret(appContext) }
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
        val key = withContext(Dispatchers.IO) { loadGpuAppSecret(appContext) }
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

    fun duplicateBot(bot: HermesBotItem) {
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
    }

    // Azioni accodate dalla sidebar (long-press): consumate quando il
    // roster e pronto (serve a risolvere il bot per chiave).
    LaunchedEffect(pendingAction, roster) {
        val action = pendingAction ?: return@LaunchedEffect
        val items = roster?.items ?: return@LaunchedEffect
        fun findBot(key: String): HermesBotItem? = items.firstOrNull { it.identityKey == key }
        when (action) {
            is BotSidebarAction.Edit -> findBot(action.botKey)?.let { openEditor(it) }
                ?: Toast.makeText(context, "Bot non più disponibile.", Toast.LENGTH_SHORT).show()
            is BotSidebarAction.Duplicate -> findBot(action.botKey)?.let { duplicateBot(it) }
                ?: Toast.makeText(context, "Bot non più disponibile.", Toast.LENGTH_SHORT).show()
            is BotSidebarAction.Delete -> {
                val bot = findBot(action.botKey)
                if (bot == null) {
                    Toast.makeText(context, "Bot non più disponibile.", Toast.LENGTH_SHORT).show()
                } else {
                    deleteBot = bot
                    deleteConfirmation = ""
                }
            }
            is BotSidebarAction.ManageGroups -> {
                status = "Gruppi qui sotto: creali e lanciali da questa scheda."
                scope.launch { botListState.animateScrollToItem(2) }
            }
            is BotSidebarAction.OpenScreen -> {
                if (findBot(action.botKey) == null) {
                    Toast.makeText(context, "Bot non più disponibile.", Toast.LENGTH_SHORT).show()
                } else {
                    showSectionScreen = true
                }
            }
        }
        onPendingActionConsumed()
    }

    LaunchedEffect(settings.gatewayUrl, refreshNonce) {
        connections = loadHermesBotConnections(context, settings)
        groups = loadHermesBotGroupsSynced(context, settings)
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
                    duplicateBot(bot)
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(AppColors.Accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Computer,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Bot Hermes", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Text("Profili reali con configurazione, memoria, skill e credenziali separate. Le routine bot richiedono multiplexing attivo.", color = AppColors.Muted, fontSize = 14.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
            Text(status, color = AppColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        // Loading: roster null = primo fetch in corso (skeleton/progress +
        // liveRegion per screen reader). Distinto da empty/error.
        if (roster == null) {
            item(key = "loading") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(AppColors.Accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Computer,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.Accent,
                        trackColor = AppColors.Border
                    )
                    Text(
                        "Carico il roster reale Hermes…",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
        // Errori sorgente: retry esplicito con refreshNonce++ (mai auto-prune).
        val sourceFailures = roster?.sourceFailures.orEmpty()
        if (sourceFailures.isNotEmpty()) {
            item(key = "sources-error") {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Alcune fonti Hermes non rispondono.", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Fonti con errore: ${sourceFailures.joinToString(", ")}.",
                        color = AppColors.Muted,
                        fontSize = 14.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                    TextButton(
                        onClick = { refreshNonce++ },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Riprova", color = AppColors.Accent) }
                    HorizontalDivider(color = AppColors.Border)
                }
            }
        }
        item(key = "fleet") {
            FleetCard(
                bots = (roster?.items?.size ?: 0) - botHiddenLocal.size,
                groups = groups.size,
                turns = turnHistory,
                gpuLine = fleetGpu,
                onRefresh = { refreshFleet() }
            )
        }
        item {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ENDPOINT HERMES", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                connections.items.forEach { connection ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(connection.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (connection.endpoint.isBlank()) "Endpoint non configurato" else connection.endpoint,
                                color = AppColors.Muted,
                                fontSize = 11.sp
                            )
                        }
                        if (!connection.isPrimary) {
                            IconButton(onClick = { removeConnection = connection }) { Icon(Icons.Rounded.Delete, contentDescription = "Rimuovi connessione", tint = AppColors.Error) }
                        } else {
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppColors.Faint)
                        }
                    }
                    HorizontalDivider(color = AppColors.Border)
                }
                connections.warnings.forEach { warning ->
                    Text(warning, color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GRUPPI HERMES", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Seleziona da 2 a 6 bot anche su connessioni diverse. Max 3 turni e 10 risposte per turno.", color = AppColors.Muted, fontSize = 14.sp)
                OutlinedTextField(
                    value = groupNameInput,
                    onValueChange = { groupNameInput = it },
                    label = { Text("Nome gruppo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                roster?.items.orEmpty().forEach { bot ->
                    val selected = bot.identityKey in selectedGroupMemberKeys
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                        BotAvatar(bot.displayName, bot.profile, 40.dp)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("@${bot.handle} · ${bot.displayName}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(bot.connectionLabel, color = AppColors.Muted, fontSize = 11.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppColors.Faint)
                    }
                    HorizontalDivider(color = AppColors.Border)
                }
                if (roster?.items.isNullOrEmpty()) {
                    Text("Crea prima almeno 2 bot qui sopra per formare un gruppo.", color = AppColors.Muted, fontSize = 13.sp)
                }
                Text("Selezionati: ${selectedGroupMemberKeys.size}/6", color = AppColors.Muted, fontSize = 12.sp)
                Button(
                    enabled = groupNameInput.isNotBlank() && selectedGroupMemberKeys.size in 2..6,
                    onClick = {
                        scope.launch {
                            runCatching {
                                val selected = roster?.items.orEmpty().filter { it.identityKey in selectedGroupMemberKeys }
                                upsertHermesBotGroupSynced(
                                    context,
                                    settings,
                                    HermesBotGroup(
                                        groupNameInput.trim(),
                                        selected.map { bot -> HermesBotGroupMember(bot.connectionId, bot.profile, bot.displayName, bot.handle, bot.identityKey) }
                                    )
                                )
                            }.onSuccess { savedStatus ->
                                groups = loadHermesBotGroupsSynced(context, settings)
                                groupNameInput = ""
                                selectedGroupMemberKeys = emptySet()
                                status = savedStatus
                            }.onFailure { status = it.message ?: "Gruppo non salvato." }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Salva gruppo", color = Color(0xFF171009)) }
                groups.forEach { group ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(group.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (group.members.size == 1) "1 membro · sessioni Group persistenti" else "${group.members.size} membri · sessioni Group persistenti",
                                color = AppColors.Muted,
                                fontSize = 11.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                openGroup = group
                                groupPrompt = ""
                                groupResult = null
                            }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Apri gruppo", tint = Color.White) }
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { deleteHermesBotGroupSynced(context, settings, group.name) }
                                        .onSuccess { removedStatus ->
                                            groups = loadHermesBotGroupsSynced(context, settings)
                                            status = removedStatus
                                        }
                                        .onFailure { status = it.message ?: "Gruppo non rimosso." }
                                }
                            }) { Icon(Icons.Rounded.Delete, contentDescription = "Elimina gruppo", tint = AppColors.Error) }
                        }
                    }
                    HorizontalDivider(color = AppColors.Border)
                }
            }
        }
        // Empty distinta da loading/error: roster caricato ma zero bot visibili.
        // Nascosta quando ci sono sourceFailures (mostra solo errore sopra).
        val emptyRoster = roster
        if (emptyRoster != null && emptyRoster.items.isEmpty() && emptyRoster.sourceFailures.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(AppColors.Accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        "Nessun bot disponibile.",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Crea il primo bot oppure collega un altro endpoint Hermes.",
                        color = AppColors.Muted,
                        fontSize = 14.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    Button(
                        onClick = { openEditor(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Crea primo bot", color = Color(0xFF171009)) }
                    TextButton(
                        onClick = {
                            connectionLabelInput = ""
                            connectionEndpointInput = ""
                            connectionTokenInput = ""
                            showConnectionEditor = true
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Verifica connessioni", color = AppColors.Accent) }
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
        LaunchedEffect(group.name) {
            turnHistory = loadGroupTurnHistory(context)
        }
        AlertDialog(
            onDismissRequest = { if (!groupRunning) openGroup = null },
            title = { Text("Turno · ${group.name}", color = Color.White) },
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
                        GroupTurnActivity(
                            result = result,
                            sourceLabel = { connectionId ->
                                connections.items.firstOrNull { it.id.equals(connectionId, true) }?.label
                                    ?: "Connessione Hermes"
                            }
                        )
                    }
                    if (!groupRunning && turnHistory.isNotEmpty()) {
                        Text("Turni recenti", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        turnHistory.take(5).forEach { summary ->
                            Text(
                                "${summary.group} · ${groupOutcomeLabel(summary.outcome)} · ${summary.replies} risposte · ${formatGroupTurnTime(summary.atMs)}",
                                color = AppColors.Muted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (groupRunning) {
                    TextButton(
                        onClick = { groupJob?.cancel() },
                        // Contratto a11y test_protocol_contract: etichetta parlata "Annulla turno".
                        modifier = Modifier.semantics { contentDescription = "Annulla turno" }
                    ) { Text("Annulla", color = AppColors.Accent) }
                } else {
                    Button(
                        enabled = groupPrompt.isNotBlank(),
                        onClick = {
                            groupRunning = true
                            groupJob = scope.launch {
                                try {
                                    val result = runHermesBotGroupTurn(context, settings, group, groupPrompt)
                                    currentCoroutineContext().ensureActive()
                                    groupResult = result
                                    recordGroupTurn(context, summarizeGroupTurn(result))
                                    turnHistory = loadGroupTurnHistory(context)
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
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.Accent,
                            contentColor = Color(0xFF171009)
                        )
                    ) { Text("Esegui", color = Color(0xFF171009)) }
                }
            },
            dismissButton = { TextButton(onClick = { openGroup = null }, enabled = !groupRunning) { Text("Chiudi", color = AppColors.Accent) } }
        )
    }

    if (showEditor) {
        val editing = editorBot
        val selectedConnection = connections.items.firstOrNull { it.id.equals(selectedConnectionId, true) }
        // remember map: niente I/O SharedPreferences a ogni ricomposizione.
        val autoApproveMap = remember(editing?.profile, showEditor) { loadBotAutoApproveMap(appContext) }
        AlertDialog(
            onDismissRequest = { if (!mutating) showEditor = false },
            title = { Text(if (editing == null) "Nuovo bot Hermes" else "Modifica ${editing.displayName}", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(profileInput, { profileInput = it; editorError = "" }, label = { Text("Nome profilo") }, enabled = editing == null, singleLine = true)
                    OutlinedTextField(displayNameInput, { displayNameInput = it; editorError = "" }, label = { Text("Nome visualizzato") }, singleLine = true)
                    OutlinedTextField(descriptionInput, { descriptionInput = it; editorError = "" }, label = { Text("Descrizione (${descriptionInput.length}/$MAX_BOT_DESCRIPTION)") }, minLines = 2)
                    OutlinedTextField(soulInput, { soulInput = it; editorError = "" }, label = { Text("SOUL.md (opzionale, ${soulInput.length}/$MAX_BOT_SOUL)") }, minLines = 4)
                    val editorValidation = validateBotEditor(profileInput, displayNameInput, descriptionInput, soulInput, editing == null)
                    if (editorValidation != null) {
                        Text(editorValidation, color = AppColors.Error, fontSize = 12.sp)
                    }
                    if (editorError.isNotBlank()) {
                        Text(editorError, color = AppColors.Error, fontSize = 12.sp)
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
                                Text(
                                    if (selected) "✓ $label" else label,
                                    color = if (selected) AppColors.Accent else AppColors.Muted
                                )
                            }
                        }
                    }
                    Text("Connessione", color = AppColors.Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        connections.items.filter { it.enabled }.forEach { connection ->
                            val connectionSelected = connection.id.equals(selectedConnectionId, true)
                            TextButton(onClick = { selectedConnectionId = connection.id }, enabled = editing == null) {
                                Text(
                                    if (connectionSelected) "✓ ${connection.label}" else connection.label,
                                    color = if (connectionSelected) AppColors.Accent else AppColors.Muted
                                )
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(1.dp))
                }
            },
            confirmButton = {
                // Stessa validazione del server: mai un 400 criptico.
                val editorValidation = validateBotEditor(profileInput, displayNameInput, descriptionInput, soulInput, editing == null)
                Button(
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
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = Color(0xFF171009)
                    )
                ) { Text("Salva", color = Color(0xFF171009)) }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }, enabled = !mutating) { Text("Annulla", color = AppColors.Accent) } }
        )
    }

    deleteBot?.let { bot ->
        AlertDialog(
            onDismissRequest = { if (!mutating) deleteBot = null },
            title = { Text("Elimina ${bot.displayName}", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("L'eliminazione rimuove il profilo Hermes e i suoi dati dal server. Scrivi esattamente ${bot.profile} per confermare.", color = AppColors.Muted)
                    OutlinedTextField(deleteConfirmation, { deleteConfirmation = it }, label = { Text("Conferma nome") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
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
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Error,
                        contentColor = Color.White
                    )
                ) { Text("Conferma", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { deleteBot = null }, enabled = !mutating) { Text("Annulla", color = AppColors.Accent) } }
        )
    }

    removeConnection?.let { connection ->
        AlertDialog(
            onDismissRequest = { removeConnection = null },
            title = { Text("Rimuovi ${connection.label}", color = Color.White) },
            text = { Text("La connessione endpoint verrà eliminata dal dispositivo. I bot su questa connessione smetteranno di funzionare.", color = AppColors.Muted) },
            confirmButton = {
                Button(
                    onClick = {
                        removeConnection = null
                        scope.launch {
                            runCatching { deleteHermesBotConnection(context, settings, connection.id) }
                                .onSuccess { status = "Connessione rimossa."; refreshNonce++ }
                                .onFailure { status = it.message ?: "Connessione non rimossa." }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Error,
                        contentColor = Color.White
                    )
                ) { Text("Conferma", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { removeConnection = null }) { Text("Annulla", color = AppColors.Accent) } }
        )
    }

    if (showConnectionEditor) {
        AlertDialog(
            onDismissRequest = { if (!mutating) showConnectionEditor = false },
            title = { Text("Nuova connessione Hermes", color = Color.White) },
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
                Button(
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
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = Color(0xFF171009)
                    )
                ) { Text("Salva", color = Color(0xFF171009)) }
            },
            dismissButton = { TextButton(onClick = { showConnectionEditor = false }, enabled = !mutating) { Text("Annulla", color = AppColors.Accent) } }
        )
    }
}

