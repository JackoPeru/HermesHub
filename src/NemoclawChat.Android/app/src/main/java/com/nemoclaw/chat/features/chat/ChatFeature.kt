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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Send
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
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
import com.nemoclaw.chat.ui.theme.ChatClawTheme
import com.nemoclaw.chat.createVideoPlayerView
import com.nemoclaw.chat.FullscreenVideoOrientationEffect
import com.nemoclaw.chat.findActivity
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
internal fun ChatScreen(
    context: Context,
    settings: AppSettings,
    state: ChatStateHolder,
    scope: kotlinx.coroutines.CoroutineScope,
    conversationId: String? = null,
    botProfile: String? = null,
    botSessionId: String? = null,
    botDisplayName: String? = null,
    botMultiplexEnabled: Boolean = false,
    botConnectionId: String? = null,
    botEndpoint: String? = null,
    onNewChat: () -> Unit = { state.resetForNewChat() },
    initialPrompt: String = "",
    onInitialPromptConsumed: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
    onSwitchTab: (Tab) -> Unit = {}
) {
    val remoteBot = !botConnectionId.isNullOrBlank() && !botConnectionId.equals("primary", true)
    val botSettings = if (remoteBot && !botEndpoint.isNullOrBlank()) {
        settings.copy(
            gatewayUrl = botEndpoint.trimEnd('/'),
            gatewayWsUrl = "",
            inferenceEndpoint = botEndpoint.trimEnd('/'),
            adminBridgeUrl = botEndpoint.trimEnd('/')
        )
    } else {
        settings
    }
    val botApiKey = if (remoteBot) {
        loadGatewayConnectionSecret(context, botConnectionId.orEmpty())
    } else {
        loadGatewaySecret(context)
    }
    val botAllowCompatAuth = !remoteBot
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var quickPrompt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(conversationId, initialPrompt) {
        if (!conversationId.isNullOrBlank()) {
            val saved = withContext(Dispatchers.IO) { loadConversation(context, conversationId) }
            if (saved != null) {
                state.activeConversationId = saved.id
                val expectedServerConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, saved.id)
                state.previousResponseId = if (botProfile.isNullOrBlank() && saved.serverConversationId == expectedServerConversationId) {
                    saved.previousResponseId
                } else {
                    null
                }
                state.hermesSessionId = saved.hermesSessionId
                    ?: withContext(Dispatchers.IO) { loadSessionBinding(context, saved.id, botProfile) }
                state.chatModelOverride = saved.modelOverride
                state.chatProviderOverride = saved.providerOverride
                state.chatReasoningEffort = saved.reasoningEffort
                state.messages.clear()
                val loadedMessages = saved.messages.toMutableList()
                val activeStream = state.activeStreams[saved.id]
                if (activeStream != null && loadedMessages.isNotEmpty() && loadedMessages.last().author == "Hermes") {
                    loadedMessages.removeAt(loadedMessages.lastIndex)
                }
                state.messages.addAll(loadedMessages)
            }
        }

        if (initialPrompt.isNotBlank()) {
            state.draft = initialPrompt
        }

        if (!conversationId.isNullOrBlank() || initialPrompt.isNotBlank()) {
            onInitialPromptConsumed()
        }
    }

    val haptics = LocalHapticFeedback.current
    val networkOnline by rememberOnlineState(context)
    var gatewayAvailable by remember(settings.gatewayUrl, settings.inferenceEndpoint, botConnectionId, botEndpoint) {
        mutableStateOf(false)
    }
    var gatewayRuntime by remember(settings.gatewayUrl, settings.inferenceEndpoint, botConnectionId, botEndpoint) {
        mutableStateOf<GatewayRuntimeStatus?>(null)
    }
    LaunchedEffect(networkOnline, botSettings.gatewayUrl, botSettings.inferenceEndpoint, botApiKey) {
        if (!networkOnline) {
            gatewayAvailable = false
            return@LaunchedEffect
        }
        while (true) {
            gatewayAvailable = withContext(Dispatchers.IO) {
                probeHermesGateway(botSettings, botApiKey)
            }
            gatewayRuntime = if (gatewayAvailable) {
                withContext(Dispatchers.IO) { loadGatewayRuntimeStatus(botSettings, botApiKey) }
            } else {
                null
            }
            delay(if (gatewayAvailable) 15_000L else 5_000L)
        }
    }
    // Capabilities + model catalog in background (fonte capability-driven, mai version check).
    LaunchedEffect(botSettings.gatewayUrl, botApiKey) {
        if (botSettings.gatewayUrl.isBlank()) return@LaunchedEffect
        val caps = withContext(Dispatchers.IO) {
            runCatching { loadHermesCapabilitiesCached(botSettings, botApiKey) }.getOrNull()
        } ?: return@LaunchedEffect
        state.chatCapabilities = caps
        if (caps.supportsModelOptions()) {
            val catalog = withContext(Dispatchers.IO) {
                runCatching {
                    val body = httpGet("${botSettings.gatewayUrl.trimEnd('/')}/api/model/options", botApiKey)
                    parseModelOptionsPayload(body)
                }.getOrElse {
                    runCatching {
                        val fallback = httpGet("${botSettings.gatewayUrl.trimEnd('/')}/v1/models", botApiKey)
                        parseV1ModelsFallback(fallback)
                    }.getOrNull()
                }
            }
            if (catalog != null && (catalog.models.isNotEmpty() || catalog.providers.isNotEmpty())) state.chatModelCatalog = catalog
        } else {
            val fallback = withContext(Dispatchers.IO) {
                runCatching { httpGet("${botSettings.gatewayUrl.trimEnd('/')}/v1/models", botApiKey) }.getOrNull()
            }?.let { parseV1ModelsFallback(it) }
            if (fallback != null && fallback.models.isNotEmpty()) state.chatModelCatalog = fallback
        }
    }
    val isStreaming = state.streamingState != null
    val archivedBotWithoutContext = botProfile.isNullOrBlank() &&
        (conversationId ?: state.activeConversationId)
            ?.startsWith("bot-", ignoreCase = true) == true
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val attachment = withContext(Dispatchers.IO) { createAttachmentFromUri(context, uri, settings.maxAttachmentMb) }
                if (attachment != null) {
                    state.pendingAttachments.add(attachment)
                    state.messages.add(ChatMessage("Allegato", "${attachment.filename} pronto per Hermes (${attachment.sizeBytes.toReadableFileSize()}).", fromUser = false, isAction = true))
                } else {
                    state.messages.add(ChatMessage("Allegato", "File vuoto, non leggibile o troppo grande. Limite attuale: ${settings.maxAttachmentMb} MB.", fromUser = false, isAction = true))
                }
            }
        }
    }
    var scanUri by remember { mutableStateOf<Uri?>(null) }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = scanUri
        if (ok && uri != null) scope.launch { createAttachmentFromUri(context, uri, settings.maxAttachmentMb)?.let { attachment -> state.pendingAttachments.add(attachment.copy(filename = "scansione-${System.currentTimeMillis()}.jpg")); state.messages.add(ChatMessage("Scanner", "Documento acquisito e allegato.", false, isAction = true)) } }
    }
    LaunchedEffect(isStreaming) {
        if (!isStreaming && state.messages.isNotEmpty()) {
            runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        }
    }
    val streamingTextLen = state.streamingState?.text?.length ?: 0
    LaunchedEffect(isStreaming) {
        if (!isStreaming) {
            return@LaunchedEffect
        }
        val totalItems = state.messages.size + 1
        if (totalItems > 0) {
            listState.scrollToItem(totalItems - 1)
        }
    }
    val contextUsage = remember(
        state.messages.size,
        state.draft,
        state.streamingState?.stats?.promptTokens,
        state.streamingState?.stats?.tokensOut,
        state.streamingState?.stats?.contextTokens,
        state.streamingState?.stats?.contextLength,
        state.streamingState?.stats?.contextPercent,
        streamingTextLen,
        settings.gatewayUrl,
        settings.model,
        settings.preferredApi
    ) {
        estimateChatContextUsage(
            settings = settings,
            messages = state.messages.toList(),
            draft = state.draft,
            streamingState = state.streamingState
        )
    }

    val isEmptyChat = state.messages.isEmpty() && state.streamingState == null
    val emptyChatBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0x34F5A524),
                Color(0x241F1710),
                Color(0x12181510),
                AppColors.Background,
                AppColors.Background
            ),
            startY = -260f,
            endY = 1440f
        )
    }
    val solidBrush = remember {
        Brush.verticalGradient(listOf(AppColors.Background, AppColors.Background))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isEmptyChat) emptyChatBrush else solidBrush)
    ) {
        TopBar(
            contextUsage = contextUsage,
            connected = gatewayAvailable,
            gatewayRuntime = gatewayRuntime,
            onNewChat = onNewChat,
            onOpenSidebar = onOpenSidebar,
            onOpenArchive = { onSwitchTab(Tab.Archive) }
        )
        if (!botProfile.isNullOrBlank()) {
            Surface(color = AppColors.NavIndicator, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Bot attivo · ${botDisplayName ?: botProfile} · Bot Chat",
                    color = AppColors.Accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp)
                )
            }
        }
        if (archivedBotWithoutContext) {
            Surface(color = Color(0xFF7A3E00), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Chat bot archiviata: riaprila da Bot Hermes",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onSwitchTab(Tab.Bots) }) { Icon(Icons.Rounded.SmartToy, contentDescription = "Apri Bot Hermes", tint = Color.White) }
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(
                    state.messages,
                    key = { message -> message.id }
                ) { message ->
                    MessageBubble(message, settings)
                }
                state.streamingState?.let { streaming ->
                    item(key = "streaming") {
                        StreamingBubbleView(
                            streaming,
                            settings.showToolCalls,
                            settings.showMessageMetrics,
                            settings.metricFilter(),
                            state.streamUiTickNs,
                            onSpeakMessage = { text ->
                                scope.launch {
                                    runCatching { speakChatMessage(context, botSettings, text, botApiKey) }
                                        .onFailure { Toast.makeText(context, "TTS Kokoro non disponibile: ${it.message}", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        )
                    }
                }
            }
            if (isEmptyChat) {
                // Empty state must stay above the transparent LazyColumn or the list consumes taps.
                EmptyState(onPrompt = { quickPrompt = it })
            }
            val showJumpToBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val total = info.totalItemsCount
                    if (total <= 1) {
                        false
                    } else {
                        val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
                        (total - 1 - last.index) >= 2 ||
                            (last.index == total - 1 && last.offset + last.size > info.viewportEndOffset + 150)
                    }
                }
            }
            if (showJumpToBottom) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) listState.animateScrollToItem(total - 1)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp),
                    containerColor = AppColors.Elevated,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Vai alla fine della chat")
                }
            }
        }
        val slashMatches = remember(state.draft) { filterSlashCommands(state.draft) }
        if (slashMatches.isNotEmpty() && !state.sending) {
            SlashCommandList(commands = slashMatches) { cmd ->
                state.draft = ""
                executeSlashCommand(
                    command = cmd,
                    setMode = { state.mode = it },
                    clear = { state.resetForNewChat() },
                    setDraft = { state.draft = it },
                    addAction = { title, body ->
                        state.messages.add(ChatMessage(title, body, fromUser = false, isAction = true))
                    },
                    onSwitchTab = onSwitchTab
                )
            }
        }
        if (!networkOnline) {
            Surface(color = Color(0xFF7A3E00), modifier = Modifier.fillMaxWidth()) {
                Text(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    text = "Rete Internet non validata. Provo comunque Hermes via LAN/Tailnet.",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
        var mediaRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
        fun releaseVoiceRecorder(deleteTempFile: Boolean) {
            val recorder = mediaRecorder
            mediaRecorder = null
            state.isRecordingVoiceNote = false
            if (recorder != null) {
                runCatching { recorder.stop() }
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
            }
            if (deleteTempFile) {
                state.tempVoiceNoteFile?.let { runCatching { it.delete() } }
                state.tempVoiceNoteFile = null
            }
        }
        val startVoiceRecording: () -> Unit = {
            try {
                val tempFile = File.createTempFile("voice_note", ".m4a", context.cacheDir)
                state.tempVoiceNoteFile = tempFile
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.media.MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.MediaRecorder()
                }
                mediaRecorder = recorder
                recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                recorder.setOutputFile(tempFile.absolutePath)
                recorder.prepare()
                recorder.start()
                state.isRecordingVoiceNote = true
            } catch (ex: Exception) {
                releaseVoiceRecorder(deleteTempFile = true)
                state.messages.add(ChatMessage("Errore Voce", "Impossibile registrare audio: ${ex.message ?: ex.javaClass.simpleName}", fromUser = false, isAction = true))
            }
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                if (isGranted) {
                    startVoiceRecording()
                } else {
                    android.widget.Toast.makeText(context, "Permesso microfono negato.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
        DisposableEffect(Unit) {
            onDispose { releaseVoiceRecorder(deleteTempFile = true) }
        }

        ChatModelSessionBar(
            state = state,
            settings = settings,
            context = context,
            botSettings = botSettings,
            botApiKey = botApiKey,
            botProfile = botProfile,
            botMultiplexEnabled = botMultiplexEnabled,
            scope = scope
        )
        ChatApprovalCards(
            state = state,
            botSettings = botSettings,
            botApiKey = botApiKey,
            botProfile = botProfile,
            botMultiplexEnabled = botMultiplexEnabled,
            scope = scope,
            context = context
        )

        Composer(
            context = context,
            value = state.draft,
            attachments = state.pendingAttachments,
            onValueChange = { state.draft = it },
            onAttachImage = { filePicker.launch("*/*") },
            onPasteImage = {
                scope.launch {
                    val attachment = withContext(Dispatchers.IO) { createAttachmentFromClipboard(context, settings.maxAttachmentMb) }
                    if (attachment != null) {
                        state.pendingAttachments.add(attachment)
                        state.messages.add(ChatMessage("Incolla immagine", "${attachment.filename} pronta per Hermes (${attachment.sizeBytes.toReadableFileSize()}).", fromUser = false, isAction = true))
                    } else {
                        android.widget.Toast.makeText(context, "Nessuna immagine valida negli appunti", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onScanDocument = {
                val directory = File(context.cacheDir, "attachments").apply { mkdirs() }
                val file = File(directory, "scan-${System.currentTimeMillis()}.jpg")
                scanUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                scanUri?.let { scanLauncher.launch(it) }
            },
            onCaptureScreenshot = {
                scope.launch {
                    val attachment = captureHermesAppScreenshot(context, settings.maxAttachmentMb)
                    if (attachment != null) { state.pendingAttachments.add(attachment); state.messages.add(ChatMessage("Screenshot", "Screenshot reale dell'app acquisito e allegato automaticamente.", false, isAction = true)) }
                    else state.messages.add(ChatMessage("Screenshot", "Cattura screenshot non riuscita.", false, isAction = true))
                }
            },
            onRemoveAttachment = { state.pendingAttachments.remove(it) },
            onAction = { title, text, prompt ->
                state.messages.add(ChatMessage(title, text, fromUser = false, isAction = true))
                if (prompt.isNotBlank()) {
                    state.draft = if (state.draft.isBlank()) prompt else "${state.draft.trimEnd()}\n\n$prompt"
                }
            },
            onModeChange = { state.mode = it },
            quickPrompt = quickPrompt,
            onQuickPromptConsumed = { quickPrompt = null },
            onSend = {
                var text = state.draft.trim()
                if (archivedBotWithoutContext) {
                    state.messages.add(
                        ChatMessage(
                            "Hermes Hub",
                            "Chat bot archiviata: riaprila da Bot Hermes",
                            fromUser = false,
                            isAction = true
                        )
                    )
                    return@Composer
                }
                if ((text.isNotEmpty() || state.pendingAttachments.isNotEmpty()) && !state.sending && state.activeStreamJob == null) {
                    // No fallback prompt required when only sending attachments
                    val attachments = state.pendingAttachments.toList()
                    state.pendingAttachments.clear()
                    val displayText = if (attachments.isEmpty()) {
                        text
                    } else {
                        text.ifBlank { "Media condiviso." }
                    }
                    val localHistory = state.messages.toMutableList()
                    localHistory.add(ChatMessage("Tu", displayText, true))

                    state.messages.add(ChatMessage("Tu", displayText, true, visualBlocks = createLocalAttachmentBlocks(attachments)))
                    state.draft = ""
                    val streamCid = state.activeConversationId
                        ?: "conv_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
                    state.activeConversationId = streamCid
                    // Un invio sopra uno stream esistente deve prima cancellarlo,
                    // altrimenti il vecchio collector resta orfano e la sua finally
                    // ripulisce lo stato del nuovo stream. Bug del blocco post-stop.
                    state.activeStreams[streamCid]?.job?.cancel()
                    state.activeStreams[streamCid] = ActiveStreamState(StreamingState(), null)

                    val job = HermesStreamRuntime.scope.launch {
                        val collectorJob = coroutineContext[kotlinx.coroutines.Job]
                        var localState = StreamingState()
                        val mode = state.mode
                        val convId = streamCid
                        val prevId = state.previousResponseId
                        var interrupted = false
                        var lastCheckpointAt = 0L
                        val rawEvents = mutableListOf<HermesRawEvent>()
                        val initialConversation = withContext(NonCancellable + Dispatchers.IO) {
                            saveConversationSnapshot(
                                context = context,
                                conversationId = convId,
                                mode = mode,
                                prompt = displayText,
                                messages = localHistory.toList(),
                                source = "Hermes in corso",
                                responseId = prevId,
                                projectId = settings.activeProjectId,
                                syncAfterSave = false
                            )
                        }
                        val shouldGenerateTitle = initialConversation.title == UNTITLED_CHAT_TITLE
                        val persistedStreamCid = initialConversation.id
                        if (persistedStreamCid != streamCid) {
                            state.activeStreams.remove(streamCid)?.let { state.activeStreams[persistedStreamCid] = it }
                            if (state.activeConversationId == streamCid) state.activeConversationId = persistedStreamCid
                        }
                        val activeStreamCid = persistedStreamCid
                        val initialActiveState = state.activeStreams[streamCid] ?: ActiveStreamState(null, null)
                        state.activeStreams[activeStreamCid] = initialActiveState.copy(streamingState = localState, job = coroutineContext[kotlinx.coroutines.Job])

                        // Percorso primario: Sessions API quando capability presente, altriment legacy.
                        // Nessun fallback invisibile su 401/403 profile scope.
                        val capsSnapshot = state.chatCapabilities
                        val useSessions = capsSnapshot?.supportsModernSessions() == true && botSessionId.isNullOrBlank()
                        var sessionIdForTurn: String? = null
                        if (useSessions) {
                            state.sessionRoute = "sessions"
                            sessionIdForTurn = try {
                                withContext(Dispatchers.IO) {
                                    ensureHermesChatSession(
                                        context, botSettings, botApiKey, activeStreamCid,
                                        botProfile, botMultiplexEnabled, capsSnapshot, displayText.take(80)
                                    )
                                }
                            } catch (se: SecurityException) {
                                localState = localState.applyEvent(
                                    ChatStreamEvent.Error(se.message ?: "Hermes ha rifiutato la chiave per il profilo.")
                                )
                                if (state.activeConversationId == activeStreamCid) state.streamingState = localState
                                null
                            }
                            if (sessionIdForTurn != null) {
                                state.hermesSessionId = sessionIdForTurn
                                withContext(NonCancellable + Dispatchers.IO) {
                                    saveSessionBinding(context, activeStreamCid, botProfile, sessionIdForTurn)
                                }
                            }
                        } else {
                            state.sessionRoute = "legacy"
                        }
                        val effModel = state.chatModelOverride.ifBlank { botSettings.model }
                        val effProvider = state.chatProviderOverride.ifBlank { botSettings.provider }
                        val effReasoning = state.chatReasoningEffort.ifBlank { botSettings.reasoningEffort }

                        suspend fun collectFlow(flow: kotlinx.coroutines.flow.Flow<ChatStreamEvent>) {
                            flow.collect { event ->
                                if (event is ChatStreamEvent.ApprovalResolved) {
                                    // Risoluzione già applicata allo stato via applyEvent; notifica leggera.
                                }
                                if (event is ChatStreamEvent.RawHermesEvent) {
                                    rawEvents += safeRawHermesEvent()
                                    if (rawEvents.size > 200) {
                                        rawEvents.subList(0, rawEvents.size - 200).clear()
                                    }
                                    if (!SHOW_RAW_HERMES_EVENTS_IN_CHAT) {
                                        // Applica comunque metadata/run tracking senza mostrare raw.
                                        localState = localState.applyEvent(event)
                                        if (state.activeConversationId == activeStreamCid) {
                                            state.streamingState = localState
                                        }
                                        return@collect
                                    }
                                }
                                localState = localState.applyEvent(event)
                                if (state.activeConversationId == activeStreamCid) {
                                    state.streamingState = localState
                                } else {
                                    val existing = state.activeStreams[activeStreamCid]
                                    if (existing != null) {
                                        state.activeStreams[activeStreamCid] = existing.copy(streamingState = localState)
                                    }
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastCheckpointAt >= STREAMING_CHECKPOINT_INTERVAL_MS &&
                                    (localState.activityTimeline.isNotEmpty() || localState.text.isNotBlank() || localState.visualBlocks.isNotEmpty())) {
                                    lastCheckpointAt = now
                                    withContext(Dispatchers.IO) {
                                        saveConversationSnapshot(
                                            context = context,
                                            conversationId = activeStreamCid,
                                            mode = mode,
                                            prompt = displayText,
                                            messages = localHistory.toList() + ChatMessage(
                                                "Hermes",
                                                localState.text.streamingCheckpointPreview().ifBlank { "Hermes sta lavorando..." },
                                                fromUser = false,
                                                thinking = localState.thinking,
                                                activityTimeline = localState.activityTimeline,
                                                visualBlocksVersion = localState.visualBlocksVersion,
                                                visualBlocks = localState.visualBlocks,
                                                stats = localState.stats,
                                                rawEvents = rawEvents.toList()
                                            ),
                                            source = if (sessionIdForTurn != null) "Sessione Hermes" else "Hermes in corso",
                                            responseId = localState.responseId ?: prevId,
                                            hermesSessionId = sessionIdForTurn ?: state.hermesSessionId,
                                            modelOverride = state.chatModelOverride,
                                            providerOverride = state.chatProviderOverride,
                                            reasoningEffort = state.chatReasoningEffort,
                                            syncAfterSave = false
                                        )
                                    }
                                }
                            }
                        }

                        try {
                            if (sessionIdForTurn != null) {
                                val sessionSettings = botSettings.copy(
                                    model = effModel,
                                    provider = effProvider,
                                    reasoningEffort = effReasoning
                                )
                                collectFlow(
                                    streamHermesSessionChat(
                                        sessionSettings,
                                        sessionIdForTurn,
                                        text,
                                        botApiKey,
                                        botProfile,
                                        botMultiplexEnabled,
                                        model = effModel,
                                        provider = effProvider.takeIf { it.isNotBlank() && !it.equals("hermes-agent", true) },
                                        modelOptions = buildHermesModelOptions(effReasoning, botSettings.serviceTier, capsSnapshot),
                                        sessionKey = botSettings.hermesSessionKey.takeIf { isValidHermesSessionKey(it) },
                                        allowCompatAuth = botAllowCompatAuth
                                    )
                                )
                            } else if (useSessions && localState.error == null) {
                                // Sessions dichiarate ma creazione fallita: errore esplicito, un solo fallback legacy
                                // solo se non è un problema auth/profile.
                                localState = localState.applyEvent(
                                    ChatStreamEvent.Status("Sessions non disponibili, fallback legacy esplicito...")
                                )
                                if (state.activeConversationId == activeStreamCid) state.streamingState = localState
                                state.sessionRoute = "legacy-fallback"
                                collectFlow(
                                    streamChatRequest(
                                        botSettings.copy(model = effModel, provider = effProvider, reasoningEffort = effReasoning),
                                        mode,
                                        text,
                                        localHistory.takeLast(CHAT_HISTORY_MAX_MESSAGES).toList(),
                                        activeStreamCid,
                                        prevId,
                                        attachments,
                                        botApiKey,
                                        botProfile,
                                        botSessionId,
                                        botMultiplexEnabled,
                                        botAllowCompatAuth
                                    )
                                )
                            } else if (localState.error == null) {
                                collectFlow(
                                    streamChatRequest(
                                        botSettings.copy(model = effModel, provider = effProvider, reasoningEffort = effReasoning),
                                        mode,
                                        text,
                                        localHistory.takeLast(CHAT_HISTORY_MAX_MESSAGES).toList(),
                                        activeStreamCid,
                                        prevId,
                                        attachments,
                                        botApiKey,
                                        botProfile,
                                        botSessionId,
                                        botMultiplexEnabled,
                                        botAllowCompatAuth
                                    )
                                )
                            }
                        } catch (_: CancellationException) {
                            interrupted = true
                        } catch (ex: Exception) {
                            val message = ex.message?.takeIf { it.isNotBlank() } ?: ex.javaClass.simpleName
                            localState = localState.applyEvent(ChatStreamEvent.Error("Errore runtime Hermes: $message"))
                            if (state.activeConversationId == activeStreamCid) {
                                state.streamingState = localState
                            } else {
                                state.activeStreams[activeStreamCid]?.let {
                                    state.activeStreams[activeStreamCid] = it.copy(streamingState = localState)
                                }
                            }
                        } finally {
                            val finalState = localState
                            val partialText = finalState.text.trimEnd()
                            val transportDetached = finalState.error?.contains("connection abort", ignoreCase = true) == true ||
                                finalState.error?.contains("software caused connection abort", ignoreCase = true) == true
                            val finalText = when {
                                interrupted && partialText.isNotEmpty() -> "$partialText\n\n_Interrotto._"
                                interrupted -> "Generazione interrotta."
                                transportDetached && partialText.isNotEmpty() -> "$partialText\n\n_Stream scollegato: Hermes potrebbe continuare il lavoro sul gateway._"
                                transportDetached -> ""
                                else -> finalState.text.ifEmpty { finalState.error ?: "" }
                            }

                            val newMessagesToAppend = mutableListOf<ChatMessage>()

                            if (finalState.activityTimeline.isNotEmpty() || finalText.isNotEmpty() || finalState.visualBlocks.isNotEmpty()) {
                                newMessagesToAppend.add(
                                    ChatMessage(
                                        if (interrupted && partialText.isEmpty()) "Stato" else "Hermes",
                                        finalText,
                                        fromUser = false,
                                        isAction = interrupted && partialText.isEmpty(),
                                        thinking = finalState.thinking,
                                        activityTimeline = finalState.activityTimeline,
                                        visualBlocksVersion = finalState.visualBlocksVersion,
                                        visualBlocks = finalState.visualBlocks,
                                        stats = finalState.stats,
                                        rawEvents = rawEvents.toList()
                                    )
                                )
                            }
                            if (finalState.error != null && finalText.isEmpty()) {
                                newMessagesToAppend.add(ChatMessage("Stato", finalState.error, fromUser = false, isAction = true))
                            }
                            val workspaceKind = if (!interrupted) detectWorkspaceIntent(text) else null
                            if (workspaceKind != null) {
                                val workspaceResult = sendWorkspaceRunRequest(settings, workspaceKind, text, loadGatewaySecret(context))
                                withContext(Dispatchers.IO) {
                                    saveWorkspaceRequest(
                                        context = context,
                                        kind = workspaceKind,
                                        prompt = displayText,
                                        result = workspaceResult.result.ifBlank { finalText },
                                        source = workspaceResult.source,
                                        status = workspaceResult.status,
                                        remoteId = workspaceResult.remoteId,
                                        title = workspaceResult.title.ifBlank { makeTitle(text) },
                                        streamUrl = workspaceResult.streamUrl,
                                        downloadUrl = workspaceResult.downloadUrl
                                    )
                                }
                                newMessagesToAppend.add(
                                    ChatMessage(
                                        "Hermes Hub",
                                        "${workspaceKind}: aggiunto alla sezione dedicata. ${workspaceResult.status}",
                                        fromUser = false,
                                        isAction = true
                                    )
                                )
                            }

                            localHistory.addAll(newMessagesToAppend)
                            if (state.activeConversationId == activeStreamCid) {
                                state.messages.addAll(newMessagesToAppend)
                            }

                            val saved = withContext(NonCancellable + Dispatchers.IO) {
                                saveConversationSnapshot(
                                    context = context,
                                    conversationId = activeStreamCid,
                                    mode = mode,
                                    prompt = displayText,
                                    messages = localHistory.toList(),
                                    source = if (interrupted) "Hermes interrotto" else if (finalState.error != null) "Errore Hermes" else if (state.sessionRoute == "sessions") "Sessione Hermes" else "Hermes",
                                    responseId = finalState.responseId ?: prevId,
                                    hermesSessionId = state.hermesSessionId,
                                    modelOverride = state.chatModelOverride,
                                    providerOverride = state.chatProviderOverride,
                                    reasoningEffort = state.chatReasoningEffort,
                                    // Su stop non spingere subito sul gateway: la rete in finally
                                    // allungherebbe lo sblocco del composer; ci pensa l'autosync.
                                    syncAfterSave = !interrupted
                                )
                            }
                            if (state.activeConversationId == activeStreamCid) {
                                // Ripulisci solo se nessun invio successivo ha preso il posto di
                                // questo stream: altrimenti cancelleresti lo stato del nuovo turno.
                                val current = state.activeStreams[activeStreamCid]
                                if (current == null || current.job == null || current.job === collectorJob) {
                                    state.activeConversationId = saved.id
                                    state.previousResponseId = saved.previousResponseId
                                    state.streamingState = null
                                    state.activeStreamJob = null
                                }
                            } else {
                                state.activeStreams.remove(activeStreamCid)
                            }
                            if (shouldGenerateTitle && !interrupted && finalState.error == null && finalText.isNotBlank()) {
                                val generatedTitle = generateConversationTitle(
                                    settings = settings,
                                    firstPrompt = displayText,
                                    firstAnswer = finalText,
                                    apiKey = loadGatewaySecret(context)
                                )
                                withContext(NonCancellable + Dispatchers.IO) {
                                    // Propaga rename alla sessione server quando disponibile.
                                    // Se il server rifiuta/fallisce, il titolo resta da generare
                                    // al prossimo turno: niente falso successo, niente divergenza.
                                    runCatching {
                                        renameHermesSessionForConversation(
                                            context, botSettings, botApiKey, saved.id,
                                            generatedTitle, botProfile, botMultiplexEnabled
                                        )
                                    }.getOrNull()
                                }
                            }
                        }
                    }
                    state.activeStreams[streamCid] = (state.activeStreams[streamCid] ?: ActiveStreamState(StreamingState(), null)).copy(job = job)
                }
            },
            onStop = {
                val activeRunId = state.streamingState?.activeRunId
                // Aggiornamento UI immediato + vero POST /v1/runs/{id}/stop (non solo cancel locale).
                state.streamingState = state.streamingState?.copy(
                    status = "Interruzione richiesta. Chiudo stream Hermes...",
                    error = null
                )
                if (!activeRunId.isNullOrBlank()) {
                    HermesStreamRuntime.scope.launch {
                        val (code, body) = runCatching {
                            stopHermesRun(botSettings, activeRunId, botApiKey, botProfile, botMultiplexEnabled, botAllowCompatAuth)
                        }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) }
                        if (code !in 200..299) {
                            val msg = when (code) {
                                401, 403 -> "Stop rifiutato (HTTP $code): chiave non valida per il profilo."
                                404 -> "Run non trovato sul server (404)."
                                else -> "Stop HTTP $code: ${body.take(160)}"
                            }
                            state.messages.add(ChatMessage("Hermes Hub", msg, fromUser = false, isAction = true))
                        }
                    }
                }
                state.activeStreamJob?.cancel()
            },
            isBusy = state.sending && state.streamingState?.status?.contains("Interruzione") != true,
            isRecordingVoiceNote = state.isRecordingVoiceNote,
            onToggleVoiceNote = {
                if (!state.isRecordingVoiceNote) {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        return@Composer
                    }
                    startVoiceRecording()
                } else {
                    val recorder = mediaRecorder
                    mediaRecorder = null
                    state.isRecordingVoiceNote = false
                    val file = state.tempVoiceNoteFile
                    try {
                        recorder?.stop()
                        if (file != null && file.exists()) {
                            scope.launch {
                                try {
                                    val secret = loadGatewaySecret(context) ?: ""
                                    val baseUrl = settings.gatewayUrl.trimEnd('/')

                                    val requestBody = okhttp3.MultipartBody.Builder()
                                        .setType(okhttp3.MultipartBody.FORM)
                                        .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaTypeOrNull()))
                                        .build()

                                    val requestBuilder = okhttp3.Request.Builder()
                                        .url("$baseUrl/audio/transcriptions")
                                        .post(requestBody)

                                    HermesHubProtocol.addCorrelationHeaders(
                                        requestBuilder,
                                        HermesHubProtocol.newCorrelationContext()
                                    )

                                    if (secret.isNotEmpty()) {
                                        requestBuilder.header("Authorization", "Bearer $secret")
                                    }

                                    withContext(Dispatchers.IO) { voiceNoteHttpClient.newCall(requestBuilder.build()).execute() }.use { response ->
                                        if (response.isSuccessful) {
                                            val responseStr = response.body.byteStream().readUtf8Bounded()
                                            val json = org.json.JSONObject(responseStr)
                                            val text = json.optString("text", "")
                                            if (text.isNotEmpty()) {
                                                if (state.draft.isNotEmpty() && !state.draft.endsWith(" ")) {
                                                    state.draft += " "
                                                }
                                                state.draft += text
                                            }
                                        } else {
                                            state.messages.add(ChatMessage("Errore Voce", "Trascrizione fallita: ${response.code}", fromUser = false, isAction = true))
                                        }
                                    }
                                } catch (_: Exception) {
                                    state.messages.add(ChatMessage("Errore Voce", "Invio audio fallito.", fromUser = false, isAction = true))
                                } finally {
                                    file.delete()
                                    state.tempVoiceNoteFile = null
                                }
                            }
                        }
                    } catch (e: Exception) {
                        file?.let { runCatching { it.delete() } }
                        state.tempVoiceNoteFile = null
                        state.messages.add(ChatMessage("Errore Voce", "Errore arresto registrazione.", fromUser = false, isAction = true))
                    } finally {
                        runCatching { recorder?.release() }
                    }
                }
            }
        )
    }
}

internal fun executeSlashCommand(
    command: SlashCommand,
    setMode: (String) -> Unit,
    clear: () -> Unit,
    setDraft: (String) -> Unit,
    addAction: (String, String) -> Unit,
    onSwitchTab: (Tab) -> Unit
) {
    when (command.action) {
        SlashAction.ModeChat -> {
            setMode("Chat"); addAction("Modalita", "Chat attiva.")
        }
        SlashAction.ModeAgent -> {
            setMode("Agente"); addAction("Modalita", "Agente attivo.")
        }
        SlashAction.Clear -> clear()
        SlashAction.Help -> {
            val lines = slashCommands().distinctBy { it.display }.joinToString("\n") { "${it.display} — ${it.title}" }
            addAction("Comandi", lines)
        }
        SlashAction.PromptSetup -> setDraft("Preparami i passaggi per avviare Hermes Agent API Server su Tailscale/LAN.")
        SlashAction.PromptVisual -> setDraft("Spiega con blocchi visuali (tabella, diagramma, chart o callout) mantenendo output_text completo.")
        SlashAction.PromptResearch -> setDraft("Esegui una ricerca approfondita citando fonti e chiedendo conferma prima di uscire dalla LAN/VPN.")
        SlashAction.PromptWeb -> setDraft("Cerca sul web informazioni aggiornate, chiedendo conferma prima di uscire dalla LAN/VPN.")
        SlashAction.PromptImage -> setDraft("Prepara una richiesta di generazione immagine, chiedendo conferma prima di usare tool esterni.")
        SlashAction.PromptVideo -> setDraft("Crea un job video per la sezione Video di Hermes Hub: ")
        SlashAction.PromptNews -> setDraft("Crea un articolo per la sezione News di Hermes Hub: ")
        SlashAction.Health -> setDraft("Controlla stato Hermes, modello disponibile e capabilities API.")
        SlashAction.OpenServer -> onSwitchTab(Tab.Server)
        SlashAction.OpenHardware -> onSwitchTab(Tab.Hardware)
        SlashAction.OpenCron -> onSwitchTab(Tab.Cron)
        SlashAction.OpenArchive -> onSwitchTab(Tab.Archive)
        SlashAction.OpenVideo -> onSwitchTab(Tab.Video)
        SlashAction.OpenNews -> onSwitchTab(Tab.News)
        SlashAction.OpenSettings -> onSwitchTab(Tab.Settings)
        SlashAction.OpenAbout -> onSwitchTab(Tab.Profile)
    }
}

@Composable
internal fun TopBar(
    contextUsage: ContextUsage,
    connected: Boolean,
    gatewayRuntime: GatewayRuntimeStatus?,
    onNewChat: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
    onOpenArchive: () -> Unit = {}
) {
    Surface(color = AppColors.Background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(68.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.chatclaw_logo),
                contentDescription = "Apri navigazione",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenSidebar)
            )
            Column(modifier = Modifier.padding(start = 11.dp).weight(1f)) {
                Text("Hermes Hub", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (connected) AppColors.Success else AppColors.Error, CircleShape)
                    )
                    Text(
                        gatewayRuntimeLabel(connected, gatewayRuntime),
                        color = AppColors.Faint,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }
            }
            IconButton(onClick = onOpenArchive, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = "Archivio chat", tint = AppColors.Muted, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onNewChat, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Edit, contentDescription = "Nuova chat", tint = AppColors.Accent, modifier = Modifier.size(20.dp))
            }
            ContextMeter(usage = contextUsage, modifier = Modifier.size(40.dp))
        }
    }
    HorizontalDivider(color = AppColors.Border.copy(alpha = 0.72f))
}

