package com.nemoclaw.chat
import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.text.htmlEncode
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.AccountCircle
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nemoclaw.chat.jarvis.ui.JarvisModeScreen
import com.nemoclaw.chat.features.bots.BotsScreen
import com.nemoclaw.chat.features.bots.BotChatContext
import com.nemoclaw.chat.features.bots.HermesBotItem
import com.nemoclaw.chat.features.bots.loadLastBot
import com.nemoclaw.chat.features.bots.resolveCanonicalBotContext
import com.nemoclaw.chat.features.bots.saveLastBot
import com.nemoclaw.chat.features.screen.ScreenScreen
import com.nemoclaw.chat.ui.theme.ChatClawTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
internal fun ChatApp() {
    val context = LocalContext.current
    val loadedSettings by produceState<AppSettings?>(initialValue = null, context.applicationContext) {
        value = withContext(Dispatchers.IO) { loadSettings(context.applicationContext) }
    }
    val initialSettings = loadedSettings
    if (initialSettings == null) {
        StartupLoadingScreen()
        return
    }
    var settings by remember(initialSettings) { mutableStateOf(initialSettings) }
    var gatewaySecretRevision by remember { mutableIntStateOf(0) }
    val loadedGatewaySecret by produceState<LoadedGatewaySecret?>(
        initialValue = null,
        context.applicationContext,
        gatewaySecretRevision
    ) {
        value = withContext(Dispatchers.IO) { LoadedGatewaySecret(loadGatewaySecret(context.applicationContext)) }
    }
    val tabNavController = rememberNavController()
    val tabNavBackStackEntry by tabNavController.currentBackStackEntryAsState()
    // Mai null-transient: durante la navigate la route puo mancare per un
    // frame e il fallback a Chat flashe rebbe la chrome. Resta sull'ultimo tab.
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Chat) }
    val currentRoute = tabNavBackStackEntry?.destination?.route
    LaunchedEffect(currentRoute) {
        currentRoute?.let { selectedTab = tabForNavRoute(it) }
    }
    // Sezione Bot: i bot non sono piu un tab sidebar ma [Chat | Bot] in alto
    // alla chat. true = roster/dettaglio bot visibile con slide da destra.
    var botSectionVisible by rememberSaveable { mutableStateOf(false) }
    // Guard condivisa dai 4 entry-point di apertura bot (sidebar, last-bot,
    // roster, menu link): un solo resolve alla volta. remember (non saveable):
    // a processo morto non deve restare true senza coroutine viva.
    var sidebarBotOpening by remember { mutableStateOf(false) }
    val setSelectedTab: (Tab) -> Unit = { tab ->
        // Bot e schermo vivono nella sezione interna alla Chat: navigare
        // pulisce sempre il flag (coerce anche da vecchi stati salvati).
        botSectionVisible = false
        tabNavController.navigateToTab(if (tab == Tab.Bots || tab == Tab.Screen) Tab.Chat else tab)
    }
    val voiceProfileRevision = VoiceProfileEvents.revision
    val loadedWakeVoiceProfile by produceState<VoiceProfile?>(
        initialValue = null,
        settings.activeProjectId,
        voiceProfileRevision
    ) {
        value = withContext(Dispatchers.IO) { loadVoiceProfile(context.applicationContext, settings.activeProjectId) }
    }
    val initialGatewaySecret = loadedGatewaySecret
    val wakeVoiceProfile = loadedWakeVoiceProfile
    if (initialGatewaySecret == null || wakeVoiceProfile == null) {
        StartupLoadingScreen()
        return
    }
    var voiceAutoStartToken by rememberSaveable { mutableLongStateOf(0L) }
    var pendingPrompt by rememberSaveable { mutableStateOf("") }
    var pendingConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    // Parcelable: sopravvive anche al process death (prima solo ViewModel).
    var pendingBot by rememberSaveable { mutableStateOf<BotChatContext?>(null) }
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }
    var savedDraft by rememberSaveable { mutableStateOf("") }
    // Retained alla rotazione via ViewModel (prima: remember = stato perso).
    val chatViewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val chatState = remember(chatViewModel) {
        chatViewModel.chatState.apply { if (draft.isBlank()) draft = savedDraft }
    }
    // Chat <- [Chat | Bot]: esci da bot chat verso chat nuova; dalla
    // sezione roster torni alla chat sottostante senza resettarla.
    // Il reset scarta la bozza: avvisa invece di perderla in silenzio.
    // Mai durante apertura in corso (la coroutine sovrascriverebbe).
    val selectChatSegment: () -> Unit = {
        if (sidebarBotOpening) {
            Toast.makeText(context, "Apertura in corso.", Toast.LENGTH_SHORT).show()
        } else {
            if (pendingBot != null) {
                if (chatState.draft.isNotBlank() || chatState.pendingAttachments.isNotEmpty()) {
                    Toast.makeText(context, "Bozza scartata, chat bot chiusa.", Toast.LENGTH_SHORT).show()
                }
                pendingBot = null
                pendingConversationId = null
                pendingPrompt = ""
                chatState.resetForNewChat()
            }
            botSectionVisible = false
        }
    }
    // Apertura bot: mai sopra un turno attivo (reset ammazzerebbe stream,
    // binding e coda senza pulizia). L'utente interrompe o aspetta.
    fun canOpenBot(): Boolean {
        if (chatState.sending || chatState.backgroundWork != null) {
            Toast.makeText(context, "Finisci o interrompi il turno prima di aprire un bot.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }
    // Sidebar-bot stile desktop: apre la chat persistente del bot.
    // (Dichiarata dopo chatScope: lo usa per il resolve.)
    // Lato bot attivo = sezione roster visibile o bot chat aperta: la
    // sidebar mostra i bot (desktop), non tab/chat normali.
    val botSideActive = botSectionVisible || pendingBot != null
    val incoming = IncomingIntentBus.request
    LaunchedEffect(incoming.version) {
        if (incoming.version == 0L) return@LaunchedEffect
        pendingBot = null
        pendingConversationId = incoming.conversationId.ifBlank { null }
        pendingPrompt = incoming.prompt
        if (incoming.uri.isNotBlank()) {
            createAttachmentFromUri(context, incoming.uri.toUri(), settings.maxAttachmentMb)?.let { attachment -> chatState.pendingAttachments.add(attachment) }
        }
        setSelectedTab(tabForIncomingRoute(incoming.tab))
        // Deeplink "bots": Chat con sezione Bot attiva (non piu un tab).
        // Deeplink "screen": AppRoot non possiede showSectionScreen (vive in
        // BotsScreen): apri la sezione bot, da li lo schermo e a un tap.
        if (incoming.tab.equals("bots", ignoreCase = true)) botSectionVisible = true
        if (incoming.tab.equals("screen", ignoreCase = true)) botSectionVisible = true
    }
    LaunchedEffect(
        selectedTab,
        wakeVoiceProfile.wakeWord,
        wakeVoiceProfile.wakePhrase,
        settings.gatewayUrl,
        voiceProfileRevision
    ) {
        if (!wakeVoiceProfile.wakeWord || selectedTab == Tab.Voice || selectedTab == Tab.Jarvis) return@LaunchedEffect
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@LaunchedEffect
        }

        var detected = false
        startVoiceForegroundService(context, mode = "wake")
        try {
            awaitWakePhrase(context, settings, initialGatewaySecret.value, wakeVoiceProfile.wakePhrase)
            detected = true
            val activity = context as? Activity
            if (activity != null) {
                runCatching {
                    context.getSystemService(android.app.ActivityManager::class.java)
                        .moveTaskToFront(activity.taskId, android.app.ActivityManager.MOVE_TASK_WITH_HOME)
                }
            }
            voiceAutoStartToken = System.nanoTime()
            setSelectedTab(Tab.Voice)
        } finally {
            if (!detected) stopVoiceForegroundService(context)
        }
    }
    LaunchedEffect(chatState.draft) {
        if (chatState.draft != savedDraft) {
            savedDraft = chatState.draft
        }
    }
    // Pull periodico Hub (120s) solo a lifecycle STARTED, stesso pattern
    // LifecycleEventObserver usato in ChatTopBar.kt. Logica invariata:
    // attach, pullFromHub + scheduleUpload ogni 120s, detach in finally.
    val hubLifecycleOwner = LocalLifecycleOwner.current
    var hubPollStarted by remember { mutableStateOf(true) }
    DisposableEffect(hubLifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            hubPollStarted = event.targetState.isAtLeast(Lifecycle.State.STARTED)
        }
        hubLifecycleOwner.lifecycle.addObserver(obs)
        onDispose { hubLifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(hubPollStarted) {
        if (!hubPollStarted) return@LaunchedEffect
        ConversationArchiveAutoSync.attach(context)
        try {
            while (hubPollStarted) {
                ConversationArchiveAutoSync.pullFromHub(context)
                ConversationArchiveAutoSync.scheduleUpload(context)
                delay(120_000)
            }
        } finally {
            ConversationArchiveAutoSync.detach()
        }
    }
    val chatScope = rememberCoroutineScope()
    // Invariante bot-side: BotChatContext porta la SESSIONE CANONICA
    // condivisa col desktop (stessa transcript); pendingConversationId
    // porta l'id stabile locale (cache/snapshot). ChatScreen carica la
    // cache e poi la storia autorevole dal server; lo stream usa S.

    fun openSidebarBot(bot: HermesBotItem) {
        if (sidebarBotOpening) {
            Toast.makeText(context, "Apertura gia in corso.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!canOpenBot()) return
        sidebarBotOpening = true
        chatScope.launch {
            try {
                // Niente lock esterno: e dentro resolveCanonicalBotChat
                // (stessa chiave = deadlock). La guard sidebarBotOpening
                // copre il doppio-tap.
                resolveCanonicalBotContext(context.applicationContext, settings, bot, rosterMultiplex = true)
                    .onSuccess {
                        chatState.resetForNewChat()
                        pendingBot = it
                        pendingConversationId = it.localConversationId
                        pendingPrompt = ""
                        saveLastBot(context.applicationContext, it.connectionId, it.profile, it.displayName)
                        botSectionVisible = false
                        sidebarOpen = false
                    }
                    .onFailure {
                        Toast.makeText(context, it.message ?: "Apertura Bot Chat fallita.", Toast.LENGTH_LONG).show()
                    }
            } finally {
                sidebarBotOpening = false
            }
        }
    }
    // Riapre l'ultimo bot usato (main page della sezione): risolve la
    // forever-chat condivisa. Ritorna false se nessun ultimo bot
    // (chiamante: mostra il roster).
    fun openLastBot(): Boolean {
        val ref = loadLastBot(context.applicationContext) ?: return false
        if (sidebarBotOpening) {
            Toast.makeText(context, "Apertura gia in corso.", Toast.LENGTH_SHORT).show()
            return true
        }
        sidebarBotOpening = true
        chatScope.launch {
            try {
                // Niente lock esterno: e dentro resolveCanonicalBotChat
                // (stessa chiave = deadlock). La guard sidebarBotOpening
                // copre il doppio-tap.
                resolveCanonicalBotContext(context.applicationContext, settings, ref.toItem(), rosterMultiplex = true)
                    .onSuccess {
                        chatState.resetForNewChat()
                        pendingBot = it
                        pendingConversationId = it.localConversationId
                        pendingPrompt = ""
                        botSectionVisible = false
                    }
                    .onFailure {
                        botSectionVisible = true
                        Toast.makeText(context, it.message ?: "Bot non disponibile, apro il roster.", Toast.LENGTH_LONG).show()
                    }
            } finally {
                sidebarBotOpening = false
            }
        }
        return true
    }
    val selectBotSegment: () -> Unit = {
        // Main page della sezione = chat dell'ultimo bot usato (non roster).
        // Da bot chat aperta: vai al roster tenendo il contesto.
        // Mai durante apertura in corso.
        if (sidebarBotOpening) {
            Toast.makeText(context, "Apertura in corso.", Toast.LENGTH_SHORT).show()
        } else if (!botSectionVisible) {
            if (pendingBot != null) {
                botSectionVisible = true
            } else if (!openLastBot()) {
                botSectionVisible = true
            }
        }
    }
    val baseDensity = LocalDensity.current
    val rawFontScale = settings.fontScale
    val safeFontScale = if (rawFontScale.isFinite()) {
        rawFontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    } else {
        1f
    }
    val appDensity = Density(
        density = baseDensity.density,
        fontScale = safeFontScale
    )

    // Solo overlay sidebar: il back tra tab è gestito dal back stack reale
    // del NavHost di sistema (torna al tab precedente poi esce).
    BackHandler(enabled = sidebarOpen) {
        sidebarOpen = false
    }
    // Dalla sezione Bot il back torna alla chat (la sidebar ha priorita).
    // Mai durante apertura in corso: la coroutine sovrascriverebbe.
    BackHandler(enabled = botSectionVisible && !sidebarOpen && !sidebarBotOpening && selectedTab == Tab.Chat) {
        selectChatSegment()
    }
    // Da bot chat aperta (sezione chiusa) il back chiude il bot e torna
    // alla chat sottostante senza resettarla (stesso selectChatSegment).
    BackHandler(enabled = pendingBot != null && !botSectionVisible && !sidebarOpen && !sidebarBotOpening && selectedTab == Tab.Chat) {
        selectChatSegment()
    }

    CompositionLocalProvider(LocalDensity provides appDensity) {
        AppNavigation(
            selectedTab = selectedTab,
            topBar = {
                SectionTopBar(
                    tab = selectedTab,
                    onOpenSidebar = { sidebarOpen = true },
                    onBackToChat = { setSelectedTab(Tab.Chat) }
                )
            },
            sidebar = {
                if (sidebarOpen) {
                Box(
                modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB8000000))
                .clickable { sidebarOpen = false }
                ) {
                HermesSidebar(
                context = context,
                selectedTab = selectedTab,
                settings = settings,
                onClose = { sidebarOpen = false },
                botMode = selectedTab == Tab.Chat && botSideActive,
                activeBotKey = pendingBot?.let { "${it.connectionId}::${it.profile}" },
                onOpenBotItem = { openSidebarBot(it) },
                onManageBots = { sidebarOpen = false; botSectionVisible = true },
                onNewChat = {
                chatState.resetForNewChat()
                pendingBot = null
                setSelectedTab(Tab.Chat)
                sidebarOpen = false
                },
                onOpenConversation = { id ->
                pendingBot = null
                pendingConversationId = id
                pendingPrompt = ""
                setSelectedTab(Tab.Chat)
                sidebarOpen = false
                },
                onOpenTab = { tab ->
                setSelectedTab(tab)
                sidebarOpen = false
                },
                onToggleSidebarSection = { key ->
                val next = when (key) {
                "operativita" -> settings.copy(sidebarOperativita = !settings.sidebarOperativita)
                "controllo" -> settings.copy(sidebarControllo = !settings.sidebarControllo)
                "contenuti" -> settings.copy(sidebarContenuti = !settings.sidebarContenuti)
                "account" -> settings.copy(sidebarAccount = !settings.sidebarAccount)
                else -> settings.copy(sidebarRecenti = !settings.sidebarRecenti)
                }
                settings = next
                saveSettings(context.applicationContext, next)
                }
                )
                }
                }
            },
            content = {
                NavHost(
                    navController = tabNavController,
                    startDestination = tabNavStartDestination,
                    enterTransition = { androidx.compose.animation.EnterTransition.None },
                    exitTransition = { androidx.compose.animation.ExitTransition.None },
                    popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                    popExitTransition = { androidx.compose.animation.ExitTransition.None }
                ) {
                composable(Tab.Chat.navRoute) { Column(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                targetState = botSectionVisible,
                // Sezione Bot entra da destra (qualcosa di diverso), esce a
                // sinistra; ritorno speculare. Niente slide NavHost (None).
                transitionSpec = {
                    if (targetState) {
                        (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 3 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "chat-bot-section"
                ) { botSection ->
                if (botSection) {
                Column(modifier = Modifier.fillMaxSize()) {
                ChatBotToggle(
                botActive = true,
                onSelectChat = selectChatSegment,
                onSelectBot = selectBotSegment
                )
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                BotsScreen(
                    context = context,
                    settings = settings,
                onOpenBot = { bot ->
                        // bot e gia BotChatContext canonico dal funnel BotsScreen:
                        // niente re-resolve (la scan e fail-closed, non si ripete).
                        // Never carry normal-chat messages, attachments or
                        // previous-response state into a bot archive.
                        chatState.resetForNewChat()
                        pendingBot = bot
                        pendingConversationId = bot.localConversationId
                        pendingPrompt = ""
                        saveLastBot(context.applicationContext, bot.connectionId, bot.profile, bot.displayName)
                        setSelectedTab(Tab.Chat)
                    },
                    onOpenScreen = { setSelectedTab(Tab.Screen) },
                    onOpenCron = { setSelectedTab(Tab.Cron) },
                    onOpenSidebar = { sidebarOpen = true },
                    canOpenBotChat = { canOpenBot() }
                )
                }
                }
                } else {
                ChatScreen(
                context = context,
                settings = settings,
                state = chatState,
                scope = chatScope,
                conversationId = pendingConversationId,
                botProfile = pendingBot?.profile,
                botSessionId = pendingBot?.sessionId,
                botDisplayName = pendingBot?.displayName,
                botMultiplexEnabled = pendingBot?.multiplexEnabled == true,
                botConnectionId = pendingBot?.connectionId,
                botEndpoint = pendingBot?.endpoint,
                onNewChat = {
                    pendingBot = null
                    pendingConversationId = null
                    chatState.resetForNewChat()
                },
                initialPrompt = pendingPrompt,
                onInitialPromptConsumed = {
                pendingPrompt = ""
                pendingConversationId = null
                },
                onOpenSidebar = { sidebarOpen = true },
                onSwitchTab = { tab -> setSelectedTab(tab) },
                chatBotActive = pendingBot != null,
                chatBotOpening = sidebarBotOpening,
                onSelectChat = selectChatSegment,
                onSelectBot = selectBotSegment,
                onOpenBotSection = selectBotSegment
                )
                }
                }
                } }
                composable(Tab.Voice.navRoute) { VoiceModeScreen(settings, initialGatewaySecret.value, voiceAutoStartToken) }
                composable(Tab.Jarvis.navRoute) { JarvisModeScreen(settings, initialGatewaySecret.value) }
                composable(Tab.Projects.navRoute) { ProjectsScreen(
                context = context,
                settings = settings,
                onSettingsChanged = { updated ->
                settings = updated
                saveSettings(context, updated)
                },
                onNewChat = {
                pendingBot = null
                pendingConversationId = null
                pendingPrompt = ""
                setSelectedTab(Tab.Chat)
                },
                onOpenConversation = { id ->
                pendingBot = null
                pendingConversationId = id
                pendingPrompt = ""
                setSelectedTab(Tab.Chat)
                }
                ) }
                // Rotta legacy: i bot non sono piu un tab. Tenuta per vecchi
                // backstack salvati: redirect a Chat con sezione Bot attiva.
                composable(Tab.Bots.navRoute) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                // setSelectedTab azzera il flag: ordine obbligato.
                setSelectedTab(Tab.Chat)
                botSectionVisible = true
                }
                }
                // Rotta legacy: lo schermo non e piu un tab. Tenuta per vecchi
                // backstack salvati: redirect a Chat con sezione Bot attiva
                // (lo schermo si apre da li col pulsante dedicato).
                composable(Tab.Screen.navRoute) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                // setSelectedTab azzera il flag: ordine obbligato.
                setSelectedTab(Tab.Chat)
                botSectionVisible = true
                }
                }
                composable(Tab.Artifacts.navRoute) { ArtifactLibraryScreen(
                context = context,
                settings = settings,
                onOpenConversation = { id -> pendingBot = null; pendingConversationId = id; pendingPrompt = ""; setSelectedTab(Tab.Chat) },
                onRegenerate = { prompt -> pendingBot = null; pendingConversationId = null; pendingPrompt = prompt; setSelectedTab(Tab.Chat) }
                ) }
                composable(Tab.Search.navRoute) { UniversalSearchScreen(context, settings) { kind, id ->
                when (kind) {
                "Chat", "Task" -> { pendingBot = null; pendingConversationId = id; pendingPrompt = ""; setSelectedTab(Tab.Chat) }
                "Progetto" -> setSelectedTab(Tab.Projects)
                "Artifact" -> setSelectedTab(Tab.Artifacts)
                "Cron" -> setSelectedTab(Tab.Cron)
                "Notifica" -> setSelectedTab(Tab.Notifications)
                "Memoria" -> setSelectedTab(Tab.Profile)
                }
                } }
                composable(Tab.Archive.navRoute) { ArchiveScreen(
                context = context,
                onOpenConversation = { id, _ ->
                chatState.resetForNewChat()
                pendingBot = null
                pendingConversationId = id
                pendingPrompt = ""
                setSelectedTab(Tab.Chat)
                }
                ) }
                composable(Tab.Cron.navRoute) { CronScreen(context, settings) }
                composable(Tab.Notifications.navRoute) { NotificationsScreen(context, settings) { prompt ->
                pendingBot = null
                pendingPrompt = prompt
                setSelectedTab(Tab.Chat)
                } }
                composable(Tab.Continuity.navRoute) { ContinuityScreen(context, settings) { id -> pendingBot = null; pendingConversationId = id; pendingPrompt = ""; setSelectedTab(Tab.Chat) } }
                composable(Tab.Audit.navRoute) { AuditScreen(context, settings) }
                composable(Tab.Server.navRoute) { ServerScreen(context, settings) }
                composable(Tab.Hardware.navRoute) { HardwareScreen(context, settings) }
                composable(Tab.Health.navRoute) { HealthDashboardScreen(context, settings) { setSelectedTab(Tab.Settings) } }
                composable(Tab.Video.navRoute) { VideoScreen(context, settings) { prompt ->
                pendingBot = null
                pendingPrompt = prompt
                setSelectedTab(Tab.Chat)
                } }
                composable(Tab.News.navRoute) { NewsScreen(context, settings) { prompt ->
                pendingBot = null
                pendingPrompt = prompt
                setSelectedTab(Tab.Chat)
                } }
                composable(Tab.Settings.navRoute) { SettingsScreen(
                settings = settings,
                gatewaySecret = initialGatewaySecret.value,
                voiceProfile = wakeVoiceProfile,
                onGatewaySecretChanged = { gatewaySecretRevision++ },
                onSave = { newSettings ->
                settings = newSettings
                chatScope.launch(Dispatchers.IO) { saveSettings(context, newSettings) }
                },
                onReset = {
                val reset = AppSettings()
                settings = reset
                chatScope.launch {
                withContext(Dispatchers.IO) {
                saveSettings(context, reset)
                saveGatewaySecret(context, null)
                }
                gatewaySecretRevision++
                }
                }
                ) }
                composable(Tab.Profile.navRoute) { ProfileScreen(
                context = context,
                settings = settings,
                onOpenTab = { tab -> setSelectedTab(tab) }
                ) }
                }
            }
        )
    }
}
