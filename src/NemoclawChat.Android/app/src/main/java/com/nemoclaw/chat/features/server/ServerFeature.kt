package com.nemoclaw.chat

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
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
import android.webkit.WebSettings
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ArrowBack
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
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.core.content.ContextCompat
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
import com.nemoclaw.chat.resolveHermesUrl
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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
internal fun ServerScreen(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var wsProbe by remember(settings) {
        mutableStateOf(
            GatewayWsProbe(
                wsUrl = "${settings.gatewayUrl.trimEnd('/')}/capabilities",
                connected = false,
                status = "Nessun controllo capabilities eseguito.",
                detail = "Leggi /v1/capabilities e /v1/models per verificare Hermes."
            )
        )
    }
    var snapshot by remember(settings) {
        mutableStateOf(
            ServerSnapshot(
                gateway = settings.gatewayUrl,
                model = settings.model,
                providerDetail = "Provider: ${settings.provider} | API: ${settings.preferredApi}",
                inferenceEndpoint = settings.inferenceEndpoint,
                policy = settings.accessMode,
                statusMessage = if (settings.demoMode) "Fallback locale attivo. Provero' comunque a usare Hermes." else "Solo Hermes. Verifica lo stato del server.",
                videoLibraryPath = settings.videoLibraryPath
            )
        )
    }
    var diagnostics by remember { mutableStateOf<List<DiagnosticCheck>>(emptyList()) }
    var controlService by rememberSaveable { mutableStateOf("gateway") }
    var controlOutput by remember { mutableStateOf("Centro controllo non ancora interrogato.") }
    var logFilter by rememberSaveable { mutableStateOf("") }
    var pendingControlAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingMaintenanceAction by remember { mutableStateOf<String?>(null) }
    var deviceIdState by remember { mutableStateOf(loadDeviceId(context)) }
    var deviceStatus by remember { mutableStateOf("Telefono non registrato: le azioni server usano solo la chiave.") }
    var deviceBusy by remember { mutableStateOf(false) }
    var registeredDevices by remember { mutableStateOf<List<RegisteredDevice>>(emptyList()) }
    var devicesMsg by remember { mutableStateOf("") }
    var pushStatus by remember { mutableStateOf("Notifiche non attive.") }
    var pushBusy by remember { mutableStateOf(false) }
    val pushPermissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    suspend fun postServerControlSigned(
        url: String,
        payload: JSONObject,
        actionLabel: String,
        onDone: (String) -> Unit
    ) {
        val devId = deviceIdState
        if (devId == null) {
            val result = runCatching {
                postJson(url, payload, loadGatewaySecret(context), allowCompatAuth = false).second
            }.getOrElse { it.message ?: "Errore" }
            onDone(result)
            return
        }
        val activity = findFragmentActivity(context)
        if (activity == null) {
            onDone("Activity non disponibile per la conferma biometrica.")
            return
        }
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        authenticateDeviceOwner(
            activity,
            "Conferma $actionLabel",
            "Firma con impronta o PIN per eseguire.",
            onSuccess = { gate.complete(true) },
            onError = { gate.completeExceptionally(IllegalStateException(it)) }
        )
        try {
            gate.await()
        } catch (denied: Exception) {
            onDone(denied.message ?: "Autenticazione annullata.")
            return
        }
        val result = runCatching {
            postDeviceSignedJson(url, payload, devId, loadGatewaySecret(context)).let { (code, body) ->
                if (code in 200..299) body.ifBlank { "Eseguito (firmato)." } else "Server HTTP $code: ${body.take(200)}"
            }
        }.getOrElse { it.message ?: "Errore" }
        onDone(result)
    }

    fun refreshDevices() {
        scope.launch {
            devicesMsg = "Leggo…"
            try {
                val body = httpGet(resolveHermesUrl(settings, "/v1/hub/devices"), loadGatewaySecret(context))
                val arr = JSONObject(body).optJSONArray("devices") ?: JSONArray()
                registeredDevices = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    RegisteredDevice(
                        o.optString("device_id"),
                        o.optString("label"),
                        o.optBoolean("revoked"),
                        o.optBoolean("has_token")
                    )
                }.filter { it.id.isNotBlank() }
                devicesMsg = if (registeredDevices.isEmpty()) "Nessun telefono registrato."
                else "${registeredDevices.size} registrati (un telefono che non riconosci: revocalo)."
            } catch (failed: Exception) {
                devicesMsg = failed.message ?: "Errore lettura."
            }
        }
    }

    fun revokeDevice(id: String) {
        scope.launch {
            devicesMsg = runCatching {
                val (code, _) = postJson(
                    resolveHermesUrl(settings, "/v1/hub/devices/$id"),
                    JSONObject(), loadGatewaySecret(context), method = "DELETE", allowCompatAuth = false
                )
                if (code !in 200..299) "Revoca HTTP $code" else ""
            }.getOrElse { it.message ?: "Errore revoca." }
            if (devicesMsg.isBlank()) {
                if (deviceIdState == id) {
                    clearDeviceId(context)
                    deviceIdState = null
                    deviceStatus = "Telefono non registrato: le azioni server usano solo la chiave."
                }
                refreshDevices()
            }
        }
    }
    val maintenanceLabels = mapOf(
        "update" to "Aggiorna",
        "rollback" to "Ripristina versione",
        "backup" to "Salva backup",
        "restore" to "Ripristina backup",
        "diagnostic" to "Diagnostica"
    )

    LaunchedEffect(settings) {
        snapshot = loadServerSnapshot(context, settings, loadGatewaySecret(context))
        diagnostics = runDiagnostics(settings, loadGatewaySecret(context))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppColors.Accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Dns, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text("Hermes Server", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text("Dashboard Hermes Agent API: health, detailed health, models e capabilities.", color = AppColors.Muted, fontSize = 14.sp)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ServerMetric("Hermes API", snapshot.gateway, "Health endpoint: ${hermesRoot(settings)}/health", valueColor = AppColors.Success)
                ServerMetric("Capabilities", wsProbe.wsUrl, wsProbe.status, valueColor = if (wsProbe.status.contains("lette", ignoreCase = true)) AppColors.Success else AppColors.Warning)
                ServerMetric("Modello", snapshot.model, snapshot.providerDetail)
                ServerMetric("API lato server", snapshot.inferenceEndpoint, "Il client parla a Hermes API, non direttamente al runtime modello.")
                ServerMetric("Sicurezza", snapshot.policy, "Client usa solo la API key Bearer salvata dall'utente.")
                ServerMetric("Cartella video Hermes", snapshot.videoLibraryPath.ifBlank { "In attesa di sync server" }, "Hermes decide path e app lo recepisce da /health/detailed.", valueColor = if (snapshot.videoLibraryPath.isBlank()) AppColors.Warning else Color.White)
            }
        }
        item {
            PulseCard(context = context, settings = settings)
        }
        item {
            GpuComputeCard(context = context, settings = settings)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AZIONI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(snapshot.statusMessage, color = AppColors.Muted)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val error = validateHttpUrl(settings.gatewayUrl, "Indirizzo server Hermes")
                                if (error != null) {
                                    snapshot = snapshot.copy(statusMessage = error)
                                    return@Button
                                }
                                val healthUrl = "${hermesRoot(settings)}/health"
                                snapshot = snapshot.copy(statusMessage = "Test: $healthUrl")
                                scope.launch {
                                    snapshot = snapshot.copy(statusMessage = testGateway(healthUrl, loadGatewaySecret(context)))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Test Hermes", fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    snapshot = loadServerSnapshot(context, settings, loadGatewaySecret(context))
                                    diagnostics = runDiagnostics(settings, loadGatewaySecret(context))
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                        ) {
                            Text("Aggiorna stato")
                        }
                        TextButton(
                            onClick = {
                                snapshot = snapshot.copy(statusMessage = "Contratto Hermes: GET /health, GET /health/detailed, GET /v1/models, GET /v1/capabilities, POST /v1/responses, POST /v1/chat/completions, POST /v1/runs, GET/POST /api/jobs cron.")
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                        ) {
                            Text("Mostra API")
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CENTRO CONTROLLO SERVER", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Azioni tipizzate per Hermes, gateway, llama.cpp e Tailscale; nessuna shell arbitraria.", color = AppColors.Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("hermes", "gateway", "llama", "tailscale").forEach { service ->
                            Button(
                                onClick = { controlService = service },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (controlService == service) AppColors.Accent else AppColors.Panel,
                                    contentColor = if (controlService == service) Color(0xFF171009) else Color.White
                                ),
                                border = if (controlService == service) null else BorderStroke(1.dp, AppColors.Border),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text(service) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { pendingControlAction = controlService to "start" }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Avvia") }
                        TextButton(onClick = { pendingControlAction = controlService to "stop" }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Ferma") }
                        TextButton(onClick = { pendingControlAction = controlService to "restart" }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Error)) { Text("Riavvia") }
                        TextButton(onClick = { scope.launch { controlOutput = runCatching { httpGet(resolveHermesUrl(settings, "/v1/hub/server/control") + "?filter=${java.net.URLEncoder.encode(logFilter, "UTF-8")}", loadGatewaySecret(context)) }.getOrElse { it.message ?: "Errore" } } }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Aggiorna output") }
                    }
                    SettingsField("Filtro log", logFilter, { logFilter = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("update", "rollback", "backup", "restore", "diagnostic").forEach { operation -> TextButton(onClick = { pendingMaintenanceAction = operation }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text(maintenanceLabels[operation] ?: operation) } }
                    }
                    SelectionContainer { Text(controlOutput, color = AppColors.Muted, fontSize = 11.sp) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DIAGNOSTICA GATEWAY", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    if (diagnostics.isEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.NetworkCheck, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                            Text("Nessuna diagnostica eseguita.", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Esegui un controllo per verificare Hermes.", color = AppColors.Muted, fontSize = 12.sp)
                            Button(
                                onClick = {
                                    scope.launch {
                                        snapshot = loadServerSnapshot(context, settings, loadGatewaySecret(context))
                                        diagnostics = runDiagnostics(settings, loadGatewaySecret(context))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Esegui diagnostica", fontWeight = FontWeight.SemiBold) }
                        }
                    } else {
                        diagnostics.forEach { check ->
                            Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${if (check.ok) "OK" else "Errore"} - ${check.label}", color = if (check.ok) AppColors.Success else AppColors.Error, fontWeight = FontWeight.SemiBold)
                                Text(check.endpoint, color = AppColors.Faint, fontSize = 11.sp)
                                Text(check.message, color = AppColors.Muted, fontSize = 12.sp)
                                if (!check.ok) Text("Azione: ${check.action}", color = AppColors.Accent, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = AppColors.Border)
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CAPABILITIES HERMES", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(wsProbe.detail, color = AppColors.Muted)
                    Button(
                        onClick = {
                            wsProbe = wsProbe.copy(status = "Lettura capabilities...", detail = "Chiamata a /v1/capabilities e /v1/models.")
                            scope.launch {
                                val capabilities = runCatching { httpGet(resolveHermesUrl(settings, "/v1/capabilities"), loadGatewaySecret(context)) }.getOrElse { it.message ?: it.javaClass.simpleName }
                                val models = runCatching { httpGet(resolveHermesUrl(settings, "/v1/models"), loadGatewaySecret(context)) }.getOrElse { it.message ?: it.javaClass.simpleName }
                                wsProbe = GatewayWsProbe(
                                    wsUrl = resolveHermesUrl(settings, "/v1/capabilities"),
                                    connected = true,
                                    status = "Capabilities lette.",
                                    detail = "Capabilities e models letti da Hermes.",
                                    capabilityLines = listOf("Capabilities: ${capabilities.limitText(240)}", "Models: ${models.limitText(240)}")
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Leggi capabilities", fontWeight = FontWeight.SemiBold)
                    }
                    if (wsProbe.capabilityLines.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            wsProbe.capabilityLines.forEach { line ->
                                Text(line, color = AppColors.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("TELEFONO SICURO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    if (deviceIdState == null) deviceStatus
                    else "Registrato: ${deviceIdState!!.take(12)}… · $deviceStatus",
                    color = AppColors.Muted,
                    fontSize = 13.sp
                )
                Text(
                    "La chiave firma ogni azione server dopo impronta o PIN. Senza firma il server rifiuta (appena un telefono e' registrato).",
                    color = AppColors.Faint,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                deviceBusy = true
                                deviceStatus = "Genero chiave…"
                                try {
                                    val id = withContext(Dispatchers.IO) {
                                        ensureDeviceKeyPair()
                                        newDeviceId()
                                    }
                                    val pem = withContext(Dispatchers.IO) { devicePublicKeyPem() }
                                    val label = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(80)
                                    val (code, body) = postJson(
                                        resolveHermesUrl(settings, "/v1/hub/devices/register"),
                                        JSONObject().put("device_id", id).put("public_key", pem).put("label", label),
                                        loadGatewaySecret(context),
                                        allowCompatAuth = false
                                    )
                                    if (code in 200..299) {
                                        saveDeviceId(context, id)
                                        deviceIdState = id
                                        val fingerprint = runCatching {
                                            JSONObject(body).optString("fingerprint")
                                        }.getOrDefault("")
                                        deviceStatus = if (fingerprint.isNotBlank()) "impronta $fingerprint." else "registrato."
                                    } else {
                                        deviceStatus = "Registrazione fallita: HTTP $code."
                                    }
                                } catch (denied: Exception) {
                                    deviceStatus = denied.message ?: "Errore registrazione."
                                } finally {
                                    deviceBusy = false
                                }
                            }
                        },
                        enabled = !deviceBusy && deviceIdState == null
                    ) { Text("Registra telefono") }
                    Button(
                        onClick = {
                            scope.launch {
                                deviceBusy = true
                                try {
                                    val id = deviceIdState
                                    if (id != null) {
                                        postJson(
                                            resolveHermesUrl(settings, "/v1/hub/devices/$id"),
                                            JSONObject(),
                                            loadGatewaySecret(context),
                                            method = "DELETE",
                                            allowCompatAuth = false
                                        )
                                        clearDeviceId(context)
                                        deviceIdState = null
                                    }
                                    deviceStatus = "Telefono non registrato: le azioni server usano solo la chiave."
                                } catch (denied: Exception) {
                                    deviceStatus = denied.message ?: "Errore revoca."
                                } finally {
                                    deviceBusy = false
                                }
                            }
                        },
                        enabled = !deviceBusy && deviceIdState != null,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Error, contentColor = Color.White)
                    ) { Text("Revoca") }
                }
                HorizontalDivider(color = AppColors.Border)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Telefoni registrati",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { refreshDevices() }) { Text("Vedi", color = AppColors.Accent) }
                }
                if (devicesMsg.isNotBlank()) {
                    Text(devicesMsg, color = AppColors.Muted, fontSize = 12.sp)
                }
                registeredDevices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                (device.label.ifBlank { device.id }) + if (device.revoked) " (revocato)" else "",
                                color = if (device.revoked) AppColors.Muted else Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                device.id + if (device.hasToken) " · push" else "",
                                color = AppColors.Faint,
                                fontSize = 11.sp
                            )
                        }
                        if (!device.revoked) {
                            TextButton(onClick = { revokeDevice(device.id) }) {
                                Text("Revoca", color = AppColors.Error)
                            }
                        }
                    }
                }
            }
        }
    }
    item {
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NOTIFICHE PUSH", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(pushStatus, color = AppColors.Muted, fontSize = 13.sp)
                Text(
                    "Avvisa a telefono spento quando un job finisce. Il server manda solo il segnale, mai i contenuti.",
                    color = AppColors.Faint,
                    fontSize = 12.sp
                )
                Button(
                    onClick = {
                        scope.launch {
                            pushBusy = true
                            pushStatus = "Controllo…"
                            try {
                                if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
                                    pushStatus = "Push non disponibile: niente Play Services."
                                    return@launch
                                }
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    pushPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                                val devId = deviceIdState
                                if (devId == null) {
                                    pushStatus = "Registra prima il telefono qui sopra."
                                    return@launch
                                }
                                val configBody = runCatching {
                                    httpGet(resolveHermesUrl(settings, "/v1/hub/push-config"), loadGatewaySecret(context))
                                }.getOrElse {
                                    pushStatus = "Config non letta: ${it.message}"
                                    return@launch
                                }
                                val config = runCatching { JSONObject(configBody) }.getOrElse {
                                    pushStatus = "Config illeggibile."
                                    return@launch
                                }
                                if (!config.optBoolean("configured", false)) {
                                    pushStatus = "Server senza FCM: mancano service account e client JSON."
                                    return@launch
                                }
                                val client = config.optJSONObject("client")
                                if (client == null) {
                                    pushStatus = "Config incompleta."
                                    return@launch
                                }
                                // Default FirebaseApp (niente google-services.json:
                                // init manuale dai dati del server). Default perche'
                                // getInstance(app) nominale e' package-private.
                                val app = withContext(Dispatchers.IO) {
                                    FirebaseApp.getApps(context).firstOrNull()
                                        ?: FirebaseApp.initializeApp(
                                            context,
                                            FirebaseOptions.Builder()
                                                .setApiKey(client.optString("api_key"))
                                                .setApplicationId(client.optString("app_id"))
                                                .setProjectId(client.optString("project_id"))
                                                .setGcmSenderId(client.optString("sender_id"))
                                                .build()
                                        )
                                }
                                if (app == null) {
                                    pushStatus = "Init Firebase fallita."
                                    return@launch
                                }
                                val token = withContext(Dispatchers.IO) {
                                    try {
                                        com.google.android.gms.tasks.Tasks.await(FirebaseMessaging.getInstance().token)
                                    } catch (failed: Exception) {
                                        null
                                    }
                                }
                                if (token.isNullOrBlank()) {
                                    pushStatus = "Token FCM non ottenuto: riprova."
                                    return@launch
                                }
                                savePendingFcmToken(context, token)
                                val (code, _) = postJson(
                                    resolveHermesUrl(settings, "/v1/hub/devices/$devId/token"),
                                    JSONObject().put("fcm_token", token),
                                    loadGatewaySecret(context),
                                    allowCompatAuth = false
                                )
                                pushStatus = if (code in 200..299) "Notifiche attive su questo telefono."
                                else "Server HTTP $code: token non salvato."
                            } catch (failed: Exception) {
                                pushStatus = failed.message ?: "Errore attivazione."
                            } finally {
                                pushBusy = false
                            }
                        }
                    },
                    enabled = !pushBusy
                ) { Text("Attiva notifiche") }
            }
        }
    }
    }
    pendingControlAction?.let { (service, action) ->
        AlertDialog(
            onDismissRequest = { pendingControlAction = null },
            containerColor = AppColors.Surface,
            title = { Text("Conferma $action", color = Color.White) },
            text = { Text("Eseguire $action su $service? Le sessioni attive possono interrompersi.", color = AppColors.Muted) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingControlAction = null
                        scope.launch {
                            postServerControlSigned(
                                resolveHermesUrl(settings, "/v1/hub/server/action"),
                                JSONObject().put("service", service).put("action", action),
                                action
                            ) { controlOutput = it }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (action == "restart") AppColors.Error else AppColors.Accent,
                        contentColor = if (action == "restart") Color.White else Color(0xFF171009)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Conferma") }
            },
            dismissButton = { TextButton(onClick = { pendingControlAction = null }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Annulla") } }
        )
    }

    pendingMaintenanceAction?.let { operation ->
        AlertDialog(
            onDismissRequest = { pendingMaintenanceAction = null },
            containerColor = AppColors.Surface,
            title = { Text("Conferma ${maintenanceLabels[operation] ?: operation}", color = Color.White) },
            text = { Text("Eseguire \"${maintenanceLabels[operation] ?: operation}\" sul server? Update, rollback e restore possono interrompere il servizio.", color = AppColors.Muted) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingMaintenanceAction = null
                        scope.launch {
                            postServerControlSigned(
                                resolveHermesUrl(settings, "/v1/hub/server/maintenance"),
                                JSONObject().put("operation", operation),
                                operation
                            ) { controlOutput = it }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (operation == "update" || operation == "rollback" || operation == "restore") AppColors.Error else AppColors.Accent,
                        contentColor = if (operation == "update" || operation == "rollback" || operation == "restore") Color.White else Color(0xFF171009)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Conferma") }
            },
            dismissButton = { TextButton(onClick = { pendingMaintenanceAction = null }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Annulla") } }
        )
    }
}

internal data class RegisteredDevice(
    val id: String,
    val label: String,
    val revoked: Boolean,
    val hasToken: Boolean
)

@Composable
internal fun ServerMetric(title: String, value: String, detail: String, valueColor: Color = Color.White) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Text(detail, color = AppColors.Muted, fontSize = 12.sp)
        HorizontalDivider(color = AppColors.Border)
    }
}

@Composable
internal fun HardwareScreen(context: Context, settings: AppSettings) {
    var snapshot by remember(settings) { mutableStateOf(HardwareSnapshot()) }
    var previous by remember(settings) { mutableStateOf<HardwareSnapshot?>(null) }
    var selectedComponentId by rememberSaveable { mutableStateOf("cpu") }
    var history by remember { mutableStateOf<Map<String, List<HardwareHistoryPoint>>>(emptyMap()) }
    val apiKey = remember(settings.gatewayUrl) { loadGatewaySecret(context) }

    LaunchedEffect(settings.gatewayUrl, apiKey) {
        var failures = 0
        while (true) {
            val next = loadHardwareSnapshot(settings, apiKey)
            previous = snapshot.takeIf { it.status != "loading" }
            snapshot = next
            failures = if (next.status == "unavailable") failures + 1 else 0
            // Backoff su gateway spento: da 1s fino a 30s, niente raffica.
            kotlinx.coroutines.delay((1000L shl failures.coerceAtMost(4)).coerceAtMost(30_000L))
        }
    }

    val dtSeconds = previous?.let { ((snapshot.timestampMs - it.timestampMs).coerceAtLeast(100L)) / 1000.0 } ?: 0.0
    val downRate = previous?.let { ((snapshot.networkBytesReceived - it.networkBytesReceived).coerceAtLeast(0) / dtSeconds).toLong() } ?: 0L
    val upRate = previous?.let { ((snapshot.networkBytesSent - it.networkBytesSent).coerceAtLeast(0) / dtSeconds).toLong() } ?: 0L
    val temperatureViews = remember(snapshot.temperatures) { snapshot.temperatures.toHardwareTemperatureViews() }
    val components = remember(snapshot, previous, downRate, upRate, temperatureViews) {
        buildHardwareComponents(snapshot, temperatureViews, downRate, upRate)
    }
    val selectedComponent = components.firstOrNull { it.id == selectedComponentId } ?: components.firstOrNull()

    LaunchedEffect(snapshot.timestampMs, components) {
        if (components.isNotEmpty()) {
            if (components.none { it.id == selectedComponentId }) {
                selectedComponentId = components.first().id
            }
            val next = history.toMutableMap()
            components.forEach { component ->
                val points = next[component.id].orEmpty()
                next[component.id] = (points + HardwareHistoryPoint(component.utilizationPercent, component.temperatureC)).takeLast(120)
            }
            history = next
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppColors.Accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Prestazioni", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text("${snapshot.hostname} - ${snapshot.operatingSystem} ${snapshot.architecture}. Uptime ${formatHardwareUptime(snapshot.uptimeSeconds)}. Processi ${snapshot.processCount}.", color = AppColors.Muted, fontSize = 14.sp)
                }
            }
        }
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth > 720.dp) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        HardwareComponentList(
                            components = components,
                            selectedId = selectedComponent?.id.orEmpty(),
                            onSelect = { selectedComponentId = it },
                            modifier = Modifier.width(240.dp)
                        )
                        HardwareComponentDetail(
                            component = selectedComponent,
                            history = selectedComponent?.let { history[it.id].orEmpty() }.orEmpty(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        HardwareComponentList(
                            components = components,
                            selectedId = selectedComponent?.id.orEmpty(),
                            onSelect = { selectedComponentId = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        HardwareComponentDetail(
                            component = selectedComponent,
                            history = selectedComponent?.let { history[it.id].orEmpty() }.orEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HardwareComponentList(
    components: List<HardwareComponentView>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("COMPONENTI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (components.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                Text("Nessun componente", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("In attesa di dati hardware dal server.", color = AppColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        components.forEachIndexed { index, component ->
            val selected = component.id == selectedId
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(component.id) }
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(component.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(component.primaryValue, color = AppColors.Accent, fontWeight = FontWeight.SemiBold)
                }
                Text(component.subtitle, color = AppColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    progress = { (component.utilizationPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = AppColors.Accent,
                    trackColor = AppColors.Border
                )
                if (selected) {
                    Text("SELEZIONATO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
            if (index != components.lastIndex) {
                HorizontalDivider(color = AppColors.Border)
            }
        }
    }
}

@Composable
internal fun HardwareComponentDetail(component: HardwareComponentView?, history: List<HardwareHistoryPoint>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (component == null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                Text("Nessun componente disponibile.", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Seleziona un componente per vederne i dettagli.", color = AppColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            return@Column
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(component.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(component.subtitle, color = AppColors.Muted, fontSize = 13.sp)
            }
            Text(component.primaryValue, color = AppColors.Accent, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = AppColors.Border)
        HardwareLineChart("Utilizzo", history.map { it.utilizationPercent }, 100.0, "%", AppColors.Accent)
        HardwareLineChart("Temperatura", history.mapNotNull { it.temperatureC }, 100.0, " C", Color(0xFFFF7062))
        HorizontalDivider(color = AppColors.Border)
        Text("DETTAGLI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            component.stats.forEach { stat ->
                Column(modifier = Modifier.widthIn(min = 120.dp, max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stat.label.uppercase(), color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(stat.value, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun HardwareLineChart(title: String, values: List<Double>, maxValue: Double, unit: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((if (values.isEmpty()) "$title: n/d" else "$title: ${String.format(java.util.Locale.US, "%.1f", values.last())}$unit").uppercase(), color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Canvas(modifier = Modifier.fillMaxWidth().height(170.dp).padding(vertical = 8.dp)) {
            val chartWidth = size.width
            val chartHeight = size.height
            repeat(5) { index ->
                val y = chartHeight * index / 4f
                drawLine(color = AppColors.Border, start = Offset(0f, y), end = Offset(chartWidth, y), strokeWidth = 1f)
            }
            if (values.size < 2) return@Canvas
            val clamped = values.takeLast(120).map { it.coerceIn(0.0, maxValue) }
            val step = chartWidth / (clamped.size - 1).coerceAtLeast(1)
            for (i in 1 until clamped.size) {
                val x1 = step * (i - 1)
                val x2 = step * i
                val y1 = chartHeight - (clamped[i - 1] / maxValue * chartHeight).toFloat()
                val y2 = chartHeight - (clamped[i] / maxValue * chartHeight).toFloat()
                drawLine(color = color, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 4f, cap = StrokeCap.Round)
            }
        }
    }
}

internal fun buildHardwareComponents(
    snapshot: HardwareSnapshot,
    temperatures: List<HardwareTemperatureView>,
    downRate: Long,
    upRate: Long
): List<HardwareComponentView> {
    val tempByTitle = temperatures.associateBy { it.title }
    val components = mutableListOf<HardwareComponentView>()
    val cpuTemp = tempByTitle["CPU package"]?.currentC
    components += HardwareComponentView(
        id = "cpu",
        title = "CPU",
        subtitle = snapshot.processor.takeUnless { it == "-" } ?: "${snapshot.physicalCores} core / ${snapshot.logicalCores} thread",
        primaryValue = "${snapshot.cpuPercent.roundToInt().coerceIn(0, 100)}%",
        utilizationPercent = snapshot.cpuPercent,
        temperatureC = cpuTemp,
        stats = listOf(
            HardwareStatView("Utilizzo", "${snapshot.cpuPercent.roundToInt().coerceIn(0, 100)}%"),
            HardwareStatView("Temperatura", formatTemperature(cpuTemp)),
            HardwareStatView("Core", "${snapshot.physicalCores} fisici / ${snapshot.logicalCores} thread"),
            HardwareStatView("Frequenza", "${formatMhz(snapshot.currentMhz)} / max ${formatMhz(snapshot.maxMhz)}"),
            HardwareStatView("Processi", "${snapshot.processCount}"),
            HardwareStatView("Uptime", formatHardwareUptime(snapshot.uptimeSeconds))
        )
    )
    components += HardwareComponentView(
        id = "memory",
        title = "Memoria",
        subtitle = "${snapshot.memoryUsedBytes.toReadableFileSize()} / ${snapshot.memoryTotalBytes.toReadableFileSize()}",
        primaryValue = "${snapshot.memoryPercent.roundToInt().coerceIn(0, 100)}%",
        utilizationPercent = snapshot.memoryPercent,
        temperatureC = null,
        stats = listOf(
            HardwareStatView("Uso RAM", "${snapshot.memoryPercent.roundToInt().coerceIn(0, 100)}%"),
            HardwareStatView("Usata", snapshot.memoryUsedBytes.toReadableFileSize()),
            HardwareStatView("Totale", snapshot.memoryTotalBytes.toReadableFileSize()),
            HardwareStatView("Disponibile", snapshot.memoryAvailableBytes.toReadableFileSize())
        )
    )
    if (snapshot.swapTotalBytes > 0L) {
        components += HardwareComponentView(
            id = "swap",
            title = "Swap",
            subtitle = "${snapshot.swapUsedBytes.toReadableFileSize()} / ${snapshot.swapTotalBytes.toReadableFileSize()}",
            primaryValue = "${snapshot.swapPercent.roundToInt().coerceIn(0, 100)}%",
            utilizationPercent = snapshot.swapPercent,
            temperatureC = null,
            stats = listOf(
                HardwareStatView("Uso swap", "${snapshot.swapPercent.roundToInt().coerceIn(0, 100)}%"),
                HardwareStatView("Usata", snapshot.swapUsedBytes.toReadableFileSize()),
                HardwareStatView("Totale", snapshot.swapTotalBytes.toReadableFileSize())
            )
        )
    }
    val networkPercent = ((downRate + upRate).toDouble() / (125.0 * 1024.0 * 1024.0) * 100.0).coerceIn(0.0, 100.0)
    components += HardwareComponentView(
        id = "network",
        title = "Ethernet",
        subtitle = "Down ${downRate.toReadableFileSize()}/s / Up ${upRate.toReadableFileSize()}/s",
        primaryValue = "${(downRate + upRate).toReadableFileSize()}/s",
        utilizationPercent = networkPercent,
        temperatureC = temperatures.firstOrNull { it.title.startsWith("Ethernet") }?.currentC,
        stats = listOf(
            HardwareStatView("Ricezione", "${downRate.toReadableFileSize()}/s"),
            HardwareStatView("Invio", "${upRate.toReadableFileSize()}/s"),
            HardwareStatView("Totale ricevuto", snapshot.networkBytesReceived.toReadableFileSize()),
            HardwareStatView("Totale inviato", snapshot.networkBytesSent.toReadableFileSize())
        )
    )
    snapshot.gpus.sortedBy { it.index }.forEach { gpu ->
        val memoryPercent = if (gpu.memoryTotalBytes > 0L) {
            (gpu.memoryUsedBytes.toDouble() / gpu.memoryTotalBytes.toDouble() * 100.0).coerceIn(0.0, 100.0)
        } else {
            gpu.memoryUtilizationPercent
        }
        components += HardwareComponentView(
            id = "gpu-${gpu.index}",
            title = "GPU ${gpu.index}",
            subtitle = gpu.name.removePrefix("NVIDIA ").trim(),
            primaryValue = "${gpu.utilizationPercent.roundToInt().coerceIn(0, 100)}%",
            utilizationPercent = gpu.utilizationPercent,
            temperatureC = gpu.temperatureC,
            stats = listOf(
                HardwareStatView("Utilizzo GPU", "${gpu.utilizationPercent.roundToInt().coerceIn(0, 100)}%"),
                HardwareStatView("Temperatura", formatTemperature(gpu.temperatureC)),
                HardwareStatView("VRAM", "${gpu.memoryUsedBytes.toReadableFileSize()} / ${gpu.memoryTotalBytes.toReadableFileSize()} (${memoryPercent.roundToInt().coerceIn(0, 100)}%)"),
                HardwareStatView("Power", "${formatWatts(gpu.powerDrawWatts)} / ${formatWatts(gpu.powerLimitWatts)}"),
                HardwareStatView("Driver", gpu.driverVersion)
            )
        )
    }
    buildHardwareDiskGroups(snapshot.disks).forEachIndexed { index, disk ->
        val diskTemp = if (disk.isSsd) tempByTitle["SSD NVMe"]?.currentC else null
        components += HardwareComponentView(
            id = "disk-$index",
            title = if (disk.isSsd) "SSD $index" else "Disco $index",
            subtitle = disk.subtitle,
            primaryValue = "${disk.percent.roundToInt().coerceIn(0, 100)}%",
            utilizationPercent = disk.percent,
            temperatureC = diskTemp,
            stats = listOf(
                HardwareStatView("Spazio usato", "${disk.percent.roundToInt().coerceIn(0, 100)}%"),
                HardwareStatView("Usato", disk.usedBytes.toReadableFileSize()),
                HardwareStatView("Libero", disk.freeBytes.toReadableFileSize()),
                HardwareStatView("Totale", disk.totalBytes.toReadableFileSize()),
                HardwareStatView("Temperatura", formatTemperature(diskTemp)),
                HardwareStatView("Partizioni", disk.partitionsText),
                HardwareStatView("Device", disk.devicesText)
            )
        )
    }
    return components
}

internal fun buildHardwareDiskGroups(disks: List<HardwareDisk>): List<HardwareDiskGroup> {
    val physicalKeys = disks.mapNotNull { tryHardwarePhysicalDiskKey(it.device) }.distinct()
    val singlePhysicalKey = physicalKeys.singleOrNull()
    return disks
        .groupBy { hardwarePhysicalDiskKey(it.device, singlePhysicalKey) }
        .toSortedMap()
        .map { (key, itemsRaw) ->
            val items = itemsRaw.sortedBy { diskMountSortKey(it.mountpoint) }
            val total = items.sumOf { it.totalBytes.coerceAtLeast(0L) }
            val used = items.sumOf { it.usedBytes.coerceAtLeast(0L) }
            val free = items.sumOf { it.freeBytes.coerceAtLeast(0L) }
            val percent = if (total > 0L) used.toDouble() / total.toDouble() * 100.0 else 0.0
            val isSsd = key.contains("nvme", ignoreCase = true) || items.any { it.device.contains("nvme", ignoreCase = true) }
            val filesystems = items.map { it.fileSystem }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            val subtitle = if (items.size == 1) {
                "${items.first().mountpoint} (${items.first().fileSystem})"
            } else {
                "${items.size} partizioni - $filesystems"
            }
            HardwareDiskGroup(
                key = key,
                isSsd = isSsd,
                subtitle = subtitle,
                totalBytes = total,
                usedBytes = used,
                freeBytes = free,
                percent = percent,
                partitionsText = items.joinToString(", ") { it.mountpoint },
                devicesText = items.map { it.device }.distinct().joinToString(", ")
            )
        }
}

internal fun hardwarePhysicalDiskKey(device: String, singlePhysicalKey: String?): String {
    val direct = tryHardwarePhysicalDiskKey(device)
    if (!direct.isNullOrBlank()) return direct
    if (!singlePhysicalKey.isNullOrBlank() && device.startsWith("/dev/mapper/", ignoreCase = true)) return singlePhysicalKey
    return device
}

internal fun tryHardwarePhysicalDiskKey(device: String): String? {
    val name = device.trim().replace("\\", "/").substringAfterLast("/")
    if (name.startsWith("nvme", ignoreCase = true)) {
        val partitionIndex = name.indexOf('p')
        return if (partitionIndex > 0) name.substring(0, partitionIndex) else name
    }
    if (name.startsWith("sd", ignoreCase = true)) {
        val base = name.dropLastWhile { it.isDigit() }
        return base.ifBlank { name }
    }
    return null
}

internal fun diskMountSortKey(mountpoint: String): String {
    return if (mountpoint == "/") " " else mountpoint
}

internal val ignoredHardwareFileSystems = setOf(
    "autofs", "cgroup", "cgroup2", "configfs", "debugfs", "devtmpfs", "efivarfs",
    "fusectl", "hugetlbfs", "mqueue", "nsfs", "overlay", "proc", "pstore", "ramfs",
    "securityfs", "squashfs", "sysfs", "tmpfs", "tracefs"
)

internal fun isMeaningfulHardwareDisk(device: String, fileSystem: String): Boolean {
    return !device.trim().startsWith("/dev/loop", ignoreCase = true) &&
        fileSystem.trim().lowercase() !in ignoredHardwareFileSystems
}

internal fun List<HardwareTemperature>.toHardwareTemperatureViews(): List<HardwareTemperatureView> {
    val hasNvmeComposite = any { it.name.equals("nvme", ignoreCase = true) && it.label.equals("Composite", ignoreCase = true) }
    return mapNotNull { temp ->
        val current = temp.currentC
        if (!current.isFinite() || current < 0.0 || current > 150.0) {
            return@mapNotNull null
        }
        if (hasNvmeComposite &&
            temp.name.equals("nvme", ignoreCase = true) &&
            temp.label.startsWith("Sensor 2", ignoreCase = true)
        ) {
            return@mapNotNull null
        }
        val rawName = temp.name.trim()
        val rawLabel = temp.label.trim()
        val name = rawName.lowercase()
        val label = rawLabel.lowercase()
        val title = when {
            name == "k10temp" && label == "tctl" -> "CPU package"
            name == "k10temp" && label.startsWith("tccd") -> "CPU CCD ${rawLabel.filter { it.isDigit() }.ifBlank { "1" }}"
            name == "nvme" && label == "composite" -> "SSD NVMe"
            name == "nvme" && label == "sensor 1" -> "SSD NVMe controller"
            name == "nvme" && label == "sensor 3" -> "SSD NVMe NAND"
            name.startsWith("spd") -> "RAM DIMM"
            name.startsWith("r8169") -> "Ethernet controller ${rawName.substringAfter("_0_", "").ifBlank { "" }}".trim()
            rawLabel.isNotBlank() && rawLabel != "-" -> rawLabel
            else -> rawName.ifBlank { "Sensore temperatura" }
        }
        val sortKey = when {
            title.startsWith("CPU package") -> 0
            title.startsWith("CPU CCD") -> 1
            title.startsWith("SSD") -> 2
            title.startsWith("RAM") -> 3
            title.startsWith("Ethernet") -> 4
            else -> 9
        }
        HardwareTemperatureView(
            title = title,
            source = "Sensore ${rawName.ifBlank { "-" }}${if (rawLabel.isNotBlank() && rawLabel != rawName) " / $rawLabel" else ""}",
            currentC = current,
            highC = sanitizeTemperatureLimit(temp.highC),
            criticalC = sanitizeTemperatureLimit(temp.criticalC),
            sortKey = sortKey
        )
    }
        .distinctBy { it.title }
        .sortedWith(compareBy<HardwareTemperatureView> { it.sortKey }.thenByDescending { it.currentC })
}

internal fun sanitizeTemperatureLimit(value: Double?): Double? {
    return value?.takeIf { it.isFinite() && it in 1.0..150.0 }
}

@Composable
internal fun OperatorScreen(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf("GET /health") }
    var params by remember { mutableStateOf("") }
    var approvalId by remember { mutableStateOf("") }
    var baseHash by remember { mutableStateOf("") }
    var configPatch by remember { mutableStateOf("{\"ops\":[]}") }
    var workspacePath by rememberSaveable { mutableStateOf("") }
    var workspaceText by rememberSaveable { mutableStateOf("") }
    var quickRunText by rememberSaveable { mutableStateOf("Controlla lo stato operativo e riassumi cosa richiede attenzione.") }
    var status by remember { mutableStateOf("Pronto.") }
    var summary by remember { mutableStateOf("Nessuna risposta.") }
    var raw by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Cron", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Automazioni Hermes programmate sul gateway.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Avvia lavoro in background", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Scrivi cosa deve fare Hermes. Non serve conoscere endpoint, JSON o ID tecnici.", color = AppColors.Muted, fontSize = 13.sp)
                    SettingsField("Cosa deve fare Hermes?", quickRunText, { quickRunText = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(onClick = {
                            val input = quickRunText.ifBlank { "Controlla stato operativo Hermes e riassumi." }
                            runOperatorRpc(scope, context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${input.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it })
                        }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Avvia lavoro", tint = Color.White) }
                        IconButton(onClick = {
                            val input = "Crea o prepara un video per l'utente. Salva il file finale nella cartella video configurata sul server cosi appare nella sezione Video."
                            quickRunText = input
                            runOperatorRpc(scope, context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${input.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it })
                        }) { Icon(Icons.Rounded.Videocam, contentDescription = "Crea video", tint = Color.White) }
                        IconButton(onClick = {
                            runOperatorRpc(scope, context, settings, "GET /api/jobs", "", { status = it }, { summary = it }, { raw = it })
                        }) { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Vedi lavori", tint = Color.White) }
                    }
                    Text(status, color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Preset Hermes", color = Color.White, fontWeight = FontWeight.SemiBold)
                    OPERATOR_PRESETS.groupBy { it.group }.forEach { (group, presets) ->
                        Text(group, color = AppColors.Muted, fontSize = 12.sp)
                        presets.forEach { preset ->
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    method = preset.method
                                    params = preset.params
                                    status = "${preset.method}..."
                                    summary = "Attesa risposta Hermes..."
                                    raw = ""
                                    scope.launch {
                                        val result = hermesHttpCall(settings, loadGatewaySecret(context), preset.method, preset.params)
                                        status = result.status
                                        summary = result.summary
                                        raw = result.rawJson.ifBlank { result.summary }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Elevated, contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(preset.label)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cron", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Cron ID", approvalId, { approvalId = it })
                    OperatorActionButton("Lista") { runOperatorRpc(scope, context, settings, "GET /api/jobs", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Run") { runOperatorRpc(scope, context, settings, "POST /api/jobs/${URLEncoder.encode(approvalId.trim(), "UTF-8")}/run", "{}", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Pausa") { runOperatorRpc(scope, context, settings, "POST /api/jobs/${URLEncoder.encode(approvalId.trim(), "UTF-8")}/pause", "{}", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Elimina") { runOperatorRpc(scope, context, settings, "DELETE /api/jobs/${URLEncoder.encode(approvalId.trim(), "UTF-8")}", "", { status = it }, { summary = it }, { raw = it }) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Run tecnico", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Run ID", baseHash, { baseHash = it })
                    SettingsField("Input run", configPatch, { configPatch = it })
                    OperatorActionButton("Capabilities") { runOperatorRpc(scope, context, settings, "GET /v1/capabilities", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Crea run") { runOperatorRpc(scope, context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${configPatch.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Models") { runOperatorRpc(scope, context, settings, "GET /v1/models", "", { status = it }, { summary = it }, { raw = it }) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Diagnostica", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Filtro cron", workspacePath, { workspacePath = it })
                    SettingsField("Input run rapido", workspaceText, { workspaceText = it })
                    OperatorActionButton("Cron") { runOperatorRpc(scope, context, settings, "GET /api/jobs", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Health") { runOperatorRpc(scope, context, settings, "GET /health/detailed", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Run") { runOperatorRpc(scope, context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${workspaceText.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it }) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Endpoint manuale", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Metodo + path", method, { method = it })
                    SettingsField("Body JSON", params, { params = it })
                    IconButton(onClick = {
                        status = "${method.trim()}..."
                        summary = "Attesa risposta Hermes..."
                        raw = ""
                        scope.launch {
                            val result = hermesHttpCall(settings, loadGatewaySecret(context), method, params)
                            status = result.status
                            summary = result.summary
                            raw = result.rawJson.ifBlank { result.summary }
                        }
                    }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Esegui chiamata", tint = Color.White)
                    }
                    Text(status, color = AppColors.Muted)
                    Text(summary, color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = raw.ifBlank { "Nessuna risposta." },
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun OperatorActionButton(label: String, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Elevated, contentColor = Color.White),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(label)
    }
}

@Composable
internal fun VideoScreen(context: Context, settings: AppSettings, onOpenChatPrompt: (String) -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Sincronizzo cartella video Hermes...") }
    var items by remember { mutableStateOf<List<VideoLibraryItem>>(emptyList()) }
    var selectedVideoId by rememberSaveable { mutableStateOf<String?>(null) }
    var videoFilter by rememberSaveable { mutableStateOf("Tutti") }
    var manualVideoUrl by rememberSaveable { mutableStateOf("") }
    var manualVideoError by rememberSaveable { mutableStateOf("") }
    val manualVideoItem = remember(manualVideoUrl) { createManualVideoItem(manualVideoUrl) }
    val allItems = remember(items, manualVideoItem) {
        if (manualVideoItem == null) items else listOf(manualVideoItem) + items
    }
    val selectedVideo = remember(allItems, selectedVideoId) { allItems.firstOrNull { it.id == selectedVideoId } }
    val displayedItems = remember(allItems, videoFilter) {
        when (videoFilter) {
            "Recenti" -> allItems.sortedByDescending { it.modifiedAt }
            "Feedback" -> allItems.filter { loadVideoFeedback(context, it.id).isNotBlank() || loadVideoReaction(context, it.id).isNotBlank() }
            else -> allItems
        }
    }

    LaunchedEffect(settings.gatewayUrl, settings.videoLibraryPath, refreshKey) {
        val result = loadVideoLibrary(settings, loadGatewaySecret(context))
        items = result.first
        status = result.second
        if (selectedVideoId != null && selectedVideoId?.startsWith("manual:") != true && result.first.none { it.id == selectedVideoId }) {
            selectedVideoId = null
        }
    }

    if (selectedVideo != null) {
        BackHandler { selectedVideoId = null }
        VideoWatchScreen(
            context = context,
            settings = settings,
            item = selectedVideo,
            onBack = { selectedVideoId = null }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    Icon(Icons.Rounded.Videocam, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hermes Video", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(settings.videoLibraryPath.ifBlank { "Sincronizzo cartella video Hermes..." }, color = AppColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    onOpenChatPrompt(
                        "Modalita Video Hermes Hub. Usa la cartella video monitorata del PC Hermes: ${settings.videoLibraryPath}. " +
                            "Crea, scarica o prepara un video e salva sempre il file finale in quella cartella, cosi appare automaticamente nella sezione Video. Richiesta: "
                    )
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Nuovo video", tint = AppColors.Accent)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoFeedChip("Tutti", selected = videoFilter == "Tutti") { videoFilter = "Tutti" }
                VideoFeedChip("Recenti", selected = videoFilter == "Recenti") { videoFilter = "Recenti" }
                VideoFeedChip("Feedback", selected = videoFilter == "Feedback") { videoFilter = "Feedback" }
                TextButton(
                    onClick = {
                        status = "Aggiorno feed video..."
                        refreshKey++
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                ) { Text("Aggiorna", fontWeight = FontWeight.SemiBold) }
            }
        }
        item {
            Text(status, color = AppColors.Faint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        item {
            Surface(color = AppColors.Panel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppColors.Border)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("URL VIDEO MANUALE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    TextField(
                        value = manualVideoUrl,
                        onValueChange = {
                            manualVideoUrl = it
                            manualVideoError = ""
                        },
                        singleLine = true,
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val manual = createManualVideoItem(manualVideoUrl)
                                if (manual == null) {
                                    manualVideoError = "URL non valido. Usa un link http/https diretto."
                                } else {
                                    manualVideoError = ""
                                    selectedVideoId = manual.id
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Apri video", fontWeight = FontWeight.SemiBold)
                        }
                        if (manualVideoError.isNotBlank()) {
                            Text(manualVideoError, color = AppColors.Accent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (displayedItems.isEmpty()) {
            item {
                Surface(color = AppColors.Panel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppColors.Border)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Videocam, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                        Text(
                            if (items.isEmpty()) "Nessun video trovato" else "Nessun video con feedback salvato",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            if (items.isEmpty()) {
                                "Metti un file video nella cartella video Hermes sul PC e premi Aggiorna feed."
                            } else {
                                "Nessun video con feedback salvato."
                            },
                            color = AppColors.Muted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                status = "Aggiorno feed video..."
                                refreshKey++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                        ) { Text("Aggiorna feed", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        items(displayedItems, key = { it.id }) { video ->
            VideoFeedCard(
                settings = settings,
                item = video,
                apiKey = loadGatewaySecret(context),
                onClick = { selectedVideoId = video.id }
            )
        }
    }
}

@Composable
internal fun ImageScreen(context: Context, settings: AppSettings, onOpenChatPrompt: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    // Radice iniziale: impostazione immagini, senno default del manager.
    val startPath = remember(settings.imageLibraryPath, settings.extraImageLibraryPaths) {
        settings.imageLibraryPath.trim().ifBlank {
            settings.extraImageLibraryPaths.firstOrNull { it.isNotBlank() }.orEmpty()
        }
    }
    var currentPath by rememberSaveable(settings.gatewayUrl, startPath) {
        mutableStateOf(startPath.ifBlank { null })
    }
    var parentPath by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<ImageBrowseFolder>>(emptyList()) }
    var items by remember { mutableStateOf<List<ImageLibraryItem>>(emptyList()) }
    var status by remember { mutableStateOf("Sfoglio cartella immagini...") }
    // Foto aperta (id): la lista resta composta sotto il dialogo (niente
    // early-return), cosi scroll e miniature non si perdono. Al cambio
    // cartella il viewer si chiude (l'id non apparterra piu alla lista).
    var viewerId by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    val apiKey = remember { loadGatewaySecret(context) }
    // Browse manager (:8643/browse, ruolo app dal lockdown): mai la chiave gateway.
    val managerKey = remember { loadGpuAppSecret(context) }
    // Scroll issato: sopravvive ad apertura/chiusura viewer.
    val listState = rememberLazyListState()

    LaunchedEffect(settings.gatewayUrl, currentPath, refreshKey) {
        status = "Sfoglio cartella immagini..."
        val result = loadImageBrowse(settings, managerKey, currentPath)
        parentPath = result.parent
        folders = result.folders
        items = result.items
        status = result.status
        // Prima apertura senza radice: adotta il default del server (una sola volta).
        if (currentPath == null && result.path.isNotBlank()) {
            currentPath = result.path
        }
    }
    // Cambio cartella: chiudi eventuale viewer (foto di un'altra lista).
    LaunchedEffect(currentPath) {
        viewerId = null
    }

    val viewingIndex = viewerId?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    if (viewingIndex != null) {
        BackHandler { viewerId = null }
        ChatImageGalleryDialog(
            items = items,
            initialIndex = viewingIndex,
            settings = settings,
            apiKey = apiKey,
            isDownloading = isDownloading,
            onClose = { viewerId = null },
            onDownload = { item ->
                scope.launch {
                    isDownloading = true
                    try {
                        openHermesMediaExternally(
                            context, settings,
                            resolveWorkspaceUrl(settings, item.mediaUrl),
                            item.filename.ifBlank { "hermes-immagine" },
                            item.mimeType.ifBlank { "image/*" }, apiKey
                        )
                    } finally {
                        isDownloading = false
                    }
                }
            }
        )
    }
    // Back fisico: risale di un livello (mai fuori dalla sezione, mai sopra il viewer).
    val parent = parentPath
    BackHandler(enabled = parent != null && viewingIndex == null) {
        currentPath = parent
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppColors.Accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hermes Immagini", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, color = AppColors.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onOpenChatPrompt(
                            "Modalita Immagini Hermes Hub. Usa la cartella immagini monitorata del PC Hermes: ${settings.imageLibraryPath.ifBlank { "quella del server" }}. " +
                                "Crea o modifica un'immagine e salva sempre il file finale in quella cartella, cosi appare automaticamente nella sezione Immagini. Richiesta: "
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Nuova immagine", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                TextButton(onClick = {
                    status = "Aggiorno cartella..."
                    refreshKey++
                }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Aggiorna", color = AppColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("PERCORSO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            // Navigazione: Home (radice) + Su (parent server) + segmenti.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { currentPath = startPath.ifBlank { null } }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Home", color = AppColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                if (parent != null) {
                    Text("·", color = AppColors.Faint, fontSize = 14.sp)
                    TextButton(onClick = { currentPath = parent }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("↑ Su", color = AppColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                }
                val shown = currentPath
                if (!shown.isNullOrBlank()) {
                    browseSegments(shown).takeLast(3).forEach { (label, segmentPath) ->
                        Text("·", color = AppColors.Faint, fontSize = 14.sp)
                        TextButton(onClick = { currentPath = segmentPath }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) {
                            Text(label, color = if (segmentPath == shown) Color.White else AppColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (folders.isNotEmpty()) {
            item {
                Text("CARTELLE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            items(folders, key = { "folder|" + it.path }) { folder ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { currentPath = folder.path }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppColors.NavIndicator),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                        }
                        Text(
                            folder.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Apri cartella", tint = AppColors.Muted)
                    }
                    HorizontalDivider(color = AppColors.Border)
                }
            }
        }
        if (items.isEmpty() && folders.isEmpty()) {
            item {
                Surface(color = AppColors.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppColors.Border)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                        Text("Niente qui", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(
                            text = "Entra in una cartella o genera immagini con Hermes.",
                            color = AppColors.Muted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                onOpenChatPrompt(
                                    "Modalita Immagini Hermes Hub. Usa la cartella immagini monitorata del PC Hermes: ${settings.imageLibraryPath.ifBlank { "quella del server" }}. " +
                                        "Crea o modifica un'immagine e salva sempre il file finale in quella cartella, cosi appare automaticamente nella sezione Immagini. Richiesta: "
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                        ) {
                            Text("Genera con Hermes", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else if (items.isNotEmpty()) {
            item {
                Text("FOTO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        items(items.chunked(2), key = { row -> row.joinToString("|") { it.folder + "#" + it.id } }) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { item ->
                    ImageFeedCard(
                        settings = settings,
                        item = item,
                        apiKey = apiKey,
                        onClick = { viewerId = item.id },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun folderLabel(folder: String): String =
    folder.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() } ?: "Principale"

@Composable
internal fun ImageFeedCard(
    settings: AppSettings,
    item: ImageLibraryItem,
    apiKey: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageUrl = remember(settings.gatewayUrl, item.mediaUrl) { resolveWorkspaceUrl(settings, item.mediaUrl) }
    val bitmap by produceState<Bitmap?>(initialValue = null, imageUrl, apiKey) {
        // Card piccola: decodifica campionata a 256px (il fullscreen usa il full).
        value = withContext(Dispatchers.IO) { loadRemoteBitmap(settings, imageUrl, apiKey, FEED_THUMBNAIL_MAX_DIM) }
    }
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.Panel),
            contentAlignment = Alignment.Center
        ) {
            val loaded = bitmap
            if (loaded != null) {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = item.title.ifBlank { item.filename },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.Image, contentDescription = null, tint = AppColors.Muted, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            item.title.ifBlank { item.filename },
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${item.sizeBytes.toReadableFileSize()} - ${folderLabel(item.folder)}",
            color = AppColors.Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun createManualVideoItem(rawUrl: String): VideoLibraryItem? {    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: "link esterno"
    return VideoLibraryItem(
        id = "manual:${trimmed.hashCode()}",
        title = "URL video manuale",
        filename = host,
        mediaUrl = trimmed,
        thumbnailUrl = "",
        path = trimmed,
        mimeType = "video/url",
        sizeBytes = 0L,
        durationMs = 0L,
        modifiedAt = System.currentTimeMillis()
    )
}

internal fun resolveVideoPlaybackUrl(settings: AppSettings, item: VideoLibraryItem): String {
    if (item.compatUrl.isBlank() &&
        item.playbackUrl.contains("format=mp4", ignoreCase = true) &&
        item.playbackUrl.contains("/v1/media/", ignoreCase = true) &&
        item.mediaUrl.isNotBlank()
    ) {
        return resolveWorkspaceUrl(settings, item.mediaUrl)
    }
    val raw = item.playbackUrl.ifBlank { item.mediaUrl }
    return resolveWorkspaceUrl(settings, raw)
}

internal fun resolveVideoCompatUrl(settings: AppSettings, item: VideoLibraryItem): String {
    val raw = item.compatUrl.ifBlank {
        if (item.playbackUrl.contains("format=mp4", ignoreCase = true) &&
            item.playbackUrl.contains("/v1/media/", ignoreCase = true)
        ) {
            return resolveWorkspaceUrl(settings, item.playbackUrl)
        }
        val base = item.mediaUrl.ifBlank { item.playbackUrl }
        if (base.isBlank()) return ""
        val resolvedBase = resolveWorkspaceUrl(settings, base)
        if (!resolvedBase.contains("/v1/media/", ignoreCase = true) ||
            resolvedBase.contains("format=mp4", ignoreCase = true)
        ) {
            return resolvedBase
        }
        val separator = if (resolvedBase.contains("?")) "&" else "?"
        return "$resolvedBase${separator}format=mp4"
    }
    return resolveWorkspaceUrl(settings, raw)
}

@Composable
internal fun VideoFeedChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (selected) AppColors.Accent else AppColors.Panel,
        contentColor = if (selected) Color(0xFF171009) else Color.White,
        shape = RoundedCornerShape(20.dp),
        border = if (selected) null else BorderStroke(1.dp, AppColors.Border)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

@Composable
internal fun VideoFeedCard(settings: AppSettings, item: VideoLibraryItem, apiKey: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VideoThumbnail(settings, item, apiKey, modifier = Modifier.width(120.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Hermes Hub - ${item.sizeBytes.toReadableFileSize()} - ${formatVideoTimestamp(item.modifiedAt)}",
                    color = AppColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(22.dp))
        }
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}

@Composable
internal fun VideoThumbnail(settings: AppSettings, item: VideoLibraryItem, apiKey: String?, modifier: Modifier = Modifier) {
    val videoUrl = remember(settings.gatewayUrl, item.mediaUrl, item.playbackUrl) { resolveVideoPlaybackUrl(settings, item) }
    val thumbUrl = remember(settings.gatewayUrl, item.thumbnailUrl) {
        item.thumbnailUrl.takeIf { it.isNotBlank() }?.let { resolveWorkspaceUrl(settings, it) }
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, videoUrl, thumbUrl, apiKey) {
        value = withContext(Dispatchers.IO) {
            // Card piccola: miniature campionate a 256px (il fullscreen usa il full).
            thumbUrl?.let { loadRemoteBitmap(settings, it, apiKey, FEED_THUMBNAIL_MAX_DIM) } ?: loadVideoThumbnail(settings, videoUrl, apiKey, FEED_THUMBNAIL_MAX_DIM)
        }
    }
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.linearGradient(listOf(AppColors.Panel, Color.Black, AppColors.Accent.copy(alpha = 0.22f))),
                    size = size
                )
            }
            Icon(Icons.Rounded.PlayCircle, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(56.dp))
        }
        val duration = formatVideoDuration(item.durationMs)
        if (duration.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(duration, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

internal fun fullscreenLandscapeOrientation(autoRotateEnabled: Boolean, displayRotation: Int): Int {
    if (autoRotateEnabled) return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    return if (displayRotation == android.view.Surface.ROTATION_270) {
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun isSystemAutoRotateEnabled(context: Context): Boolean = runCatching {
    Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
}.getOrDefault(false)

internal fun Activity.currentDisplayRotation(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: android.view.Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }
}

@Composable
internal fun FullscreenVideoOrientationEffect(enabled: Boolean, activity: Activity?) {
    DisposableEffect(enabled, activity) {
        if (!enabled || activity == null) {
            onDispose { }
        } else {
            val previousOrientation = activity.requestedOrientation
            val targetOrientation = fullscreenLandscapeOrientation(
                autoRotateEnabled = isSystemAutoRotateEnabled(activity),
                displayRotation = activity.currentDisplayRotation()
            )
            val changedOrientation = previousOrientation != targetOrientation
            if (changedOrientation) {
                activity.requestedOrientation = targetOrientation
            }
            onDispose {
                if (
                    changedOrientation &&
                    !activity.isFinishing &&
                    !activity.isDestroyed &&
                    activity.requestedOrientation == targetOrientation
                ) {
                    activity.requestedOrientation = previousOrientation
                }
            }
        }
    }
}

@Composable
internal fun FullscreenVideoSystemUi() {
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        if (dialogWindow == null) {
            onDispose { }
        } else {
            val previousCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            val controller = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && previousCutoutMode != null) {
                    dialogWindow.attributes = dialogWindow.attributes.apply {
                        layoutInDisplayCutoutMode = previousCutoutMode
                    }
                }
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createVideoPlayerView(
    context: Context,
    player: Player,
    onControllerVisibilityChanged: ((Boolean) -> Unit)? = null
): PlayerView {
    return PlayerView(context).apply {
        useController = true
        controllerAutoShow = true
        controllerHideOnTouch = true
        controllerShowTimeoutMs = 3_500
        setKeepContentOnPlayerReset(true)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        if (onControllerVisibilityChanged != null) {
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    onControllerVisibilityChanged(visibility == android.view.View.VISIBLE)
                }
            )
        }
        this.player = player
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
internal fun VideoWatchScreen(context: Context, settings: AppSettings, item: VideoLibraryItem, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val apiKey = remember { loadGatewaySecret(context) }
    var useCompatPlayback by rememberSaveable(item.id) { mutableStateOf(false) }
    val primaryVideoUrl = remember(settings.gatewayUrl, item.mediaUrl, item.playbackUrl) { resolveVideoPlaybackUrl(settings, item) }
    val compatVideoUrl = remember(settings.gatewayUrl, item.mediaUrl, item.playbackUrl, item.compatUrl) { resolveVideoCompatUrl(settings, item) }
    val videoUrl = remember(primaryVideoUrl, compatVideoUrl, useCompatPlayback) {
        if (useCompatPlayback && compatVideoUrl.isNotBlank()) compatVideoUrl else primaryVideoUrl
    }
    val player = remember(videoUrl, apiKey) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(if (shouldAuthenticateHermesUrl(settings, videoUrl)) authHeaders(apiKey) else emptyMap())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(videoUrl.toUri()))
                prepare()
                playWhenReady = true
            }
    }
    var feedback by remember(item.id) { mutableStateOf(loadVideoFeedback(context, item.id)) }
    var reaction by remember(item.id) { mutableStateOf(loadVideoReaction(context, item.id)) }
    var status by remember(item.id) { mutableStateOf("Lascia feedback: Hermes lo usera' come memoria editoriale per i prossimi video.") }
    var fullScreen by remember(item.id) { mutableStateOf(false) }
    var fullScreenControlsVisible by remember(item.id) { mutableStateOf(true) }
    val activity = remember(context) { context.findActivity() }
    FullscreenVideoOrientationEffect(enabled = fullScreen, activity = activity)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!useCompatPlayback && compatVideoUrl.isNotBlank() && compatVideoUrl != primaryVideoUrl) {
                    useCompatPlayback = true
                    status = "Player video: ${error.errorCodeName}. Passo al proxy MP4 compatibile Hermes."
                } else {
                    status = "Player video: ${error.errorCodeName}. Nessun fallback compatibile disponibile."
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = AppColors.Accent)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (!fullScreen) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext -> createVideoPlayerView(viewContext, player) },
                            update = { view ->
                                view.player = player
                            }
                        )
                    }
                    IconButton(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.62f), CircleShape),
                        onClick = {
                            fullScreenControlsVisible = true
                            fullScreen = true
                        }
                    ) {
                        Icon(Icons.Rounded.CropFree, contentDescription = "Schermo intero", tint = Color.White)
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                            Icon(Icons.Rounded.Videocam, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Hermes Hub - ${item.filename} - ${item.sizeBytes.toReadableFileSize()} - ${formatVideoTimestamp(item.modifiedAt)}",
                                color = AppColors.Muted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(status, color = AppColors.Faint, fontSize = 12.sp)
                    Text("FEEDBACK", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        VideoReactionButton(
                            label = "Mi piace",
                            icon = Icons.Rounded.ThumbUp,
                            selected = reaction == "like"
                        ) {
                            reaction = "like"
                            status = "Invio like a Hermes..."
                            scope.launch {
                                val result = sendVideoLibraryFeedback(settings, item, feedback, reaction, loadGatewaySecret(context))
                                saveVideoFeedback(context, item.id, feedback, reaction, result)
                                status = result
                            }
                        }
                        VideoReactionButton(
                            label = "Non mi piace",
                            icon = Icons.Rounded.ThumbDown,
                            selected = reaction == "dislike"
                        ) {
                            reaction = "dislike"
                            status = "Invio dislike a Hermes..."
                            scope.launch {
                                val result = sendVideoLibraryFeedback(settings, item, feedback, reaction, loadGatewaySecret(context))
                                saveVideoFeedback(context, item.id, feedback, reaction, result)
                                status = result
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoFeedChip("Hook") { feedback = appendFeedbackSnippet(feedback, "Hook piu' forte nei primi 5 secondi") }
                        VideoFeedChip("Ritmo") { feedback = appendFeedbackSnippet(feedback, "Ritmo piu' veloce e meno pause") }
                        VideoFeedChip("Chiarezza") { feedback = appendFeedbackSnippet(feedback, "Piu' chiarezza didattica e step concreti") }
                        VideoFeedChip("Montaggio") { feedback = appendFeedbackSnippet(feedback, "Montaggio piu' pulito e meno ridondanza") }
                    }
                    Text("FEEDBACK PER HERMES", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    SettingsField("Feedback per Hermes", feedback, { feedback = it })
                    Button(
                        onClick = {
                            if (feedback.isBlank()) {
                                status = "Scrivi feedback prima di inviare."
                                return@Button
                            }
                            status = "Invio feedback a Hermes..."
                            scope.launch {
                                val result = sendVideoLibraryFeedback(settings, item, feedback, reaction, loadGatewaySecret(context))
                                saveVideoFeedback(context, item.id, feedback, reaction, result)
                                status = result
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Invia feedback", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (fullScreen) {
            Dialog(
                onDismissRequest = { fullScreen = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false
                )
            ) {
                FullscreenVideoSystemUi()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            createVideoPlayerView(viewContext, player) { visible ->
                                fullScreenControlsVisible = visible
                            }
                        },
                        update = { view ->
                            view.player = player
                        }
                    )
                    AnimatedVisibility(
                        visible = fullScreenControlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(92.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent)
                                    )
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                IconButton(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.42f), CircleShape),
                                    onClick = { fullScreen = false }
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Chiudi schermo intero", tint = Color.White)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "Schermo intero",
                                        color = Color.White.copy(alpha = 0.72f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoReactionButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) AppColors.Accent else AppColors.Panel,
            contentColor = if (selected) Color(0xFF171009) else Color.White
        )
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
internal fun NewsScreen(context: Context, settings: AppSettings, onOpenChatPrompt: (String) -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Articoli HTML creati da Hermes.") }
    var selectedHtmlId by rememberSaveable { mutableStateOf<String?>(null) }
    var htmlItems by remember { mutableStateOf<List<NewsHtmlItem>>(emptyList()) }
    val selectedHtml = remember(htmlItems, selectedHtmlId) { htmlItems.firstOrNull { it.id == selectedHtmlId } }

    LaunchedEffect(settings.gatewayUrl, refreshKey) {
        val result = loadNewsLibrary(settings, loadGatewaySecret(context))
        htmlItems = result.first
        status = result.second
    }

    if (selectedHtml != null) {
        BackHandler { selectedHtmlId = null }
        NewsHtmlScreen(
            context = context,
            settings = settings,
            item = selectedHtml,
            onBack = { selectedHtmlId = null }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = AppColors.Accent.copy(alpha = 0.18f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Newspaper, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hermes News", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text(status, color = AppColors.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onOpenChatPrompt("Crea un articolo per la sezione News di Hermes Hub: ") }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Nuovo articolo", tint = AppColors.Accent)
                }
                // Un solo GET per refresh: basta il bump di refreshKey, il
                // LaunchedEffect sopra ricarica (niente doppio load diretto).
                IconButton(onClick = {
                    status = "Sincronizzo articoli Hermes..."
                    refreshKey++
                }) { Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna news", tint = AppColors.Muted) }
            }
        }
        item {
            Text("ARTICOLI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        if (htmlItems.isEmpty()) {
            item {
                Surface(color = AppColors.Panel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppColors.Border)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Newspaper, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                        Text("Nessun articolo HTML trovato.", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(
                            text = "Chiedi a Hermes di creare un giornale HTML e salvarlo in ${settings.newsLibraryPath}.",
                            color = AppColors.Muted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { onOpenChatPrompt("Crea un articolo per la sezione News di Hermes Hub: ") },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Nuovo articolo", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        items(htmlItems, key = { "html:${it.id}" }) { page ->
            NewsHtmlCard(item = page, onClick = { selectedHtmlId = page.id })
        }
    }
}

@Composable
internal fun NewsArticleCard(article: WorkspaceRequest, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = AppColors.Panel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(modifier = Modifier.size(44.dp), shape = RoundedCornerShape(13.dp), color = AppColors.Accent.copy(alpha = 0.18f)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        article.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${article.source} - ${article.status} - ${formatVideoTimestamp(article.updatedAt)}",
                        color = AppColors.Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(20.dp))
            }
            HorizontalDivider(color = AppColors.Border)
            val preview = article.result.ifBlank { article.prompt }.limitText(420)
            MarkdownText(preview, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            if (article.feedback.isNotBlank()) {
                Text("Feedback salvato", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun NewsHtmlCard(item: NewsHtmlItem, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AppColors.NavIndicator),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Language, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.filename} - ${item.sizeBytes.toReadableFileSize()} - ${formatVideoTimestamp(item.modifiedAt)}",
                    color = AppColors.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(color = AppColors.Border)
    }
}

// Articoli statici: niente JS, niente bridge, fail-closed sui link.
internal fun createNewsWebView(context: Context): WebView = WebView(context).apply {
    settings.javaScriptEnabled = false
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Fail-closed: URL nullo o schema non http(s) mai caricati in WebView.
            val target = request.url ?: return true
            if (!target.scheme.equals("http", ignoreCase = true) &&
                !target.scheme.equals("https", ignoreCase = true)
            ) {
                return true
            }
            // Resta in WebView solo per stesso host della pagina caricata.
            val currentHost = runCatching { (view.url ?: "").toUri().host }.getOrNull()
            if (currentHost != null && target.host.equals(currentHost, ignoreCase = true)) return false
            // Link esterni nel browser di sistema.
            runCatching {
                view.context.startActivity(
                    Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return true
        }
    }
}

internal fun updateNewsWebView(webView: WebView, pageUrl: String, html: String) {
    val content = if (html.isBlank()) {
        "<html><body style=\"font-family:sans-serif;background:#111827;color:#fff\"><p>Caricamento...</p></body></html>"
    } else {
        injectHtmlBase(html, pageUrl)
    }
    val contentKey = "$pageUrl:${content.hashCode()}"
    if (webView.tag == contentKey) return
    webView.tag = contentKey
    webView.loadDataWithBaseURL(pageUrl, content, "text/html", "utf-8", null)
}

internal fun releaseNewsWebView(webView: WebView) {
    webView.stopLoading()
    webView.webViewClient = WebViewClient()
    webView.loadUrl("about:blank")
    webView.clearHistory()
    webView.removeAllViews()
    webView.destroy()
}

@Composable
internal fun NewsHtmlScreen(context: Context, settings: AppSettings, item: NewsHtmlItem, onBack: () -> Unit) {
    val apiKey = remember { loadGatewaySecret(context) }
    val pageUrl = remember(settings.gatewayUrl, item.url) { resolveWorkspaceUrl(settings, item.url) }
    var status by remember(item.id) { mutableStateOf("Carico pagina HTML...") }
    var html by remember(item.id) { mutableStateOf("") }
    var fullScreen by rememberSaveable(item.id) { mutableStateOf(false) }

    LaunchedEffect(pageUrl, apiKey) {
        val loaded = loadNewsHtml(settings, item, apiKey)
        html = loaded.first
        status = loaded.second
    }

    DisposableEffect(fullScreen) {
        if (!fullScreen) {
            onDispose { }
        } else {
            val activity = context as? Activity
            val window = activity?.window
            val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
            insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                insetsController?.show(WindowInsetsCompat.Type.systemBars())
                if (window != null) {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
        if (!fullScreen) {
            Column(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = AppColors.Accent)
                    }
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        color = AppColors.Accent.copy(alpha = 0.18f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Language, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status, color = AppColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { fullScreen = true }) {
                        Icon(Icons.Rounded.CropFree, contentDescription = "Apri a schermo intero", tint = AppColors.Accent)
                    }
                }
                HorizontalDivider(color = AppColors.Border)
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = ::createNewsWebView,
                    update = { updateNewsWebView(it, pageUrl, html) },
                    onRelease = ::releaseNewsWebView
                )
            }
        }

        if (fullScreen) {
            BackHandler { fullScreen = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = ::createNewsWebView,
                    update = { updateNewsWebView(it, pageUrl, html) },
                    onRelease = ::releaseNewsWebView
                )
                TextButton(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    onClick = { fullScreen = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                ) {
                    Text("Chiudi", color = AppColors.Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun NewsArticleScreen(
    context: Context,
    settings: AppSettings,
    article: WorkspaceRequest,
    onBack: () -> Unit,
    onChanged: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var feedback by remember(article.id) { mutableStateOf(article.feedback) }
    var status by remember(article.id) { mutableStateOf("Lascia feedback: Hermes lo usera' per migliorare articoli e briefing futuri.") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AppColors.Background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = AppColors.Accent) }
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = AppColors.Accent.copy(alpha = 0.18f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(article.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${article.source} - ${article.status} - ${formatVideoTimestamp(article.updatedAt)}",
                        color = AppColors.Muted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(status, color = AppColors.Faint, fontSize = 12.sp)
                HorizontalDivider(color = AppColors.Border)
                MarkdownText(article.result.ifBlank { article.prompt }, color = Color.White, fontSize = 16.sp)
                HorizontalDivider(color = AppColors.Border)
                Text("FEEDBACK", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoFeedChip("Piu' fonti") { feedback = appendFeedbackSnippet(feedback, "Aggiungi piu' fonti verificabili") }
                    VideoFeedChip("Piu' breve") { feedback = appendFeedbackSnippet(feedback, "Sintesi piu' breve e densa") }
                    VideoFeedChip("Piu' profondo") { feedback = appendFeedbackSnippet(feedback, "Analisi piu' profonda e meno superficiale") }
                    VideoFeedChip("Tono") { feedback = appendFeedbackSnippet(feedback, "Tono piu' chiaro, diretto e operativo") }
                }
                SettingsField("Feedback per Hermes", feedback, { feedback = it })
                Button(
                    onClick = {
                        if (feedback.isBlank()) {
                            status = "Scrivi feedback prima di inviare."
                            return@Button
                        }
                        status = "Invio feedback a Hermes..."
                        scope.launch {
                            val result = sendWorkspaceFeedback(settings, article, feedback, loadGatewaySecret(context))
                            saveWorkspaceFeedback(context, article.id, feedback, result)
                            status = result
                            onChanged(result)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Invia feedback", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Invia feedback", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceFeedScreen(
    context: Context,
    settings: AppSettings,
    kind: String,
    title: String,
    description: String,
    empty: String,
    chatPrompt: String,
    onOpenChatPrompt: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Feed sincronizzato localmente. I nuovi spunti arrivano dalla chat e dagli artifact Hermes.") }
    val items = remember(refreshKey) { loadWorkspaceRequests(context, kind) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(description, color = AppColors.Muted)
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = { onOpenChatPrompt(chatPrompt) }) { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "Nuovo spunto in chat", tint = Color.White) }
                IconButton(onClick = {
                    status = "Sincronizzo artifact Hermes..."
                    scope.launch {
                        status = syncWorkspaceJobs(context, settings, kind, loadGatewaySecret(context))
                        refreshKey++
                    }
                }) { Icon(Icons.Rounded.Sync, contentDescription = "Sincronizza Hermes", tint = Color.White) }
            }
        }
        item {
            PremiumPanel {
                Text(modifier = Modifier.padding(14.dp), text = status, color = AppColors.Muted)
            }
        }
        if (items.isEmpty()) {
            item {
                PremiumPanel {
                    Text(modifier = Modifier.padding(16.dp), text = empty, color = AppColors.Muted)
                }
            }
        }
        items.forEach { feedItem ->
            item {
                WorkspaceFeedItem(context, settings, feedItem) {
                    refreshKey++
                    status = it
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceFeedItem(
    context: Context,
    settings: AppSettings,
    item: WorkspaceRequest,
    onChanged: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var feedback by remember(item.id) { mutableStateOf("") }
    PremiumPanel {
        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("${item.status} · ${item.source}", color = AppColors.Muted, fontSize = 12.sp)
            if (item.result.isNotBlank()) {
                MarkdownText(item.result.limitText(900), color = Color.White, fontSize = 14.sp)
            }
            item.remoteId?.let { Text("Job: $it", color = AppColors.Faint, fontSize = 12.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (item.streamUrl.isNotBlank()) {
                    IconButton(onClick = { openAndroidIntent(context, Intent(Intent.ACTION_VIEW, resolveWorkspaceUrl(settings, item.streamUrl).toUri())) }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Streaming", tint = Color.White) }
                }
                if (item.downloadUrl.isNotBlank()) {
                    IconButton(onClick = { openAndroidIntent(context, Intent(Intent.ACTION_VIEW, resolveWorkspaceUrl(settings, item.downloadUrl).toUri())) }) { Icon(Icons.Rounded.Download, contentDescription = "Scarica", tint = Color.White) }
                }
                if (item.remoteId != null) {
                    IconButton(onClick = {
                        scope.launch { onChanged(runWorkspaceJobAction(settings, item, "run", loadGatewaySecret(context))) }
                    }) { Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna job", tint = Color.White) }
                }
            }
            SettingsField("Feedback per Hermes", feedback, { feedback = it })
            IconButton(onClick = {
                if (feedback.isBlank()) {
                    onChanged("Scrivi un feedback prima di inviarlo.")
                    return@IconButton
                }
                scope.launch {
                    val result = sendWorkspaceFeedback(settings, item, feedback, loadGatewaySecret(context))
                    saveWorkspaceFeedback(context, item.id, feedback, result)
                    feedback = ""
                    onChanged(result)
                }
            }) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Invia feedback", tint = Color.White) }
            if (item.feedback.isNotBlank()) {
                Text("Ultimo feedback: ${item.feedback}", color = AppColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

internal fun runOperatorRpc(
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    settings: AppSettings,
    method: String,
    params: String,
    setStatus: (String) -> Unit,
    setSummary: (String) -> Unit,
    setRaw: (String) -> Unit
) {
    setStatus("$method...")
    setSummary("Attesa risposta Hermes...")
    setRaw("")
    scope.launch {
        val result = hermesHttpCall(settings, loadGatewaySecret(context), method, params)
        setStatus(result.status)
        setSummary(result.summary)
        setRaw(result.rawJson.ifBlank { result.summary })
    }
}

@Composable
internal fun ProfileScreen(
    context: Context,
    settings: AppSettings,
    onOpenTab: (Tab) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val conversations = remember { loadConversations(context) }
    val version = remember { appVersion(context) }
    var updateState by remember { mutableStateOf(UpdateDownloadState()) }
    var memory by remember { mutableStateOf(HubMemoryState()) }
    var memoryStatus by remember { mutableStateOf("Memoria gateway non ancora letta.") }
    val managerBase = remember(settings.gatewayUrl) { gpuManagerBase(settings.gatewayUrl) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var rebootBusy by remember { mutableStateOf(false) }
    var rebootStatus by remember { mutableStateOf("") }

    fun doReboot() {
        // Riavvio manager via laya (il POST diretto /system/reboot e'
        // controllo laya: la chiave app non basta piu'). Host reboot solo via ssh.
        scope.launch {
            rebootBusy = true
            rebootStatus = try {
                val bridge = gpuBridgeBase(settings.gatewayUrl)
                postLayaCommand(bridge, "riavvia il manager", loadGpuAppSecret(context))
            } catch (ex: Exception) {
                "Errore invio riavvio: ${ex.message ?: ex.javaClass.simpleName}"
            }
            rebootBusy = false
        }
    }

    LaunchedEffect(settings.gatewayUrl) {
        val loaded = loadHubMemory(settings, loadGatewaySecret(context))
        memory = loaded.first
        memoryStatus = loaded.second
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppColors.Accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Profilo", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text("Profilo locale. Nessun account cloud, nessun token provider nel client.", color = AppColors.Muted, fontSize = 14.sp)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.chatclaw_logo),
                        contentDescription = "Logo Hermes Hub",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Profilo locale", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Home-server Hermes Agent locale", color = AppColors.Muted, fontSize = 14.sp)
                        Text("App: $version", color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = AppColors.Border)
            }
        }
        item {
            ServerMetric("Hermes API", settings.gatewayUrl, if (settings.demoMode) "Fallback locale attivo" else "Solo Hermes")
        }
        item {
            ServerMetric("Archivio locale", "${conversations.size} elementi", "Cronologia e progetti salvati sul dispositivo.")
        }
        item {
            ServerMetric("Privacy", "Locale-first", "Chat/settings restano sul dispositivo finche' non colleghi Hermes. API key salvata in Keystore.")
        }
        item {
            ServerMetric("Parita Windows", "Allineata", "Chat, archivio, progetti/recenti, cron, Hermes server, prestazioni, video, news, settings e profilo presenti anche su Android.")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AREE RAPIDE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Schermate secondarie spostate qui per lasciare la barra bassa pulita.", color = AppColors.Muted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onOpenTab(Tab.Server) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Hermes") }
                    TextButton(onClick = { onOpenTab(Tab.Cron) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Cron") }
                    TextButton(onClick = { onOpenTab(Tab.Notifications) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Notifiche") }
                    TextButton(onClick = { onOpenTab(Tab.Hardware) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Prestazioni") }
                    TextButton(onClick = { onOpenTab(Tab.News) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("News") }
                    TextButton(onClick = { onOpenTab(Tab.Settings) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Impostazioni") }
                    TextButton(onClick = { onOpenTab(Tab.Projects) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Progetti") }
                    TextButton(onClick = { onOpenTab(Tab.Archive) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Archivio") }
                }
                HorizontalDivider(color = AppColors.Border)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SERVER HERMES", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    "Riavvio completo del server (come sudo reboot now). Da usare se il server non risponde più.",
                    color = AppColors.Muted,
                    fontSize = 12.sp
                )
                if (rebootStatus.isNotBlank()) {
                    Text(rebootStatus, color = AppColors.Muted, fontSize = 12.sp)
                }
                Button(
                    onClick = { showRebootConfirm = true },
                    enabled = !rebootBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (rebootBusy) "Riavvio in corso..." else "Riavvia server", fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = AppColors.Border)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PROGETTO ATTIVO", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (settings.activeProjectId.isBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                        Text("Nessun progetto selezionato.", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text("Scegli un progetto per continuare.", color = AppColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Button(onClick = { onOpenTab(Tab.Projects) }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)), shape = RoundedCornerShape(12.dp)) { Text("Apri Progetti", fontWeight = FontWeight.SemiBold) }
                    }
                } else {
                    Text("Attivo: ${settings.activeProjectName}", color = AppColors.Muted, fontSize = 14.sp)
                    TextButton(onClick = { onOpenTab(Tab.Projects) }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Apri Progetti") }
                }
                HorizontalDivider(color = AppColors.Border)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("MEMORIA HERMES", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(memoryStatus, color = AppColors.Muted, fontSize = 12.sp)
                SettingsField("Preferenze video", memory.videoPreferences, { memory = memory.copy(videoPreferences = it) })
                SettingsField("Preferenze news", memory.newsPreferences, { memory = memory.copy(newsPreferences = it) })
                SettingsField("Stile risposta", memory.responseStyle, { memory = memory.copy(responseStyle = it) })
                SettingsField("Regole progetto", memory.projectRules, { memory = memory.copy(projectRules = it) })
                SettingsField("Note generali", memory.generalNotes, { memory = memory.copy(generalNotes = it) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            memoryStatus = "Salvo memoria gateway..."
                            scope.launch {
                                memoryStatus = saveHubMemory(settings, memory, loadGatewaySecret(context))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Salva memoria", fontWeight = FontWeight.SemiBold) }
                    TextButton(
                        onClick = {
                            memory = HubMemoryState()
                            memoryStatus = "Contenuti locali svuotati. Premi Salva memoria per cancellare sul gateway."
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Error)
                    ) { Text("Svuota memoria") }
                }
                HorizontalDivider(color = AppColors.Border)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AGGIORNAMENTI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Installata: $version", color = AppColors.Muted, fontSize = 12.sp)
                updateState.latestVersion?.let { Text("Latest: $it", color = AppColors.Muted, fontSize = 12.sp) }
                updateState.releaseAssetUrl?.let { Text("Asset: ${it.substringAfterLast('/')}", color = AppColors.Muted, fontSize = 12.sp) }
                updateState.downloadedApkPath?.let { Text("APK pronto: $it", color = AppColors.Muted, fontSize = 12.sp) }
                Text(updateState.status, color = AppColors.Muted)
                if (updateState.releaseSummary.isNotBlank()) {
                    Text(
                        "Changelog:\n${updateState.releaseSummary}",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
                if (updateState.progress != null) {
                    LinearProgressIndicator(
                        progress = { updateState.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.Accent,
                        trackColor = AppColors.Border
                    )
                        Text(
                            downloadProgressLabel(updateState.progress, updateState.downloadLabel),
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                updateState = updateState.copy(
                                    status = "Controllo GitHub Releases...",
                                    progress = null,
                                    downloadLabel = "",
                                    downloadedApkPath = null,
                                    isDownloading = false,
                                    releaseSummary = ""
                                )
                                scope.launch {
                                    val result = checkGithubUpdate(version)
                                    val downloadedApk = if (result.hasUpdate) {
                                        result.latestVersion?.let { findDownloadedUpdateApk(context, it) }
                                    } else {
                                        null
                                    }
                                    updateState = updateState.copy(
                                        status = if (downloadedApk != null) {
                                            "Aggiornamento gia' scaricato. Premi Aggiorna per installarlo."
                                        } else if (result.hasUpdate && result.assetUrl != null) {
                                            "${result.message} Scarica l'APK dentro l'app e poi premi Aggiorna."
                                        } else {
                                            result.message
                                        },
                                        releaseAssetUrl = result.assetUrl,
                                        latestVersion = result.latestVersion,
                                        hasUpdate = result.hasUpdate,
                                        progress = if (downloadedApk != null) 1f else null,
                                        downloadLabel = downloadedApk?.length()?.toReadableFileSize() ?: "",
                                        downloadedApkPath = downloadedApk?.absolutePath,
                                        releaseSummary = result.releaseSummary
                                    )
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                        ) {
                            Text("Controlla aggiornamenti")
                        }
                        if (updateState.hasUpdate && updateState.releaseAssetUrl != null && updateState.downloadedApkPath == null && !updateState.isDownloading) {
                            Button(
                                onClick = {
                                    val assetUrl = updateState.releaseAssetUrl ?: return@Button
                                    scope.launch {
                                        updateState = updateState.copy(
                                            status = "Scaricamento APK in corso...",
                                            isDownloading = true,
                                            progress = 0f,
                                            downloadLabel = ""
                                        )
                                        val downloaded = downloadUpdateApk(
                                            context = context,
                                            assetUrl = assetUrl,
                                            version = updateState.latestVersion ?: version
                                        ) { fraction, status, label ->
                                            updateState = updateState.copy(
                                                progress = fraction,
                                                status = status,
                                                downloadLabel = label,
                                                isDownloading = true
                                            )
                                        }

                                        updateState = if (downloaded != null) {
                                            updateState.copy(
                                                status = "APK pronto. Premi Aggiorna per avviare l'installazione Android.",
                                                downloadedApkPath = downloaded.absolutePath,
                                                isDownloading = false,
                                                progress = 1f,
                                                downloadLabel = downloaded.length().toReadableFileSize()
                                            )
                                        } else {
                                            updateState.copy(
                                                status = "Download non riuscito. Premi Controlla e riprova.",
                                                downloadedApkPath = null,
                                                isDownloading = false,
                                                progress = null,
                                                downloadLabel = ""
                                            )
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Scarica APK", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (updateState.downloadedApkPath != null && !updateState.isDownloading) {
                            TextButton(
                                onClick = {
                                    val apkPath = updateState.downloadedApkPath ?: return@TextButton
                                    val status = installDownloadedApk(context, apkPath)
                                    updateState = updateState.copy(status = status)
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                            ) {
                                Text("Installa aggiornamento")
                            }
                        }
                    }
                    HorizontalDivider(color = AppColors.Border)
                }
        }
    }
    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            containerColor = AppColors.Surface,
            title = { Text("Riavviare il manager?", color = Color.White) },
            text = { Text("laya riavvia hermes-gpu-manager (non l'intero server: per quello serve ssh). Code e modelli si interrompono e tornano da soli.", color = AppColors.Muted) },
            confirmButton = {
                Button(
                    onClick = { showRebootConfirm = false; doReboot() },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Error, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Riavvia ora", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Annulla") }
            }
        )
    }
}

@Composable
internal fun HealthDashboardScreen(context: Context, settings: AppSettings, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshRevision by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<HealthHistoryResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(
        settings.healthIncludeSteps,
        settings.healthIncludeSleep,
        settings.healthIncludeWorkouts,
        settings.healthIncludeHeartRate,
        refreshRevision
    ) {
        loading = true
        history = HealthSync.readHistory(context, settings)
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppColors.Accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.FavoriteBorder, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Salute", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Dati letti dal telefono tramite Samsung Health e Health Connect. Riepiloghi wellness, non diagnosi mediche.",
                        color = AppColors.Muted,
                        fontSize = 14.sp
                    )
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { refreshRevision++ }, enabled = !loading, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Aggiorna dati") }
                TextButton(onClick = onOpenSettings, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Impostazioni salute") }
            }
        }
        when (val current = history) {
            null -> item { HealthLoadingPanel() }
            is HealthHistoryResult.Unavailable -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                    Text("Dati non disponibili", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(current.message, color = AppColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text("Apri Impostazioni salute, collega Health Connect e abilita almeno una categoria.", color = AppColors.Faint, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Collega Health Connect", fontWeight = FontWeight.SemiBold) }
                    HorizontalDivider(color = AppColors.Border)
                }
            }
            is HealthHistoryResult.Success -> {
                val items = current.items
                val today = items.lastOrNull()
                if (today == null) {
                    item { HealthLoadingPanel() }
                } else {
                    item { HealthTodayPanel(today) }
                    item {
                        WellbeingBarChart(
                            title = "Passi · ultimi 7 giorni",
                            unit = "passi",
                            items = items,
                            color = AppColors.Accent,
                            value = { it.steps?.toFloat() }
                        )
                    }
                    item {
                        WellbeingBarChart(
                            title = "Sonno · ultimi 7 giorni",
                            unit = "minuti",
                            items = items,
                            color = Color(0xFF7C8CFF),
                            value = { it.sleepMinutes?.toFloat() }
                        )
                    }
                    item {
                        WellbeingBarChart(
                            title = "Allenamento · ultimi 7 giorni",
                            unit = "minuti",
                            items = items,
                            color = Color(0xFF4DD6A7),
                            value = { it.workoutMinutes?.toFloat() }
                        )
                    }
                    if (items.any { it.heartRateAverage != null }) {
                        item {
                            WellbeingBarChart(
                                title = "Frequenza cardiaca media · ultimi 7 giorni",
                                unit = "bpm",
                                items = items,
                                color = Color(0xFFFF6B82),
                                value = { it.heartRateAverage?.toFloat() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HealthLoadingPanel() {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinearProgressIndicator(modifier = Modifier.width(84.dp), color = AppColors.Accent, trackColor = AppColors.Border)
            Text("Lettura dati Health Connect...", color = AppColors.Muted, fontSize = 13.sp)
        }
        HorizontalDivider(color = AppColors.Border)
    }
}

@Composable
internal fun HealthTodayPanel(summary: DailyWellbeingSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(("Oggi · ${summary.date}").uppercase(), color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HealthMetric("Passi", summary.steps?.toString() ?: "—", Modifier.weight(1f))
                HealthMetric("Calorie", summary.activeCaloriesKcal?.let { String.format(java.util.Locale.ITALY, "%.0f kcal", it) } ?: "—", Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HealthMetric("Sonno", summary.sleepMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "—", Modifier.weight(1f))
                HealthMetric("Allenamento", summary.workoutMinutes?.let { "${it} min" } ?: "—", Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HealthMetric("Sessioni", summary.workoutCount?.toString() ?: "—", Modifier.weight(1f))
                HealthMetric("FC media", summary.heartRateAverage?.let { String.format(java.util.Locale.ITALY, "%.0f bpm", it) } ?: "—", Modifier.weight(1f))
            }
            summary.heartRateMin?.let { min ->
                Text(
                    "Frequenza cardiaca oggi: ${String.format(java.util.Locale.ITALY, "%.0f", min)}–${String.format(java.util.Locale.ITALY, "%.0f", summary.heartRateMax ?: min)} bpm",
                    color = AppColors.Muted,
                    fontSize = 12.sp
                )
            }
            HorizontalDivider(color = AppColors.Border)
        }
}

@Composable
internal fun HealthMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label.uppercase(), color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun WellbeingBarChart(
    title: String,
    unit: String,
    items: List<DailyWellbeingSummary>,
    color: Color,
    value: (DailyWellbeingSummary) -> Float?
) {
    val values = items.map { value(it)?.coerceAtLeast(0f) ?: 0f }
    val peak = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("max ${String.format(java.util.Locale.ITALY, "%.0f", peak)} $unit", color = AppColors.Faint, fontSize = 11.sp)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
                val gap = 8.dp.toPx()
                val width = ((size.width - gap * (values.size - 1)) / values.size.coerceAtLeast(1)).coerceAtLeast(2.dp.toPx())
                values.forEachIndexed { index, current ->
                    val height = if (current <= 0f) 2.dp.toPx() else (current / peak) * size.height
                    val left = index * (width + gap)
                    drawRect(
                        color = if (current <= 0f) AppColors.Border else color,
                        topLeft = Offset(left, size.height - height),
                        size = Size(width, height)
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                items.forEach { item -> Text(item.date.takeLast(2), color = AppColors.Faint, fontSize = 10.sp) }
            }
            HorizontalDivider(color = AppColors.Border)
        }
}

internal fun gpuManagerBase(gatewayUrl: String): String {    val trimmed = gatewayUrl.trim().trimEnd('/')
    val noPath = trimmed.substringBefore("/v1").substringBefore("/api")
    return if (Regex(":[0-9]+$").containsMatchIn(noPath)) {
        noPath.replace(Regex(":[0-9]+$"), ":8643")
    } else {
        "$noPath:8643"
    }
}

/**
 * Base del bridge laya (hermes-laya-bridge, :8645): sempre attivo e indipendente
 * dal manager, così il canale risponde anche se il manager è bloccato.
 */
internal fun gpuBridgeBase(gatewayUrl: String): String {
    val trimmed = gatewayUrl.trim().trimEnd('/')
    val noPath = trimmed.substringBefore("/v1").substringBefore("/api")
    return if (Regex(":[0-9]+$").containsMatchIn(noPath)) {
        noPath.replace(Regex(":[0-9]+$"), ":8645")
    } else {
        "$noPath:8645"
    }
}

internal data class PulseDay(val done: Int, val failed: Int)

/** Secchi giornalieri 7 giorni (oggi ultimo) da (createdAtSec, status). Puro. */
internal fun bucketPulseDays(created: List<Pair<Long, String>>, nowMs: Long): List<PulseDay> {
    val days = Array(7) { PulseDay(0, 0) }
    val dayMs = 86_400_000L
    val todayStart = (nowMs / dayMs) * dayMs
    for ((atSec, status) in created) {
        val atMs = atSec * 1000L
        if (atMs <= 0) continue
        val diff = (todayStart - (atMs / dayMs) * dayMs) / dayMs
        if (diff < 0 || diff > 6) continue
        val slot = (6 - diff.toInt()).coerceIn(0, 6)
        val prev = days[slot]
        days[slot] = if (status == "done") prev.copy(done = prev.done + 1)
        else prev.copy(failed = prev.failed + 1)
    }
    return days.toList()
}

@Composable
internal fun PulseCard(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val base = remember(settings.gatewayUrl) { gpuManagerBase(settings.gatewayUrl) }
    var days by remember(base) { mutableStateOf(emptyList<PulseDay>()) }
    var line by remember(base) { mutableStateOf("Carico giornata…") }
    var busy by remember(base) { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            try {
                val key = loadGpuAppSecret(context)
                val (code, body) = httpGetResponse("$base/jobs?limit=300", key)
                if (code !in 200..299) {
                    line = "Job non leggibili (HTTP $code)."
                    return@launch
                }
                val arr = runCatching { JSONObject(body).optJSONArray("jobs") }.getOrNull()
                val created = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            add(o.optDouble("created_at", 0.0).toLong() to o.optString("status"))
                        }
                    }
                }
                val buckets = bucketPulseDays(created, System.currentTimeMillis())
                days = buckets
                val today = buckets.last()
                line = "Oggi: ${today.done} completati · ${today.failed} falliti."
            } catch (failed: Exception) {
                line = failed.message ?: "Errore rete."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(base) { refresh() }
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "PULSE · 7 GIORNI",
                    color = AppColors.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { refresh() }, enabled = !busy) { Text("Aggiorna", color = AppColors.Accent) }
            }
            Text(line, color = Color.White, fontSize = 13.sp)
            if (days.size == 7) {
                val max = (days.maxOf { it.done + it.failed }).coerceAtLeast(1)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    val slot = size.width / 7f
                    val barW = (slot * 0.55f).coerceAtLeast(4f)
                    days.forEachIndexed { index, day ->
                        val total = day.done + day.failed
                        if (total <= 0) return@forEachIndexed
                        val h = size.height * (total.toFloat() / max)
                        val x = index * slot + (slot - barW) / 2f
                        val doneH = h * (day.done.toFloat() / total)
                        drawRoundRect(
                            color = AppColors.Success,
                            topLeft = androidx.compose.ui.geometry.Offset(x, size.height - doneH),
                            size = androidx.compose.ui.geometry.Size(barW, doneH)
                        )
                        val failH = h - doneH
                        if (failH > 1f) {
                            drawRoundRect(
                                color = AppColors.Error,
                                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                                size = androidx.compose.ui.geometry.Size(barW, failH)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GpuComputeCard(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val base = remember(settings.gatewayUrl) { gpuManagerBase(settings.gatewayUrl) }
    val bridge = remember(settings.gatewayUrl) { gpuBridgeBase(settings.gatewayUrl) }
    var body by remember(base) { mutableStateOf<JSONObject?>(null) }
    var jobsBody by remember(base) { mutableStateOf<JSONArray?>(null) }
    var error by remember(base) { mutableStateOf("") }
    var busy by remember(base) { mutableStateOf(false) }
    var layaText by remember(bridge) { mutableStateOf("") }
    var layaReply by remember(bridge) { mutableStateOf("") }
    var layaBusy by remember(bridge) { mutableStateOf(false) }

    suspend fun refreshSuspend(): Boolean {
        busy = true
        try {
            error = runCatching {
                val key = loadGpuAppSecret(context)
                val (statusCode, statusBody) = httpGetResponse("$base/status", key)
                if (statusCode !in 200..299) {
                    throw IllegalStateException(managerStatusErrorMessage(statusCode, statusBody))
                }
                body = JSONObject(statusBody)
                val (jobsCode, jobsBodyRaw) = httpGetResponse("$base/jobs", key)
                if (jobsCode !in 200..299) {
                    throw IllegalStateException("Job manager HTTP $jobsCode")
                }
                jobsBody = JSONObject(jobsBodyRaw).optJSONArray("jobs")
            }.exceptionOrNull()?.message ?: ""
            return error.isBlank()
        } finally {
            busy = false
        }
    }

    fun refresh() {
        scope.launch {
            refreshSuspend()
        }
    }

    fun sendLaya() {
        val text = layaText.trim()
        if (text.isEmpty() || layaBusy) return
        scope.launch {
            layaBusy = true
            layaReply = runCatching {
                postLayaCommand(bridge, text, loadGpuAppSecret(context))
            }.getOrElse { it.message ?: "Errore" }
            layaBusy = false
            refresh()
        }
    }

    PollWhileStarted(base, baseIntervalMs = 10_000L) {
        if (busy) return@PollWhileStarted true
        refreshSuspend()
    }

    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MODALITÀ GPU (QWEN / COMFYUI)", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (body == null) {
                Text(
                    if (error.isBlank()) "Lettura stato GPU..." else "GPU Manager non raggiungibile ($base). Verifica che hermes-gpu-manager sia attivo.",
                    color = AppColors.Muted,
                    fontSize = 12.sp
                )
                IconButton(onClick = { refresh() }, enabled = !busy) { Icon(Icons.Rounded.Refresh, contentDescription = "Riprova lettura GPU", tint = AppColors.Accent) }
            } else {
            val status = body ?: JSONObject()
            val desired = status.optString("desired_mode", "?")
            val state = status.optString("current_state", "?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("LLM", "MEDIA", "AUTO").forEach { mode ->
                    Button(
                        onClick = {
                            scope.launch {
                                val layaText = when (mode) {
                                    "LLM" -> "metti LLM ready"
                                    "MEDIA" -> "attiva comfy"
                                    else -> "metti in auto"
                                }
                                layaReply = runCatching {
                                    postLayaCommand(bridge, layaText, loadGpuAppSecret(context))
                                }.getOrElse { it.message ?: "Errore" }
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (desired == mode) AppColors.Accent else AppColors.Panel,
                            contentColor = if (desired == mode) Color(0xFF171009) else Color.White
                        ),
                        border = if (desired == mode) null else BorderStroke(1.dp, AppColors.Border),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text(if (desired == mode) "$mode ✓" else mode) }
                }
            }
            Text("Stato: $state", color = Color.White, fontWeight = FontWeight.SemiBold)
            if (layaReply.isNotBlank()) {
                Text(layaReply, color = AppColors.Muted, fontSize = 12.sp)
            }
            Text(
                "Qwen: ${if (status.optBoolean("llm_loaded", false)) "Online" else if (status.optBoolean("llm_online", false)) "Avvio..." else "Offline"} · " +
                    "ComfyUI: ${if (status.optBoolean("media_online", false)) "Online" else "Offline"}",
                color = AppColors.Muted,
                fontSize = 12.sp
            )
            val gpus = status.optJSONArray("gpu") ?: JSONArray()
            for (i in 0 until gpus.length()) {
                val gpu = gpus.optJSONObject(i) ?: continue
                val usedGb = gpu.optDouble("memory_used_mb", 0.0) / 1024.0
                val totalGb = gpu.optDouble("memory_total_mb", 1.0) / 1024.0
                val pct = if (totalGb > 0) (usedGb / totalGb * 100).toInt() else 0
                Text(
                    "GPU${gpu.optInt("index")} %.1f / %.1f GB (%d%%)".format(usedGb, totalGb, pct),
                    color = AppColors.Muted,
                    fontSize = 12.sp
                )
            }
            Text(
                "Coda media: ${status.optInt("queue_length", 0)}" +
                    (status.optString("current_job", "").takeIf { it.isNotBlank() }?.let { " · Job $it" } ?: ""),
                color = AppColors.Muted,
                fontSize = 12.sp
            )
            Text("MEDIA JOBS RECENTI", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            val recentJobs = jobsBody ?: JSONArray()
            if (recentJobs.length() == 0) {
                Text("Nessun job media.", color = AppColors.Muted, fontSize = 12.sp)
            }
            for (i in 0 until minOf(recentJobs.length(), 5)) {
                val job = recentJobs.optJSONObject(i) ?: continue
                val results = job.optJSONArray("result_paths")?.length() ?: 0
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${job.optString("job_id", "?")} · ${job.optString("preset", job.optString("kind", "?"))} · ${job.optString("status", "?")}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Progresso ${(job.optDouble("progress", 0.0) * 100).toInt()}% · output $results" +
                            (job.optString("phase", "").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                            (job.optString("error", "").takeIf { it.isNotBlank() }?.let { " · Errore: $it" } ?: "") +
                            (job.optString("model", "").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        color = AppColors.Muted,
                        fontSize = 11.sp
                    )
                }
            }
            status.optString("last_error", "").takeIf { it.isNotBlank() }?.let {
                Text("Ultimo errore: $it", color = AppColors.Error, fontSize = 12.sp)
            }
            status.optString("h3_license_state", "").takeIf { it.isNotBlank() }?.let {
                Text("Video H3: $it", color = AppColors.Muted, fontSize = 12.sp)
            }
                TextButton(onClick = { refresh() }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Aggiorna") }
            }
            HorizontalDivider(color = AppColors.Border)
            Text("CHAT LAYA (canale diretto)", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(
                "Chiedi in linguaggio naturale: \"metti l'LLM ready\", \"attiva comfy\", \"metti in auto\", \"come sta?\".",
                color = AppColors.Muted,
                fontSize = 12.sp
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = layaText,
                    onValueChange = { layaText = it },
                    singleLine = true,
                    placeholder = { Text("Chiedi a laya...") },
                    modifier = Modifier.weight(1f),
                    enabled = !layaBusy
                )
                Button(
                    onClick = { sendLaya() },
                    enabled = !layaBusy && layaText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009))
                ) { Text(if (layaBusy) "..." else "Invia") }
            }
            if (layaReply.isNotBlank()) {
                Text(layaReply, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