@Composable
internal fun ContextMeter(usage: ContextUsage, modifier: Modifier = Modifier) {
    val fill = (usage.percent.coerceIn(0, 100) / 100f)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 2.2.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawCircle(
                color = AppColors.Elevated,
                radius = size.minDimension / 2f,
                center = center
            )
            if (fill > 0f) {
                drawArc(
                    color = AppColors.Accent.copy(alpha = 0.88f),
                    startAngle = -90f,
                    sweepAngle = 360f * fill,
                    useCenter = true,
                    topLeft = Offset(inset, inset),
                    size = arcSize
                )
            }
            drawCircle(
                color = AppColors.Border,
                radius = size.minDimension / 2f - inset,
                center = center,
                style = Stroke(width = strokeWidth)
            )
            drawCircle(
                color = AppColors.Accent.copy(alpha = 0.62f),
                radius = size.minDimension / 2f - (strokeWidth * 1.8f),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        Text(
            text = if (usage.delegatedToHermes && usage.tokens <= 0) "H" else "${usage.percent.coerceIn(0, 100)}%",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

internal fun estimateChatContextUsage(
    settings: AppSettings,
    messages: List<ChatMessage>,
    draft: String,
    streamingState: StreamingState?
): ContextUsage {
    val authoritativeStats = streamingState?.stats
        ?: messages.asReversed().firstNotNullOfOrNull { it.stats?.takeIf { stats -> stats.contextTokens() > 0 } }
    val contextWindow = authoritativeStats?.contextLength?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
    val explicitPercent = authoritativeStats?.contextPercent?.takeIf { it in 0..100 }
    val serverContextTokens = authoritativeStats?.contextTokens()
        ?: 0
    if (isHermesNative(settings) && serverContextTokens <= 0) {
        return ContextUsage(tokens = 0, percent = 0, delegatedToHermes = true)
    }
    val historyTokens = messages
        .takeLast(CHAT_HISTORY_MAX_MESSAGES)
        .sumOf { estimateTokenCount(it.author) + estimateTokenCount(it.text) + MESSAGE_CONTEXT_OVERHEAD_TOKENS }
    val draftTokens = draft.trim()
        .takeIf { it.isNotBlank() }
        ?.let { estimateTokenCount(it) + MESSAGE_CONTEXT_OVERHEAD_TOKENS }
        ?: 0
    val estimated = if (historyTokens == 0 && draftTokens == 0) {
        0
    } else {
        CONTEXT_SYSTEM_OVERHEAD_TOKENS + historyTokens + draftTokens
    }
    val tokens = if (isHermesNative(settings)) serverContextTokens else maxOf(estimated, serverContextTokens).coerceAtLeast(0)
    val percent = explicitPercent ?: ((tokens.coerceAtMost(contextWindow).toDouble() / contextWindow) * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
    return ContextUsage(tokens = tokens, maxTokens = contextWindow, percent = percent, delegatedToHermes = isHermesNative(settings))
}

internal fun estimateTokenCount(text: String): Int {
    if (text.isBlank()) return 0
    return ((text.length + 3) / 4).coerceAtLeast(1)
}

@Composable
internal fun EmptyState(onPrompt: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Image(
            painter = painterResource(id = R.drawable.chatclaw_logo),
            contentDescription = "Logo Hermes Hub",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(24.dp))
        )
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = "Che vuoi fare oggi?",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 27.sp,
            lineHeight = 32.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Chat live, cron e strumenti Hermes Agent sul tuo home-server.",
            color = AppColors.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(28.dp))
        Text("OPERAZIONI RAPIDE", color = AppColors.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SuggestionButton("Prepara setup Hermes") {
                onPrompt("Preparami i passaggi per avviare Hermes Agent API Server su Tailscale/LAN.")
            }
            SuggestionButton("Controlla server") {
                onPrompt("Controlla stato Hermes, modello disponibile e capabilities API.")
            }
            SuggestionButton("Crea ordine agente") {
                onPrompt("Crea un task agente sicuro con richiesta approve/deny prima di ogni azione rischiosa.")
            }
        }
    }
}

internal fun createInitialTasks(@Suppress("UNUSED_PARAMETER") settings: AppSettings): List<AgentTask> {
    return emptyList()
}

@Composable
internal fun SuggestionButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = AppColors.Panel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(7.dp).background(AppColors.Accent, CircleShape))
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Text("›", color = AppColors.Faint, fontSize = 22.sp)
        }
    }
}

