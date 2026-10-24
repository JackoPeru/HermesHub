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
import com.nemoclaw.chat.ui.apple.AppleMotion
import com.nemoclaw.chat.ui.apple.AppleType
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.nemoclaw.chat.features.phone.isPhoneArmed
import com.nemoclaw.chat.features.phone.setPhoneArmed
import com.nemoclaw.chat.features.phone.isPhoneAccessibilityEnabled
import com.nemoclaw.chat.features.phone.openPhoneAccessibilitySettings
import com.nemoclaw.chat.features.phone.loadPhoneLog
import com.nemoclaw.chat.resolveHermesUrl
import com.nemoclaw.chat.loadWakeStatsToday
import com.nemoclaw.chat.wakeStatsLine
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

internal const val SETTINGS_FIELD_MAX_LENGTH = 2048

// Solo stile iOS (mock Impostazioni): helper privati locali, nessuna logica/prefs/stringa toccata.
@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = AppColors.Accent,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Color(0xFF3A3F47),
    uncheckedBorderColor = Color.Transparent
)

@Composable
private fun SettingsHairline() {
    HorizontalDivider(thickness = 0.5.dp, color = AppColors.Border)
}

@Composable
private fun SettingsServerCard(gatewayUrl: String, statusText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF14171C))
            .border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(AppColors.Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Dns,
                contentDescription = null,
                tint = AppColors.Accent,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                gatewayUrl,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(AppColors.Success)
                )
                Text(
                    statusText,
                    color = AppColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = AppColors.Muted,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    gatewaySecret: String?,
    voiceProfile: VoiceProfile,
    onGatewaySecretChanged: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var gatewayUrl by remember(settings.gatewayUrl) { mutableStateOf(settings.gatewayUrl) }
    var localGatewayUrl by remember(settings.localGatewayUrl) { mutableStateOf(settings.localGatewayUrl) }
    var adminBridgeUrl by remember(settings.adminBridgeUrl) { mutableStateOf(settings.adminBridgeUrl) }
    var provider by remember(settings.provider) { mutableStateOf(settings.provider) }
    var inferenceEndpoint by remember(settings.inferenceEndpoint) { mutableStateOf(settings.inferenceEndpoint) }
    var preferredApi by remember(settings.preferredApi) { mutableStateOf(settings.preferredApi) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var voiceModel by remember(settings.voiceModel) { mutableStateOf(settings.voiceModel) }
    var accessMode by remember(settings.accessMode) { mutableStateOf(settings.accessMode) }
    var visualBlocksMode by remember(settings.visualBlocksMode) { mutableStateOf(settings.visualBlocksMode) }
    var videoLibraryPath by remember(settings.videoLibraryPath) { mutableStateOf(settings.videoLibraryPath) }
    var newsLibraryPath by remember(settings.newsLibraryPath) { mutableStateOf(settings.newsLibraryPath) }
    var imageLibraryPath by remember(settings.imageLibraryPath) { mutableStateOf(settings.imageLibraryPath) }
    var extraImagePaths by remember(settings.extraImageLibraryPaths) { mutableStateOf(settings.extraImageLibraryPaths.joinToString("\n")) }
    var apiKey by remember(gatewaySecret) { mutableStateOf(gatewaySecret.orEmpty()) }
    var gpuAppKey by remember { mutableStateOf(loadGpuAppSecret(context).orEmpty()) }
    var fontScale by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)) }
    var showToolCalls by remember(settings.showToolCalls) { mutableStateOf(settings.showToolCalls) }
    var backgroundWork by remember(settings.backgroundWork) { mutableStateOf(settings.backgroundWork) }
    var blockScreenshots by remember(settings.blockScreenshots) { mutableStateOf(settings.blockScreenshots) }
    var autoApprove by remember(settings.autoApprove) { mutableStateOf(settings.autoApprove) }
    var smartFastPath by remember(settings.smartFastPath) { mutableStateOf(settings.smartFastPath) }
    var smartCreate by remember(settings.smartCreate) { mutableStateOf(settings.smartCreate) }
    var smartCreatePrompt by remember(settings.smartCreatePrompt) { mutableStateOf(settings.smartCreatePrompt) }
    var smartEdit by remember(settings.smartEdit) { mutableStateOf(settings.smartEdit) }
    var smartEditPrompt by remember(settings.smartEditPrompt) { mutableStateOf(settings.smartEditPrompt) }
    var smartVideo by remember(settings.smartVideo) { mutableStateOf(settings.smartVideo) }
    var smartVideoPrompt by remember(settings.smartVideoPrompt) { mutableStateOf(settings.smartVideoPrompt) }
    var smartBlankPhoto by remember(settings.smartBlankPhoto) { mutableStateOf(settings.smartBlankPhoto) }
    var showMessageMetrics by remember(settings.showMessageMetrics) { mutableStateOf(settings.showMessageMetrics) }
    var metricTtft by remember(settings.metricTtft) { mutableStateOf(settings.metricTtft) }
    var metricTokensPerSecond by remember(settings.metricTokensPerSecond) { mutableStateOf(settings.metricTokensPerSecond) }
    var metricOutputTokens by remember(settings.metricOutputTokens) { mutableStateOf(settings.metricOutputTokens) }
    var metricPromptTokens by remember(settings.metricPromptTokens) { mutableStateOf(settings.metricPromptTokens) }
    var metricContextTokens by remember(settings.metricContextTokens) { mutableStateOf(settings.metricContextTokens) }
    var metricDuration by remember(settings.metricDuration) { mutableStateOf(settings.metricDuration) }
    var metricAcceptanceRate by remember(settings.metricAcceptanceRate) { mutableStateOf(settings.metricAcceptanceRate) }
    var maxAttachmentMb by remember(settings.maxAttachmentMb) { mutableIntStateOf(settings.maxAttachmentMb.coerceIn(1, 150)) }
    var strictNativeMode by remember(settings.strictNativeMode) { mutableStateOf(settings.strictNativeMode) }
    var demoMode by remember(settings.demoMode) { mutableStateOf(settings.demoMode) }
    var healthSyncEnabled by remember(settings.healthSyncEnabled) { mutableStateOf(settings.healthSyncEnabled) }
    var healthIncludeSteps by remember(settings.healthIncludeSteps) { mutableStateOf(settings.healthIncludeSteps) }
    var healthIncludeSleep by remember(settings.healthIncludeSleep) { mutableStateOf(settings.healthIncludeSleep) }
    var healthIncludeWorkouts by remember(settings.healthIncludeWorkouts) { mutableStateOf(settings.healthIncludeWorkouts) }
    var healthIncludeHeartRate by remember(settings.healthIncludeHeartRate) { mutableStateOf(settings.healthIncludeHeartRate) }
    var voiceName by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.voice) }
    var voiceSpeed by remember(settings.activeProjectId, voiceProfile) { mutableFloatStateOf(voiceProfile.speed) }
    var voiceWakeWord by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.wakeWord) }
    var voiceWakePhrase by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.wakePhrase) }
    var voiceWakeSensitivity by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.wakeSensitivity) }
    var voicePushToTalk by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.pushToTalk) }
    var voiceTranscript by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.showTranscript) }
    var voiceBluetooth by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.bluetooth) }
    var voiceParticleShape by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.particleShape) }
    var reasoningEffort by remember(settings.reasoningEffort) { mutableStateOf(settings.reasoningEffort) }
    var serviceTier by remember(settings.serviceTier) { mutableStateOf(settings.serviceTier) }
    var hermesSessionKey by remember(settings.hermesSessionKey) { mutableStateOf(settings.hermesSessionKey) }
    var modelCatalogStatus by remember { mutableStateOf("Picker Hermes non caricato.") }
    var modelCatalogModels by remember { mutableStateOf<List<HermesModelOption>>(emptyList()) }
    var reasoningLadder by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Pronto.") }
    // Password backup: solo memoria (remember, MAI rememberSaveable/persistita né salvata).
    var backupPassword by remember { mutableStateOf("") }
    var showEraseHealthConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    // Restore backup: solo memoria, mai persistiti. Doppia conferma + report.
    var restoreAttempts by remember { mutableStateOf(0) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var restorePendingJson by remember { mutableStateOf<String?>(null) }
    var restorePendingCount by remember { mutableStateOf(0) }
    var restorePendingIgnoredSettings by remember { mutableIntStateOf(0) }
    var showRestoreReport by remember { mutableStateOf(false) }
    var restoreReportText by remember { mutableStateOf("") }
    var advancedVisible by rememberSaveable { mutableStateOf(false) }
    val wakePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voiceWakeWord = granted
        status = if (granted) {
            "Permesso microfono concesso. Premi Salva per attivare la wake word."
        } else {
            "Permesso microfono negato: wake word non attivata."
        }
    }

    fun currentSettings(scale: Float = fontScale): AppSettings {
        return AppSettings(
            gatewayUrl = gatewayUrl.trim(),
            localGatewayUrl = localGatewayUrl.trim(),
            adminBridgeUrl = hermesRoot(AppSettings(gatewayUrl = gatewayUrl.trim())),
            provider = provider.trim(),
            inferenceEndpoint = inferenceEndpoint.trim(),
            preferredApi = preferredApi.trim(),
            model = model.trim(),
            reasoningEffort = reasoningEffort.trim().lowercase(),
            serviceTier = serviceTier.trim(),
            hermesSessionKey = hermesSessionKey.trim(),
            voiceModel = voiceModel.trim(),
            accessMode = accessMode.trim(),
            visualBlocksMode = visualBlocksMode.trim(),
            videoLibraryPath = videoLibraryPath.trim(),
            newsLibraryPath = newsLibraryPath.trim(),
            imageLibraryPath = imageLibraryPath.trim(),
            extraImageLibraryPaths = parseExtraImageLibraryPaths(extraImagePaths),
            activeProjectId = settings.activeProjectId,
            activeProjectName = settings.activeProjectName,
            fontScale = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
            showToolCalls = showToolCalls,
            backgroundWork = backgroundWork,
            blockScreenshots = blockScreenshots,
            autoApprove = autoApprove,
            smartFastPath = smartFastPath,
            smartCreate = smartCreate,
            smartCreatePrompt = smartCreatePrompt,
            smartEdit = smartEdit,
            smartEditPrompt = smartEditPrompt,
            smartVideo = smartVideo,
            smartVideoPrompt = smartVideoPrompt,
            smartBlankPhoto = smartBlankPhoto,
            showMessageMetrics = showMessageMetrics,
            metricTtft = metricTtft,
            metricTokensPerSecond = metricTokensPerSecond,
            metricOutputTokens = metricOutputTokens,
            metricPromptTokens = metricPromptTokens,
            metricContextTokens = metricContextTokens,
            metricDuration = metricDuration,
            metricAcceptanceRate = metricAcceptanceRate,
            maxAttachmentMb = maxAttachmentMb.coerceIn(1, 150),
            strictNativeMode = strictNativeMode,
            demoMode = demoMode,
            healthSyncEnabled = healthSyncEnabled,
            healthIncludeSteps = healthIncludeSteps,
            healthIncludeSleep = healthIncludeSleep,
            healthIncludeWorkouts = healthIncludeWorkouts,
            healthIncludeHeartRate = healthIncludeHeartRate
        )
    }

    val healthPermissionLauncher = rememberLauncherForActivityResult(HealthSync.permissionRequestContract()) { granted ->
        val candidate = currentSettings().copy(healthSyncEnabled = true)
        if (granted.containsAll(HealthSync.requiredPermissions(candidate))) {
            HealthSync.recordBackgroundGrant(context, granted)
            healthSyncEnabled = true
            onSave(candidate)
            HealthSync.schedule(context, candidate)
            status = "Dati salute autorizzati. Prima sincronizzazione in corso..."
            scope.launch {
                status = when (val result = HealthSync.sync(context)) {
                    is HealthSyncResult.Success -> "Riepilogo salute ${result.summary.date} inviato a Hermes."
                    is HealthSyncResult.Permanent -> result.message
                    is HealthSyncResult.Transient -> "Sincronizzazione da riprovare: ${result.message}"
                    HealthSyncResult.Disabled -> "Sincronizzazione salute disattivata."
                }
            }
        } else {
            healthSyncEnabled = false
            status = "Permessi salute incompleti: nessun dato inviato a Hermes."
        }
    }

    val backupImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            status = "Import backup annullato."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: throw BackupDecryptException("Backup non valido: file illeggibile")
                    // Password impostata -> import v2 con password (portabile, niente Keystore);
                    // vuota -> comportamento attuale (v1 Keystore / legacy plain).
                    val decoded = if (backupPassword.isNotEmpty()) {
                        importWithPassword(raw, backupPassword)
                    } else {
                        decodeBackupPayload(raw, getOrCreateBackupKey())
                    }
                    val json = runCatching { JSONObject(String(decoded, Charsets.UTF_8)) }
                        .getOrElse { throw BackupDecryptException("Backup non valido: payload JSON illeggibile") }
                    val schema = json.optString("schema", "sconosciuto")
                    val count = json.optJSONArray("items")?.length()
                        ?: json.optJSONArray("conversations")?.length()
                        ?: 0
                    "Backup verificato ($schema, $count voci)."
                }.getOrElse { "Import non riuscito: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    val backupRestorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            status = "Ripristino backup annullato."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: throw BackupDecryptException("Backup non valido: file illeggibile")
                    // Reader unificato v2/v1/plain. Password mai loggata, vive solo in memoria.
                    val decoded = decodeBackupPayloadWithPassword(
                        raw,
                        getOrCreateBackupKey(),
                        backupPassword.ifEmpty { null }
                    )
                    val json = runCatching { JSONObject(String(decoded, Charsets.UTF_8)) }
                        .getOrElse { throw BackupDecryptException("Backup non valido: payload JSON illeggibile") }
                    Triple(json.toString(), countRestoreEntries(json), countIgnoredRestoreSettings(json))
                }
            }
            outcome.onSuccess { (jsonString, count, ignoredSettings) ->
                restoreAttempts = 0
                restorePendingJson = jsonString
                restorePendingCount = count
                restorePendingIgnoredSettings = ignoredSettings
                showRestoreConfirm = true
                status = "Backup pronto: $count voci importabili, $ignoredSettings impostazioni ignorate. " +
                    "Server, credenziali, connessioni e privacy/sicurezza restano invariate."
            }.onFailure { error ->
                val message = error.message ?: error.javaClass.simpleName
                // Tre tentativi per password v2 poi errore esplicito, mai loggare la password.
                if (message.contains("password errata", ignoreCase = true) ||
                    message.contains("password richiesta", ignoreCase = true)
                ) {
                    restoreAttempts += 1
                    status = if (restoreAttempts >= 3) {
                        restoreAttempts = 0
                        "Ripristino bloccato dopo tre tentativi: password errata. Re-inserisci la password e riprova."
                    } else {
                        "Ripristino non riuscito (tentativo $restoreAttempts/3): $message"
                    }
                } else {
                    status = "Ripristino non riuscito: $message"
                }
            }
        }
    }

    if (showEraseHealthConfirm) {
        AlertDialog(
            onDismissRequest = { showEraseHealthConfirm = false },
            title = { Text("Eliminare riepiloghi salute?") },
            text = { Text("Verranno eliminati dal gateway Hermes tutti i riepiloghi salute sincronizzati. I dati in Samsung Health e Health Connect non vengono toccati.") },
            confirmButton = {
                TextButton(onClick = {
                    showEraseHealthConfirm = false
                    scope.launch {
                        when (val result = HealthSync.eraseAllFromHermes(context)) {
                            HealthEraseResult.Success -> {
                                healthSyncEnabled = false
                                status = "Riepiloghi salute eliminati da Hermes. Sincronizzazione disattivata."
                            }
                            is HealthEraseResult.Failed -> status = result.message
                        }
                    }
                }) { Text("Elimina", color = AppColors.Error) }
            },
            dismissButton = { TextButton(onClick = { showEraseHealthConfirm = false }) { Text("Annulla", color = AppColors.Accent) } }
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = {
                showRestoreConfirm = false
                restorePendingJson = null
            },
            title = { Text("Ripristinare backup?") },
            text = { Text("Voci importabili: $restorePendingCount. Impostazioni ignorate: $restorePendingIgnoredSettings. " +
                "Il backup ripristina solo preferenze di visualizzazione e dati archiviati. Indirizzi server, credenziali, " +
                "connessioni profilo e opzioni privacy/sicurezza restano invariate.") },
            confirmButton = {
                Button(onClick = {
                    val pending = restorePendingJson
                    showRestoreConfirm = false
                    restorePendingJson = null
                    if (pending == null) {
                        status = "Ripristino annullato: backup vuoto."
                    } else {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    restoreLocalBackup(context, JSONObject(pending), backupPassword.ifEmpty { null })
                                }
                            }
                            result.onSuccess { report ->
                                restoreReportText = "Ripristino completato: ${report.applied} impostazioni, " +
                                    "${report.conversationsMerged} conversazioni, ${report.tasksMerged} task, " +
                                    "${report.workspaceMerged} workspace. Impostazioni ignorate: ${report.skippedSettings} " +
                                    "(incluse ${report.skippedSensitive} chiavi sensibili). Endpoint, credenziali e privacy/sicurezza invariate."
                                status = restoreReportText
                                showRestoreReport = true
                            }.onFailure { error ->
                                restoreReportText = "Ripristino non riuscito: ${error.message ?: error.javaClass.simpleName}"
                                status = restoreReportText
                                showRestoreReport = true
                            }
                        }
                    }
                },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = Color(0xFF171009)
                    )
                ) { Text("Ripristina", color = Color(0xFF171009)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    restorePendingJson = null
                    status = "Ripristino annullato."
                }) { Text("Annulla", color = AppColors.Accent) }
            }
        )
    }

    if (showRestoreReport) {
        AlertDialog(
            onDismissRequest = { showRestoreReport = false },
            title = { Text("Report ripristino") },
            text = { Text(restoreReportText) },
            confirmButton = {
                TextButton(onClick = { showRestoreReport = false }) {
                    Text("Chiudi", color = AppColors.Accent)
                }
            },
            dismissButton = null
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1C1F26))
                    .border(0.5.dp, AppColors.Border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = AppColors.Accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Impostazioni", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Impostazioni salvate sul dispositivo. Le nuove installazioni non includono endpoint o credenziali preconfigurati.",
                    color = AppColors.Muted,
                    fontSize = AppleType.HeroSubtitle
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        SettingsServerCard(gatewayUrl = gatewayUrl, statusText = status)
        Spacer(modifier = Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                SettingsSectionHeader(title = "Connessione", subtitle = "Il tuo server Hermes.")
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14171C)).border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Dove vive il tuo server e con quale chiave parlarci. Senza questi due, niente funziona.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        SettingsField("Indirizzo server Hermes", gatewayUrl, { gatewayUrl = it })
                        SettingsField("URL locale casa (opzionale)", localGatewayUrl, { localGatewayUrl = it })
                        Text(
                            "Se a casa: accesso diretto veloce senza relay",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        SettingsHairline()
                        SettingsPasswordField("API key Hermes", apiKey, { apiKey = it })
                        SettingsPasswordField("Chiave app GPU/manager", gpuAppKey, { gpuAppKey = it })
                        Text(
                            "Seconda chiave (solo letture + coda job + laya). L'agente non la conosce: senza, Comfy/GPU e laya non rispondono.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        SettingsHairline()
                        SettingsField("Cartella video Hermes (dal server)", videoLibraryPath, { }, readOnly = true)
                        SettingsField("Cartella news Hermes", newsLibraryPath, { newsLibraryPath = it })
                        SettingsField("Cartella immagini Hermes (dal server)", imageLibraryPath, { }, readOnly = true)
                        SettingsField("Cartelle immagini extra (una per riga)", extraImagePaths, { extraImagePaths = it })
                        Text(
                            "Le cartelle immagini dicono all'app dove il server tiene le immagini generate. Quella principale e fissata dal server, le extra le aggiungi tu.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        Text(
                            "Le cartelle dicono all'app dove il server tiene video e news. Quella video e fissata dal server.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        SettingsHairline()
                        SettingsField("Limite allegati file (MB, max 150)", maxAttachmentMb.toString(), { value ->
                            maxAttachmentMb = value.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 150) ?: maxAttachmentMb
                        })
                        Text(
                            "Dimensione massima dei file che puoi allegare in chat. Oltre, l'invio viene rifiutato.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                }
            }
            item {
                SettingsSectionHeader(title = "Voce e chiamata", subtitle = "Voci, velocita, wake word e Bluetooth.")
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14171C)).border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Profilo progetto: ${settings.activeProjectName.ifBlank { "Generale" }}",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        SettingsField("Modello dedicato Voce", voiceModel, { voiceModel = it })
                        Text("VOCE KOKORO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SupportedVoiceNames.forEach { candidate ->
                                Button(
                                    onClick = { voiceName = candidate },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (voiceName == candidate) AppColors.Accent else AppColors.Composer,
                                        contentColor = if (voiceName == candidate) Color(0xFF171009) else Color.White
                                    )
                                ) {
                                    Text(candidate, color = if (voiceName == candidate) Color(0xFF171009) else Color.White)
                                }
                            }
                            TextButton(onClick = {
                                status = "Genero anteprima voce..."
                                scope.launch {
                                    status = runCatching {
                                        previewVoiceProfile(context, currentSettings(), apiKey, voiceName, voiceSpeed)
                                        "Anteprima completata."
                                    }.getOrElse { "Anteprima non disponibile: ${it.message ?: it.javaClass.simpleName}" }
                                }
                            }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Anteprima voce", tint = AppColors.Accent)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Anteprima", color = AppColors.Accent)
                            }
                        }
                        Text("Velocita: ${String.format(java.util.Locale.ROOT, "%.2f", voiceSpeed)}x", color = Color.White)
                        Slider(
                            value = voiceSpeed,
                            onValueChange = { voiceSpeed = it },
                            valueRange = 0.75f..1.35f
                        )
                        Text("FORMA PARTICELLE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SupportedParticleShapes.forEach { candidate ->
                                Button(
                                    onClick = { voiceParticleShape = candidate },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (voiceParticleShape == candidate) AppColors.Accent else AppColors.Composer,
                                        contentColor = if (voiceParticleShape == candidate) Color(0xFF171009) else Color.White
                                    )
                                ) {
                                    Text(particleShapeLabel(candidate), color = if (voiceParticleShape == candidate) Color(0xFF171009) else Color.White)
                                }
                            }
                        }
                        Text("PAROLA DI ATTIVAZIONE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SupportedWakePhrases.forEach { candidate ->
                                Button(
                                    onClick = { voiceWakePhrase = candidate },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (voiceWakePhrase.equals(candidate, ignoreCase = true)) AppColors.Accent else AppColors.Composer,
                                        contentColor = if (voiceWakePhrase.equals(candidate, ignoreCase = true)) Color(0xFF171009) else Color.White
                                    )
                                ) {
                                    Text(candidate, color = if (voiceWakePhrase.equals(candidate, ignoreCase = true)) Color(0xFF171009) else Color.White)
                                }
                            }
                        }
                        SettingsField("Wake word personalizzata", voiceWakePhrase, { voiceWakePhrase = it })
                        Text("SENSIBILITÀ ASCOLTO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Permissiva" to 0.6f, "Normale" to 1.0f, "Selettiva" to 1.6f).forEach { (label, value) ->
                                Button(
                                    onClick = { voiceWakeSensitivity = value },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (voiceWakeSensitivity == value) AppColors.Accent else AppColors.Composer,
                                        contentColor = if (voiceWakeSensitivity == value) Color(0xFF171009) else Color.White
                                    )
                                ) {
                                    Text(label, color = if (voiceWakeSensitivity == value) Color(0xFF171009) else Color.White)
                                }
                            }
                        }
                        Text(
                            "Permissiva sente di più (più falsi), selettiva solo voci chiare. Sotto: ascolto e attivazioni di oggi.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        Text(
                            wakeStatsLine(loadWakeStatsToday(context)),
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        MetricSwitch("Abilita wake word", voiceWakeWord) { enabled ->
                            if (enabled && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                wakePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                voiceWakeWord = enabled
                            }
                        }
                        Text(
                            "Quando Hermes Hub è aperto, la frase scelta porta l'app in primo piano e avvia una chiamata.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        MetricSwitch("Tieni premuto per parlare", voicePushToTalk) { voicePushToTalk = it }
                        MetricSwitch("Mostra trascrizione", voiceTranscript) { voiceTranscript = it }
                        MetricSwitch("Audio su Bluetooth", voiceBluetooth) { voiceBluetooth = it }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsSectionHeader(title = "Avanzate", subtitle = "Solo se sai cosa stai toccando.", modifier = Modifier.weight(1f))
                    IconButton(onClick = { advancedVisible = !advancedVisible }) {
                        Icon(
                            if (advancedVisible) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (advancedVisible) "Nascondi avanzate" else "Mostra avanzate",
                            tint = AppColors.Accent
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = advancedVisible,
                    enter = AppleMotion.enter(),
                    exit = AppleMotion.exit()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14171C)).border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Parametri letti dal server: cambiali solo se Hermes non risponde o su indicazione specifica.",
                                color = AppColors.Muted,
                                fontSize = 12.sp
                            )
                            SettingsField("Provider", provider, { provider = it })
                            SettingsField("Endpoint inferenza server", inferenceEndpoint, { inferenceEndpoint = it })
                            SettingsField("Protocollo preferito", preferredApi, { preferredApi = it })
                            SettingsField("Modello", model, { model = it })
                            SettingsField("Reasoning effort (vuoto = default server)", reasoningEffort, { reasoningEffort = it.lowercase().trim() })
                            SettingsField("Service tier (vuoto = default)", serviceTier, { serviceTier = it })
                            SettingsField("Session key stabile (vuoto = disattivata)", hermesSessionKey, { hermesSessionKey = it })
                            Text(
                                "Session key: identifica le tue sessioni sul server tra un avvio e l'altro. Lasciala vuota se non ti serve.",
                                color = AppColors.Muted,
                                fontSize = 12.sp
                            )
                            Text(
                                if (reasoningLadder.isEmpty()) "Reasoning: ladder non ancora letta da /v1/capabilities. Premi sotto per caricare picker e ladder."
                                else "Reasoning supportati dal server: ${reasoningLadder.joinToString(", ")}.",
                                color = AppColors.Muted, fontSize = 12.sp
                            )
                            Text(modelCatalogStatus, color = AppColors.Muted, fontSize = 12.sp)
                            TextButton(onClick = {
                                scope.launch {
                                    modelCatalogStatus = "Leggo capabilities e catalogo Hermes..."
                                    try {
                                        val capsBody = runCatching { httpGet("${gatewayUrl.trim().trimEnd('/')}/v1/capabilities", apiKey.takeIf { it.isNotBlank() }) }.getOrNull()
                                        val caps = capsBody?.let { parseHermesCapabilities(it) }
                                        if (caps != null && caps.reasoningEfforts.isNotEmpty()) reasoningLadder = caps.reasoningEfforts
                                        // Primario /api/model/options, fallback /v1/models (nessun catalogo hardcodato).
                                        val optionsBody = runCatching { httpGet("${gatewayUrl.trim().trimEnd('/')}/api/model/options", apiKey.takeIf { it.isNotBlank() }) }.getOrNull()
                                        if (optionsBody != null && !optionsBody.contains("\"error\"", ignoreCase = true)) {
                                            val catalog = parseModelOptionsPayload(optionsBody)
                                            modelCatalogModels = catalog.models.take(200)
                                            modelCatalogStatus = if (catalog.models.isEmpty()) "Model options vuoto: uso /v1/models come fallback."
                                            else "${catalog.models.size} modelli da /api/model/options."
                                            if (catalog.models.isEmpty()) throw IllegalStateException("empty")
                                            else return@launch
                                        }
                                        val fallbackBody = runCatching { httpGet("${gatewayUrl.trim().trimEnd('/')}/v1/models", apiKey.takeIf { it.isNotBlank() }) }.getOrNull()
                                        if (fallbackBody != null) {
                                            val catalog = parseV1ModelsFallback(fallbackBody)
                                            modelCatalogModels = catalog.models.take(200)
                                            modelCatalogStatus = "${catalog.models.size} modelli da /v1/models (fallback)."
                                        } else {
                                            modelCatalogStatus = "Catalogo non disponibile: verifica gateway e API key."
                                        }
                                    } catch (ex: Exception) {
                                        modelCatalogStatus = "Errore picker: ${ex.message ?: ex.javaClass.simpleName}"
                                    }
                                }
                            }) {
                                Icon(Icons.Rounded.Download, contentDescription = "Carica picker Hermes", tint = AppColors.Accent)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Carica modelli", color = AppColors.Accent)
                            }
                            if (modelCatalogModels.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    modelCatalogModels.take(12).forEach { opt ->
                                        // Riga cliccabile: seleziona il modello nel campo Modello qui sotto.
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { model = opt.id },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                if (model == opt.id) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = if (model == opt.id) "Modello selezionato" else "Seleziona ${opt.displayName}",
                                                tint = if (model == opt.id) AppColors.Accent else AppColors.Muted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                "${opt.displayName} — ${opt.provider.ifBlank { "default" }}${if (opt.contextWindow != null) " — ctx ${opt.contextWindow}" else ""}${if (opt.reasoningSupported) " — reasoning" else ""}${opt.warning?.let { " — $it" }.orEmpty()}",
                                                color = if (model == opt.id) Color.White else AppColors.Muted,
                                                fontSize = 12.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    if (modelCatalogModels.size > 12) Text("+${modelCatalogModels.size - 12} altri...", color = AppColors.Muted, fontSize = 12.sp)
                                }
                            }
                            SettingsField("Tipo rete", accessMode, { accessMode = it })
                            Text(
                                "Etichetta che descrive come raggiungi il server (es. Tailscale/LAN). Non cambia il comportamento, solo promemoria.",
                                color = AppColors.Muted,
                                fontSize = 12.sp
                            )
                    }
                }
            }
            item {
                SettingsSectionHeader(title = "Chat e risposte", subtitle = "Come si presenta la conversazione.")
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14171C)).border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tool call in chat", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(checked = showToolCalls, onCheckedChange = { showToolCalls = it }, colors = settingsSwitchColors())
                        }
                        Text("ON = mostra il pannello compatto degli strumenti usati da Hermes. Output lunghi restano collassati.", color = AppColors.Muted, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Metriche messaggi", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(checked = showMessageMetrics, onCheckedChange = { showMessageMetrics = it }, colors = settingsSwitchColors())
                        }
                        Text("ON = mostra sotto ogni messaggio tempi e token (TTFT, token, velocita).", color = AppColors.Muted, fontSize = 12.sp)
                        Text("DETTAGLIO METRICHE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        MetricSwitch("Tempo primo token", metricTtft) { metricTtft = it }
                        MetricSwitch("Token/sec", metricTokensPerSecond) { metricTokensPerSecond = it }
                        MetricSwitch("Token output", metricOutputTokens) { metricOutputTokens = it }
                        MetricSwitch("Token input", metricPromptTokens) { metricPromptTokens = it }
                        MetricSwitch("Contesto", metricContextTokens) { metricContextTokens = it }
                        MetricSwitch("Durata totale", metricDuration) { metricDuration = it }
                        MetricSwitch("Acceptance rate (speculative)", metricAcceptanceRate) { metricAcceptanceRate = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Solo modalità nativa", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(checked = strictNativeMode, onCheckedChange = { strictNativeMode = it }, colors = settingsSwitchColors())
                        }
                        Text(
                            "ON = parla solo il protocollo Hermes nativo. Se fallisce, errore esplicito invece di ripiegare su Chat Completions.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Risposta offline di emergenza", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(checked = demoMode, onCheckedChange = { demoMode = it }, colors = settingsSwitchColors())
                        }
                        Text(
                            "ON = se il server non risponde, l'app prova a rispondere da sola in locale (qualita limitata, niente tool).",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        SettingsField("Blocchi visuali (auto / always / never)", visualBlocksMode, { visualBlocksMode = it })
                        Text(
                            "auto = immagini e schede quando utili; always = sempre; never = mai, solo testo.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                }
            }
            item {
                SettingsSectionHeader(title = "Aspetto", subtitle = "Dimensione del testo in app.")
            }
            item {
                FontScaleControl(
                    value = fontScale,
                    onValueChange = { scale ->
                        fontScale = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                        status = "Dimensione caratteri: ${(fontScale * 100).toInt()}%. Premi Salva per applicare."
                    }
                )
            }
            item {
                SettingsSectionHeader(title = "Automazione", subtitle = "Lavoro senza mani: background, approvazioni, media.")
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14171C)).border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Lavoro in background", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(checked = backgroundWork, onCheckedChange = { backgroundWork = it }, colors = settingsSwitchColors())
                        }
                        Text("ON = se esci dall'app Hermes continua il lavoro sul gateway e ti avvisa alla fine.", color = AppColors.Muted, fontSize = 12.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("AUTO-APPROVAZIONE RUN", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoApproveChip("Chiedi", "off", autoApprove) { autoApprove = it }
                        AutoApproveChip("Sessione", "session", autoApprove) { autoApprove = it }
                        AutoApproveChip("Sempre", "always", autoApprove) { autoApprove = it }
                    }
                    Text(
                        "Chiedi = notifica e card ogni volta. Sessione/Sempre = approva da solo (mai deny automatico); ogni auto-approvazione resta visibile in chat e notifica.",
                        color = AppColors.Muted, fontSize = 12.sp
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("FAST PATH MEDIA", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                        Switch(checked = smartFastPath, onCheckedChange = { smartFastPath = it }, colors = settingsSwitchColors())
                    }
                    Text(
                        "Con allegati, prova prima la via veloce (triage + prompt + Comfy) senza turn agentico. Spento = sempre chat normale.",
                        color = AppColors.Muted, fontSize = 12.sp
                    )
                    SmartCaseRow(
                        title = "1. Crea immagine",
                        subtitle = "Prompt vuoto = auto via una chiamata LLM; con {testo} = template; senza placeholder = fisso, zero LLM.",
                        enabled = smartCreate,
                        onEnabledChange = { smartCreate = it },
                        prompt = smartCreatePrompt,
                        onPromptChange = { smartCreatePrompt = it }
                    )
                    SmartCaseRow(
                        title = "2. Modifica foto",
                        subtitle = "Come sopra, per le modifiche alla foto allegata.",
                        enabled = smartEdit,
                        onEnabledChange = { smartEdit = it },
                        prompt = smartEditPrompt,
                        onPromptChange = { smartEditPrompt = it }
                    )
                    SmartCaseRow(
                        title = "3. Video da foto",
                        subtitle = "Come sopra, per animare la foto allegata.",
                        enabled = smartVideo,
                        onEnabledChange = { smartVideo = it },
                        prompt = smartVideoPrompt,
                        onPromptChange = { smartVideoPrompt = it }
                    )
                    Text("4. DOMANDE SULLA FOTO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        "Vanno sempre in chat normale: nessuna generazione, nessun fast path.",
                        color = AppColors.Muted, fontSize = 12.sp
                    )
                    Text("5. FOTO SENZA ISTRUZIONI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoApproveChip("Chat normale", "chat", smartBlankPhoto) { smartBlankPhoto = it }
                        AutoApproveChip("Video preview", "video", smartBlankPhoto) { smartBlankPhoto = it }
                    }
                } // Column fast path
                } // Column Automazione
            } // item Automazione
            item {
                SettingsSectionHeader(title = "Controllo telefono", subtitle = "L'agente opera il telefono solo se armato.")
            }
            item {
                val phoneArmedState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(isPhoneArmed(context)) }
                val phoneAccessState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(isPhoneAccessibilityEnabled(context)) }
                val phoneLogState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(loadPhoneLog(context)) }
                fun refreshPhone() {
                    phoneArmedState.value = isPhoneArmed(context)
                    phoneAccessState.value = isPhoneAccessibilityEnabled(context)
                    phoneLogState.value = loadPhoneLog(context)
                }
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14171C)).border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Arma controllo remoto", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = phoneArmedState.value,
                            onCheckedChange = {
                                setPhoneArmed(context, it)
                                refreshPhone()
                            },
                            colors = settingsSwitchColors()
                        )
                    }
                    Text(
                        "Arma = il telefono esegue i comandi in coda. Kill switch: disarma qui o dalla notifica STOP.",
                        color = AppColors.Muted,
                        fontSize = 12.sp
                    )
                    Text(
                        "Accesso accessibilità: " + if (phoneAccessState.value) "concesso." else "mancante: apri le impostazioni e attivalo.",
                        color = if (phoneAccessState.value) AppColors.Success else AppColors.Error,
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            openPhoneAccessibilitySettings(context)
                        }) { Text("Accessibilità") }
                        TextButton(onClick = { refreshPhone() }) { Text("Aggiorna", color = AppColors.Accent) }
                    }
                    if (phoneLogState.value.isNotEmpty()) {
                        Text("Ultimi comandi", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        phoneLogState.value.take(10).forEach { line ->
                            Text(line, color = AppColors.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            item {
                SettingsSectionHeader(title = "Privacy e dati", subtitle = "Screenshot, backup e ripristino.")
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14171C)).border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Blocca screenshot", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(checked = blockScreenshots, onCheckedChange = { blockScreenshots = it }, colors = settingsSwitchColors())
                        }
                        Text("ON = impedisce screenshot e registrazioni dell'app (anche i tuoi). Riavvia l'app per applicare.", color = AppColors.Muted, fontSize = 12.sp)
                        SettingsPasswordField(
                            "Password backup (vuota = chiave dispositivo)",
                            backupPassword,
                            { backupPassword = it }
                        )
                        Text(
                            "Se impostata: export cifrato con password (lo riapri anche su altri dispositivi). Vuota: usa la chiave del telefono.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    status = runCatching {
                                        if (backupPassword.isNotEmpty()) exportLocalBackupWithPassword(context, backupPassword)
                                        else exportLocalBackup(context)
                                    }.getOrElse { "Backup non riuscito: ${it.message ?: it.javaClass.simpleName}" }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.Accent,
                                    contentColor = Color(0xFF171009)
                                )
                            ) { Text("Esporta", color = Color(0xFF171009)) }
                            TextButton(onClick = { backupImportPicker.launch(arrayOf("application/json")) }) { Text("Verifica", color = AppColors.Accent) }
                            TextButton(onClick = { backupRestorePicker.launch(arrayOf("application/json")) }) { Text("Ripristina", color = AppColors.Accent) }
                        }
                        Text(
                            "Esporta = salva una copia. Verifica = controlla un file senza toccare nulla. Ripristina = sovrascrive le impostazioni attuali (chiede conferma).",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                }
            }
            item {
                SettingsSectionHeader(title = "Salute", subtitle = "Dati dal Galaxy Watch.")
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14171C)).border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Watch → Samsung Health → Health Connect → Hermes. Solo riepiloghi giornalieri; nessun battito grezzo viene salvato o inviato.",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        MetricSwitch("Sincronizzazione salute", healthSyncEnabled) { enabled -> healthSyncEnabled = enabled }
                        MetricSwitch("Passi e calorie attive", healthIncludeSteps) { healthIncludeSteps = it }
                        MetricSwitch("Sonno", healthIncludeSleep) { healthIncludeSleep = it }
                        MetricSwitch("Allenamenti", healthIncludeWorkouts) { healthIncludeWorkouts = it }
                        MetricSwitch("Frequenza cardiaca aggregata", healthIncludeHeartRate) { healthIncludeHeartRate = it }
                        Text(HealthSync.sdkStatus(context), color = AppColors.Muted, fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val candidate = currentSettings().copy(healthSyncEnabled = true)
                                val error = validateHttpUrl(candidate.gatewayUrl, "Hermes API URL")
                                if (error != null) {
                                    status = error
                                } else if (!HealthSync.sdkStatus(context).startsWith("Health Connect disponibile")) {
                                    status = HealthSync.sdkStatus(context)
                                } else {
                                    healthPermissionLauncher.launch(HealthSync.permissionsForRequest(context, candidate))
                                }
                            }) { Text("Collega", color = AppColors.Accent) }
                            TextButton(onClick = {
                                if (!HealthSync.openSettings(context)) status = "Impossibile aprire Health Connect."
                            }) { Text("Gestisci", color = AppColors.Accent) }
                            TextButton(onClick = {
                                val candidate = currentSettings()
                                if (!candidate.healthSyncEnabled) {
                                    status = "Attiva e autorizza prima la sincronizzazione salute."
                                } else {
                                    onSave(candidate)
                                    HealthSync.schedule(context, candidate)
                                    status = "Sincronizzazione salute in corso..."
                                    scope.launch {
                                        status = when (val result = HealthSync.sync(context)) {
                                            is HealthSyncResult.Success -> "Riepilogo salute ${result.summary.date} inviato a Hermes."
                                            is HealthSyncResult.Permanent -> result.message
                                            is HealthSyncResult.Transient -> "Sincronizzazione da riprovare: ${result.message}"
                                            HealthSyncResult.Disabled -> "Sincronizzazione salute disattivata."
                                        }
                                    }
                                }
                            }) { Text("Sincronizza", color = AppColors.Accent) }
                            TextButton(onClick = { showEraseHealthConfirm = true }) { Text("Elimina", color = AppColors.Error) }
                        }
                }
            }
            item {
                Text(
                        text = status,
                        color = AppColors.Muted,
                        fontSize = 12.sp
                    )
            }
            item {
                SettingsSectionHeader(title = "Azioni e manutenzione", subtitle = "Salva, verifica, reset.")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = {
                            val candidate = currentSettings()
                            val error = validateSettings(candidate)
                            if (error == null) {
                                val candidateSecret = apiKey
                                val candidateVoiceProfile = VoiceProfile(
                                    voice = voiceName,
                                    speed = voiceSpeed,
                                    wakeWord = voiceWakeWord,
                                    wakePhrase = normalizeWakePhrase(voiceWakePhrase),
                                    wakeSensitivity = voiceWakeSensitivity,
                                    pushToTalk = voicePushToTalk,
                                    showTranscript = voiceTranscript,
                                    bluetooth = voiceBluetooth,
                                    particleShape = voiceParticleShape
                                )
                                scope.launch {
                                    val saved = withContext(Dispatchers.IO) {
                                        val secretSaved = saveGatewaySecret(context, candidateSecret)
                                        val gpuSaved = saveGpuAppSecret(context, gpuAppKey)
                                        if (secretSaved) {
                                            saveVoiceProfile(
                                                context,
                                                settings.activeProjectId,
                                                candidateVoiceProfile
                                            )
                                        }
                                        secretSaved && gpuSaved
                                    }
                                    if (!saved) {
                                        status = "Chiavi non salvate: Android Keystore non disponibile. Credenziali non scritte in chiaro."
                                    } else {
                                        onGatewaySecretChanged()
                                        onSave(candidate)
                                        HealthSync.schedule(context, candidate)
                                        status = "Impostazioni e profilo voce salvati."
                                    }
                                }
                            } else {
                                status = error
                            }
                        },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Accent,
                                contentColor = Color(0xFF171009)
                            )
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = "Salva impostazioni", tint = Color(0xFF171009))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Salva", color = Color(0xFF171009), fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = {
                            val candidate = currentSettings()
                            val error = validateHttpUrl(candidate.gatewayUrl, "Hermes API URL")
                            if (error != null) {
                                status = error
                                return@TextButton
                            }
                        status = "Leggo capabilities Hermes..."
                        scope.launch {
                            status = runCatching { httpGet(resolveHermesUrl(candidate, "/v1/capabilities"), apiKey) }
                                .getOrElse { "Capabilities non leggibili: ${it.message ?: it.javaClass.simpleName}" }
                        }
                        }) {
                            Text("Capabilities", color = AppColors.Accent)
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = {
                            apiKey = ""
                            scope.launch {
                                val removed = withContext(Dispatchers.IO) { saveGatewaySecret(context, null) }
                                status = if (removed) {
                                    onGatewaySecretChanged()
                                    "API key rimossa."
                                } else {
                                    "API key non rimossa: Android Keystore non disponibile."
                                }
                            }
                        }) {
                            Text("Rimuovi key", color = AppColors.Error)
                        }
                        TextButton(onClick = {
                            val error = validateHttpUrl(gatewayUrl, "Indirizzo server Hermes")
                            if (error != null) {
                                status = error
                                return@TextButton
                            }

                            val healthUrl = "${hermesRoot(AppSettings(gatewayUrl = gatewayUrl.trim()))}/health"
                            status = "Test: $healthUrl"
                            scope.launch {
                                status = testGateway(healthUrl, apiKey)
                            }
                        }) {
                            Text("Test Hermes", color = AppColors.Accent)
                        }
                        TextButton(onClick = { showResetConfirm = true }) {
                            Text("Reset", color = AppColors.Error)
                        }
                        if (showResetConfirm) {
                            AlertDialog(
                                onDismissRequest = { showResetConfirm = false },
                                title = { Text("Ripristinare tutto?") },
                                 text = { Text("Verranno rimossi API key e secret connessioni bot (Keystore), impostazioni, profilo voce, archivio conversazioni e task locali. I backup esterni non vengono toccati. L'operazione non si può annullare.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showResetConfirm = false
                                        apiKey = ""
                                        val defaults = VoiceProfile()
                                        voiceName = defaults.voice
                                        voiceSpeed = defaults.speed
                                        voiceWakeWord = defaults.wakeWord
                                        voiceWakePhrase = defaults.wakePhrase
                                        voicePushToTalk = defaults.pushToTalk
                                        voiceTranscript = defaults.showTranscript
                                        voiceBluetooth = defaults.bluetooth
                                        voiceParticleShape = defaults.particleShape
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                saveVoiceProfile(context, settings.activeProjectId, defaults)
                                            }
                                            onReset()
                                        }
                                    }) { Text("Ripristina", color = AppColors.Error) }
                                },
                                dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Annulla", color = AppColors.Accent) } }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FontScaleControl(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    var editingPercent by remember { mutableStateOf(false) }
    var percentText by remember { mutableStateOf("${(value * 100).toInt()}") }

    fun commitPercent(raw: String) {
        val parsed = raw
            .filter { it.isDigit() || it == ',' || it == '.' }
            .replace(',', '.')
            .toFloatOrNull()
        val next = ((parsed ?: (value * 100f)) / 100f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        editingPercent = false
        percentText = "${(next * 100).toInt()}"
        onValueChange(next)
    }

    LaunchedEffect(value, editingPercent) {
        if (!editingPercent) percentText = "${(value * 100).toInt()}"
    }

    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14171C)).border(0.5.dp, AppColors.Border, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DIMENSIONE CARATTERI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                if (editingPercent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = percentText,
                            onValueChange = { next ->
                                if (next.contains('\n') || next.contains('\r')) {
                                    commitPercent(next)
                                } else {
                                    percentText = next.filter { it.isDigit() || it == ',' || it == '.' }.take(5)
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = AppColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.End),
                            cursorBrush = SolidColor(AppColors.Accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { commitPercent(percentText) }),
                            modifier = Modifier.width(54.dp)
                        )
                        Text("%", color = AppColors.Accent, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(
                        "${(value * 100).toInt()}%",
                        color = AppColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            percentText = "${(value * 100).toInt()}"
                            editingPercent = true
                        }
                    )
                }
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = MIN_FONT_SCALE..MAX_FONT_SCALE
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ANTEPRIMA TESTO CONVERSAZIONE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Questo e' il modo in cui leggerai chat, sezioni e impostazioni.", color = AppColors.Muted, fontSize = 13.sp)
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsField(label: String, value: String, onValueChange: (String) -> Unit, readOnly: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = AppColors.Muted, fontSize = 13.sp)
        TextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = { v -> onValueChange(v.take(SETTINGS_FIELD_MAX_LENGTH)) },
            readOnly = readOnly,
            shape = RoundedCornerShape(12.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AppColors.Composer,
                unfocusedContainerColor = AppColors.Composer,
                disabledContainerColor = AppColors.Composer,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedIndicatorColor = AppColors.Accent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = AppColors.Muted, fontSize = 13.sp)
        TextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = { v -> onValueChange(v.take(SETTINGS_FIELD_MAX_LENGTH)) },
            shape = RoundedCornerShape(12.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (visible) "Nascondi chiave" else "Mostra chiave",
                        tint = AppColors.Muted
                    )
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AppColors.Composer,
                unfocusedContainerColor = AppColors.Composer,
                disabledContainerColor = AppColors.Composer,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedIndicatorColor = AppColors.Accent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

@Composable
internal fun MetricSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = settingsSwitchColors())
    }
}

