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
internal fun AttachSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            color = AppColors.Elevated,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AppColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Strip miniature SOPRA il canvas del prompt: solo anteprima + X, niente nomi file. */
@Composable
internal fun ComposerAttachmentStrip(
    attachments: List<ChatInputAttachment>,
    onRemoveAttachment: (ChatInputAttachment) -> Unit
) {
    var viewing by remember { mutableStateOf<ChatInputAttachment?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        attachments.forEach { attachment ->
            ComposerAttachmentThumb(
                attachment = attachment,
                onRemove = { onRemoveAttachment(attachment) },
                onView = { viewing = attachment }
            )
        }
    }
    viewing?.let { current ->
        LocalAttachmentViewerDialog(
            source = current.localFilePath ?: current.dataUrl,
            alt = "Allegato",
            onClose = { viewing = null }
        )
    }
}

@Composable
internal fun ComposerAttachmentThumb(
    attachment: ChatInputAttachment,
    onRemove: () -> Unit,
    onView: () -> Unit
) {
    val isImage = attachment.mimeType.startsWith("image/", ignoreCase = true)
    val previewSource = attachment.localFilePath ?: attachment.dataUrl
    val preview by produceState<Bitmap?>(initialValue = null, previewSource) {
        value = withContext(Dispatchers.IO) { decodeAttachmentPreview(previewSource) }
    }
    Box(modifier = Modifier.size(76.dp)) {
        val p = preview
        if (isImage && p != null) {
            Image(
                bitmap = p.asImageBitmap(),
                contentDescription = "Anteprima allegato",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onView() }
            )
        } else {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.Elevated)
                    .clickable { onView() },
                contentAlignment = Alignment.Center
            ) {
                val icon = when {
                    attachment.mimeType.startsWith("video/", ignoreCase = true) -> Icons.Rounded.PlayArrow
                    attachment.mimeType.startsWith("audio/", ignoreCase = true) -> Icons.Rounded.Mic
                    else -> Icons.Rounded.Description
                }
                Icon(icon, contentDescription = "Anteprima allegato", tint = AppColors.Accent, modifier = Modifier.size(30.dp))
            }
        }
        Surface(
            color = Color.Black.copy(alpha = 0.65f),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .clickable { onRemove() }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Rounded.Close, contentDescription = "Rimuovi allegato", tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Composer(
    value: String,
    attachments: List<ChatInputAttachment>,
    onValueChange: (String) -> Unit,
    onAttachImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onScanDocument: () -> Unit,
    onRemoveAttachment: (ChatInputAttachment) -> Unit,
    reasoningEffort: String,
    reasoningOptions: List<String>,
    onReasoningChange: (String) -> Unit,
    quickPrompt: String?,
    onQuickPromptConsumed: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isBusy: Boolean,
    isRecordingVoiceNote: Boolean,
    onToggleVoiceNote: () -> Unit
) {

    LaunchedEffect(quickPrompt) {
        val prompt = quickPrompt ?: return@LaunchedEffect
        onValueChange(prompt)
        onSend()
        onQuickPromptConsumed()
    }

    var showSheet by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
            .widthIn(max = 1040.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                containerColor = AppColors.Surface,
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Aggiungi alla chat", color = AppColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    AttachSheetRow(
                        icon = Icons.Rounded.AttachFile,
                        title = "Allega file",
                        subtitle = "Foto, video o documenti dalla galleria",
                        onClick = { showSheet = false; onAttachImage() }
                    )
                    AttachSheetRow(
                        icon = Icons.Rounded.PhotoCamera,
                        title = "Scatta foto",
                        subtitle = "Fotocamera, allegata subito in chat",
                        onClick = { showSheet = false; onTakePhoto() }
                    )
                    AttachSheetRow(
                        icon = Icons.Rounded.Description,
                        title = "Scansiona documento",
                        subtitle = "Scatto singolo nominato scansione",
                        onClick = { showSheet = false; onScanDocument() }
                    )
                    Spacer(modifier = Modifier.navigationBarsPadding().height(12.dp))
                }
            }
        }

        val fontScale = LocalDensity.current.fontScale.coerceIn(0.5f, 2.0f)
        // Miniature FUORI dal canvas del prompt: strip sopra la casella di input.
        if (attachments.isNotEmpty()) {
            ComposerAttachmentStrip(
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = (104 * fontScale).dp, max = (214 * fontScale).dp),
            color = AppColors.Composer,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.dp, AppColors.Border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = (38 * fontScale).dp, max = (138 * fontScale).dp)
                        .padding(vertical = 5.dp)
                ) {
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
                        Text("Chiedi a Hermes", color = AppColors.Faint, fontSize = 16.sp)
                    }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { showSheet = true }
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Apri menu allegati", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    var showReasoningMenu by remember { mutableStateOf(false) }
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clickable { showReasoningMenu = true }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = "Sforzo ragionamento: ${reasoningEffort.ifBlank { "Auto" }}",
                                tint = if (reasoningEffort.isBlank()) AppColors.Muted else AppColors.Accent,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showReasoningMenu,
                            onDismissRequest = { showReasoningMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Auto") },
                                onClick = { showReasoningMenu = false; onReasoningChange("") }
                            )
                            reasoningOptions.forEach { eff ->
                                DropdownMenuItem(
                                    text = { Text(eff) },
                                    onClick = { showReasoningMenu = false; onReasoningChange(eff) }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(enabled = true) { onToggleVoiceNote() }
                    ) {
                        Icon(
                            imageVector = if (isRecordingVoiceNote) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = if (isRecordingVoiceNote) "Ferma registrazione" else "Registra nota vocale",
                            tint = if (isRecordingVoiceNote) Color.Red else Color.White,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    val canSend = (value.isNotBlank() || attachments.isNotEmpty()) && !isBusy
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable {
                                if (isBusy) onStop()
                                else if (canSend) onSend()
                                else onToggleVoiceNote()
                            },
                        color = if (canSend || isBusy) AppColors.Accent else AppColors.Surface,
                        shape = CircleShape,
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = if (isBusy) Icons.Rounded.Stop else if (canSend) Icons.Rounded.ArrowUpward else Icons.Rounded.GraphicEq,
                                contentDescription = if (isBusy) "Interrompi generazione" else if (canSend) "Invia" else "Tocca per parlare",
                                tint = if (canSend || isBusy) Color(0xFF171009) else AppColors.Muted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
