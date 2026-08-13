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
    var gatewayWsUrl by remember(settings.gatewayWsUrl) { mutableStateOf(settings.gatewayWsUrl) }
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
    var apiKey by remember(gatewaySecret) { mutableStateOf(gatewaySecret.orEmpty()) }
    var fontScale by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)) }
    var showToolCalls by remember(settings.showToolCalls) { mutableStateOf(settings.showToolCalls) }
    var showMessageMetrics by remember(settings.showMessageMetrics) { mutableStateOf(settings.showMessageMetrics) }
    var metricTtft by remember(settings.metricTtft) { mutableStateOf(settings.metricTtft) }
    var metricTokensPerSecond by remember(settings.metricTokensPerSecond) { mutableStateOf(settings.metricTokensPerSecond) }
    var metricOutputTokens by remember(settings.metricOutputTokens) { mutableStateOf(settings.metricOutputTokens) }
    var metricPromptTokens by remember(settings.metricPromptTokens) { mutableStateOf(settings.metricPromptTokens) }
    var metricContextTokens by remember(settings.metricContextTokens) { mutableStateOf(settings.metricContextTokens) }
    var metricDuration by remember(settings.metricDuration) { mutableStateOf(settings.metricDuration) }
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
    var voicePushToTalk by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.pushToTalk) }
    var voiceTranscript by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.showTranscript) }
    var voiceBluetooth by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.bluetooth) }
    var voiceParticleShape by remember(settings.activeProjectId, voiceProfile) { mutableStateOf(voiceProfile.particleShape) }
    var status by remember { mutableStateOf("Pronto.") }
    var showEraseHealthConfirm by remember { mutableStateOf(false) }
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
            gatewayWsUrl = "",
            adminBridgeUrl = hermesRoot(AppSettings(gatewayUrl = gatewayUrl.trim())),
            provider = provider.trim(),
            inferenceEndpoint = inferenceEndpoint.trim(),
            preferredApi = preferredApi.trim(),
            model = model.trim(),
            voiceModel = voiceModel.trim(),
            accessMode = accessMode.trim(),
            visualBlocksMode = visualBlocksMode.trim(),
            videoLibraryPath = videoLibraryPath.trim(),
            newsLibraryPath = newsLibraryPath.trim(),
            activeProjectId = settings.activeProjectId,
            activeProjectName = settings.activeProjectName,
            fontScale = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
            showToolCalls = showToolCalls,
            showMessageMetrics = showMessageMetrics,
            metricTtft = metricTtft,
            metricTokensPerSecond = metricTokensPerSecond,
            metricOutputTokens = metricOutputTokens,
            metricPromptTokens = metricPromptTokens,
            metricContextTokens = metricContextTokens,
            metricDuration = metricDuration,
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

    if (showEraseHealthConfirm) {
        AlertDialog(
            onDismissRequest = { showEraseHealthConfirm = false },
            title = { Text("Eliminare riepiloghi salute?") },
            text = { Text("Verranno eliminati dal gateway Hermes tutti i riepiloghi salute sincronizzati. I dati in Samsung Health e Health Connect non vengono toccati.") },
            confirmButton = {
                Button(onClick = {
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
                }) { Text("Elimina da Hermes") }
            },
            dismissButton = { Button(onClick = { showEraseHealthConfirm = false }) { Text("Annulla") } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text("Impostazioni", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(10.dp))
        Text("Impostazioni salvate sul dispositivo. Le nuove installazioni non includono endpoint o credenziali preconfigurati.", color = AppColors.Muted)
        Spacer(modifier = Modifier.height(18.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                PremiumPanel {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Connessione Hermes", color = Color.White, fontWeight = FontWeight.SemiBold)
                        SettingsField("Hermes API URL", gatewayUrl, { gatewayUrl = it })
                        SettingsPasswordField("API key Hermes", apiKey, { apiKey = it })
                        SettingsField("Cartella video Hermes (sync server)", videoLibraryPath, { })
                        SettingsField("Cartella news Hermes", newsLibraryPath, { newsLibraryPath = it })
                        SettingsField("Limite allegati file (MB, max 150)", maxAttachmentMb.toString(), { value ->
                            maxAttachmentMb = value.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 150) ?: maxAttachmentMb
                        })
                    }
                }
            }
            item {
                PremiumPanel {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Voce", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Profilo progetto: ${settings.activeProjectName.ifBlank { "Generale" }}",
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                        SettingsField("Modello dedicato Voce", voiceModel, { voiceModel = it })
                        Text("Voce Kokoro", color = Color.White)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SupportedVoiceNames.forEach { candidate ->
                                Button(
                                    onClick = { voiceName = candidate },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (voiceName == candidate) AppColors.Accent else AppColors.Composer
                                    )
                                ) {
                                    Text(candidate)
                                }
                            }
                            Button(onClick = {
                                status = "Genero anteprima voce..."
                                scope.launch {
                                    status = runCatching {
                                        previewVoiceProfile(context, currentSettings(), apiKey, voiceName, voiceSpeed)
                                        "Anteprima completata."
                                    }.getOrElse { "Anteprima non disponibile: ${it.message ?: it.javaClass.simpleName}" }
                                }
                            }) {
                                Text("Anteprima")
                            }
                        }
                        Text("Velocita: ${String.format(java.util.Locale.ROOT, "%.2f", voiceSpeed)}x", color = Color.White)
                        Slider(
                            value = voiceSpeed,
                            onValueChange = { voiceSpeed = it },
                            valueRange = 0.75f..1.35f
                        )
                        Text("Forma particelle", color = Color.White)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SupportedParticleShapes.forEach { candidate ->
                                Button(
                                    onClick = { voiceParticleShape = candidate },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (voiceParticleShape == candidate) AppColors.Accent else AppColors.Composer
                                    )
                                ) {
                                    Text(particleShapeLabel(candidate))
                                }
                            }
                        }
                        Text("Parola di attivazione", color = Color.White)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SupportedWakePhrases.forEach { candidate ->
                                Button(
                                    onClick = { voiceWakePhrase = candidate },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (voiceWakePhrase.equals(candidate, ignoreCase = true)) AppColors.Accent else AppColors.Composer
                                    )
                                ) {
                                    Text(candidate)
                                }
                            }
                        }
                        SettingsField("Wake word personalizzata", voiceWakePhrase, { voiceWakePhrase = it })
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
                        MetricSwitch("Push-to-talk", voicePushToTalk) { voicePushToTalk = it }
                        MetricSwitch("Mostra trascrizione", voiceTranscript) { voiceTranscript = it }
                        MetricSwitch("Instrada su Bluetooth", voiceBluetooth) { voiceBluetooth = it }
                    }
                }
            }
            item {
                Button(onClick = { advancedVisible = !advancedVisible }) {
                    Text(if (advancedVisible) "Nascondi avanzate" else "Mostra avanzate")
                }
            }
            if (advancedVisible) {
                item {
                    PremiumPanel {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Avanzate", color = Color.White, fontWeight = FontWeight.SemiBold)
                            SettingsField("Provider", provider, { provider = it })
                            SettingsField("Endpoint API lato server", inferenceEndpoint, { inferenceEndpoint = it })
                            SettingsField("API preferita", preferredApi, { preferredApi = it })
                            SettingsField("Modello", model, { model = it })
                            SettingsField("Accesso", accessMode, { accessMode = it })
                            SettingsField("Modalita visuale (auto / always / never)", visualBlocksMode, { visualBlocksMode = it })
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tool call in chat", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = showToolCalls, onCheckedChange = { showToolCalls = it })
                }
                Text("ON = mostra pannello tool compatto. Output lunghi restano collassati.", color = AppColors.Muted, fontSize = 12.sp)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Metriche messaggi", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = showMessageMetrics, onCheckedChange = { showMessageMetrics = it })
                }
                Text("ON = mostra TTFT, token e t/s nei messaggi.", color = AppColors.Muted, fontSize = 12.sp)
            }
            item {
                PremiumPanel {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Metriche visibili", color = Color.White, fontWeight = FontWeight.SemiBold)
                        MetricSwitch("Tempo primo token", metricTtft) { metricTtft = it }
                        MetricSwitch("Token/sec", metricTokensPerSecond) { metricTokensPerSecond = it }
                        MetricSwitch("Token output", metricOutputTokens) { metricOutputTokens = it }
                        MetricSwitch("Token input", metricPromptTokens) { metricPromptTokens = it }
                        MetricSwitch("Contesto", metricContextTokens) { metricContextTokens = it }
                        MetricSwitch("Durata totale", metricDuration) { metricDuration = it }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Strict native mode", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = strictNativeMode, onCheckedChange = { strictNativeMode = it })
                }
                Text(
                    "ON = niente fallback Chat Completions/no-auth se Hermes Native/Responses fallisce.",
                    color = AppColors.Muted,
                    fontSize = 12.sp
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fallback locale", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = demoMode, onCheckedChange = { demoMode = it })
                }
            }
            item {
                PremiumPanel {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Salute Galaxy Watch", color = Color.White, fontWeight = FontWeight.SemiBold)
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
                            Button(onClick = {
                                val candidate = currentSettings().copy(healthSyncEnabled = true)
                                val error = validateHttpUrl(candidate.gatewayUrl, "Hermes API URL")
                                if (error != null) {
                                    status = error
                                } else if (!HealthSync.sdkStatus(context).startsWith("Health Connect disponibile")) {
                                    status = HealthSync.sdkStatus(context)
                                } else {
                                    healthPermissionLauncher.launch(HealthSync.permissionsForRequest(context, candidate))
                                }
                            }) { Text("Collega e autorizza") }
                            Button(onClick = {
                                if (!HealthSync.openSettings(context)) status = "Impossibile aprire Health Connect."
                            }) { Text("Gestisci accesso") }
                            Button(onClick = {
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
                            }) { Text("Sincronizza ora") }
                            Button(onClick = { showEraseHealthConfirm = true }) { Text("Elimina da Hermes") }
                        }
                    }
                }
            }
            item {
                PremiumPanel {
                    Text(
                        modifier = Modifier.padding(14.dp),
                        text = status,
                        color = AppColors.Muted
                    )
                }
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
                                    pushToTalk = voicePushToTalk,
                                    showTranscript = voiceTranscript,
                                    bluetooth = voiceBluetooth,
                                    particleShape = voiceParticleShape
                                )
                                scope.launch {
                                    val saved = withContext(Dispatchers.IO) {
                                        val secretSaved = saveGatewaySecret(context, candidateSecret)
                                        if (secretSaved) {
                                            saveVoiceProfile(
                                                context,
                                                settings.activeProjectId,
                                                candidateVoiceProfile
                                            )
                                        }
                                        secretSaved
                                    }
                                    if (!saved) {
                                        status = "API key non salvata: Android Keystore non disponibile. Credenziale non scritta in chiaro."
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
                        }) {
                            Text("Salva")
                        }
                        Button(onClick = {
                            val candidate = currentSettings()
                            val error = validateHttpUrl(candidate.gatewayUrl, "Hermes API URL")
                            if (error != null) {
                                status = error
                                return@Button
                            }
                        status = "Leggo capabilities Hermes..."
                        scope.launch {
                            status = runCatching { httpGet("${candidate.gatewayUrl.trimEnd('/')}/capabilities", apiKey) }
                                .getOrElse { "Capabilities non leggibili: ${it.message ?: it.javaClass.simpleName}" }
                        }
                        }) {
                            Text("Capabilities")
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = {
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
                            Text("Ripristina API key")
                        }
                        Button(onClick = {
                            val error = validateHttpUrl(gatewayUrl, "Hermes API URL")
                            if (error != null) {
                                status = error
                                return@Button
                            }

                            val healthUrl = "${hermesRoot(AppSettings(gatewayUrl = gatewayUrl.trim()))}/health"
                            status = "Test: $healthUrl"
                            scope.launch {
                                status = testGateway(healthUrl, apiKey)
                            }
                        }) {
                            Text("Test Hermes")
                        }
                        Button(onClick = {
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
                        }) {
                            Text("Reset")
                        }
                        Button(onClick = {
                            status = runCatching { exportLocalBackup(context) }
                                .getOrElse { "Backup non riuscito: ${it.message ?: it.javaClass.simpleName}" }
                        }) {
                            Text("Backup locale")
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

    PremiumPanel {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dimensione caratteri", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
                Text("Anteprima testo conversazione", color = Color.White, fontSize = 16.sp)
                Text("Questo e' il modo in cui leggerai chat, sezioni e impostazioni.", color = AppColors.Muted, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsField(label: String, value: String, onValueChange: (String) -> Unit) {
    TextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = { v -> onValueChange(v.take(SETTINGS_FIELD_MAX_LENGTH)) },
        label = { Text(label, color = AppColors.Muted) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AppColors.Composer,
            unfocusedContainerColor = AppColors.Composer,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedIndicatorColor = AppColors.Accent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    TextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = { v -> onValueChange(v.take(SETTINGS_FIELD_MAX_LENGTH)) },
        label = { Text(label, color = AppColors.Muted) },
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
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedIndicatorColor = AppColors.Accent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@Composable
internal fun MetricSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal fun appendPrompt(current: String, addition: String): String {
    val trimmed = current.trim()
    return if (trimmed.isEmpty()) addition else "$trimmed\n$addition"
}

internal fun validateSettings(settings: AppSettings): String? {
    return validateHttpUrl(settings.gatewayUrl, "Hermes API URL")
        ?: validateRequired(settings.provider, "Provider")
        ?: validateHttpUrl(settings.inferenceEndpoint, "Endpoint API")
        ?: validatePreferredApi(settings.preferredApi)
        ?: validateRequired(settings.model, "Modello")
        ?: validateRequired(settings.voiceModel, "Modello Voce")
        ?: validateRequired(settings.accessMode, "Accesso")
        ?: validateVisualBlocksMode(settings.visualBlocksMode)
}

internal fun validateVisualBlocksMode(value: String): String? {
    return if (value == "auto" || value == "always" || value == "never") null else "Modalita visuale deve essere auto, always o never."
}

internal fun validatePreferredApi(value: String): String? {
    return if (value == "hermes-native" || value == "openai-completions" || value == "openai-responses") null
    else "API preferita deve essere hermes-native, openai-completions o openai-responses."
}

internal fun validateWsUrl(value: String, label: String): String? {
    if (value.isBlank()) return "$label obbligatorio."

    return try {
        val uri = URI(value)
        val scheme = uri.scheme.orEmpty().lowercase()
        if ((scheme == "ws" || scheme == "wss") && uri.host != null) null else "$label deve essere URL ws/wss valido."
    } catch (_: Exception) {
        "$label deve essere URL ws/wss valido."
    }
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
                "${settings.gatewayUrl.trimEnd('/')}/responses",
                payload,
                apiKey,
                allowCompatAuth = true
            )
            if (response.first !in 200..299) fallback
            else normalizeGeneratedConversationTitle(extractAssistantText(response.second), fallback)
        }.getOrDefault(fallback)
    } ?: fallback
}