@Composable
internal fun AutoApproveChip(label: String, value: String, current: String, onSelect: (String) -> Unit) {
    val selected = current == value
    Surface(
        color = if (selected) AppColors.Accent else AppColors.Elevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.5.dp, if (selected) AppColors.Accent else AppColors.Border),
        modifier = Modifier.clickable { onSelect(value) }
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF171009) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun SettingsSectionHeader(modifier: Modifier = Modifier, title: String, subtitle: String = "") {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.uppercase(java.util.Locale.ROOT), color = AppColors.Muted, fontSize = AppleType.RowTitleSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (subtitle.isNotBlank()) Text(subtitle, color = AppColors.Muted, fontSize = AppleType.Footnote)
    }
}

@Composable
internal fun SmartCaseRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = onEnabledChange, colors = settingsSwitchColors())
        }
        Text(subtitle, color = AppColors.Muted, fontSize = 12.sp)
        SettingsField("Prompt incollato (vuoto = auto, {testo} = template)", prompt, onPromptChange)
    }
}

internal fun validateSettings(settings: AppSettings): String? {
    return validateHttpUrl(settings.gatewayUrl, "Hermes API URL")
        ?: validateRequired(settings.provider, "Provider")
        ?: validateHttpUrl(settings.inferenceEndpoint, "Endpoint API")
        ?: validatePreferredApi(settings.preferredApi)
        ?: validateRequired(settings.model, "Modello")
        ?: validateRequired(settings.voiceModel, "Modello Voce")
        ?: validateRequired(settings.accessMode, "Tipo rete")
        ?: validateVisualBlocksMode(settings.visualBlocksMode)
}