@Composable
internal fun PremiumPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(content = content)
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.78f), thickness = 1.dp)
    }
}

@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    colors: Any? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    PremiumPanel(modifier = modifier, content = content)
}

@Composable
internal fun MessageBubble(message: ChatMessage, settings: AppSettings) {
    SelectionContainer {
        if (!message.fromUser && !message.isAction) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(modifier = Modifier.size(7.dp).background(AppColors.Accent, CircleShape))
                    Text("HERMES", color = AppColors.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                }
                MarkdownText(message.text, color = Color.White, fontSize = 15.sp)
                ArchivedActivityDisclosure(message.activityTimeline, message.thinking, settings.showToolCalls)
                if (message.visualBlocks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        message.visualBlocks.filter { it.isValidVisualBlock() }.forEach { block ->
                            VisualBlockView(block)
                        }
                    }
                }
                RawHermesEventsView(message.rawEvents)
                MessageFooter(message.text, message.stats, settings, settings.showMessageMetrics, settings.metricFilter())
            }
            return@SelectionContainer
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(if (message.fromUser) 0.86f else 0.92f),
                color = if (message.fromUser) AppColors.UserBubble else AppColors.Panel,
                shape = if (message.fromUser) {
                    RoundedCornerShape(topStart = 20.dp, topEnd = 7.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
                } else {
                    RoundedCornerShape(16.dp)
                },
                border = BorderStroke(1.dp, if (message.fromUser) AppColors.Accent.copy(alpha = 0.28f) else AppColors.Border),
                shadowElevation = 0.dp,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = message.author,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (message.fromUser || message.isAction) {
                        Text(text = message.text, color = Color.White)
                    } else {
                        MarkdownText(message.text, color = Color.White)
                        ArchivedActivityDisclosure(message.activityTimeline, message.thinking, settings.showToolCalls)
                    }
                    if (message.visualBlocks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            message.visualBlocks.filter { it.isValidVisualBlock() }.forEach { block ->
                                VisualBlockView(block)
                            }
                        }
                    }
                    RawHermesEventsView(message.rawEvents)
                    MessageFooter(message.text, message.stats, settings, settings.showMessageMetrics, settings.metricFilter())
                }
            }
        }
    }
}

