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
internal fun VisualBlockView(block: VisualBlock) {
    // Immagini inviate dall'utente: solo miniatura, niente card con nome file.
    if (isUserLocalImage(block)) {
        MediaFileBlock(block)
        return
    }
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
                if (!openAndroidIntent(context, intent)) {
                    Toast.makeText(context, "Nessuna app per aprire questo file.", Toast.LENGTH_SHORT).show()
                }
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
        // Scope application-lifetime: lo scroll che ricicla la card non deve
        // abortire il download a meta scrittura senza esito.
        HermesStreamRuntime.scope.launch {
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
        if (isUserLocalImage(block)) {
            // Solo miniatura: il tap apre lo stesso viewer delle immagini di Hermes.
            LocalInlineImage(block)
            return@Column
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
                            if (!openAndroidIntent(context, intent)) {
                                Toast.makeText(context, "Nessuna app per aprire questo allegato.", Toast.LENGTH_SHORT).show()
                            }
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

/**
 * Aggiunge il token gateway come query `hub_token` all'URL media.
 *
 * SICUREZZA (audit header-vs-query): preferire SEMPRE l'header `Authorization: Bearer`.
 * Già migrati a header: ExoPlayer (DefaultHttpDataSource via [authHeaders] in
 * ChatInlineVideoPlayer/ChatInlineAudioPlayer), MediaMetadataRetriever
 * ([loadVideoThumbnail] usa setDataSource con header), OkHttp/HttpURLConnection
 * ([downloadHermesMediaFile]/[loadRemoteBitmapAttempt] inviano l'header; il secondo
 * tentativo con query è solo fallback di compatibilità verso server che non accettano
 * l'header). Il query token resta SOLO per gli Intent ACTION_VIEW esterni
 * (DocumentSlimRow/apertura allegati): un'app esterna non può ricevere header custom,
 * quindi l'URL non può essere autenticato altrimenti (tecnicamente impossibile).
 * Nessun VideoView/MediaPlayer nativo usa questa funzione. Formato token ed endpoint
 * invariati; la guardia same-origin [shouldAuthenticateHermesUrl] evita leak del token
 * verso host esterni.
 */
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
internal fun appendFeedbackSnippet(current: String, snippet: String): String {
    val base = current.trim()
    return if (base.isBlank()) snippet else "$base; $snippet"
}

internal fun authHeaders(apiKey: String?): Map<String, String> {
    val token = apiKey?.trim().orEmpty()
    if (token.isEmpty()) return mapOf("User-Agent" to "HermesHub-Android")
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
/**
 * L'agente gira sul server e a volte emette URL assoluti su loopback
 * (http://127.0.0.1:8642/v1/media/...), irraggiungibili dal telefono.
 * Riscrive l'host con quello del gateway configurato, stessa porta e path.
 */
internal fun normalizeLoopbackMediaUrl(settings: AppSettings, value: String): String {
    return try {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase().trim('[', ']')
        val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0.0.0.0"
        if (!isLoopback || !uri.path.orEmpty().startsWith("/v1/media/")) return value
        val root = URI(hermesRoot(settings))
        val rootHost = root.host.orEmpty()
        if (rootHost.isBlank()) return value
        val port = if (uri.port != -1) uri.port else root.port
        val rebuilt = StringBuilder("${root.scheme}://$rootHost")
        if (port != -1) rebuilt.append(":$port")
        rebuilt.append(uri.rawPath)
        if (!uri.rawQuery.isNullOrEmpty()) rebuilt.append("?${uri.rawQuery}")
        if (!uri.rawFragment.isNullOrEmpty()) rebuilt.append("#${uri.rawFragment}")
        rebuilt.toString()
    } catch (_: Exception) {
        value
    }
}

internal fun resolveMediaUrl(settings: AppSettings, value: String, allowExternalImage: Boolean = false, allowExternalMedia: Boolean = false): String? {
    val candidate = normalizeLoopbackMediaUrl(settings, value)
    return if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) {
        try {
            val uri = URI(candidate)
            val root = URI(hermesRoot(settings))
            val path = uri.path.orEmpty()
            if (
                (uri.scheme == "http" || uri.scheme == "https") &&
                path.startsWith("/v1/media/") &&
                (uri.host.equals(root.host, ignoreCase = true) || isKnownHermesGatewayHost(uri.host))
            ) {
                candidate
            } else if (
                allowExternalImage &&
                uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                !candidate.startsWith("file:", ignoreCase = true) &&
                !candidate.startsWith("data:", ignoreCase = true)
            ) {
                candidate
            } else if (
                allowExternalMedia &&
                uri.scheme == "https" &&
                !uri.host.isNullOrBlank()
            ) {
                candidate
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