internal fun validateVisualBlocksMode(value: String): String? {
    return if (value == "auto" || value == "always" || value == "never") null else "Modalita visuale deve essere auto, always o never."
}

internal fun validatePreferredApi(value: String): String? {
    return if (value == "hermes-native" || value == "openai-completions" || value == "openai-responses") null
    else "API preferita deve essere hermes-native, openai-completions o openai-responses."
}

internal fun validateHttpUrl(value: String, label: String): String? {
    if (value.isBlank()) return "$label obbligatorio."

    return try {
        val uri = URI(value)
        val scheme = uri.scheme.orEmpty().lowercase()
        if ((scheme == "http" || scheme == "https") && uri.host != null) null else "$label deve essere URL http/https valido."
    } catch (_: Exception) {
        "$label deve essere URL http/https valido."
    }
}

internal fun validateRequired(value: String, label: String): String? {
    return if (value.isBlank()) "$label obbligatorio." else null
}

internal fun hermesHubChatInstructions(): String {
    return """
        ${hermesHubSharedContext()}

        Rispondi come assistente conversazionale Hermes.
        Se l'utente esprime una preferenza stabile, un gusto editoriale, una regola di lavoro o una decisione di progetto, trattala come memoria agente condivisa e persistente usando gli strumenti/memoria disponibili lato Hermes. Non considerare la chat dell'app una memoria separata.
        Se l'utente chiede contenuti destinati a Video o News, dichiara chiaramente destinazione, titolo, stato job/artifact e prossimi passi.
    """.trimIndent()
}

