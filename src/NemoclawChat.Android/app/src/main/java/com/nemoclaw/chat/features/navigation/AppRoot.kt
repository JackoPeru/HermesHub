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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nemoclaw.chat.jarvis.ui.JarvisModeScreen
import com.nemoclaw.chat.features.bots.BotChatContext
import com.nemoclaw.chat.features.bots.BotsScreen
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
    var selectedTabName by rememberSaveable { mutableStateOf(Tab.Chat.name) }
    val selectedTab = remember(selectedTabName) {
        runCatching { Tab.valueOf(selectedTabName) }.getOrDefault(Tab.Chat)
    }
    var tabHistory by rememberSaveable { mutableStateOf(listOf(Tab.Chat.name)) }
    val setSelectedTab: (Tab) -> Unit = { tab ->
        if (tab.name != selectedTabName) {
            tabHistory = (tabHistory + tab.name).takeLast(10)
            selectedTabName = tab.name
        }
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
    var pendingBot by remember { mutableStateOf<BotChatContext?>(null) }
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }
    var savedDraft by rememberSaveable { mutableStateOf("") }
    val chatState = remember { ChatStateHolder().apply { draft = savedDraft } }
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
    }
    LaunchedEffect(chatState.activeStreams.size) {
        while (chatState.activeStreams.isNotEmpty()) {
            chatState.streamUiTickNs = System.nanoTime()
            delay(500L)
        }
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
    LaunchedEffect(Unit) {
        ConversationArchiveAutoSync.attach(context)
        try {
            while (true) {
                ConversationArchiveAutoSync.pullFromHub(context)
                ConversationArchiveAutoSync.scheduleUpload(context)
                delay(120_000)
            }
        } finally {
            ConversationArchiveAutoSync.detach()
        }
    }
    val chatScope = rememberCoroutineScope()
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

    BackHandler(enabled = sidebarOpen || tabHistory.size > 1) {
        if (sidebarOpen) {
            sidebarOpen = false
            return@BackHandler
        }
        if (tabHistory.size > 1) {
            val popped = tabHistory.dropLast(1)
            tabHistory = popped
            selectedTabName = popped.last()
        }
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
                onClose = { sidebarOpen = false },
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
                }
                )
                }
                }
            },
            content = {
                when (selectedTab) {
                Tab.Chat -> ChatScreen(
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
                onSwitchTab = { tab -> setSelectedTab(tab) }
                )
                Tab.Voice -> VoiceModeScreen(settings, initialGatewaySecret.value, voiceAutoStartToken)
                Tab.Jarvis -> JarvisModeScreen(settings, initialGatewaySecret.value)
                Tab.Projects -> ProjectsScreen(
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
                )
                Tab.Bots -> BotsScreen(
                    context = context,
                    settings = settings,
                onOpenBot = { bot ->
                        // Never carry normal-chat messages, attachments or
                        // previous-response state into a bot archive.
                        chatState.resetForNewChat()
                        pendingBot = bot
                        pendingConversationId = bot.localConversationId
                        pendingPrompt = ""
                        setSelectedTab(Tab.Chat)
                    }
                )
                Tab.Artifacts -> ArtifactLibraryScreen(
                context = context,
                settings = settings,
                onOpenConversation = { id -> pendingBot = null; pendingConversationId = id; pendingPrompt = ""; setSelectedTab(Tab.Chat) },
                onRegenerate = { prompt -> pendingBot = null; pendingConversationId = null; pendingPrompt = prompt; setSelectedTab(Tab.Chat) }
                )
                Tab.Search -> UniversalSearchScreen(context, settings) { kind, id ->
                when (kind) {
                "Chat", "Task" -> { pendingBot = null; pendingConversationId = id; pendingPrompt = ""; setSelectedTab(Tab.Chat) }
                "Progetto" -> setSelectedTab(Tab.Projects)
                "Artifact" -> setSelectedTab(Tab.Artifacts)
                "Cron" -> setSelectedTab(Tab.Cron)
                "Notifica" -> setSelectedTab(Tab.Notifications)
                "Memoria" -> setSelectedTab(Tab.Profile)
                }
                }
                Tab.Archive -> ArchiveScreen(
                context = context,
                onOpenConversation = { id, _ ->
                chatState.resetForNewChat()
                pendingBot = null
                pendingConversationId = id
                pendingPrompt = ""
                setSelectedTab(Tab.Chat)
                }
                )
                Tab.Cron -> CronScreen(context, settings)
                Tab.Notifications -> NotificationsScreen(context, settings) { prompt ->
                pendingBot = null
                pendingPrompt = prompt
                setSelectedTab(Tab.Chat)
                }
                Tab.Continuity -> ContinuityScreen(context, settings) { id -> pendingBot = null; pendingConversationId = id; pendingPrompt = ""; setSelectedTab(Tab.Chat) }
                Tab.Audit -> AuditScreen(context, settings)
                Tab.Server -> ServerScreen(context, settings)
                Tab.Hardware -> HardwareScreen(context, settings)
                Tab.Health -> HealthDashboardScreen(context, settings) { setSelectedTab(Tab.Settings) }
                Tab.Video -> VideoScreen(context, settings) { prompt ->
                pendingBot = null
                pendingPrompt = prompt
                setSelectedTab(Tab.Chat)
                }
                Tab.News -> NewsScreen(context, settings) { prompt ->
                pendingBot = null
                pendingPrompt = prompt
                setSelectedTab(Tab.Chat)
                }
                Tab.Settings -> SettingsScreen(
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
                )
                Tab.Profile -> ProfileScreen(
                context = context,
                settings = settings,
                onOpenTab = { tab -> setSelectedTab(tab) }
                )
                }
            }
        )
    }
}