@Composable
internal fun RawHermesEventsView(events: List<HermesRawEvent>) {
    if (events.isEmpty() || !SHOW_RAW_HERMES_EVENTS_IN_CHAT) return
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = AppColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Eventi Hermes raw: ${events.size}", color = AppColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (expanded) {
                events.take(40).forEach { event ->
                    Text("${event.name}: ${event.json.take(800)}", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
internal fun ArchivedActivityDisclosure(
    persisted: List<AssistantActivity>,
    legacyThinking: String,
    showToolCalls: Boolean
) {
    val timeline = remember(persisted, legacyThinking, showToolCalls) {
        val compatible = if (persisted.isEmpty() && legacyThinking.isNotBlank()) {
            listOf(AssistantActivity(AssistantActivity.Kind.Reasoning, text = legacyThinking))
        } else persisted
        compatible.filter { showToolCalls || it.kind != AssistantActivity.Kind.Tool }
    }
    if (timeline.isNotEmpty()) HermesActivityDisclosure(timeline)
}

@Composable
internal fun MessageFooter(text: String, stats: ChatStreamStats?, settings: AppSettings, showMetrics: Boolean, filter: MetricDisplayFilter) {
    val context = LocalContext.current
    val clipboardManager = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val scope = rememberCoroutineScope()
    var speaking by remember { mutableStateOf(false) }
    val line = remember(stats, filter, showMetrics) {
        if (showMetrics) formatChatStatsLine(stats, filter) else ""
    }

    Spacer(modifier = Modifier.height(2.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (line.isNotBlank()) {
            Text(
                text = line,
                color = AppColors.Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        androidx.compose.material3.IconButton(
            onClick = {
                if (text.isBlank()) return@IconButton
                scope.launch {
                    speaking = true
                    runCatching { speakChatMessage(context, settings, text, loadGatewaySecret(context)) }
                        .onFailure { Toast.makeText(context, "TTS Kokoro non disponibile: ${it.message}", Toast.LENGTH_SHORT).show() }
                    speaking = false
                }
            },
            modifier = Modifier.size(24.dp),
            enabled = text.isNotBlank() && !speaking
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayCircle,
                contentDescription = "Leggi messaggio",
                tint = AppColors.Muted,
                modifier = Modifier.size(16.dp)
            )
        }

        androidx.compose.material3.IconButton(
            onClick = { clipboardManager.setPrimaryClip(ClipData.newPlainText("Hermes", text)) },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Rounded.ContentCopy,
                contentDescription = "Copia messaggio",
                tint = AppColors.Muted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

internal fun formatChatStatsLine(stats: ChatStreamStats?, filter: MetricDisplayFilter): String {
    if (stats == null) return ""
    val parts = mutableListOf<String>()
    stats.ttftMs?.takeIf { filter.ttft && it > 0 }?.let {
        parts += "TTFT ${String.format(java.util.Locale.US, "%.1f", it / 1000.0)}s"
    }
    stats.tokensPerSecond?.takeIf { filter.tokensPerSecond && it > 0 }?.let {
        parts += "${String.format(java.util.Locale.US, "%.2f", it)} t/s"
    }
    stats.acceptanceRate?.takeIf { filter.acceptanceRate && it.isFinite() }?.let {
        parts += formatAcceptancePart(it, stats.acceptanceLabel)
    }
    stats.tokensOut?.takeIf { filter.outputTokens && it > 0 }?.let { parts += "$it tok" }
    stats.promptTokens?.takeIf { filter.promptTokens && it > 0 }?.let { parts += "prompt $it" }
    stats.contextTokens().takeIf { filter.contextTokens && it > 0 }?.let { parts += "ctx $it" }
    stats.contextLength?.takeIf { filter.contextTokens && it > 0 }?.let { parts += "max $it" }
    stats.totalMs?.takeIf { filter.duration && it > 0 }?.let {
        parts += "${String.format(java.util.Locale.US, "%.1f", it / 1000.0)}s"
    }
    return parts.joinToString("  ·  ")
}

@Composable
internal fun VisualBlockView(block: VisualBlock) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppColors.Surface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (block.title.isNotBlank()) {
                Text(block.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            when (block.type.lowercase()) {
                "markdown" -> MarkdownBlock(block.text)
                "code" -> CodeBlock(block.language, block.code, block.filename)
                "table" -> TableBlock(block)
                "chart" -> ChartBlock(block)
                "diagram" -> DiagramBlock(block)
                "image_gallery" -> GalleryBlock(block)
                "media_file" -> MediaFileBlock(block)
                "callout" -> CalloutBlock(block)
                "metric" -> MetricBlock(block)
                "progress" -> ProgressBlock(block)
                "approval" -> ApprovalBlock(block)
                "device" -> DeviceBlock(block)
                "unknown_block" -> CodeBlock("json", block.rawJson.ifBlank { "{}" }, "hermes-unknown-block.json")
            }
            if (block.caption.isNotBlank()) {
                Text(block.caption, color = AppColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun MetricBlock(block: VisualBlock) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${block.value}${block.unit}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        if (block.status.isNotBlank()) Text(block.status, color = AppColors.Muted, fontSize = 12.sp)
        if (block.summary.isNotBlank()) Text(block.summary, color = AppColors.Muted, fontSize = 13.sp)
    }
}

@Composable
internal fun ProgressBlock(block: VisualBlock) {
    val progress = (block.progressValue ?: 0.0).toFloat().coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = AppColors.Accent,
            trackColor = AppColors.Border
        )
        Text(
            text = "${progress * 100f}${block.unit.ifBlank { "%" }}${if (block.status.isBlank()) "" else " · ${block.status}"}",
            color = AppColors.Muted,
            fontSize = 12.sp
        )
        if (block.summary.isNotBlank()) Text(block.summary, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
internal fun ApprovalBlock(block: VisualBlock) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${block.status}: ${block.action}", color = Color.White, fontWeight = FontWeight.SemiBold)
        if (block.text.isNotBlank()) Text(block.text, color = AppColors.Muted, fontSize = 13.sp)
    }
}

@Composable
internal fun DeviceBlock(block: VisualBlock) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(block.deviceName, color = Color.White, fontWeight = FontWeight.SemiBold)
        val kindStatus = listOf(block.deviceKind, block.deviceStatus).filter { it.isNotBlank() }.joinToString(" · ")
        val battery = block.batteryPercent?.let { " · Batteria ${String.format(java.util.Locale.ITALY, "%.0f", it)}%" }.orEmpty()
        Text(kindStatus + battery, color = AppColors.Muted, fontSize = 12.sp)
        if (block.summary.isNotBlank()) Text(block.summary, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
internal fun MarkdownBlock(markdown: String) {
    MarkdownText(markdown, color = Color.White, fontSize = 14.sp)
}

@Composable
internal fun CodeBlock(language: String, code: String, filename: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (filename.isBlank()) language else "$filename · $language",
            color = AppColors.Muted,
            fontSize = 12.sp
        )
        Surface(color = AppColors.Composer, shape = RoundedCornerShape(10.dp)) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                text = code,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
internal fun TableBlock(block: VisualBlock) {
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            block.columns.forEach { column ->
                TableCell(column.label, header = true)
            }
        }
        block.rows.take(100).forEach { row ->
            Row {
                block.columns.forEach { column ->
                    TableCell(row[column.key].orEmpty(), header = false)
                }
            }
        }
    }
}

@Composable
internal fun TableCell(text: String, header: Boolean) {
    Surface(
        color = if (header) AppColors.Elevated else AppColors.Composer,
        modifier = Modifier
            .widthIn(min = 96.dp, max = 180.dp)
            .border(0.5.dp, AppColors.Border)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ChartBlock(block: VisualBlock) {
    val points = block.series.firstOrNull()?.points.orEmpty().take(12)
    val max = points.maxOfOrNull { it.y }?.takeIf { it > 0.0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(block.summary, color = AppColors.Muted, fontSize = 13.sp)
        points.forEach { point ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(point.x, color = Color.White, fontSize = 12.sp, modifier = Modifier.widthIn(min = 82.dp, max = 92.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(modifier = Modifier.weight(1f).height(14.dp)) {
                    val barWidth = (size.width * (point.y / max)).toFloat().coerceAtLeast(6f)
                    drawRoundRect(color = AppColors.Accent, size = Size(barWidth, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f))
                    if (block.chartType == "line") {
                        drawLine(color = AppColors.Accent, start = Offset(0f, size.height / 2), end = Offset(barWidth, size.height / 2), strokeWidth = 4f)
                    }
                }
                Text("${point.y.toInt()}${block.unit}", color = AppColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun DiagramBlock(block: VisualBlock) {
    CodeBlock("mermaid", block.source, "diagram.mmd")
}

@Composable
internal fun GalleryBlock(block: VisualBlock) {
    val context = LocalContext.current
    val settings = remember { loadSettings(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.images.take(12).forEach { image ->
            RemoteGalleryImage(settings, image)
            if (image.caption.isNotBlank()) {
                Text(image.caption, color = AppColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

/**
 * URL MP4 compatibile per i video proxy Hermes (stessa regola del feed Video):
 * aggiunge ?format=mp4 quando manca.
 */
internal fun chatVideoCompatUrl(settings: AppSettings, url: String): String {
    if (!url.contains("/v1/media/", ignoreCase = true) || url.contains("format=mp4", ignoreCase = true)) return url
    val separator = if (url.contains("?")) "&" else "?"
    return "$url${separator}format=mp4"
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ChatInlineVideoPlayer(
    settings: AppSettings,
    mediaUrl: String,
    durationLabel: String,
    apiKey: String?
) {
    val context = LocalContext.current
    val compatUrl = remember(settings.gatewayUrl, mediaUrl) { chatVideoCompatUrl(settings, mediaUrl) }
    var started by remember(mediaUrl) { mutableStateOf(false) }
    var fullScreen by remember(mediaUrl) { mutableStateOf(false) }
    var useCompat by remember(mediaUrl) { mutableStateOf(false) }
    var status by remember(mediaUrl) { mutableStateOf<String?>(null) }
    val activeUrl = if (useCompat && compatUrl.isNotBlank()) compatUrl else mediaUrl
    val poster by produceState<Bitmap?>(initialValue = null, mediaUrl) {
        value = withContext(Dispatchers.IO) { loadVideoThumbnail(settings, mediaUrl, apiKey) }
    }

    if (!started) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .clickable { started = true },
            contentAlignment = Alignment.Center
        ) {
            val bitmap = poster
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Riproduci video", tint = Color.White, modifier = Modifier.size(34.dp))
            }
            if (durationLabel.isNotBlank()) {
                Text(
                    durationLabel,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
        return
    }

    val player = remember(activeUrl, apiKey) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(if (shouldAuthenticateHermesUrl(settings, activeUrl)) authHeaders(apiKey) else emptyMap())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(activeUrl.toUri()))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!useCompat && compatUrl.isNotBlank() && compatUrl != activeUrl) {
                    useCompat = true
                    status = "Passo al proxy MP4 compatibile Hermes."
                } else {
                    status = "Player video: ${error.errorCodeName}."
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val activity = remember(context) { context.findActivity() }
    FullscreenVideoOrientationEffect(enabled = fullScreen, activity = activity)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (!fullScreen) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext -> createVideoPlayerView(viewContext, player) },
                    update = { view -> view.player = player }
                )
            } else {
                val bitmap = poster
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            IconButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.62f), CircleShape),
                onClick = { fullScreen = true }
            ) {
                Icon(Icons.Rounded.CropFree, contentDescription = "Schermo intero", tint = Color.White)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { fullScreen = false; started = false }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Chiudi player", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            if (status != null) {
                Text(status.orEmpty(), color = AppColors.Muted, fontSize = 12.sp)
            }
        }
    }
    if (fullScreen) {
        Dialog(
            onDismissRequest = { fullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext -> createVideoPlayerView(viewContext, player) },
                    update = { view -> view.player = player }
                )
            }
        }
    }
}

@Composable
internal fun ChatInlineImage(
    settings: AppSettings,
    block: VisualBlock,
    mediaUrl: String,
    apiKey: String?,
    onDownload: (String, String) -> Unit,
    isDownloading: Boolean
) {
    var viewer by remember(block.mediaUrl) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(initialValue = null, mediaUrl) {
        value = withContext(Dispatchers.IO) { loadRemoteBitmap(settings, mediaUrl, apiKey) }
    }
    val loaded = bitmap
    if (loaded == null) {
        Text(
            "${block.alt.ifBlank { block.filename.ifBlank { "Immagine" } }}: caricamento immagine...",
            color = AppColors.Muted,
            fontSize = 13.sp
        )
        return
    }
    Image(
        bitmap = loaded.asImageBitmap(),
        contentDescription = block.alt.ifBlank { block.filename },
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { viewer = true }
    )
    if (viewer) {
        Dialog(onDismissRequest = { viewer = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
            ) {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = block.alt.ifBlank { block.filename },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewer = false },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.62f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            if (!isDownloading) {
                                onDownload(mediaUrl, block.filename.ifBlank { block.title.ifBlank { "hermes-file" } })
                            }
                        },
                        enabled = !isDownloading,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.62f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = "Scarica immagine", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ChatInlineAudioPlayer(
    settings: AppSettings,
    mediaUrl: String,
    filename: String,
    apiKey: String?
) {
    val context = LocalContext.current
    var isPlaying by remember(mediaUrl) { mutableStateOf(false) }
    var durationMs by remember(mediaUrl) { mutableStateOf(0L) }
    var positionMs by remember(mediaUrl) { mutableStateOf(0L) }
    var status by remember(mediaUrl) { mutableStateOf<String?>(null) }
    val player = remember(mediaUrl, apiKey) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(if (shouldAuthenticateHermesUrl(settings, mediaUrl)) authHeaders(apiKey) else emptyMap())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(mediaUrl.toUri()))
                prepare()
                playWhenReady = false
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                status = "Audio: ${error.errorCodeName}."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            delay(500L)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Composer)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { player.playWhenReady = !player.playWhenReady },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pausa" else "Riproduci",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                formatMediaDuration(positionMs),
                color = AppColors.Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Slider(
                value = positionMs.toFloat(),
                onValueChange = { player.seekTo(it.toLong()); positionMs = it.toLong() },
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f)
            )
            Text(
                formatMediaDuration(durationMs),
                color = AppColors.Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (filename.isNotBlank()) {
            Text(filename, color = AppColors.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status != null) {
            Text(status.orEmpty(), color = AppColors.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun DocumentSlimRow(
    settings: AppSettings,
    block: VisualBlock,
    mediaUrl: String
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Composer)
            .clickable {
                val viewUrl = withHermesMediaQueryToken(settings, mediaUrl, loadGatewaySecret(context))
                val intent = Intent(Intent.ACTION_VIEW, viewUrl.toUri())
                if (block.mimeType.isNotBlank()) {
                    intent.setDataAndType(viewUrl.toUri(), block.mimeType)
                }
                openAndroidIntent(context, intent)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Rounded.Description, contentDescription = null, tint = AppColors.Muted, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                block.filename.ifBlank { block.title.ifBlank { "Documento" } },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOf(block.mediaKind.ifBlank { "file" }, block.mimeType, formatMediaBytes(block.sizeBytes))
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = AppColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun MediaFileBlock(block: VisualBlock) {
    val context = LocalContext.current
    val settings = remember { loadSettings(context) }
    val allowExternalImage = block.mediaKind == "image"
    val isLocalAttachment = block.localDataUrl.isNotBlank()
    val resolvedMediaUrl = remember(settings.gatewayUrl, block.mediaUrl, allowExternalImage) { resolveMediaUrl(settings, block.mediaUrl, allowExternalImage, allowExternalMedia = true) }
    val previewUrl = remember(settings.gatewayUrl, block.thumbnailUrl, allowExternalImage) { resolveMediaUrl(settings, block.thumbnailUrl, allowExternalImage) }
    val previewSource = when {
        isLocalAttachment -> ""
        block.mediaKind == "image" && resolvedMediaUrl != null -> block.mediaUrl
        previewUrl != null -> block.thumbnailUrl
        else -> ""
    }
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val scope = rememberCoroutineScope()
    val gatewaySecret = remember { loadGatewaySecret(context) }
    var isDownloading by remember(block.mediaUrl) { mutableStateOf(false) }
    var pendingLegacyDownload by remember(block.id) { mutableStateOf<Pair<String, String>?>(null) }
    val canOpen = resolvedMediaUrl != null
    val downloadNow: (String, String) -> Unit = { url, filename ->
        isDownloading = true
        android.widget.Toast.makeText(context, "Scaricamento: ${sanitizeDownloadFilename(filename)}", android.widget.Toast.LENGTH_SHORT).show()
        scope.launch {
            val message = runCatching {
                downloadHermesMediaFile(context, settings, url, filename, block.mimeType, loadGatewaySecret(context))
            }.getOrElse { "Download fallito: ${it.message ?: "errore sconosciuto"}" }
            isDownloading = false
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingLegacyDownload
        pendingLegacyDownload = null
        if (granted && pending != null) {
            downloadNow(pending.first, pending.second)
        } else if (!granted) {
            android.widget.Toast.makeText(context, "Permesso Download negato.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val requestDownload: (String, String) -> Unit = { url, filename ->
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingLegacyDownload = url to filename
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            downloadNow(url, filename)
        }
    }

    if (block.mediaKind == "image" && canOpen && !isLocalAttachment && resolvedMediaUrl != null) {
        ChatInlineImage(
            settings = settings,
            block = block,
            mediaUrl = resolvedMediaUrl,
            apiKey = gatewaySecret,
            onDownload = requestDownload,
            isDownloading = isDownloading
        )
        return
    }
    if (!isLocalAttachment && canOpen && resolvedMediaUrl != null) {
        when (block.mediaKind) {
            "video" -> {
                ChatInlineVideoPlayer(
                    settings = settings,
                    mediaUrl = resolvedMediaUrl,
                    durationLabel = formatMediaDuration(block.durationMs),
                    apiKey = gatewaySecret
                )
                return
            }
            "audio" -> {
                ChatInlineAudioPlayer(
                    settings = settings,
                    mediaUrl = resolvedMediaUrl,
                    filename = block.filename.ifBlank { block.title },
                    apiKey = gatewaySecret
                )
                return
            }
            else -> {
                DocumentSlimRow(settings = settings, block = block, mediaUrl = resolvedMediaUrl)
                return
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isLocalAttachment && block.mediaKind == "image") {
            decodeAttachmentPreview(block.localDataUrl)?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = block.alt.ifBlank { block.filename },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    contentScale = ContentScale.Fit
                )
            }
        } else if (previewSource.isNotBlank()) {
            RemoteGalleryImage(
                settings,
                VisualGalleryImage(
                    mediaUrl = previewSource,
                    alt = block.alt.ifBlank { block.filename.ifBlank { "Media Hermes" } },
                    caption = ""
                ),
                allowExternalImage = allowExternalImage
            )
        }

        Surface(color = AppColors.Composer, shape = RoundedCornerShape(10.dp)) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = block.filename.ifBlank { block.title.ifBlank { block.alt } },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOf(
                        block.mediaKind.ifBlank { "media" },
                        block.mimeType,
                        formatMediaBytes(block.sizeBytes),
                        formatMediaDuration(block.durationMs)
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    color = AppColors.Muted,
                    fontSize = 12.sp
                )
                if (isLocalAttachment) {
                    Text("Condiviso con Hermes.", color = AppColors.Muted, fontSize = 12.sp)
                } else if (!canOpen) {
                    Text("media non proxy rifiutato.", color = AppColors.Muted, fontSize = 12.sp)
                }
                if (canOpen) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        enabled = canOpen,
                        onClick = {
                            val url = resolvedMediaUrl
                            val viewUrl = withHermesMediaQueryToken(settings, url, loadGatewaySecret(context))
                            val intent = Intent(Intent.ACTION_VIEW, viewUrl.toUri())
                            if (block.mimeType.isNotBlank()) {
                                intent.setDataAndType(viewUrl.toUri(), block.mimeType)
                            }
                            openAndroidIntent(context, intent)
                        }
                    ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Apri allegato", tint = Color.White) }
                    IconButton(
                        enabled = canOpen && !isDownloading,
                        onClick = {
                            val url = resolvedMediaUrl.orEmpty()
                            val filename = block.filename.ifBlank { block.title.ifBlank { "hermes-file" } }
                            if (url.isNotBlank()) requestDownload(url, filename)
                        }
                    ) { Icon(if (isDownloading) Icons.Rounded.Refresh else Icons.Rounded.Download, contentDescription = "Scarica allegato", tint = Color.White) }
                    IconButton(
                        enabled = canOpen,
                        onClick = {
                            val url = resolvedMediaUrl
                            clipboard.setPrimaryClip(ClipData.newPlainText("hermes-media-url", url))
                        }
                    ) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copia link", tint = Color.White) }
                }
            }
        }
    }
}

suspend fun downloadHermesMediaFile(context: Context, settings: AppSettings, url: String, filename: String, mimeType: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    val safeName = sanitizeDownloadFilename(filename.ifBlank {
        runCatching { url.toUri().lastPathSegment.orEmpty() }.getOrDefault("").ifBlank { "hermes-file" }
    })
    var lastError = "nessuna risposta"
    val requestContext = HermesHubProtocol.newCorrelationContext()
    for (candidateUrl in plugAndPlayUrlCandidates(url)) {
        val needsHermesAuth = shouldAuthenticateHermesUrl(settings, candidateUrl)
        val candidates = if (needsHermesAuth) hermesAuthCandidates(apiKey) else listOf<String?>(null)
        for (token in candidates) {
            val urls = if (needsHermesAuth && !token.isNullOrBlank()) {
                listOf(candidateUrl, withHermesMediaQueryToken(settings, candidateUrl, token))
            } else {
                listOf(candidateUrl)
            }
            for ((index, attemptUrl) in urls.distinct().withIndex()) {
                val request = Request.Builder()
                    .url(attemptUrl)
                    .get()
                    .header("Accept", "*/*")
                    .header("User-Agent", "HermesHub-Android")
                    .apply {
                        if (needsHermesAuth) {
                            HermesHubProtocol.addCorrelationHeaders(this, requestContext)
                        }
                        if (!token.isNullOrBlank() && index == 0) {
                            header("Authorization", "Bearer $token")
                        }
                    }
                    .build()
                apiHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        return@use
                    }
                    val body = response.body
                    saveDownloadBytes(
                        context = context,
                        filename = safeName,
                        mimeType = mimeType.ifBlank { body.contentType()?.toString().orEmpty() },
                        input = body.byteStream(),
                        expectedBytes = body.contentLength()
                    )
                    return@withContext "File salvato in Download: $safeName"
                }
            }
        }
    }
    throw IllegalStateException(lastError)
}

internal fun withHermesMediaQueryToken(settings: AppSettings, url: String, apiKey: String?): String {
    val token = apiKey?.trim().orEmpty()
    return try {
        val parsed = url.toUri()
        if (token.isBlank() || !shouldAuthenticateHermesUrl(settings, url)) {
            return url
        }
        if (!parsed.getQueryParameter("hub_token").isNullOrBlank() ||
            !parsed.getQueryParameter("api_key").isNullOrBlank() ||
            !parsed.getQueryParameter("token").isNullOrBlank()
        ) {
            return url
        }
        parsed.buildUpon().appendQueryParameter("hub_token", token).build().toString()
    } catch (_: Exception) {
        url
    }
}

internal fun saveDownloadBytes(
    context: Context,
    filename: String,
    mimeType: String,
    input: java.io.InputStream,
    expectedBytes: Long = -1L
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("cartella Download non accessibile")
        }
        ensureDownloadSpace(context, dir, expectedBytes)
        val target = File(dir, filename)
        val partial = File(dir, ".$filename.${java.util.UUID.randomUUID()}.part")
        try {
            FileOutputStream(partial).use { output ->
                input.use { it.copyTo(output) }
                output.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    partial.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    partial.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (ex: Exception) {
            partial.delete()
            throw ex
        }
        return
    }

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("impossibile creare file in Download")
    try {
        resolver.openOutputStream(target)?.use { output ->
            input.use { it.copyTo(output) }
        } ?: throw IllegalStateException("impossibile scrivere file")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(target, values, null, null)
    } catch (ex: Exception) {
        resolver.delete(target, null, null)
        throw ex
    }
}

internal fun ensureDownloadSpace(context: Context, directory: File, expectedBytes: Long) {
    if (expectedBytes <= 0L) return
    val reserveBytes = 16L * 1024L * 1024L
    if (expectedBytes > Long.MAX_VALUE - reserveBytes) {
        throw IllegalStateException("dimensione download non valida")
    }
    val requiredBytes = expectedBytes + reserveBytes
    val storageManager = context.getSystemService(android.os.storage.StorageManager::class.java)
    val storageUuid = storageManager.getUuidForPath(directory)
    if (storageManager.getAllocatableBytes(storageUuid) < requiredBytes) {
        throw IllegalStateException("spazio insufficiente nella cartella Download")
    }
    storageManager.allocateBytes(storageUuid, requiredBytes)
}

internal fun sanitizeDownloadFilename(value: String): String {
    val cleaned = value.substringBefore('?').substringBefore('#')
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim()
        .take(180)
    return cleaned.ifBlank { "hermes-file" }
}

internal fun formatMediaBytes(value: Long?): String {
    val bytes = value?.takeIf { it > 0 } ?: return ""
    val units = listOf("B", "KB", "MB", "GB")
    var amount = bytes.toDouble()
    var unit = 0
    while (amount >= 1024.0 && unit < units.lastIndex) {
        amount /= 1024.0
        unit++
    }
    return if (unit == 0) "${bytes} B" else String.format(java.util.Locale.US, "%.1f %s", amount, units[unit])
}

internal fun formatMediaDuration(value: Long?): String {
    val millis = value?.takeIf { it > 0 } ?: return ""
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

internal fun formatVideoDuration(value: Long): String {
    if (value <= 0L) return ""
    val totalSeconds = value / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}

internal fun formatVideoTimestamp(value: Long): String {
    if (value <= 0L) return "data non disponibile"
    return java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.ITALY).format(java.util.Date(value))
}

internal fun appendFeedbackSnippet(current: String, snippet: String): String {
    val base = current.trim()
    return if (base.isBlank()) snippet else "$base; $snippet"
}

internal fun authHeaders(apiKey: String?): Map<String, String> {
    val token = apiKey?.trim().orEmpty()
    return mapOf("Authorization" to "Bearer $token", "User-Agent" to "HermesHub-Android")
}

internal fun loadVideoThumbnail(settings: AppSettings, url: String, apiKey: String?): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            retriever.setDataSource(url, if (shouldAuthenticateHermesUrl(settings, url)) authHeaders(apiKey) else emptyMap())
        } else {
            retriever.setDataSource(url)
        }
        val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
        frame?.scaleBitmapToMaxWidth(900)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun Bitmap.scaleBitmapToMaxWidth(maxWidth: Int): Bitmap {
    if (width <= maxWidth || width <= 0 || height <= 0) return this
    val ratio = maxWidth.toFloat() / width.toFloat()
    val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
    return scale(maxWidth, targetHeight)
}

internal fun decodeAttachmentPreview(source: String): Bitmap? {
    return try {
        val payload = source.substringAfter(',', missingDelimiterValue = "")
        val file = source.takeIf { payload.isBlank() }?.let(::File)?.takeIf { it.isFile }
        val bytes = if (file == null && payload.isNotBlank()) Base64.decode(payload, Base64.DEFAULT) else null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        when {
            file != null -> BitmapFactory.decodeFile(file.absolutePath, options)
            bytes != null -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            else -> return null
        }
        val maxWidth = 240
        val scale = if (options.outWidth > maxWidth) (options.outWidth / maxWidth).coerceAtLeast(1) else 1
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
        val decoded = if (file != null) {
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } else {
            BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size, decodeOptions)
        }
        decoded?.scaleBitmapToMaxWidth(maxWidth)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

@Composable
internal fun RemoteGalleryImage(settings: AppSettings, image: VisualGalleryImage, allowExternalImage: Boolean = true) {
    val context = LocalContext.current
    val resolved = remember(settings.gatewayUrl, image.mediaUrl, allowExternalImage) { resolveMediaUrl(settings, image.mediaUrl, allowExternalImage) }
    if (resolved == null) {
        Text("${image.alt}: media non proxy rifiutato.", color = AppColors.Muted, fontSize = 13.sp)
        return
    }
    val apiKey = remember { loadGatewaySecret(context) }

    val bitmap by produceState<Bitmap?>(initialValue = null, resolved) {
        value = withContext(Dispatchers.IO) { loadRemoteBitmap(settings, resolved, apiKey) }
    }
    val loaded = bitmap
    if (loaded == null) {
        Text("${image.alt}: caricamento immagine...", color = AppColors.Muted, fontSize = 13.sp)
        return
    }

    val ratio = (loaded.width.toFloat() / loaded.height.toFloat()).coerceIn(0.7f, 1.9f)
    Image(
        bitmap = loaded.asImageBitmap(),
        contentDescription = image.alt,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(8.dp))
    )
}

internal fun resolveMediaUrl(settings: AppSettings, value: String, allowExternalImage: Boolean = false, allowExternalMedia: Boolean = false): String? {
    return if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
        try {
            val uri = URI(value)
            val root = URI(hermesRoot(settings))
            val path = uri.path.orEmpty()
            if (
                (uri.scheme == "http" || uri.scheme == "https") &&
                path.startsWith("/v1/media/") &&
                (uri.host.equals(root.host, ignoreCase = true) || isKnownHermesGatewayHost(uri.host))
            ) {
                value
            } else if (
                allowExternalImage &&
                uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                !value.startsWith("file:", ignoreCase = true) &&
                !value.startsWith("data:", ignoreCase = true)
            ) {
                value
            } else if (
                allowExternalMedia &&
                uri.scheme == "https" &&
                !uri.host.isNullOrBlank()
            ) {
                value
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    } else {
        if (!isSafeMediaUrl(value)) return null
        "${hermesRoot(settings).trimEnd('/')}${if (value.startsWith('/')) value else "/$value"}"
    }
}

internal const val REMOTE_BITMAP_MAX_BYTES = 10L * 1024 * 1024
internal const val REMOTE_BITMAP_MAX_DIMENSION = 2048

internal fun loadRemoteBitmap(settings: AppSettings, url: String, apiKey: String?): Bitmap? {
    val parsed = runCatching { url.toUri() }.getOrNull()
    val needsHermesAuth = parsed != null && shouldAuthenticateHermesUrl(settings, url)
    val candidates = if (needsHermesAuth) hermesAuthCandidates(apiKey) else listOf<String?>(null)
    val gatewayUrls = if (needsHermesAuth) plugAndPlayUrlCandidates(url) else listOf(url)
    for (gatewayUrl in gatewayUrls) {
        for (token in candidates) {
            val urls = if (needsHermesAuth && !token.isNullOrBlank()) {
                listOf(gatewayUrl, withHermesMediaQueryToken(settings, gatewayUrl, token))
            } else {
                listOf(gatewayUrl)
            }
            for ((index, attemptUrl) in urls.distinct().withIndex()) {
                val loaded = loadRemoteBitmapAttempt(attemptUrl, token.takeIf { index == 0 })
                if (loaded != null) return loaded
            }
        }
    }
    return null
}

internal fun isKnownHermesGatewayHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    return plugAndPlayGatewayRoots.any { root ->
        runCatching { URI(root).host.equals(host, ignoreCase = true) }.getOrDefault(false)
    }
}

internal fun loadRemoteBitmapAttempt(url: String, bearerToken: String?): Bitmap? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "HermesHub-Android")
            if (!bearerToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
        }
        if (connection.responseCode !in 200..299) return null
        val advertised = connection.contentLengthLong
        if (advertised > REMOTE_BITMAP_MAX_BYTES) return null

        val bytes = connection.inputStream.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(chunk)
                if (read <= 0) break
                total += read
                if (total > REMOTE_BITMAP_MAX_BYTES) return null
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth / sample) > REMOTE_BITMAP_MAX_DIMENSION ||
               (bounds.outHeight / sample) > REMOTE_BITMAP_MAX_DIMENSION) {
            sample *= 2
        }

        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

@Composable
internal fun CalloutBlock(block: VisualBlock) {
    val color = when (block.variant) {
        "warning" -> Color(0xFFE0A21A)
        "error" -> Color(0xFFE05D5D)
        "success" -> Color(0xFF4CB878)
        else -> Color(0xFF4C9BE8)
    }
    Row(
        modifier = Modifier.border(0.dp, Color.Transparent),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.width(3.dp).height(56.dp).background(color, RoundedCornerShape(2.dp)))
        MarkdownBlock(block.text)
    }
}

@Composable
internal fun ChatModelSessionBar(
    state: ChatStateHolder,
    settings: AppSettings,
    context: Context,
    botSettings: AppSettings,
    botApiKey: String?,
    botProfile: String?,
    botMultiplexEnabled: Boolean,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val caps = state.chatCapabilities
    val catalog = state.chatModelCatalog
    var showModelDialog by remember { mutableStateOf(false) }
    var showReasoningDialog by remember { mutableStateOf(false) }
    var showSteerDialog by remember { mutableStateOf(false) }
    var steerText by remember { mutableStateOf("") }
    var steerStatus by remember { mutableStateOf("") }
    val streaming = state.streamingState
    val runId = streaming?.activeRunId
    val steerable = !runId.isNullOrBlank() && streaming?.isDone == false &&
        (caps?.supportsSteer() ?: false) && streaming?.runStatus !in listOf("completed", "failed", "cancelled")
    val effModel = state.chatModelOverride.ifBlank { settings.model }
    val effProvider = state.chatProviderOverride.ifBlank { settings.provider }
    val selectedOption = catalog.models.firstOrNull {
        it.id == state.chatModelOverride && (state.chatProviderOverride.isBlank() || it.provider == state.chatProviderOverride)
    }
    val ladder = when {
        selectedOption != null && selectedOption.reasoningEfforts.isNotEmpty() && caps?.reasoningEfforts?.isNotEmpty() == true ->
            selectedOption.reasoningEfforts.filter { eff -> caps.supportsReasoningEffort(eff) }
        caps?.reasoningEfforts?.isNotEmpty() == true -> caps.reasoningEfforts
        else -> FALLBACK_REASONING_EFFORTS
    }
    val reasoningVisible = ladder.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val routeLabel = when (state.sessionRoute) {
                "sessions" -> "Sessione Hermes${state.hermesSessionId?.take(8)?.let { " · ${it}" }.orEmpty()}"
                "legacy-fallback" -> "Legacy (sessions non riuscite)"
                else -> "Legacy"
            }
            Text(routeLabel, color = AppColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (steerable) {
                IconButton(onClick = { steerStatus = ""; showSteerDialog = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Edit, contentDescription = "Correggi run in corso", tint = AppColors.Muted, modifier = Modifier.size(20.dp)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { showModelDialog = true }, modifier = Modifier.weight(1f)) {
                Text(
                    if (state.chatModelOverride.isBlank()) "Modello: $effModel (default)"
                    else "Modello: ${state.chatModelOverride}${effProvider.takeIf { it.isNotBlank() && !it.equals("hermes-agent", true) }?.let { " · $it" }.orEmpty()}",
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (reasoningVisible) {
                Button(onClick = { showReasoningDialog = true }) {
                    Text(if (state.chatReasoningEffort.isBlank()) "Reasoning: auto" else "Reasoning: ${state.chatReasoningEffort}", fontSize = 12.sp)
                }
            }
        }
        if (streaming?.pendingSteer?.isNotBlank() == true) {
            Text("Steer non consegnato: riproponilo come turno successivo.", color = AppColors.Muted, fontSize = 11.sp)
        }
    }

    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text(if (catalog.models.isEmpty() && catalog.providers.isEmpty()) "Modello" else "Modello Hermes (${catalog.source})") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (catalog.models.isEmpty() && catalog.providers.isEmpty()) {
                        item {
                            Text(
                                "Catalogo non caricato. Verifica gateway e API key, oppure digita provider/modello nelle Impostazioni.",
                                color = AppColors.Muted, fontSize = 12.sp
                            )
                        }
                    }
                    if (catalog.models.isEmpty() && catalog.providers.isNotEmpty()) {
                        item {
                            Text(
                                "Il server non espone singoli modelli (solo provider). Il backend LLM resta quello configurato sul server.",
                                color = AppColors.Muted, fontSize = 12.sp
                            )
                        }
                    }
                    item {
                        TextButton(onClick = {
                            state.chatModelOverride = ""
                            state.chatProviderOverride = ""
                            persistChatOverrides(context, state)
                            showModelDialog = false
                        }) { Text("Usa default server (${settings.model})") }
                    }
                    items(catalog.providers.take(20), key = { "provider::${it.slug}" }) { row ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(row.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (!row.available) "non disponibile"
                                else row.warning ?: "provider server",
                                color = AppColors.Muted, fontSize = 11.sp
                            )
                        }
                    }
                    items(catalog.models.take(40), key = { it.id + "::" + it.provider }) { opt ->
                        Column(modifier = Modifier.fillMaxWidth().clickable {
                            state.chatModelOverride = opt.id
                            state.chatProviderOverride = opt.provider
                            // Se il nuovo modello non supporta l'effort corrente, resettalo.
                            val okEffort = state.chatReasoningEffort.isBlank() ||
                                (opt.reasoningEfforts.isEmpty() && ladder.contains(state.chatReasoningEffort)) ||
                                opt.reasoningEfforts.any { it.equals(state.chatReasoningEffort, true) }
                            if (!okEffort) state.chatReasoningEffort = ""
                            persistChatOverrides(context, state)
                            // Model lock persistente server-side (precedence #1 sui turni della sessione).
                            // Fallimento MAI silenzioso: il turno usa comunque model/provider/model_options.
                            val sid = state.hermesSessionId
                            if (!sid.isNullOrBlank() && state.chatCapabilities?.sessionModelLock == true) {
                                scope.launch {
                                    val code = runCatching {
                                        HermesSessionClient(botSettings, botApiKey, botProfile, botMultiplexEnabled, state.chatCapabilities)
                                            .lockModel(sid, opt.id, opt.provider.takeIf { p -> p.isNotBlank() }).first
                                    }.getOrElse { 0 }
                                    hermesModelLockWarning(code, opt.displayName)?.let { warning ->
                                        state.messages.add(ChatMessage("Hermes Hub", warning, fromUser = false, isAction = true))
                                    }
                                }
                            }
                            showModelDialog = false
                        }.padding(vertical = 6.dp)) {
                            Text(opt.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append(opt.provider.ifBlank { "default" })
                                    opt.contextWindow?.let { append(" · ctx $it") }
                                    if (opt.reasoningSupported) append(" · reasoning")
                                    opt.warning?.let { append(" · $it") }
                                },
                                color = AppColors.Muted, fontSize = 11.sp
                            )
                            opt.pricing?.let { p ->
                                Text("pricing: ${p.toString().take(120)}", color = AppColors.Faint, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelDialog = false }) { Text("Chiudi") } }
        )
    }
    if (showReasoningDialog) {
        AlertDialog(
            onDismissRequest = { showReasoningDialog = false },
            title = { Text("Reasoning effort") },
            text = {
                LazyColumn {
                    item {
                        TextButton(onClick = {
                            state.chatReasoningEffort = ""
                            persistChatOverrides(context, state)
                            showReasoningDialog = false
                        }) { Text("Auto (default server)") }
                    }
                    items(ladder) { eff ->
                        TextButton(onClick = {
                            state.chatReasoningEffort = eff
                            persistChatOverrides(context, state)
                            showReasoningDialog = false
                        }) { Text(eff) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showReasoningDialog = false }) { Text("Chiudi") } }
        )
    }
    if (showSteerDialog && runId != null) {
        AlertDialog(
            onDismissRequest = { showSteerDialog = false },
            title = { Text("Correggi run in corso") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inviata al run $runId. Consegna al prossimo tool boundary; non crea un nuovo turno.", fontSize = 12.sp, color = AppColors.Muted)
                    TextField(value = steerText, onValueChange = { steerText = it }, placeholder = { Text("Nuova istruzione...") })
                    if (steerStatus.isNotBlank()) Text(steerStatus, fontSize = 12.sp, color = AppColors.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val payload = steerText.trim()
                    if (payload.isEmpty()) {
                        steerStatus = "Testo obbligatorio."
                        return@TextButton
                    }
                    scope.launch {
                        // Singolo invio, niente retry su esito incerto.
                        val (code, body) = steerHermesRun(botSettings, runId, payload, botApiKey, botProfile, botMultiplexEnabled)
                        steerStatus = when {
                            code in 200..299 -> "Guida accodata (consegna al prossimo tool boundary)."
                            code == 409 -> "Run non più steerable (409): è terminato o in arresto."
                            code == 401 || code == 403 -> "Chiave rifiutata per questo profilo (HTTP $code)."
                            code == 404 -> "Run non trovato (404)."
                            else -> "Steer fallito: HTTP $code ${body.take(160)}"
                        }
                        if (code in 200..299) {
                            steerText = ""
                        }
                    }
                }) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Invia correzione", tint = Color.White) }
            },
            dismissButton = { IconButton(onClick = { showSteerDialog = false }) { Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = Color.White) } }
        )
    }
}

private fun persistChatOverrides(context: Context, state: ChatStateHolder) {
    val cid = state.activeConversationId ?: return
    runCatching {
        val current = loadConversation(context, cid) ?: return
        saveConversationSnapshot(
            context = context,
            conversationId = cid,
            mode = "Chat",
            prompt = current.prompt,
            messages = current.messages,
            source = current.description,
            responseId = current.previousResponseId,
            hermesSessionId = state.hermesSessionId ?: current.hermesSessionId,
            modelOverride = state.chatModelOverride,
            providerOverride = state.chatProviderOverride,
            reasoningEffort = state.chatReasoningEffort,
            syncAfterSave = false
        )
    }
}

@Composable
internal fun ChatApprovalCards(
    state: ChatStateHolder,
    botSettings: AppSettings,
    botApiKey: String?,
    botProfile: String?,
    botMultiplexEnabled: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context
) {
    val approvals = state.streamingState?.pendingApprovals.orEmpty()
    if (approvals.isEmpty()) return
    var resolving by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        approvals.take(3).forEach { approval ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A00))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Approvazione Hermes richiesta", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                    Text(
                        listOf(approval.tool, approval.command, approval.description)
                            .filter { it.isNotBlank() }.joinToString(" — ").take(300)
                            .ifBlank { "Il run attende una decisione." },
                        color = AppColors.Muted, fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Renderizza ESATTAMENTE le choices offerte dal server per questa richiesta.
                        val offered = approval.choices.filter { it in HERMES_APPROVAL_CHOICES }
                            .ifEmpty { listOf("once", "deny") }
                        offered.forEach { choice ->
                            val busy = resolving == "${approval.approvalId}::$choice"
                            TextButton(
                                enabled = resolving.isBlank(),
                                onClick = {
                                    resolving = "${approval.approvalId}::$choice"
                                    status = ""
                                    scope.launch {
                                        val runId = approval.runId.ifBlank { state.streamingState?.activeRunId.orEmpty() }
                                        if (runId.isBlank()) {
                                            status = "Run non disponibile per questa approval."
                                            resolving = ""
                                            return@launch
                                        }
                                        val (code, body) = resolveHermesRunApproval(
                                            botSettings, runId, choice, botApiKey,
                                            approval.requestId.takeIf { it.isNotBlank() }, botProfile, botMultiplexEnabled
                                        )
                                        if (code in 200..299) {
                                            val cur = state.streamingState
                                            if (cur != null && state.activeConversationId != null) {
                                                state.streamingState = cur.applyEvent(
                                                    ChatStreamEvent.ApprovalResolved(runId, approval.approvalId, choice)
                                                )
                                            }
                                            status = ""
                                        } else {
                                            status = when (code) {
                                                401, 403 -> "Chiave rifiutata (HTTP $code)."
                                                404 -> "Approval/run non trovato (404)."
                                                409 -> "Run non in attesa di approval (409)."
                                                else -> "Approval fallita: HTTP $code ${body.take(140)}"
                                            }
                                        }
                                        resolving = ""
                                    }
                                }
                            ) { Text(if (busy) "..." else choice, fontSize = 12.sp) }
                        }
                    }
                    if (status.isNotBlank()) Text(status, color = AppColors.Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Composer(
    context: Context,
    value: String,
    attachments: List<ChatInputAttachment>,
    onValueChange: (String) -> Unit,
    onAttachImage: () -> Unit,
    onPasteImage: () -> Unit,
    onScanDocument: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onRemoveAttachment: (ChatInputAttachment) -> Unit,
    onAction: (String, String, String) -> Unit,
    onModeChange: (String) -> Unit,
    quickPrompt: String?,
    onQuickPromptConsumed: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isBusy: Boolean,
    isRecordingVoiceNote: Boolean,
    onToggleVoiceNote: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(quickPrompt) {
        val prompt = quickPrompt ?: return@LaunchedEffect
        onValueChange(prompt)
        onSend()
        onQuickPromptConsumed()
    }

    fun queueAction(title: String, detail: String, prompt: String) {
        onAction(title, detail, prompt)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
            .widthIn(max = 1040.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { expanded = true },
                color = AppColors.Composer,
                shape = CircleShape,
                border = BorderStroke(1.dp, AppColors.Border)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Rounded.Add, contentDescription = "Apri menu azioni", tint = AppColors.Muted, modifier = Modifier.size(25.dp))
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = AppColors.Elevated
            ) {
                DropdownMenuItem(
                    text = { Text("Allega file", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.AttachFile, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        onAttachImage()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Incolla immagine", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.Image, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        onPasteImage()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Cattura screenshot", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.CropFree, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        onCaptureScreenshot()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Scansiona documento", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, tint = Color.White) },
                    onClick = { expanded = false; onScanDocument() }
                )
                DropdownMenuItem(
                    text = { Text("Scatta foto", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        val opened = openAndroidIntent(context, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                        queueAction(
                            "Foto",
                            if (opened) "Fotocamera Android aperta. Scatta la foto e allegala al task quando pronta." else "Nessuna app fotocamera disponibile. Seleziona una foto esistente dal menu file.",
                            "Acquisisci una foto e usala come allegato per la conversazione."
                        )
                    }
                )
                HorizontalDivider(color = AppColors.Border)
                DropdownMenuItem(
                    text = { Text("Passa a modalita Chat", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        onModeChange("Chat")
                        onAction("Modalita", "Chat attiva: messaggi normali, nessun task agente automatico.", "")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Passa a modalita Agente", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.SmartToy, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        onModeChange("Agente")
                        onAction("Modalita", "Agente attivo: usa strumenti Hermes se disponibili, altrimenti fallback locale.", "")
                    }
                )
                HorizontalDivider(color = AppColors.Border)
                DropdownMenuItem(
                    text = { Text("Crea immagine", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.Image, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Immagine",
                            "Generazione immagine richiedera' tool Hermes dedicato e conferma prima di chiamate esterne.",
                            "Prepara una richiesta di generazione immagine, ma chiedi conferma prima di usare tool esterni."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Deep Research locale", color = Color.White) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ManageSearch, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Deep Research",
                            "Ricerca approfondita locale; rete solo dopo approvazione esplicita.",
                            "Esegui una ricerca approfondita e cita fonti, usando rete solo dopo approvazione."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Ricerca web autorizzata", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.Language, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Web",
                            "Ricerca web marcata come azione autorizzabile: nessuna rete fuori LAN/VPN senza conferma.",
                            "Cerca sul web informazioni aggiornate, chiedendo conferma prima di uscire dalla LAN/VPN."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Spiegazione visiva", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.Image, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Visuale",
                            "Spiegazione visiva richiesta: Hermes usera' blocchi statici sicuri se disponibili.",
                            "Spiega anche con blocchi visuali se utile: tabella, diagramma, chart o callout. Mantieni output_text completo."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Progetti e workspace", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Workspace",
                            "Workspace/progetti saranno collegati agli artifact Hermes con audit trail.",
                            "Lavora sul workspace o progetto selezionato e mostra piano prima di modificare file."
                        )
                    }
                )
            }
        }

        val fontScale = LocalDensity.current.fontScale.coerceIn(0.5f, 2.0f)
        Surface(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = (54 * fontScale).dp, max = (156 * fontScale).dp),
            color = AppColors.Composer,
            shape = RoundedCornerShape(25.dp),
            border = BorderStroke(1.dp, AppColors.Border)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = (38 * fontScale).dp, max = (138 * fontScale).dp)
                        .padding(vertical = 5.dp)
                ) {
                    if (attachments.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            attachments.forEach { attachment ->
                                val previewSource = attachment.localFilePath ?: attachment.dataUrl
                                val preview by produceState<Bitmap?>(initialValue = null, previewSource) {
                                    this.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        decodeAttachmentPreview(previewSource)
                                    }
                                }
                                Surface(
                                    color = AppColors.Surface,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, AppColors.Border)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .widthIn(max = 260.dp)
                                            .padding(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val p = preview
                                        if (p != null) {
                                            Image(
                                                bitmap = p.asImageBitmap(),
                                                contentDescription = attachment.filename,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(AppColors.Elevated),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Rounded.Image, contentDescription = null, tint = AppColors.Accent)
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f, fill = false)) {
                                            Text(attachment.filename, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${attachment.mimeType} · ${attachment.sizeBytes.toReadableFileSize()}", color = AppColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = { onRemoveAttachment(attachment) }, modifier = Modifier.size(22.dp)) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Rimuovi allegato", tint = AppColors.Muted, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        minLines = 1,
                        maxLines = 5,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 16.sp,
                            lineHeight = 23.sp
                        ),
                        cursorBrush = SolidColor(AppColors.Accent),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            autoCorrectEnabled = true,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Default
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (value.isEmpty()) {
                        Text("Fai una domanda", color = AppColors.Faint, fontSize = 16.sp)
                    }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    modifier = Modifier
                        .size(42.dp)
                        .clickable(enabled = true) {
                            onToggleVoiceNote()
                        },
                    color = Color.Transparent,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isRecordingVoiceNote) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = if (isRecordingVoiceNote) "Ferma registrazione" else "Registra nota vocale",
                            tint = if (isRecordingVoiceNote) Color.Red else AppColors.Muted
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))

                val canSend = (value.isNotBlank() || attachments.isNotEmpty()) && !isBusy
                val canPress = isBusy || canSend
                Surface(
                    modifier = Modifier
                        .size(42.dp)
                        .clickable(enabled = canPress) {
                            if (isBusy) onStop() else onSend()
                        },
                    color = if (canPress) AppColors.Accent else AppColors.Surface,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isBusy) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                            contentDescription = if (isBusy) "Interrompi generazione" else "Invia",
                            tint = if (canPress) Color(0xFF171009) else AppColors.Muted
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectsScreen(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onNewChat: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenConversation: (String) -> Unit
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val projects = remember(refreshKey) {
        loadConversations(context).filter { it.kind == "Progetto" }.sortedByDescending { it.updatedAt }
    }
    var selectedId by rememberSaveable { mutableStateOf(settings.activeProjectId.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id.orEmpty()) }
    val selected = projects.firstOrNull { it.id == selectedId }
    var title by rememberSaveable { mutableStateOf(selected?.title.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(selected?.projectInstructions.orEmpty()) }
    var status by remember { mutableStateOf("Pronto.") }

    fun edit(project: LocalConversation?) {
        selectedId = project?.id.orEmpty()
        title = project?.title.orEmpty()
        instructions = project?.projectInstructions.orEmpty()
        if (project != null) {
            onSettingsChanged(settings.withActiveProject(project))
            status = "Progetto selezionato. Contesto applicato automaticamente."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Progetti", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Scegli un nome e, se serve, un system prompt personalizzato. Il resto e' automatico.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("I tuoi progetti", color = Color.White, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { edit(null); status = "Inserisci nome e system prompt facoltativo." }) { Icon(Icons.Rounded.Add, contentDescription = "Nuovo progetto", tint = Color.White) }
                    }
                    if (projects.isEmpty()) {
                        Text("Nessun progetto.", color = AppColors.Muted)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            projects.forEach { project ->
                                VideoFeedChip(project.title, selected = project.id == selectedId) { edit(project) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (selected == null) "Nuovo progetto" else selected.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (selected?.id == settings.activeProjectId) Text("PROGETTO ATTIVO", color = AppColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    SettingsField("Nome progetto", title, { title = it })
                    SettingsField("System prompt (facoltativo)", instructions, { instructions = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = {
                            runCatching {
                                saveProjectWorkspace(
                                    context = context,
                                    projectId = selectedId.ifBlank { null },
                                    title = title,
                                    description = selected?.description.orEmpty(),
                                    workspacePath = selected?.workspacePath.orEmpty(),
                                    repositoryUrl = selected?.repositoryUrl.orEmpty(),
                                    instructions = instructions,
                                    memory = selected?.projectMemory.orEmpty(),
                                    authorizedTools = selected?.authorizedTools.orEmpty()
                                )
                            }.onSuccess { saved ->
                                selectedId = saved.id
                                refreshKey++
                                onSettingsChanged(settings.withActiveProject(saved))
                                status = "Progetto salvato e attivato."
                            }.onFailure { status = it.message ?: "Salvataggio progetto fallito." }
                        }) { Icon(Icons.Rounded.Save, contentDescription = "Salva progetto", tint = Color.White) }
                        IconButton(enabled = selected != null, onClick = {
                            selected?.let {
                                onSettingsChanged(settings.withActiveProject(it))
                                onNewChat()
                            }
                        }) { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "Nuova chat nel progetto", tint = Color.White) }
                    }
                    Text(status, color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

internal fun AppSettings.withActiveProject(project: LocalConversation): AppSettings = copy(
    activeProjectId = project.id,
    activeProjectName = project.title,
    activeProjectWorkspacePath = project.workspacePath,
    activeProjectRepositoryUrl = project.repositoryUrl,
    activeProjectInstructions = project.projectInstructions,
    activeProjectMemory = project.projectMemory,
    activeProjectTools = project.authorizedTools.joinToString("\n")
)

internal fun AppSettings.clearActiveProject(): AppSettings = copy(
    activeProjectId = "",
    activeProjectName = "",
    activeProjectWorkspacePath = "",
    activeProjectRepositoryUrl = "",
    activeProjectInstructions = "",
    activeProjectMemory = "",
    activeProjectTools = ""
)

@Composable
internal fun UniversalSearchScreen(context: Context, settings: AppSettings, onOpen: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Inserisci almeno 2 caratteri.") }
    var results by remember { mutableStateOf<List<Triple<String, String, Pair<String, String>>>>(emptyList()) }

    fun runSearch() {
        val needle = query.trim()
        if (needle.length < 2) { status = "Inserisci almeno 2 caratteri."; return }
        status = "Ricerca in corso..."
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                val list = mutableListOf<Triple<String, String, Pair<String, String>>>()
                loadConversations(context).forEach { item ->
                    val body = buildString {
                        append(item.title).append(' ').append(item.description).append(' ').append(item.prompt).append(' ')
                        append(item.projectMemory).append(' ').append(item.projectInstructions).append(' ').append(item.artifactFileName).append(' ').append(item.tags.joinToString(" "))
                        item.messages.forEach { message ->
                            append(' ').append(message.text)
                            message.rawEvents.forEach { append(' ').append(it.json) }
                            message.visualBlocks.forEach { append(' ').append(it.title).append(' ').append(it.caption).append(' ').append(it.text).append(' ').append(it.code).append(' ').append(it.filename) }
                        }
                    }
                    if (body.contains(needle, true)) list += Triple(item.kind, item.id, item.title to body.replace('\n', ' ').take(260))
                }
                val apiKey = loadGatewaySecret(context)
                loadCronJobs(settings, apiKey).first.filter { "${it.name} ${it.prompt} ${it.lastStatus}".contains(needle, true) }
                    .forEach { list += Triple("Cron", it.id, it.name to "${it.prompt} ${it.lastStatus}".take(260)) }
                loadHubNotifications(settings, apiKey, false).first.filter { "${it.title} ${it.message}".contains(needle, true) }
                    .forEach { list += Triple("Notifica", it.id, it.title to it.message.take(260)) }
                val memory = loadHubMemory(settings, apiKey).first
                val memoryText = "${memory.videoPreferences} ${memory.newsPreferences} ${memory.responseStyle} ${memory.projectRules} ${memory.generalNotes}"
                if (memoryText.contains(needle, true)) list += Triple("Memoria", "hub-memory", "Memoria Hermes" to memoryText.take(260))
                list
            }
            results = found
            status = "${found.size} risultati."
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ricerca universale", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold); Text("Chat, tool call, artifact, progetti, memoria, cron e notifiche.", color = AppColors.Muted) }
        item { Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { SettingsField("Cerca ovunque", query, { query = it }); Button(onClick = { runSearch() }) { Text("Cerca") }; Text(status, color = AppColors.Muted, fontSize = 12.sp) } } }
        items(results, key = { "${it.first}-${it.second}" }) { result ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(result.first, result.second) }, colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble), shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("${result.first} · ${result.third.first}", color = Color.White, fontWeight = FontWeight.SemiBold); Text(result.third.second, color = AppColors.Muted, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
internal fun ArtifactLibraryScreen(
    context: Context,
    settings: AppSettings,
    onOpenConversation: (String) -> Unit,
    onRegenerate: (String) -> Unit
) {
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectFilter by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var rename by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Pronto.") }
    val artifacts = remember(refresh, query, projectFilter) {
        loadConversations(context).filter { item ->
            item.kind == "Artifact" &&
                (projectFilter.isBlank() || item.projectId.contains(projectFilter, true)) &&
                (query.isBlank() || item.title.contains(query, true) || item.artifactFileName.contains(query, true) || item.tags.any { it.contains(query, true) })
        }.sortedByDescending { it.updatedAt }
    }
    val selected = artifacts.firstOrNull { it.id == selectedId }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Artifact", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text("Output persistenti prodotti da chat, run e automazioni.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsField("Cerca nome, file o tag", query, { query = it })
                    SettingsField("Filtra progetto", projectFilter, { projectFilter = it })
                    Text("${artifacts.size} artifact", color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
        items(artifacts, key = { it.id }) { artifact ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedId = artifact.id; rename = artifact.title; tags = artifact.tags.joinToString(", ")
                },
                colors = CardDefaults.cardColors(containerColor = if (artifact.id == selectedId) AppColors.Elevated else AppColors.AssistantBubble),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(artifact.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${artifact.artifactType} · v${artifact.version} · ${artifact.projectId}", color = AppColors.Muted, fontSize = 12.sp)
                    Text(artifact.description, color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (selected != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Dettaglio · v${selected.version}", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("File: ${selected.artifactFileName}\nMIME: ${selected.artifactMimeType}\nChat: ${selected.sourceConversationId}\nRun: ${selected.sourceRunId}", color = AppColors.Muted, fontSize = 12.sp)
                        SettingsField("Nome", rename, { rename = it })
                        SettingsField("Tag", tags, { tags = it })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = {
                                saveArtifactMetadata(context, selected.id, rename, tags.split(',', ';').map { it.trim() }.filter { it.isNotBlank() })
                                refresh++; status = "Metadata salvati; sync gateway in coda."
                            }) { Icon(Icons.Rounded.Save, contentDescription = "Salva metadata", tint = Color.White) }
                            IconButton(onClick = { if (selected.sourceConversationId.isNotBlank()) onOpenConversation(selected.sourceConversationId) }) { Icon(Icons.Rounded.Visibility, contentDescription = "Anteprima origine", tint = Color.White) }
                            IconButton(onClick = {
                                if (selected.artifactUrl.isBlank()) status = "Artifact senza URL apribile." else runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, resolveHermesUrl(settings, selected.artifactUrl).toUri()))
                                }.onFailure { status = "Apertura fallita: ${it.message}" }
                            }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Apri artifact", tint = Color.White) }
                            IconButton(onClick = { onRegenerate("Rigenera artifact '${selected.title}' versione ${selected.version}, progetto ${selected.projectId}, sorgente chat ${selected.sourceConversationId}.") }) { Icon(Icons.Rounded.Refresh, contentDescription = "Rigenera artifact", tint = Color.White) }
                        }
                        Text("Versioni", color = Color.White, fontWeight = FontWeight.SemiBold)
                        val key = selected.artifactFileName.ifBlank { selected.title }
                        loadConversations(context).filter { it.kind == "Artifact" && it.artifactFileName.ifBlank { it.title }.equals(key, true) }
                            .sortedByDescending { it.version }.forEach { version ->
                                Text("v${version.version} · ${formatDateTime(version.updatedAt)} · ${version.description}", color = AppColors.Muted, fontSize = 12.sp)
                            }
                        Text(status, color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

internal fun saveArtifactMetadata(context: Context, id: String, title: String, tags: List<String>) {
    synchronized(localArchiveLock) {
        val items = loadConversations(context, includeDeleted = true).toMutableList()
        val index = items.indexOfFirst { it.id == id && it.kind == "Artifact" && it.deletedAt == null }
        if (index < 0) return
        items[index] = items[index].copy(
            title = title.trim().take(180).ifBlank { items[index].title },
            tags = tags.map { it.take(80) }.distinctBy { it.lowercase() }.take(30),
            updatedAt = System.currentTimeMillis()
        )
        saveConversations(context, items)
    }
}