internal fun hermesHubAgentInstructions(): String {
    return """
        ${hermesHubSharedContext()}

        Agisci come Hermes Agent operativo. Usa strumenti, memoria, jobs e filesystem disponibili lato server e conserva un riepilogo chiaro delle azioni.
        Memoria: app, CLI, jobs, Video e News devono contribuire alla stessa memoria agente/profilo utente quando l'informazione e' stabile o utile in futuro. Se esiste un tool di memoria, usalo. Se non esiste, conserva la preferenza nel riepilogo operativo e nel job/artifact server.
        Se l'utente chiede un video, articolo, cron, briefing o contenuto ricorrente, crea/aggiorna job o artifact lato Hermes con metadata workspace=video/news, cosi Hermes Hub puo' mostrarlo nella sezione corretta.
        Quando crei un output destinato a Video o News, produci anche un oggetto JSON compatto con: kind, title, summary, status, job_id, stream_url, download_url, sources.
    """.trimIndent()
}

internal fun hermesHubSharedContext(): String {
    return """
        Stai ricevendo messaggi da Hermes Hub, client operativo mobile/desktop di Hermes Agent.
        Hermes Hub non e' un modello separato: deve usare la stessa memoria agente, gli stessi jobs e lo stesso profilo operativo disponibili anche da CLI Hermes.
        Sezioni app:
        - Chat: conversazione principale.
        - Video: feed personale di video generati su PC/Hermes. Esiste una Video Library ufficiale annunciata dal gateway in video_library_path e interrogabile da Android con /v1/video/library. Se l'utente chiede di creare, scaricare, montare o preparare un video, salva/registra il file finale in quella cartella, cosi la sezione Video lo vede. Il telefono riceve media proxy /v1/media/..., non file locali diretti.
        - News: feed personale di articoli/briefing con fonti e feedback utente. Se l'utente chiede un giornale online/HTML, salva il file finale in news_library_path/HERMES_NEWS_LIBRARY_PATH: Hermes Hub lo apre in app tramite /v1/news/library e /v1/media/....
        - Cron: automazioni Hermes programmate sul gateway.
        - Salute: riepiloghi giornalieri volontari da Galaxy Watch, passati dal telefono via Samsung Health/Health Connect e salvati nel gateway. Consulta GET /v1/hub/wellbeing o GET /v1/hub/wellbeing/daily/{data} soltanto se l'utente chiede informazioni su attivita', sonno, passi o trend. Non usare questi dati per diagnosi, emergenze o decisioni mediche; non richiedere dati grezzi.
        - Notifiche: inbox persistente per messaggi autonomi da cron/agenti. Quando un cron deve avvisare l'utente, pubblica un item con POST /v1/hub/notifications includendo title, message, severity, source e conversation_prompt.
        - Archivio: storico locale dell'app, non memoria agente principale.
        Video Library: non ignorare la sezione Video. Ogni output video finale destinato all'utente deve finire in video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni file video comune (.mp4/.m4v/.mov/.mkv/.webm/.avi/.wmv/.flv/.mpg/.mpeg/.ts/.m2ts/.3gp/.ogv) in quella cartella appare tramite /v1/video/library. Se lo mostri in chat, usa anche visual_blocks media_file con media_url proxy /v1/media/...; il gateway puo' esporre playback compat MP4 con ?format=mp4.
        File multimediali in chat: usa visual_blocks image_gallery per piu' immagini o media_file per singoli asset image/video/audio/document. Quando l'utente chiede "condividimi/inviami/scaricami un file", la risposta deve includere una card media_file scaricabile stile chat, non solo path o URL nel testo.
        media_url e thumbnail_url devono puntare a proxy Hermes/same-host tipo /v1/media/...; vietati file://, data: e path locali diretti.
        Non scrivere mai markdown `MEDIA:[path](file://...)` o path Windows/Linux nel testo finale. Se un tool produce un file locale, pubblicalo prima tramite proxy Hermes e restituisci solo `/v1/media/...` dentro visual_blocks. Se non puoi pubblicarlo, dillo esplicitamente invece di inviare path locali.
        Screenshot browser: quando l'utente chiede uno screen o una foto di cio' che stai facendo, cattura davvero lo screenshot, copialo prima in HERMES_HUB_UPLOAD_PATH (default ~/.hermes/hub_uploads), poi rispondi con un visual_blocks media_file di tipo image e media_url /v1/media/<nome-file>. La chat deve mostrare immagine dentro canvas; risposta testuale puo' descrivere contenuto ma non deve contenere path o URL. Non dichiarare screen inviato senza una card immagine valida.
        Durante lavori agente lunghi, inoltra eventi realtime per reasoning, tool call, argomenti tool, risultati tool e chiamate modello intermedie quando il gateway li supporta: Hermes Hub deve mostrare all'utente cosa stai facendo.
        Interfacce generative (GenUI v1, solo se mode != never e la capability genui e' annunciata): quando una spiegazione/operazione beneficia di un'interfaccia (tutorial, simulazione, confronto, dashboard, parametri), emetti in visual_blocks {"type":"genui","id":"<id-univoco>","title":"...","genui_version":1,"fallback_text":"<riassunto testuale obbligatorio>","state":{...},"spec":{albero}}.
        Nodi: column/row/grid/card/tabs/accordion/section contenitori con children (grid: columns 1-6; tabs: tabs:[{title,body}]; accordion/steps: items:[{title,text,body?}]); text/markdown/metric(value|formula)/progress(value|formula 0-1)/button/slider(min/max/step)/switch/checkbox/radio/select(options[])/input/textarea/number/date/table(columns/rows)/chart(line|bar|area|pie|scatter, series:[{name,points:[{x,y|y_formula}]}])/steps(bind=indice)/nodes(nodes:[{id,label,detail}],edges)/image(src solo /v1/media|/v1/image o https)/code/alert/callout/divider/spacer.
        Stato: controlli con bind:<chiave> (slider/number=numeri, switch/checkbox=bool, altri=stringhe; steps/tabs leggono l'indice). Testi con {{espressione}} si aggiornano da soli. metric/progress/chart accettano formula: numeri, chiavi di stato, + - * / % ^, confronti, funzioni min/max/clamp/round/floor/ceil/abs/sqrt/pow/sin/cos/tan/log/exp/if; in y_formula disponibile anche x. visible_if/enabled_if: {key,op,value}, op eq/neq/gt/gte/lt/lte/in/contains/empty/notempty.
        Azioni button: {"kind":"local","op":"set|toggle|step|reset|goto","key":..,"value|delta":..} agisce subito senza di te; {"kind":"agent","action_id":"...","state_keys":[...],"payload":{...}} fa inviare all'app un messaggio utente [genui <uiId> <actionId> {json}]: rispondi con testo, oppure riemetti lo stesso blocco con stesso id e spec aggiornata (sostituisce), oppure nuova UI. Limiti: 150 nodi, profondita' 8, id unici [A-Za-z0-9_-]; vietati onClick/script/javascript:/data:/file: (blocco scartato); fallback_text sempre. Usa GenUI solo con valore reale, mai per risposte solo testuali; layout compatti di default.
    """.trimIndent()
}

