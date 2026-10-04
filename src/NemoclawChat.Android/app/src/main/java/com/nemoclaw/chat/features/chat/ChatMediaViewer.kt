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
        ChatImageViewerDialog(
            bitmap = loaded,
            alt = block.alt.ifBlank { block.filename },
            isDownloading = isDownloading,
            onClose = { viewer = false },
            onDownload = { onDownload(mediaUrl, block.filename.ifBlank { block.title.ifBlank { "hermes-file" } }) }
        )
    }
}

/** Viewer fullscreen condiviso: stesse gesture e stessi pulsanti per le immagini
 *  di Hermes e per quelle inviate dall'utente. Senza onDownload nasconde Scarica. */
@Composable
internal fun ChatImageViewerDialog(
    bitmap: Bitmap,
    alt: String,
    isDownloading: Boolean,
    onClose: () -> Unit,
    onDownload: (() -> Unit)?
) {
    Dialog(onDismissRequest = onClose) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.62f), CircleShape)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = Color.White)
                }
                if (onDownload != null) {
                    IconButton(
                        onClick = { if (!isDownloading) onDownload() },
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

/** Vera se il blocco e' un'immagine inviata dall'utente (file locale, non remota). */
internal fun isUserLocalImage(block: VisualBlock): Boolean =
    block.type.equals("media_file", ignoreCase = true) &&
        block.localDataUrl.isNotBlank() &&
        (block.mediaKind.equals("image", ignoreCase = true) ||
            block.mimeType.startsWith("image/", ignoreCase = true))

/** Vera se la sorgente anteprima locale e' ancora leggibile (file in cache o data-url inline). */
internal fun localPreviewSourceExists(source: String): Boolean {
    if (source.isBlank()) return false
    return try {
        if (File(source).isFile) return true
        source.contains(',')
    } catch (_: Exception) {
        false
    }
}

/** Immagine inviata dall'utente: solo miniatura, tap apre il viewer come Hermes. */
@Composable
internal fun LocalInlineImage(block: VisualBlock) {
    val alt = block.alt.ifBlank { block.filename.ifBlank { "Immagine" } }
    var viewer by remember(block.localDataUrl) { mutableStateOf(false) }
    val sourceAlive = remember(block.localDataUrl) { localPreviewSourceExists(block.localDataUrl) }
    if (!sourceAlive) {
        // Cache pulita o file spostato: riga sobria con nome, niente loading infinito.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Image, contentDescription = null, tint = AppColors.Muted)
            }
            Column(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = block.filename.ifBlank { alt },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("Anteprima non disponibile.", color = AppColors.Muted, fontSize = 11.sp, maxLines = 1)
            }
        }
        return
    }
    val thumb by produceState<Bitmap?>(initialValue = null, block.localDataUrl) {
        value = withContext(Dispatchers.IO) { decodeAttachmentPreview(block.localDataUrl) }
    }
    val loaded = thumb
    if (loaded == null) {
        Text("Immagine in caricamento...", color = AppColors.Muted, fontSize = 13.sp)
        return
    }
    Image(
        bitmap = loaded.asImageBitmap(),
        contentDescription = alt,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { viewer = true }
    )
    if (viewer) {
        LocalAttachmentViewerDialog(
            source = block.localDataUrl,
            alt = alt,
            onClose = { viewer = false }
        )
    }
}

/** Viewer per un allegato locale (file o data-url): decodifica full-res solo all'apertura. */
@Composable
internal fun LocalAttachmentViewerDialog(source: String, alt: String, onClose: () -> Unit) {
    val full by produceState<Bitmap?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) { decodeAttachmentPreview(source, maxWidth = 1600) }
    }
    val bitmap = full
    if (bitmap == null) {
        Dialog(onDismissRequest = onClose) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Caricamento immagine...", color = AppColors.Muted, fontSize = 13.sp)
            }
        }
        return
    }
    ChatImageViewerDialog(
        bitmap = bitmap,
        alt = alt,
        isDownloading = false,
        onClose = onClose,
        onDownload = null
    )
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
internal fun Bitmap.scaleBitmapToMaxWidth(maxWidth: Int): Bitmap {
    if (width <= maxWidth || width <= 0 || height <= 0) return this
    val ratio = maxWidth.toFloat() / width.toFloat()
    val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
    return scale(maxWidth, targetHeight)
}

internal fun decodeAttachmentPreview(source: String, maxWidth: Int = 240): Bitmap? {
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
        val cap = maxWidth.coerceAtLeast(48)
        val scale = if (options.outWidth > cap) (options.outWidth / cap).coerceAtLeast(1) else 1
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
        val decoded = if (file != null) {
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } else {
            val data = bytes ?: return null
            BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
        }
        decoded?.scaleBitmapToMaxWidth(cap)
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
/** Messaggi d'errore manager: mai un HTTP fallito travestito da "tutto spento". */
internal fun managerStatusErrorMessage(code: Int, body: String): String = when (code) {
    401, 403 -> "Chiave rifiutata dal manager (HTTP $code)"
    0 -> "Manager non raggiungibile (${body.take(120)})"
    else -> "Manager HTTP $code: ${body.take(160)}"
}

internal fun managerModeErrorMessage(code: Int, body: String): String = when (code) {
    401, 403 -> "Chiave rifiutata dal manager (HTTP $code)"
    0 -> "Manager non raggiungibile"
    else -> "Cambio modalita rifiutato (HTTP $code): ${body.take(160)}"
}
