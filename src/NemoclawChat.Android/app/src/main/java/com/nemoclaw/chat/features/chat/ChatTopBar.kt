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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
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
internal fun TopBar(
    settings: AppSettings,
    contextUsage: ContextUsage,
    connected: Boolean,
    gatewayRuntime: GatewayRuntimeStatus?,
    probingGateway: Boolean = false,
    probeDetail: String? = null,
    title: String = "Hermes Hub",
    managerApiKey: String? = null,
    onNewChat: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
    onOpenArchive: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val managerBase = remember(settings.gatewayUrl) { gpuManagerBase(settings.gatewayUrl) }
    var menuOpen by remember { mutableStateOf(false) }
    var llmLoaded by remember { mutableStateOf<Boolean?>(null) }
    var llmState by remember { mutableStateOf("") }
    var desiredMode by remember { mutableStateOf("") }
    var queueLength by remember { mutableStateOf(0) }
    var directUrl by remember { mutableStateOf("") }
    var directActive by remember { mutableStateOf(false) }
    var llmBusy by remember { mutableStateOf(false) }
    var llmError by remember { mutableStateOf("") }

    suspend fun readManagerStatus() {
        // Errori HTTP espliciti: un 401/500 non deve mai sembrare "tutto spento".
        val (code, body) = httpGetResponse("$managerBase/status", managerApiKey)
        if (code !in 200..299) {
            throw IllegalStateException(managerStatusErrorMessage(code, body))
        }
        val status = JSONObject(body)
        llmLoaded = status.optBoolean("llm_loaded", false)
        llmState = status.optString("current_state", "")
        desiredMode = status.optString("desired_mode", "")
        queueLength = status.optInt("queue_length", 0)
        directUrl = status.optString("direct_url", "")
        directActive = status.optBoolean("direct_active", desiredMode == "DIRECT")
    }

    fun refreshLlm() {
        scope.launch {
            llmBusy = true
            llmError = runCatching { readManagerStatus() }.exceptionOrNull()?.message ?: ""
            llmBusy = false
        }
    }

    // Unico poll manager: 15 s a menu chiuso, 3 s a menu aperto per seguire
    // le transizioni in tempo reale. Solo a lifecycle STARTED (niente poll
    // in background) e backoff fino a 60 s se il manager non risponde.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var lifecycleStarted by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            lifecycleStarted = event.targetState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(managerBase, menuOpen, lifecycleStarted) {
        if (!lifecycleStarted) return@LaunchedEffect
        llmError = runCatching { readManagerStatus() }.exceptionOrNull()?.message ?: ""
        var failures = 0
        while (lifecycleStarted) {
            delay(if (menuOpen) 3_000 else 15_000)
            val err = runCatching { readManagerStatus() }.exceptionOrNull()?.message
            llmError = err ?: ""
            if (err == null) {
                failures = 0
            } else if (++failures >= 2) {
                // Backoff: evita di martellare un manager morto.
                delay(45_000)
            }
        }
    }

    fun setLlmWanted(wanted: Boolean) {
        scope.launch {
            llmBusy = true
            llmError = runCatching {
                val (code, body) = postJson("$managerBase/mode/${if (wanted) "llm" else "media"}", JSONObject(), managerApiKey, allowCompatAuth = false)
                if (code !in 200..299) {
                    throw IllegalStateException(managerModeErrorMessage(code, body))
                }
                delay(3_000)
                readManagerStatus()
            }.exceptionOrNull()?.message ?: ""
            llmBusy = false
        }
    }

    fun setDirectWanted(wanted: Boolean) {
        scope.launch {
            llmBusy = true
            llmError = runCatching {
                val mode = if (wanted) "direct" else "auto"
                val (code, body) = postJson("$managerBase/mode/$mode", JSONObject(), managerApiKey, allowCompatAuth = false)
                if (code !in 200..299) {
                    throw IllegalStateException(managerModeErrorMessage(code, body))
                }
                delay(3_000)
                readManagerStatus()
            }.exceptionOrNull()?.message ?: ""
            llmBusy = false
        }
    }

    fun setAutoWanted(wanted: Boolean) {
        scope.launch {
            llmBusy = true
            llmError = runCatching {
                val mode = if (wanted) "auto" else if (llmLoaded == true) "llm" else "media"
                val (code, body) = postJson("$managerBase/mode/$mode", JSONObject(), managerApiKey, allowCompatAuth = false)
                if (code !in 200..299) {
                    throw IllegalStateException(managerModeErrorMessage(code, body))
                }
                delay(3_000)
                readManagerStatus()
            }.exceptionOrNull()?.message ?: ""
            llmBusy = false
        }
    }

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
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (probingGateway) AppColors.Muted else if (connected) AppColors.Success else AppColors.Error, CircleShape)
                    )
                    Text(
                        if (probingGateway) {
                            "Verifica gateway…"
                        } else {
                            gatewayRuntimeLabel(connected, gatewayRuntime) +
                                if (!connected && !probeDetail.isNullOrBlank()) " · $probeDetail" else ""
                        },
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
            Box {
                IconButton(
                    onClick = { menuOpen = true; refreshLlm() },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Impostazioni rapide", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Nuova chat") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, tint = AppColors.Accent) },
                        onClick = { menuOpen = false; onNewChat() }
                    )
                    HorizontalDivider()
                    val loaded = llmLoaded
                    val subtitle = when {
                        llmError.isNotBlank() -> "Non raggiungibile"
                        loaded == null -> if (llmBusy) "Lettura..." else "Stato sconosciuto"
                        llmBusy -> "Applicazione in corso..."
                        loaded -> "Caricato sulle GPU"
                        llmState == "LLM_LOADING" -> "Caricamento in corso..."
                        llmState == "MEDIA_STOPPING" || llmState == "LLM_UNLOADING" -> "Scaricamento in corso..."
                        else -> "Scaricato"
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("LLM su GPU")
                                Text(subtitle, color = AppColors.Muted, fontSize = 12.sp)
                            }
                        },
                        trailingIcon = {
                            Switch(
                                checked = loaded == true,
                                onCheckedChange = null,
                                enabled = !llmBusy
                            )
                        },
                        enabled = !llmBusy && loaded != null && llmError.isBlank(),
                        onClick = { if (!llmBusy && loaded != null) setLlmWanted(!(loaded)) }
                    )
                    val auto = desiredMode == "AUTO"
                    val autoSubtitle = when {
                        llmError.isNotBlank() -> "Non raggiungibile"
                        desiredMode.isBlank() -> if (llmBusy) "Lettura..." else "Stato sconosciuto"
                        llmBusy -> "Applicazione in corso..."
                        auto -> "Il manager cambia da solo" + (if (queueLength > 0) " · coda $queueLength" else "")
                        else -> "Manuale ($desiredMode)" + (if (queueLength > 0) " · coda $queueLength" else "")
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Gestione automatica GPU")
                                Text(autoSubtitle, color = AppColors.Muted, fontSize = 12.sp)
                            }
                        },
                        trailingIcon = {
                            Switch(
                                checked = auto,
                                onCheckedChange = null,
                                enabled = !llmBusy
                            )
                        },
                        enabled = !llmBusy && desiredMode.isNotBlank() && llmError.isBlank(),
                        onClick = { if (!llmBusy && desiredMode.isNotBlank()) setAutoWanted(!auto) }
                    )
                    val direct = desiredMode == "DIRECT"
                    val directSubtitle = when {
                        llmError.isNotBlank() -> "Non raggiungibile"
                        desiredMode.isBlank() -> if (llmBusy) "Lettura..." else "Stato sconosciuto"
                        llmBusy -> "Applicazione in corso..."
                        direct && directUrl.isNotBlank() -> "Attivo su $directUrl"
                        direct -> "Attivo: apri Comfy nel browser"
                        else -> "LLM scaricato, Comfy per te sulla tailnet"
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Comfy diretto")
                                Text(directSubtitle, color = AppColors.Muted, fontSize = 12.sp)
                            }
                        },
                        trailingIcon = {
                            Switch(
                                checked = direct,
                                onCheckedChange = null,
                                enabled = !llmBusy
                            )
                        },
                        enabled = !llmBusy && desiredMode.isNotBlank() && llmError.isBlank(),
                        onClick = { if (!llmBusy && desiredMode.isNotBlank()) setDirectWanted(!direct) }
                    )
                }
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
    val tokens = authoritativeStats?.modelPromptTokens?.takeIf { it > 0 }
        // Prompt reale macinato dal modello nell'ultimo turno: è il riempimento
        // vero, non l'accounting lato agent (che può superare la finestra).
        ?: if (isHermesNative(settings)) serverContextTokens
        else maxOf(estimated, serverContextTokens).coerceAtLeast(0)
    // Percentuale sempre calcolata sui token rispetto alla finestra reale del
    // modello (dichiarata dal server o fallback verificato): parte da 0 a chat
    // vuota e sale col contesto. La percent del compattatore server non fa fede
    // sul riempimento modello.
    val percent = ((tokens.coerceAtMost(contextWindow).toDouble() / contextWindow) * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
    return ContextUsage(tokens = tokens, maxTokens = contextWindow, percent = percent, delegatedToHermes = isHermesNative(settings))
}

internal fun estimateTokenCount(text: String): Int {
    if (text.isBlank()) return 0
    return ((text.length + 3) / 4).coerceAtLeast(1)
}