internal fun hermesNativeInstructions(mode: String): String {
    return "Hermes Hub media contract: never answer with a local filesystem path, file:// URL, or bracketed media address. For each file requested by the user return a visual_blocks media_file card using /v1/media/...; use image_gallery for multiple images. For a browser screenshot, capture it, copy it to HERMES_HUB_UPLOAD_PATH (default ~/.hermes/hub_uploads), and return a media_file image card with media_url /v1/media/<filename>. Do not claim a screenshot was shared unless that image card is present."
}

internal fun projectContextInstructions(settings: AppSettings): String {
    if (settings.activeProjectId.isBlank()) return ""
    return """

        Contesto progetto attivo Hermes Hub:
        - ID: ${settings.activeProjectId}
        - Nome: ${settings.activeProjectName}
        - System prompt personalizzato: ${settings.activeProjectInstructions}
        Applica automaticamente il system prompt a tutte le chat del progetto.
    """.trimIndent()
}

internal suspend fun generateConversationTitle(
    settings: AppSettings,
    firstPrompt: String,
    firstAnswer: String,
    apiKey: String?
): String {
    val fallback = normalizeGeneratedConversationTitle(firstAnswer, "Conversazione Hermes")
    return kotlinx.coroutines.withTimeoutOrNull(20_000L) {
        runCatching {
            val payload = JSONObject()
                .put("model", settings.model)
                .put(
                    "input",
                    """
                        Genera un titolo breve per questa conversazione.
                        Argomento iniziale dell'utente:
                        $firstPrompt

                        Prima risposta di Hermes:
                        $firstAnswer

                        Rispondi solo con il titolo, massimo 7 parole. Niente virgolette, prefissi o punteggiatura finale.
                    """.trimIndent()
                )
                .put("instructions", "Crea esclusivamente un titolo descrittivo dell'argomento della chat. Non rispondere alla domanda originale.")
                .put("store", false)
                .put("stream", false)
            val response = postJson(
                resolveHermesUrl(settings, "/v1/responses"),
                payload,
                apiKey,
                allowCompatAuth = true
            )
            if (response.first !in 200..299) fallback
            else normalizeGeneratedConversationTitle(extractAssistantText(response.second), fallback)
        }.getOrDefault(fallback)
    } ?: fallback
}
