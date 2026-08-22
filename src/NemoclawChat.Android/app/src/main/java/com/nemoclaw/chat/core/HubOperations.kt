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

internal fun visualBlocksMetadata(settings: AppSettings, conversationId: String?): JSONObject {
    val serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId)
    return JSONObject()
        .put("client", "hermes-hub")
        .put("hub_client", true)
        .put("client_surface", HERMES_HUB_ANDROID_SURFACE)
        .put("requested_protocol", settings.preferredApi)
        .put("strict_native_mode", settings.strictNativeMode)
        .put("profile", "user")
        .put("project_id", settings.activeProjectId)
        .put("project_name", settings.activeProjectName)
        .put("workspace", settings.activeProjectName.ifBlank { "default" })
        .put(
            "project_context",
            if (settings.activeProjectId.isBlank()) JSONObject.NULL else JSONObject()
                .put("id", settings.activeProjectId)
                .put("name", settings.activeProjectName)
                .put("system_prompt", settings.activeProjectInstructions)
        )
        .put(
            "hub_conversation",
            JSONObject()
                .put("id", serverConversationId ?: JSONObject.NULL)
                .put("local_id", conversationId ?: JSONObject.NULL)
                .put("surface", HERMES_HUB_ANDROID_SURFACE)
                .put("scope", "per-chat-per-surface")
                .put("isolation_required", true)
                .put("do_not_merge_with_other_conversations", true)
                .put("do_not_merge_with_other_surfaces", true)
                .put("shared_memory_policy", "Only stable user preferences may be shared; transient chat context must stay in this conversation id.")
        )
        .put(
            "memory_policy",
            JSONObject()
                .put("scope", "shared-hermes-agent-memory")
                .put("share_with_cli", true)
                .put("use_server_memory_tools", true)
                .put("do_not_create_app_only_memory", true)
                .put("runtime_context_scope", "isolated_conversation")
                .put("do_not_use_other_active_chats_as_context", true)
                .put("context_owner", if (isHermesNative(settings)) "hermes-agent" else "client-compat")
        )
        .put(
            "native_context",
            JSONObject()
                .put("delegated", isHermesNative(settings))
                .put("conversation_id_required", true)
                .put("client_history_is_snapshot_only", isHermesNative(settings))
                .put("client_context_meter", if (isHermesNative(settings)) "server-authoritative" else "local-estimate")
        )
        .put(
            "hub_sections",
            JSONObject()
                .put("chat", "Conversazione principale Hermes Hub.")
                .put("video", "Feed personale video: Hermes conosce video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni video creato/scaricato per l'utente deve essere salvato o registrato li; Android legge /v1/video/library, desktop mostra file locali, app salva feedback e metadata.")
                .put("news", "Feed personale articoli: Hermes produce articoli con fonti; se crea HTML/giornale online salva in ${settings.newsLibraryPath} per /v1/news/library; app salva feedback.")
                .put("cron", "Automazioni Hermes programmate condivise con CLI/server.")
                .put("wellbeing", "Riepiloghi giornalieri volontari da Health Connect/Galaxy Watch. Consulta GET /v1/hub/wellbeing o /v1/hub/wellbeing/daily/{date} solo se l'utente chiede salute, attivita', sonno o trend; dati wellness, non medici.")
                .put("notifications", "Inbox notifiche: cron/agenti devono usare POST /v1/hub/notifications per avvisi importanti quando l'app non e' aperta.")
        )
        .put(
            "notification_contract",
            JSONObject()
                .put("endpoint", "/v1/hub/notifications")
                .put("required_behavior", "When a cron, monitor or long-running agent finds something the user must know, create a notification with title, message, severity, source and conversation_prompt. Keep it concise and self-contained.")
        )
        .put("news_library_path", settings.newsLibraryPath)
        .put(
            "news_contract",
            JSONObject()
                .put("mode", "watched-folder")
                .put("library_path", settings.newsLibraryPath)
                .put("required_behavior", "When the user asks for news, articles, briefings, online newspapers or HTML pages, store the final HTML file in news_library_path/HERMES_NEWS_LIBRARY_PATH, let /v1/news/library expose it, and use media proxy if referenced in chat.")
        )
        .put(
            "activity_stream",
            JSONObject()
                .put("requested", true)
                .put("include_reasoning", true)
                .put("include_tool_calls", true)
                .put("include_tool_results", true)
                .put("include_intermediate_model_calls", true)
                .put("client_requires_realtime_visibility", true)
        )
        .put("video_library_path", settings.videoLibraryPath)
        .put(
            "video_contract",
            JSONObject()
                .put("mode", "watched-folder")
                .put("library_path", settings.videoLibraryPath)
                .put("required_behavior", "When the user asks for video creation/download/editing, store the final video file in video_library_path/HERMES_VIDEO_LIBRARY_PATH, let /v1/video/library expose it, and use media proxy if referenced in chat.")
        )
        .put(
            "visual_blocks",
            JSONObject()
                .put("min_supported_version", VISUAL_BLOCKS_VERSION)
                .put("max_supported_version", VISUAL_BLOCKS_VERSION)
                .put("mode", settings.visualBlocksMode)
                .put("image_gallery", "supported via /v1/media proxy URLs only")
                .put("media_file", "supported for image/video/audio/document via safe proxy URLs; include media_kind, mime_type, filename, size_bytes, duration_ms, thumbnail_url when known")
                .put("screenshot_contract", "For browser screenshots: capture real image, copy to HERMES_HUB_UPLOAD_PATH (~/.hermes/hub_uploads by default), then emit media_file image with media_url /v1/media/<filename>. Never return a local path or URL as chat text.")
        )
}

internal suspend fun queueTaskRequest(settings: AppSettings, task: AgentTask, apiKey: String?): GatewayTaskResult = withContext(Dispatchers.IO) {
    val payload = JSONObject()
        .put("title", task.title)
        .put("instructions", task.detail)
        .put("detail", task.detail)
        .put("mode", task.mode)
        .put("requiresApproval", task.requiresApproval)
        .put("approvalRequired", task.requiresApproval)
        .put("model", settings.model)
        .put("provider", settings.provider)
        .put(
            "metadata",
            JSONObject()
                .put("client", "hermes-hub")
                .put("client_surface", "android-app")
                .put("memory_scope", "shared-hermes-agent-memory")
                .put("source", "jobs-section")
        )

    try {
        val response = postJson("${hermesRoot(settings)}/api/jobs", payload, apiKey)
        if (response.first in 200..299) {
            val remoteId = extractTaskId(response.second)
            val status = extractTaskStatus(response.second) ?: task.status
            return@withContext GatewayTaskResult(
                task.copy(
                    remoteId = remoteId,
                    mode = "Job",
                    status = status,
                    source = "Hermes Jobs",
                    updatedAt = System.currentTimeMillis()
                ),
                "Job creato su Hermes."
            )
        }

        val error = "HTTP ${response.first}: ${extractHumanError(response.second)}"
        return@withContext fallbackTaskResult(settings, task, "Creazione job fallita: $error")
    } catch (ex: Exception) {
        return@withContext fallbackTaskResult(settings, task, "Creazione job fallita: ${ex.message ?: ex.javaClass.simpleName}")
    }
}

internal suspend fun updateTaskRequest(settings: AppSettings, task: AgentTask, action: String, apiKey: String?): GatewayTaskResult = withContext(Dispatchers.IO) {
    val targetStatus = when (action) {
        "run" -> "Run richiesto"
        "pause" -> "Pausa richiesta"
        else -> "Eliminato"
    }

    if (task.remoteId.isNullOrBlank()) {
        return@withContext GatewayTaskResult(
            task.copy(
                status = "$targetStatus locale",
                source = "Fallback locale",
                updatedAt = System.currentTimeMillis()
            ),
            "Job aggiornato in locale: $targetStatus."
        )
    }

    try {
        val url = if (action == "delete") {
            "${hermesRoot(settings)}/api/jobs/${task.remoteId}"
        } else {
            "${hermesRoot(settings)}/api/jobs/${task.remoteId}/$action"
        }
        val response = postJson(url, JSONObject(), apiKey, if (action == "delete") "DELETE" else "POST")
        if (response.first in 200..299) {
            return@withContext GatewayTaskResult(
                task.copy(
                    status = if (action == "delete") "Eliminato" else extractTaskStatus(response.second) ?: targetStatus,
                    source = "Hermes Jobs",
                    updatedAt = System.currentTimeMillis()
                ),
                "Job aggiornato su Hermes."
            )
        }

        val error = "HTTP ${response.first}: ${extractHumanError(response.second)}"
        return@withContext fallbackTaskResult(settings, task.copy(status = targetStatus), "Aggiornamento job fallito: $error")
    } catch (ex: Exception) {
        return@withContext fallbackTaskResult(settings, task.copy(status = targetStatus), "Aggiornamento job fallito: ${ex.message ?: ex.javaClass.simpleName}")
    }
}

internal suspend fun loadServerSnapshot(context: Context, settings: AppSettings, apiKey: String?): ServerSnapshot = withContext(Dispatchers.IO) {
    val healthUrl = "${hermesRoot(settings)}/health"
    val baseSnapshot = ServerSnapshot(
        gateway = settings.gatewayUrl,
        model = settings.model,
        providerDetail = "Provider: ${settings.provider} | API: ${settings.preferredApi}",
        inferenceEndpoint = settings.inferenceEndpoint,
        policy = settings.accessMode,
        statusMessage = if (settings.demoMode) "Fallback locale attivo. Provero' comunque a usare Hermes." else "Solo Hermes. Verifica lo stato del server.",
        videoLibraryPath = settings.videoLibraryPath
    )

    try {
        val healthStatus = testGateway(healthUrl, apiKey)
        try {
            val body = httpGet("${hermesRoot(settings)}/health/detailed", apiKey)
            val json = JSONObject(body)
            val syncedVideoPath = json.extractString("video_library_path")
                ?: json.extractString("videoLibraryPath")
                ?: json.extractNestedString("video", "library_path")
                ?: json.extractNestedString("video", "video_library_path")
                ?: json.extractNestedString("config", "video_library_path")
                ?: settings.videoLibraryPath
            if (syncedVideoPath != settings.videoLibraryPath) {
                saveSettings(context, settings.copy(videoLibraryPath = syncedVideoPath))
            }
            ServerSnapshot(
                gateway = settings.gatewayUrl,
                model = json.extractString("model")
                    ?: json.extractNestedString("server", "model")
                    ?: json.extractNestedString("runtime", "model")
                    ?: settings.model,
                providerDetail = "Provider: ${json.extractString("provider") ?: json.extractNestedString("server", "provider") ?: settings.provider} | API: ${json.extractString("preferredApi") ?: json.extractString("api") ?: settings.preferredApi}",
                inferenceEndpoint = json.extractString("inferenceEndpoint")
                    ?: json.extractNestedString("server", "inferenceEndpoint")
                    ?: json.extractNestedString("runtime", "endpoint")
                    ?: settings.inferenceEndpoint,
                policy = json.extractString("accessMode")
                    ?: json.extractString("networkPolicy")
                    ?: json.extractNestedString("security", "networkPolicy")
                    ?: settings.accessMode,
                statusMessage = json.extractString("status")
                    ?: json.extractString("message")
                    ?: healthStatus,
                videoLibraryPath = syncedVideoPath
            )
        } catch (_: Exception) {
            baseSnapshot.copy(statusMessage = if (settings.demoMode) "$healthStatus Fallback locale attivo." else "$healthStatus Solo Hermes.")
        }
    } catch (ex: Exception) {
        baseSnapshot.copy(statusMessage = "Hermes non raggiungibile: ${ex.message ?: ex.javaClass.simpleName}")
    }
}

internal suspend fun loadHardwareSnapshot(settings: AppSettings, apiKey: String?): HardwareSnapshot = withContext(Dispatchers.IO) {
    try {
        val body = httpGet(resolveHermesUrl(settings, "/v1/hub/hardware"), apiKey)
        parseHardwareSnapshot(JSONObject(body))
    } catch (ex: Exception) {
        HardwareSnapshot(
            status = "unavailable",
            message = "Hardware gateway non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
        )
    }
}

internal fun parseHardwareSnapshot(json: JSONObject): HardwareSnapshot {
    val host = json.optJSONObject("host") ?: JSONObject()
    val cpu = json.optJSONObject("cpu") ?: JSONObject()
    val memory = json.optJSONObject("memory") ?: JSONObject()
    val swap = json.optJSONObject("swap") ?: JSONObject()
    val network = json.optJSONObject("network") ?: JSONObject()
    val timestampMs = (json.optFiniteDouble("timestamp")?.times(1000.0)?.toLong())
        ?.takeIf { it > 0 }
        ?: System.currentTimeMillis()

    val disks = buildList {
        val array = json.optJSONArray("disks") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val device = item.optString("device", "-")
            val fileSystem = item.optString("fstype", "-")
            if (!isMeaningfulHardwareDisk(device, fileSystem)) continue
            add(
                HardwareDisk(
                    device = device,
                    mountpoint = item.optString("mountpoint", "-"),
                    fileSystem = fileSystem,
                    totalBytes = item.optLong("total_bytes", 0L),
                    usedBytes = item.optLong("used_bytes", 0L),
                    freeBytes = item.optLong("free_bytes", 0L),
                    percent = item.optFiniteDouble("percent") ?: 0.0
                )
            )
        }
    }

    val temperatures = buildList {
        val array = json.optJSONArray("temperatures") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val current = item.optFiniteDouble("current_c") ?: continue
            if (current < 0.0 || current > 150.0) continue
            add(
                HardwareTemperature(
                    name = item.optString("name", "-"),
                    label = item.optString("label", item.optString("name", "-")),
                    currentC = current,
                    highC = item.optFiniteDouble("high_c"),
                    criticalC = item.optFiniteDouble("critical_c")
                )
            )
        }
    }

    val gpus = buildList {
        val array = json.optJSONArray("gpus") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val memoryTotalMb = item.optFiniteDouble("memory_total_mb") ?: 0.0
            val memoryUsedMb = item.optFiniteDouble("memory_used_mb") ?: 0.0
            add(
                HardwareGpu(
                    index = item.optInt("index", i),
                    name = item.optString("name", "GPU"),
                    utilizationPercent = item.optFiniteDouble("utilization_gpu_percent") ?: 0.0,
                    memoryUtilizationPercent = item.optFiniteDouble("utilization_memory_percent") ?: 0.0,
                    memoryUsedBytes = if (memoryUsedMb > 0.0) (memoryUsedMb * 1024.0 * 1024.0).toLong() else item.optLong("memory_used_bytes", 0L),
                    memoryTotalBytes = if (memoryTotalMb > 0.0) (memoryTotalMb * 1024.0 * 1024.0).toLong() else item.optLong("memory_total_bytes", 0L),
                    temperatureC = item.optFiniteDouble("temperature_c")?.takeIf { it in 0.0..150.0 },
                    powerDrawWatts = item.optFiniteDouble("power_draw_watts")?.takeIf { it in 0.0..1000.0 },
                    powerLimitWatts = item.optFiniteDouble("power_limit_watts")?.takeIf { it in 0.0..1000.0 },
                    driverVersion = item.optString("driver_version", "-")
                )
            )
        }
    }

    return HardwareSnapshot(
        status = json.optString("status", "ok"),
        timestampMs = timestampMs,
        hostname = host.optString("hostname", "-"),
        operatingSystem = host.optString("os", "-"),
        platform = host.optString("platform", "-"),
        architecture = host.optString("architecture", "-"),
        processor = host.optString("processor", "-"),
        uptimeSeconds = host.optLong("uptime_seconds", 0L),
        cpuPercent = cpu.optFiniteDouble("percent") ?: 0.0,
        physicalCores = cpu.optInt("physical_cores", 0),
        logicalCores = cpu.optInt("logical_cores", 0),
        currentMhz = cpu.optFiniteDouble("current_mhz"),
        maxMhz = cpu.optFiniteDouble("max_mhz"),
        memoryPercent = memory.optFiniteDouble("percent") ?: 0.0,
        memoryTotalBytes = memory.optLong("total_bytes", 0L),
        memoryUsedBytes = memory.optLong("used_bytes", 0L),
        memoryAvailableBytes = memory.optLong("available_bytes", 0L),
        swapPercent = swap.optFiniteDouble("percent") ?: 0.0,
        swapTotalBytes = swap.optLong("total_bytes", 0L),
        swapUsedBytes = swap.optLong("used_bytes", 0L),
        networkBytesSent = network.optLong("bytes_sent", 0L),
        networkBytesReceived = network.optLong("bytes_recv", 0L),
        processCount = json.optInt("process_count", 0),
        temperatureSupport = json.optString("temperature_support", "unavailable"),
        disks = disks,
        temperatures = temperatures,
        gpus = gpus,
        message = "Statistiche aggiornate dal gateway Hermes."
    )
}

internal suspend fun testGateway(healthUrl: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val response = httpGetResponse(healthUrl, apiKey)
        if (response.first in 200..299) {
            "Hermes raggiungibile."
        } else {
            "Hermes risponde: HTTP ${response.first}"
        }
    } catch (ex: Exception) {
        "Hermes non raggiungibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun hermesHttpCall(
    settings: AppSettings,
    apiKey: String?,
    target: String,
    rawPayload: String
): GatewayRpcCallResult = withContext(Dispatchers.IO) {
    val trimmed = target.trim()
    if (trimmed.isBlank()) {
        return@withContext GatewayRpcCallResult(target, false, "Endpoint obbligatorio.", "", "")
    }

    val parts = trimmed.split(" ", limit = 2).map { it.trim() }.filter { it.isNotBlank() }
    val verb = if (parts.size == 2) parts[0].uppercase() else if (rawPayload.isBlank()) "GET" else "POST"
    val path = if (parts.size == 2) parts[1] else trimmed
    val url = resolveHermesUrl(settings, path)

    try {
        val response = if (verb == "GET") {
            httpGetResponse(url, apiKey)
        } else {
            val payload = if (rawPayload.isBlank()) JSONObject() else JSONObject(rawPayload)
            postJson(url, payload, apiKey, verb)
        }
        val body = if (response.first in 200..299) {
            response.second
        } else {
            "HTTP ${response.first}: ${extractHumanError(response.second)}"
        }

        GatewayRpcCallResult(
            method = "$verb $path",
            success = response.first in 200..299,
            status = if (response.first in 200..299) "Hermes risposta ricevuta." else "Hermes HTTP ${response.first}.",
            rawJson = body,
            summary = body.limitText(180)
        )
    } catch (ex: Exception) {
        GatewayRpcCallResult(
            method = "$verb $path",
            success = false,
            status = "Hermes richiesta fallita.",
            rawJson = "",
            summary = ex.message ?: ex.javaClass.simpleName
        )
    }
}

internal suspend fun sendWorkspaceRunRequest(
    settings: AppSettings,
    kind: String,
    prompt: String,
    apiKey: String?
): WorkspaceRunResult = withContext(Dispatchers.IO) {
    val runPrompt = workspaceInstructions(settings, kind, prompt)
    val title = makeTitle(prompt)

    val jobPayload = JSONObject()
        .put("title", title)
        .put("instructions", runPrompt)
        .put("detail", runPrompt)
        .put("mode", kind)
        .put("requiresApproval", false)
        .put(
            "metadata",
            JSONObject()
                .put("client", "hermes-hub")
                .put("client_surface", "android-app")
                .put("workspace", kind.lowercase())
                .put("destination", kind)
                .put("memory_scope", "shared-hermes-agent-memory")
                .put("share_with_cli", true)
                .put("output_contract", workspaceOutputContract(kind))
        )

    try {
        val job = postJson("${hermesRoot(settings)}/api/jobs", jobPayload, apiKey)
        if (job.first in 200..299) {
            val remoteId = extractTaskId(job.second)
            if (!remoteId.isNullOrBlank()) {
                runCatching { postJson("${hermesRoot(settings)}/api/jobs/$remoteId/run", JSONObject(), apiKey) }
            }
            val artifact = parseWorkspaceArtifact(kind, job.second)
            return@withContext WorkspaceRunResult(
                result = artifact.result.ifBlank { job.second.ifBlank { "Job Hermes creato per la sezione $kind." } },
                source = "Hermes Jobs",
                status = artifact.status.ifBlank { "Job creato su Hermes." },
                remoteId = remoteId,
                title = artifact.title.ifBlank { title },
                streamUrl = artifact.streamUrl,
                downloadUrl = artifact.downloadUrl
            )
        }
    } catch (_: Exception) {
        // Fall through to runs/chat fallback.
    }

    val payload = JSONObject()
        .put("model", settings.model)
        .put("input", runPrompt)
        .put(
            "metadata",
            JSONObject()
                .put("client", "hermes-hub")
                .put("client_surface", "android-app")
                .put("workspace", kind.lowercase())
                .put("source", "workspace-section")
                .put("memory_scope", "shared-hermes-agent-memory")
                .put("share_with_cli", true)
        )

    try {
        val run = postJson(resolveHermesUrl(settings, "/v1/runs"), payload, apiKey)
        if (run.first in 200..299) {
            val artifact = parseWorkspaceArtifact(kind, run.second)
            return@withContext WorkspaceRunResult(
                result = artifact.result.ifBlank { run.second.ifBlank { "Run creata su Hermes." } },
                source = "Hermes Runs",
                status = artifact.status.ifBlank { "Run Hermes completata." },
                remoteId = extractTaskId(run.second),
                title = artifact.title.ifBlank { title },
                streamUrl = artifact.streamUrl,
                downloadUrl = artifact.downloadUrl
            )
        }
    } catch (_: Exception) {
        // Fall through to chat fallback.
    }

    val chat = sendChatRequest(
        settings = settings,
        mode = "Agente",
        prompt = runPrompt,
        history = listOf(ChatMessage("Tu", runPrompt, fromUser = true)),
        conversationId = null,
        previousResponseId = null,
        apiKey = apiKey
    )
    val artifact = parseWorkspaceArtifact(kind, chat.text)
    WorkspaceRunResult(
        result = artifact.result.ifBlank { chat.text },
        source = chat.source,
        status = chat.statusMessage,
        title = artifact.title.ifBlank { title },
        streamUrl = artifact.streamUrl,
        downloadUrl = artifact.downloadUrl
    )
}

internal fun workspaceInstructions(settings: AppSettings, kind: String, prompt: String): String {
    return if (kind.equals("Video", ignoreCase = true)) {
        """
            Destinazione: Hermes Hub / Video.
            Cartella video monitorata: ${settings.videoLibraryPath}
            Memoria: usa la memoria agente condivisa Hermes/CLI/app per preferenze utente, stile, durata, ritmo, fonti e regole editoriali. Se impari una preferenza stabile, salvala lato Hermes se possibile.
            Obiettivo: crea o programma un video personale per l'utente. File finale pensato per comparire automaticamente nella cartella video monitorata; il telefono riceve solo metadati, stream_url e download_url opzionale.
            Produzione: ricerca tema se necessario, crea script, storyboard, asset plan, eventuale Remotion project/render o pipeline IA se disponibile lato server.
            Feedback: usa feedback precedenti per adattare durata, ritmo, editing, tono, fonti, voce, musica e livello tecnico.
            Output JSON richiesto: {"kind":"Video","title":"...","summary":"...","status":"...","job_id":"...","stream_url":"...","download_url":"...","sources":[]}

            Richiesta utente:
            $prompt
        """.trimIndent()
    } else {
        """
            Destinazione: Hermes Hub / News.
            Cartella news monitorata: ${settings.newsLibraryPath}
            Memoria: usa la memoria agente condivisa Hermes/CLI/app per interessi, fonti preferite, profondita, tono e filtri di qualita. Se impari una preferenza stabile, salvala lato Hermes se possibile.
            Obiettivo: crea un articolo/briefing personale per l'utente con fonti verificabili e sintesi ragionata.
            Produzione: cerca notizie rilevanti, filtra per interesse, cita fonti, separa fatti da inferenze e prepara testo leggibile come giornale personale. Se l'utente chiede formato giornale online/HTML, salva il file finale nella cartella news monitorata/news_library_path/HERMES_NEWS_LIBRARY_PATH: Hermes Hub lo legge con /v1/news/library e lo mostra in WebView interna.
            Feedback: usa feedback precedenti per adattare argomenti, profondita, tono, fonti e frequenza.
            Output JSON richiesto: {"kind":"News","title":"...","summary":"...","status":"...","job_id":"...","download_url":"/v1/media/...","sources":[{"title":"...","url":"..."}]}

            Richiesta utente:
            $prompt
        """.trimIndent()
    }
}

internal fun workspaceOutputContract(kind: String): JSONObject {
    return JSONObject()
        .put("kind", kind)
        .put("title", "string")
        .put("summary", "string")
        .put("status", "queued|running|ready|needs_feedback|failed")
        .put("job_id", "string")
        .put("stream_url", if (kind.equals("Video", ignoreCase = true)) "URL streaming video da PC/Hermes" else "")
        .put("download_url", if (kind.equals("Video", ignoreCase = true)) "URL download opzionale" else "")
        .put("sources", "array")
}

internal fun parseWorkspaceArtifact(kind: String, body: String): WorkspaceArtifact {
    val json = findFirstJSONObject(body) ?: return WorkspaceArtifact(result = body.limitText(1600))
    val title = json.extractString("title").orEmpty()
    val status = json.extractString("status")
        ?: json.extractNestedString("job", "status")
        ?: json.extractNestedString("task", "status")
        ?: ""
    val streamUrl = json.extractString("stream_url")
        ?: json.extractString("streamUrl")
        ?: json.extractNestedString("media", "stream_url")
        ?: ""
    val downloadUrl = json.extractString("download_url")
        ?: json.extractString("downloadUrl")
        ?: json.extractNestedString("media", "download_url")
        ?: ""
    val summary = json.extractString("summary")
        ?: json.extractString("article")
        ?: json.extractString("body")
        ?: json.extractString("result")
        ?: body.limitText(1600)
    val sources = json.optJSONArray("sources")?.let { array ->
        buildString {
            append("\n\nFonti:\n")
            for (i in 0 until minOf(array.length(), 12)) {
                val src = array.optJSONObject(i) ?: continue
                append("- ")
                append(src.optString("title", src.optString("url", "Fonte")))
                src.optString("url").takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
                append('\n')
            }
        }
    }.orEmpty()
    return WorkspaceArtifact(
        title = title.ifBlank { makeTitle(summary.ifBlank { kind }) },
        result = (summary + sources).trim(),
        status = status,
        streamUrl = streamUrl,
        downloadUrl = downloadUrl
    )
}

internal fun findFirstJSONObject(body: String): JSONObject? {
    val trimmed = body.trim()
    if (trimmed.startsWith("{")) {
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start >= 0 && end > start) {
        return runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
    }
    return null
}

internal fun detectWorkspaceIntent(prompt: String): String? {
    val text = prompt.lowercase()
    val asksProduction = listOf("crea", "genera", "fammi", "prepara", "programma", "cron", "job", "ogni mattina", "ogni giorno")
        .any { text.contains(it) }
    if (!asksProduction) return null
    if (listOf("video", "remotion", "youtube", "montaggio", "storyboard").any { text.contains(it) }) return "Video"
    if (listOf("news", "notizie", "articolo", "giornale", "fonti", "briefing").any { text.contains(it) }) return "News"
    return null
}

internal fun resolveWorkspaceUrl(settings: AppSettings, url: String): String {
    if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
    return "${hermesRoot(settings)}${if (url.startsWith('/')) url else "/$url"}"
}

internal fun resolveHermesUrl(settings: AppSettings, path: String): String {
    if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) return path
    val normalized = if (path.startsWith("/")) path else "/$path"
    return if (normalized.startsWith("/v1", ignoreCase = true) ||
        normalized.startsWith("/api/", ignoreCase = true) ||
        normalized.startsWith("/health", ignoreCase = true)
    ) {
        "${hermesRoot(settings)}$normalized"
    } else {
        "${settings.gatewayUrl.trimEnd('/')}$normalized"
    }
}

internal fun resolveHermesProfileUrl(
    settings: AppSettings,
    profile: String,
    path: String,
    multiplexEnabled: Boolean
): String {
    check(multiplexEnabled) { "Il multiplexing dei profili Hermes non è pronto." }
    val name = normalizeHermesProfileName(profile)
    val root = hermesRoot(settings).trimEnd('/')
    val normalized = if (path.startsWith('/')) path else "/$path"
    val suffix = if (normalized.startsWith("/v1", ignoreCase = true)) normalized.removePrefix("/v1") else normalized
    return "$root/p/${URLEncoder.encode(name, "UTF-8")}/v1$suffix"
}

internal fun resolveHermesProfileApiUrl(
    settings: AppSettings,
    profile: String,
    path: String,
    multiplexEnabled: Boolean
): String {
    check(multiplexEnabled) { "Il multiplexing dei profili Hermes non è pronto." }
    val name = normalizeHermesProfileName(profile)
    val root = hermesRoot(settings).trimEnd('/')
    val normalized = if (path.startsWith('/')) path else "/$path"
    return "$root/p/${URLEncoder.encode(name, "UTF-8")}$normalized"
}

internal fun normalizeHermesProfileName(profile: String): String {
    val name = profile.trim().lowercase()
    require(name.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "Nome profilo non valido." }
    return name
}

internal fun normalizeBotRoutineName(profile: String, name: String): String {
    val normalizedProfile = normalizeHermesProfileName(profile)
    var value = name.trim()
    if (value.startsWith("[bot:", ignoreCase = true)) {
        val close = value.indexOf(']')
        if (close >= 0) value = value.substring(close + 1).trim()
    }
    require(value.isNotBlank()) { "Il nome della routine è obbligatorio." }
    return "[bot:$normalizedProfile] $value"
}

internal suspend fun loadVideoLibrary(settings: AppSettings, apiKey: String?): Pair<List<VideoLibraryItem>, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val response = httpGetResponse(resolveHermesUrl(settings, "/v1/video/library"), apiKey)
        if (response.first !in 200..299) {
            return@withContext emptyList<VideoLibraryItem>() to "Feed video HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
        val body = response.second
        if (body.isBlank()) {
            return@withContext emptyList<VideoLibraryItem>() to "Gateway non ha restituito dati video."
        }
        val json = JSONObject(body)
        if (json.has("error")) {
            return@withContext emptyList<VideoLibraryItem>() to extractHumanError(body)
        }
        val libraryPath = json.optString("video_library_path", json.optString("library_path", settings.videoLibraryPath))
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val mediaUrl = obj.optString("media_url")
                val filename = obj.optString("filename")
                if (mediaUrl.isBlank() || filename.isBlank()) continue
                add(
                    VideoLibraryItem(
                        id = obj.optString("id", filename),
                        title = obj.optString("title", filename.substringBeforeLast('.')),
                        filename = filename,
                        mediaUrl = mediaUrl,
                        playbackUrl = obj.optString("playback_url", obj.optString("playbackUrl")),
                        compatUrl = obj.optString("compat_url", obj.optString("compatUrl")),
                        thumbnailUrl = obj.optString("thumbnail_url", obj.optString("thumbnailUrl")),
                        path = obj.optString("path"),
                        mimeType = obj.optString("mime_type", "video/*"),
                        sizeBytes = obj.optLong("size_bytes", 0L),
                        durationMs = obj.optLong("duration_ms", obj.optLong("durationMs", 0L)),
                        modifiedAt = (obj.optDouble("modified_at", 0.0) * 1000).toLong()
                    )
                )
            }
        }
        val status = if (items.isEmpty()) {
            "Cartella video sincronizzata: $libraryPath. Nessun video trovato."
        } else {
            "${items.size} video trovati in: $libraryPath"
        }
        items to status
    } catch (ex: Exception) {
        emptyList<VideoLibraryItem>() to "Errore feed video: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun loadNewsLibrary(settings: AppSettings, apiKey: String?): Pair<List<NewsHtmlItem>, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val newsPath = settings.newsLibraryPath.ifBlank { AppDefaults.newsLibraryPath }
        val encodedPath = URLEncoder.encode(newsPath, "UTF-8")
        val response = httpGetResponse(resolveHermesUrl(settings, "/v1/news/library?path=$encodedPath"), apiKey)
        if (response.first !in 200..299) {
            return@withContext emptyList<NewsHtmlItem>() to "News HTML HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
        val body = response.second
        if (body.isBlank()) {
            return@withContext emptyList<NewsHtmlItem>() to "Gateway non ha restituito dati news."
        }
        val json = JSONObject(body)
        if (json.has("error")) {
            return@withContext emptyList<NewsHtmlItem>() to extractHumanError(body)
        }
        val libraryPath = json.optString("news_library_path", json.optString("library_path", newsPath))
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url", obj.optString("media_url", obj.optString("download_url")))
                val filename = obj.optString("filename")
                if (url.isBlank() || filename.isBlank()) continue
                add(
                    NewsHtmlItem(
                        id = obj.optString("id", filename),
                        title = obj.optString("title", filename.substringBeforeLast('.')),
                        filename = filename,
                        url = url,
                        path = obj.optString("path"),
                        mimeType = obj.optString("mime_type", "text/html"),
                        sizeBytes = obj.optLong("size_bytes", 0L),
                        modifiedAt = (obj.optDouble("modified_at", 0.0) * 1000).toLong()
                    )
                )
            }
        }.sortedByDescending { it.modifiedAt }
        val status = if (items.isEmpty()) {
            "Cartella news sincronizzata: $libraryPath. Nessun HTML trovato."
        } else {
            "${items.size} pagine HTML trovate in: $libraryPath"
        }
        items to status
    } catch (ex: Exception) {
        emptyList<NewsHtmlItem>() to "Errore feed news: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun loadNewsHtml(settings: AppSettings, item: NewsHtmlItem, apiKey: String?): Pair<String, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val url = resolveWorkspaceUrl(settings, item.url)
        val response = httpGetResponse(url, apiKey)
        if (response.first in 200..299) {
            response.second to "Pagina caricata in app."
        } else {
            "<html><body style=\"font-family:sans-serif;background:#111827;color:#fff\"><h1>Errore News</h1><p>HTTP ${response.first}: ${extractHumanError(response.second)}</p></body></html>" to "Errore HTTP ${response.first}."
        }
    } catch (ex: Exception) {
        "<html><body style=\"font-family:sans-serif;background:#111827;color:#fff\"><h1>Errore News</h1><p>${(ex.message ?: ex.javaClass.simpleName).replace("<", "&lt;").replace(">", "&gt;")}</p></body></html>" to "Errore apertura HTML."
    }
}

internal fun injectHtmlBase(html: String, baseUrl: String): String {
    val base = baseUrl.replace("\"", "%22")
    val tag = "<base href=\"$base\">"
    val headIndex = html.indexOf("<head", ignoreCase = true)
    if (headIndex >= 0) {
        val headEnd = html.indexOf('>', headIndex)
        if (headEnd >= 0) {
            return html.substring(0, headEnd + 1) + tag + html.substring(headEnd + 1)
        }
    }
    return "<!doctype html><html><head>$tag<meta charset=\"utf-8\"></head><body>$html</body></html>"
}

internal suspend fun loadHubNotifications(settings: AppSettings, apiKey: String?, unreadOnly: Boolean): Pair<List<HubNotification>, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val path = if (unreadOnly) "/v1/hub/notifications?unread=1" else "/v1/hub/notifications"
        val response = httpGetResponse(resolveHermesUrl(settings, path), apiKey)
        if (response.first !in 200..299) {
            return@withContext emptyList<HubNotification>() to "Notifiche HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
        val root = JSONObject(response.second)
        val array = root.optJSONArray("items") ?: root.optJSONArray("notifications") ?: JSONArray()
        val items = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                add(
                    HubNotification(
                        id = id,
                        title = obj.optString("title", "Hermes"),
                        message = obj.optString("message", obj.optString("body", obj.optString("text", ""))),
                        kind = obj.optString("kind", "agent_message"),
                        severity = obj.optString("severity", obj.optString("type", "info")),
                        source = obj.optString("source", "hermes-agent"),
                        conversationPrompt = obj.optString("conversation_prompt", obj.optString("message", obj.optString("body", obj.optString("text", "")))),
                        createdAt = (obj.optDouble("created_at", 0.0) * 1000).toLong().let { if (it > 0) it else System.currentTimeMillis() },
                        readAt = (obj.optDouble("read_at", 0.0) * 1000).toLong().let { if (it == 0L && obj.optBoolean("read", false)) System.currentTimeMillis() else if (it == 0L) 0L else it },
                        category = obj.optString("category", obj.optString("kind", "Generale")),
                        priority = obj.optString("priority", obj.optString("severity", "Normale")),
                        archived = obj.optBoolean("archived", false),
                        snoozedUntil = (obj.optDouble("snoozed_until", 0.0) * 1000).toLong(),
                        automationId = obj.optString("automation_id", obj.optString("cron_id")),
                        runId = obj.optString("run_id"),
                        fileUrl = obj.optString("file_url", obj.optString("url")),
                        projectId = obj.optString("project_id")
                    )
                )
            }
        }.sortedByDescending { it.createdAt }
        val unread = items.count { it.readAt <= 0L }
        items to if (unread == 1) "1 notifica non letta." else "$unread notifiche non lette."
    } catch (ex: Exception) {
        emptyList<HubNotification>() to "Notifiche non disponibili: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun markHubNotificationRead(settings: AppSettings, id: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/notifications/${URLEncoder.encode(id, "UTF-8")}"), JSONObject().put("read", true), apiKey, "PATCH")
        if (response.first in 200..299) "Notifica segnata come letta." else "Notifica non aggiornata: HTTP ${response.first}: ${extractHumanError(response.second)}"
    } catch (ex: Exception) {
        "Notifica non aggiornata: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun patchHubNotification(settings: AppSettings, id: String, patch: JSONObject, apiKey: String?): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/notifications/${URLEncoder.encode(id, "UTF-8")}"), patch, apiKey, "PATCH")
        if (response.first in 200..299) "Notifica aggiornata." else "Notifica non aggiornata: HTTP ${response.first}: ${extractHumanError(response.second)}"
    } catch (ex: Exception) { "Notifica non aggiornata: ${ex.message ?: ex.javaClass.simpleName}" }
}

internal suspend fun loadCronJobs(
    settings: AppSettings,
    apiKey: String?,
    profile: String? = null,
    profileMultiplexEnabled: Boolean = false
): Pair<List<CronJob>, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val path = "/api/jobs?type=cron&include_disabled=1"
        val endpoint = if (profile.isNullOrBlank()) {
            resolveHermesUrl(settings, path)
        } else {
            resolveHermesProfileApiUrl(settings, profile, path, profileMultiplexEnabled)
        }
        val response = httpGetResponse(endpoint, apiKey)
        if (response.first !in 200..299) {
            return@withContext emptyList<CronJob>() to "Cron HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
        val jobs = parseCronJobs(response.second)
            .sortedWith(compareByDescending<CronJob> { it.enabled }
                .thenBy { it.nextRunAt.ifBlank { "9999" } }
                .thenBy { it.name })
        val active = jobs.count { it.enabled }
        jobs to if (active == 1) "1 cron attivo." else "$active cron attivi."
    } catch (ex: Exception) {
        emptyList<CronJob>() to "Cron non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun cronAction(
    settings: AppSettings,
    id: String,
    action: String,
    apiKey: String?,
    profile: String? = null,
    profileMultiplexEnabled: Boolean = false
): String = withContext(Dispatchers.IO) {
    if (id.isBlank()) return@withContext "ID cron mancante."
    return@withContext try {
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val path = if (action == "delete") "/api/jobs/$encodedId" else "/api/jobs/$encodedId/$action"
        val method = if (action == "delete") "DELETE" else "POST"
        val endpoint = if (profile.isNullOrBlank()) {
            resolveHermesUrl(settings, path)
        } else {
            resolveHermesProfileApiUrl(settings, profile, path, profileMultiplexEnabled)
        }
        val response = postJson(endpoint, JSONObject(), apiKey, method)
        if (response.first in 200..299) {
            when (action) {
                "run" -> "Cron avviato."
                "pause" -> "Cron messo in pausa."
                "resume" -> "Cron riattivato."
                "delete" -> "Cron eliminato."
                else -> "Cron aggiornato."
            }
        } else {
            "Azione cron fallita: HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
    } catch (ex: Exception) {
        "Azione cron fallita: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun saveCronJob(
    settings: AppSettings,
    id: String?,
    name: String,
    schedule: String,
    prompt: String,
    deliver: String,
    apiKey: String?,
    profile: String? = null,
    profileMultiplexEnabled: Boolean = false
): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val creating = id.isNullOrBlank()
        val path = if (creating) "/api/jobs" else "/api/jobs/${URLEncoder.encode(id, "UTF-8")}"
        val scopedName = if (profile.isNullOrBlank()) name.trim() else normalizeBotRoutineName(profile, name)
        val payload = JSONObject()
            .put("name", scopedName)
            .put("schedule", schedule.trim())
            .put("prompt", prompt)
            .put("deliver", deliver.trim().ifBlank { "local" })
        val endpoint = if (profile.isNullOrBlank()) {
            resolveHermesUrl(settings, path)
        } else {
            resolveHermesProfileApiUrl(settings, profile, path, profileMultiplexEnabled)
        }
        val response = postJson(endpoint, payload, apiKey, if (creating) "POST" else "PATCH")
        if (response.first in 200..299) {
            if (creating) "Automazione creata." else "Automazione aggiornata."
        } else {
            "Automazione non salvata: HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
    } catch (ex: Exception) {
        "Automazione non salvata: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal fun parseCronJobs(body: String): List<CronJob> {
    if (body.isBlank()) return emptyList()
    val trimmed = body.trim()
    val array = when {
        trimmed.startsWith("[") -> JSONArray(trimmed)
        else -> {
            val root = JSONObject(trimmed)
            root.optJSONArray("jobs")
                ?: root.optJSONArray("crons")
                ?: root.optJSONArray("items")
                ?: root.optJSONArray("schedules")
                ?: root.optJSONArray("data")
                ?: JSONArray().apply {
                    root.optJSONObject("job")?.let { put(it) }
                    root.optJSONObject("cron")?.let { put(it) }
                    if (length() == 0 && root.has("id")) put(root)
                }
        }
    }
    return buildList {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val state = obj.optString("state", obj.optString("status"))
            val enabled = when {
                obj.has("enabled") -> obj.optBoolean("enabled", true)
                obj.has("active") -> obj.optBoolean("active", true)
                else -> true
            } && !state.equals("paused", true) && !state.equals("disabled", true)
            val id = obj.optString("id", obj.optString("job_id", obj.optString("jobId", obj.optString("name"))))
            val name = obj.optString("name", obj.optString("title", obj.optString("label", id.ifBlank { "Cron" })))
            add(
                CronJob(
                    id = id,
                    name = name,
                    prompt = obj.optString("prompt", obj.optString("instructions", obj.optString("description", obj.optString("input")))),
                    schedule = cronScheduleText(obj),
                    state = state.ifBlank { if (enabled) "attivo" else "pausa" },
                    enabled = enabled,
                    nextRunAt = obj.optString("next_run_at", obj.optString("nextRunAt", obj.optString("next_run", obj.optString("nextRun")))),
                    lastRunAt = obj.optString("last_run_at", obj.optString("lastRunAt", obj.optString("last_run", obj.optString("lastRun")))),
                    lastStatus = obj.optString("last_status", obj.optString("lastStatus", obj.optString("last_result", obj.optString("lastResult")))),
                    deliver = obj.optString("deliver", obj.optString("delivery", obj.optString("target"))),
                    origin = cronOriginText(obj)
                )
            )
        }
    }.filter { it.id.isNotBlank() || it.name.isNotBlank() }
}

internal fun cronScheduleText(obj: JSONObject): String {
    val direct = obj.optString("schedule_display",
        obj.optString("scheduleDisplay",
            obj.optString("cron",
                obj.optString("cron_expr", obj.optString("expression")))))
    if (direct.isNotBlank()) return direct
    val value = obj.opt("schedule") ?: return ""
    return when (value) {
        is String -> value
        is JSONObject -> {
            value.optString("expr", value.optString("cron", value.optString("display"))).ifBlank {
                val kind = value.optString("kind")
                val minutes = value.optLong("minutes", 0L)
                val seconds = value.optLong("seconds", 0L)
                when {
                    kind.equals("interval", true) && minutes > 0 -> "ogni $minutes min"
                    kind.equals("interval", true) && seconds > 0 -> "ogni $seconds sec"
                    else -> value.toString()
                }
            }
        }
        else -> value.toString()
    }
}

internal fun cronOriginText(obj: JSONObject): String {
    val origin = obj.opt("origin")
    return when (origin) {
        is String -> origin
        is JSONObject -> origin.optString("client",
            origin.optString("surface",
                origin.optString("host", origin.optString("user", origin.toString()))))
        else -> obj.optString("source", obj.optString("created_by", obj.optString("createdBy")))
    }
}

internal val gatewayProbeHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}

internal fun isSuccessfulGatewayProbe(statusCode: Int): Boolean = statusCode in 200..299

internal data class GatewayRuntimeStatus(
    val agentVersion: String,
    val status: String,
    val failureReason: String
)

internal fun gatewayRuntimeLabel(connected: Boolean, runtime: GatewayRuntimeStatus?): String {
    if (!connected) return "Rete non disponibile"
    val version = runtime?.agentVersion?.trim().orEmpty()
    val detail = when (runtime?.status?.trim()?.lowercase()) {
        "rolled_back" -> "rollback"
        "rollback_failed" -> "ripristino fallito"
        "blocked" -> "aggiornamento bloccato"
        "unhealthy" -> "stato da verificare"
        "updating" -> "aggiornamento in corso"
        else -> ""
    }
    val agent = if (version.isNotBlank()) "Agent $version" else "versione non letta"
    return if (detail.isBlank()) {
        "Gateway disponibile · $agent"
    } else {
        "Gateway disponibile · $agent · $detail"
    }
}

internal fun isValidGatewayProbeUrl(url: String): Boolean {
    return try {
        val uri = URI(url)
        val scheme = uri.scheme.orEmpty().lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

internal fun probeHermesGateway(settings: AppSettings, apiKey: String?): Boolean {
    val url = resolveHermesUrl(settings, "/v1/capabilities")
    if (!isValidGatewayProbeUrl(url)) return false
    val requestContext = HermesHubProtocol.newCorrelationContext()
    for (token in hermesAuthCandidates(apiKey)) {
        val request = try {
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android-Reachability")
                .apply {
                    HermesHubProtocol.addCorrelationHeaders(this, requestContext)
                    token?.let { header("Authorization", "Bearer $it") }
                }
                .get()
                .build()
        } catch (_: Exception) {
            return false
        }
        val statusCode = try {
            gatewayProbeHttpClient.newCall(request).execute().use { it.code }
        } catch (_: Exception) {
            return false
        }
        if (isSuccessfulGatewayProbe(statusCode)) return true
        if (statusCode != 401) return false
    }
    return false
}

internal fun loadGatewayRuntimeStatus(settings: AppSettings, apiKey: String?): GatewayRuntimeStatus? {
    val url = resolveHermesUrl(settings, "/v1/hub/runtime")
    if (!isValidGatewayProbeUrl(url)) return null
    val requestContext = HermesHubProtocol.newCorrelationContext()
    for (token in hermesAuthCandidates(apiKey)) {
        val request = try {
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android-Runtime")
                .apply {
                    HermesHubProtocol.addCorrelationHeaders(this, requestContext)
                    token?.let { header("Authorization", "Bearer $it") }
                }
                .get()
                .build()
        } catch (_: Exception) {
            return null
        }
        val response = try {
            gatewayProbeHttpClient.newCall(request).execute()
        } catch (_: Exception) {
            return null
        }
        response.use {
            if (it.code == 401) return@use
            if (it.code !in 200..299) return null
            val body = it.body.string()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val failure = json.optJSONObject("failure")
            return GatewayRuntimeStatus(
                agentVersion = json.optString("agent_version").take(80),
                status = json.optString("status").take(40),
                failureReason = failure?.optString("reason").orEmpty().take(240)
            )
        }
    }
    return null
}

internal val voiceNoteHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
}

internal val updateHttpClient: OkHttpClient by lazy {
    apiHttpClient.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
}

internal val archiveEventsHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

@Volatile
internal var activeTtsMediaPlayer: MediaPlayer? = null
@Volatile
internal var activeTtsFile: File? = null

internal data class TtsRequestResult(
    val statusCode: Int,
    val audioFile: File? = null,
    val errorBody: String = ""
)

internal suspend fun speakChatMessage(context: Context, settings: AppSettings, text: String, apiKey: String?): Unit = withContext(Dispatchers.IO) {
    val cleanText = text.trim()
    if (cleanText.isBlank()) return@withContext
    val payload = JSONObject()
        .put("input", cleanText)
        .put("voice", "if_sara")
        .put("lang", "it")
        .put("speed", 1.08)
        .put("response_format", "wav")
    var lastError = "nessuna risposta"
    var lastHttpError: String? = null
    val dir = File(context.cacheDir, "tts").apply { mkdirs() }
    dir.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
        ?.forEach { runCatching { it.delete() } }
    for (candidateUrl in ttsUrlCandidates(resolveTtsSpeechUrl(settings))) {
        for (token in hermesAuthCandidates(apiKey)) {
            val response = try {
                executeTtsRequest(dir, candidateUrl, payload, token)
            } catch (ex: Exception) {
                if (lastHttpError == null) {
                    lastError = ex.message ?: ex.javaClass.simpleName
                }
                continue
            }
            val file = response.audioFile
            if (response.statusCode in 200..299 && file != null) {
                withContext(Dispatchers.Main) {
                    runCatching { activeTtsMediaPlayer?.release() }
                    activeTtsFile?.let { runCatching { it.delete() } }
                    val player = MediaPlayer()
                    activeTtsMediaPlayer = player
                    activeTtsFile = file
                    fun cleanup() {
                        runCatching { player.release() }
                        if (activeTtsMediaPlayer === player) activeTtsMediaPlayer = null
                        if (activeTtsFile == file) activeTtsFile = null
                        runCatching { file.delete() }
                    }
                    player.setOnCompletionListener {
                        cleanup()
                    }
                    player.setOnErrorListener { mp, _, _ ->
                        cleanup()
                        true
                    }
                    player.setOnPreparedListener { it.start() }
                    try {
                        player.setDataSource(file.absolutePath)
                        player.prepareAsync()
                    } catch (ex: Exception) {
                        cleanup()
                        throw ex
                    }
                }
                return@withContext
            }
            file?.let { runCatching { it.delete() } }
            lastHttpError = "HTTP ${response.statusCode}${response.errorBody.take(160).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
            lastError = lastHttpError
            if (response.statusCode != 401) break
        }
    }
    throw java.io.IOException(lastError)
}

internal fun executeTtsRequest(
    targetDirectory: File,
    url: String,
    payload: JSONObject,
    bearerToken: String?
): TtsRequestResult {
    val builder = Request.Builder()
        .url(url)
        .header("Accept", "audio/wav")
        .header("User-Agent", "HermesHub-Android")
    bearerToken?.let { builder.header("Authorization", "Bearer $it") }
    val request = builder
        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    return apiHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            return@use TtsRequestResult(
                statusCode = response.code,
                errorBody = response.body.byteStream().readUtf8Bounded()
            )
        }
        val body = response.body
        TtsRequestResult(
            statusCode = response.code,
            audioFile = streamWavToTempFile(
                directory = targetDirectory,
                prefix = "hermes-tts-",
                input = body.byteStream(),
                contentLength = body.contentLength()
            )
        )
    }
}

internal fun resolveTtsSpeechUrl(settings: AppSettings): String {
    return try {
        val uri = URI(settings.gatewayUrl.trim())
        val scheme = uri.scheme ?: "http"
        val host = uri.host?.takeIf { it.isNotBlank() } ?: error("Configura Hermes API URL")
        val port = if (uri.port > 0) uri.port else 8642
        URI(scheme, null, host, port, "/v1/audio/speech", null, null).toString()
    } catch (_: Exception) {
        error("Configura Hermes API URL")
    }
}

internal fun ttsUrlCandidates(url: String): List<String> {
    return try {
        val uri = URI(url)
        val suffix = buildString {
            append(uri.rawPath.orEmpty())
            if (!uri.rawQuery.isNullOrBlank()) append("?").append(uri.rawQuery)
        }
        val port = if (uri.port > 0) uri.port else 8642
        val currentRoot = "${uri.scheme}://${uri.host}:$port"
        val roots = listOf(currentRoot)
        roots.distinctBy { it.lowercase() }.map { it.trimEnd('/') + suffix }
    } catch (_: Exception) {
        listOf(url)
    }
}

internal fun createAttachmentFromUri(context: Context, uri: Uri, maxAttachmentMb: Int): ChatInputAttachment? {
    val resolver = context.contentResolver
    var declaredSize = -1L
    val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } else null
    } ?: (uri.lastPathSegment ?: "allegato")
    val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() }
        ?: mimeTypeFromFilename(filename)
    val maxBytes = maxAttachmentMb.coerceIn(1, 150).toLong() * 1024L * 1024L
    if (declaredSize > maxBytes) return null
    val cacheDir = File(context.cacheDir, "attachments").apply { mkdirs() }
    pruneAttachmentCache(cacheDir)
    val extension = filename.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(12).ifBlank { "bin" }
    val cached = File.createTempFile("attachment-", ".$extension", cacheDir)
    var total = 0L
    try {
        resolver.openInputStream(uri)?.use { input ->
            cached.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        cached.delete()
                        return null
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: run {
            cached.delete()
            return null
        }
    } catch (_: Exception) {
        cached.delete()
        return null
    }
    if (total <= 0L) {
        cached.delete()
        return null
    }
    return ChatInputAttachment(
        filename = filename,
        mimeType = mimeType,
        sizeBytes = total,
        localFilePath = cached.absolutePath
    )
}

internal suspend fun captureHermesAppScreenshot(context: Context, maxAttachmentMb: Int): ChatInputAttachment? = withContext(Dispatchers.Main) {
    val activity = context as? Activity ?: return@withContext null
    val view = activity.window.decorView.rootView
    if (view.width <= 0 || view.height <= 0) return@withContext null
    val bitmap = createBitmap(view.width, view.height)
    view.draw(android.graphics.Canvas(bitmap))
    val directory = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File(directory, "screenshot-${System.currentTimeMillis()}.png")
    val ok = withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; file.length() in 1..(maxAttachmentMb.coerceIn(1, 150).toLong() * 1024 * 1024) }
    bitmap.recycle()
    if (!ok) { file.delete(); return@withContext null }
    ChatInputAttachment(filename = file.name, mimeType = "image/png", sizeBytes = file.length(), localFilePath = file.absolutePath)
}

internal fun pruneAttachmentCache(directory: File) {
    val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { runCatching { it.delete() } }
}

internal fun createLocalAttachmentBlocks(attachments: List<ChatInputAttachment>): List<VisualBlock> =
    attachments.take(12).mapIndexed { index, attachment ->
        VisualBlock(
            id = "local-media-$index-${java.util.UUID.randomUUID()}",
            type = "media_file",
            title = attachment.filename,
            filename = attachment.filename,
            mediaKind = inferVisualBlockMediaKind(attachment.filename, attachment.localFilePath ?: attachment.dataUrl),
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            alt = attachment.filename,
            caption = "Condiviso con Hermes.",
            localDataUrl = attachment.localFilePath ?: attachment.dataUrl
        )
    }

internal fun createAttachmentFromClipboard(context: Context, maxAttachmentMb: Int): ChatInputAttachment? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return null
    for (index in 0 until clip.itemCount) {
        val item = clip.getItemAt(index)
        item.uri?.let { uri ->
            createAttachmentFromUri(context, uri, maxAttachmentMb)?.takeIf { attachment ->
                attachment.mimeType.startsWith("image/", ignoreCase = true)
            }?.let { return it.copy(filename = attachmentFilenameForPaste(it.filename)) }
        }

        val text = item.text?.toString()?.trim().orEmpty()
        if (text.startsWith("data:image/", ignoreCase = true)) {
            createAttachmentFromDataUrl(context, text, maxAttachmentMb)?.let { return it }
        }
    }
    return null
}

internal fun createAttachmentFromDataUrl(context: Context, dataUrl: String, maxAttachmentMb: Int): ChatInputAttachment? {
    val comma = dataUrl.indexOf(',')
    if (comma <= 0) return null
    val meta = dataUrl.substring(5, comma)
    val mimeType = meta.substringBefore(';').takeIf { it.startsWith("image/", ignoreCase = true) } ?: return null
    val payload = dataUrl.substring(comma + 1)
    val maxBytes = minOf(maxAttachmentMb.coerceIn(1, 150), 8) * 1024 * 1024
    if ((payload.length.toLong() * 3L / 4L) > maxBytes.toLong()) return null
    val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.size > maxBytes) return null
    val extension = when {
        mimeType.equals("image/jpeg", ignoreCase = true) -> "jpg"
        mimeType.equals("image/webp", ignoreCase = true) -> "webp"
        mimeType.equals("image/bmp", ignoreCase = true) -> "bmp"
        mimeType.equals("image/gif", ignoreCase = true) -> "gif"
        else -> "png"
    }
    val cacheDir = File(context.cacheDir, "attachments").apply { mkdirs() }
    pruneAttachmentCache(cacheDir)
    val file = runCatching { File.createTempFile("clipboard-", ".$extension", cacheDir).apply { writeBytes(bytes) } }.getOrNull() ?: return null
    return ChatInputAttachment(
        filename = "clipboard-${System.currentTimeMillis()}.$extension",
        mimeType = mimeType,
        sizeBytes = bytes.size.toLong(),
        localFilePath = file.absolutePath
    )
}

internal fun attachmentFilenameForPaste(filename: String): String {
    val cleaned = filename.substringAfterLast('/').ifBlank { "image" }
    return if (cleaned.startsWith("clipboard-", ignoreCase = true)) cleaned else "clipboard-$cleaned"
}

internal fun mimeTypeFromFilename(filename: String): String {
    val lower = filename.lowercase(java.util.Locale.ROOT)
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".bmp") -> "image/bmp"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".txt") -> "text/plain"
        lower.endsWith(".md") -> "text/markdown"
        lower.endsWith(".csv") -> "text/csv"
        lower.endsWith(".json") -> "application/json"
        lower.endsWith(".xml") -> "application/xml"
        lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
        lower.endsWith(".doc") -> "application/msword"
        lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        lower.endsWith(".xls") -> "application/vnd.ms-excel"
        lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        lower.endsWith(".ppt") -> "application/vnd.ms-powerpoint"
        lower.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        lower.endsWith(".zip") -> "application/zip"
        else -> "application/octet-stream"
    }
}

internal suspend fun runDiagnostics(settings: AppSettings, apiKey: String?): List<DiagnosticCheck> = withContext(Dispatchers.IO) {
    val checks = listOf(
        "Tailscale/API" to "/health",
        "Health dettagliata" to "/health/detailed",
        "Modelli" to "/v1/models",
        "Capabilities" to "/v1/capabilities",
        "Hardware" to "/v1/hub/hardware",
        "Media proxy" to "/v1/media/not-a-real-media-id",
        "Video library" to "/v1/video/library",
        "Memoria" to "/v1/hub/memory",
        "Hub state" to "/v1/hub/state"
    )

    val (discoveryLabel, discoveryPath) = checks.first()
    val discoveryEndpoint = resolveHermesUrl(settings, discoveryPath)
    val discovery = probeDiagnosticEndpoint(discoveryEndpoint, apiKey)
    val discoveryCheck = diagnosticCheck(discoveryLabel, discoveryPath, discovery)

    if (discovery.statusCode == null || discovery.statusCode == 401) {
        val reason = if (discovery.statusCode == 401) {
            "Probe base HTTP 401: check non eseguito per evitare retry auth ridondanti."
        } else {
            "Gateway non raggiungibile: check non eseguito per evitare retry host/auth ridondanti."
        }
        return@withContext listOf(discoveryCheck) + checks.drop(1).map { (label, path) ->
            val requestedEndpoint = resolveHermesUrl(settings, path)
            DiagnosticCheck(
                label = label,
                endpoint = diagnosticEffectiveUrl(requestedEndpoint, discovery.effectiveUrl),
                ok = false,
                message = reason,
                action = diagnosticAction(label)
            )
        }
    }

    val remaining = coroutineScope {
        checks.drop(1).map { (label, path) ->
            async {
                val requestedEndpoint = resolveHermesUrl(settings, path)
                val effectiveEndpoint = diagnosticEffectiveUrl(requestedEndpoint, discovery.effectiveUrl)
                val response = probePinnedDiagnosticEndpoint(effectiveEndpoint, discovery.bearerToken)
                diagnosticCheck(label, path, response)
            }
        }.awaitAll()
    }
    listOf(discoveryCheck) + remaining
}

internal fun diagnosticCheck(label: String, path: String, response: DiagnosticProbeResult): DiagnosticCheck {
    val ok = response.error == null && when (path) {
        "/v1/media/not-a-real-media-id" -> response.statusCode == 404 || response.body.contains("media_not_found")
        else -> response.statusCode?.let { it in 200..299 } == true
    }
    val attempts = if (response.attemptCount > 1) " (${response.attemptCount} tentativi)" else ""
    val message = when {
        response.error != null -> "Probe fallito$attempts: ${response.error}"
        ok -> response.body.limitText(180).ifBlank { "HTTP ${response.statusCode}$attempts" }
        response.statusCode == null -> "Trasporto fallito$attempts."
        else -> "HTTP ${response.statusCode}$attempts: ${extractHumanError(response.body)}"
    }
    return DiagnosticCheck(
        label = label,
        endpoint = response.effectiveUrl,
        ok = ok,
        message = message,
        action = diagnosticAction(label)
    )
}

internal fun diagnosticAction(label: String): String = when (label) {
    "Tailscale/API" -> "Avvia Tailscale e hermes-hub, verifica IP/porta 8642."
    "Memoria" -> "Aggiorna Hermes Gateway alla latest release e riavvia hermes-hub. Memoria = preferenze/profilo Hermes Agent lato server, non RAM telefono."
    "Hub state" -> "Aggiorna Hermes Gateway alla latest release e riavvia hermes-hub."
    "Hardware" -> "Aggiorna Hermes Gateway/patcher e installa psutil su Linux se mancano metriche live."
    "Video library" -> "Aggiorna Hermes Gateway alla latest release. Se il feed e' vuoto, imposta HERMES_VIDEO_LIBRARY_PATH sul server."
    else -> "Controlla API key, gateway URL e log del terminale hermes-hub."
}

internal suspend fun loadHubMemory(settings: AppSettings, apiKey: String?): Pair<HubMemoryState, String> = withContext(Dispatchers.IO) {
    try {
        val body = httpGet(resolveHermesUrl(settings, "/v1/hub/memory"), apiKey)
        val root = JSONObject(body)
        if (root.has("error")) return@withContext HubMemoryState() to "Memoria gateway non esposta: ${extractHumanError(body)}"
        val categories = root.optJSONObject("categories") ?: JSONObject()
        HubMemoryState(
            videoPreferences = categories.optString("video_preferences"),
            newsPreferences = categories.optString("news_preferences"),
            responseStyle = categories.optString("response_style"),
            projectRules = categories.optString("project_rules"),
            generalNotes = categories.optString("general_notes")
        ) to "Memoria caricata da gateway."
    } catch (ex: Exception) {
        HubMemoryState() to "Memoria gateway non esposta: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun saveHubMemory(settings: AppSettings, memory: HubMemoryState, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val payload = JSONObject().put(
            "categories",
            JSONObject()
                .put("video_preferences", memory.videoPreferences)
                .put("news_preferences", memory.newsPreferences)
                .put("response_style", memory.responseStyle)
                .put("project_rules", memory.projectRules)
                .put("general_notes", memory.generalNotes)
        )
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/memory"), payload, apiKey, "PATCH")
        if (response.first in 200..299) "Memoria salvata sul gateway." else "Memoria gateway non esposta: HTTP ${response.first} ${extractHumanError(response.second)}"
    } catch (ex: Exception) {
        "Memoria gateway non esposta: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun postHubState(settings: AppSettings, kind: String, entityId: String, payload: JSONObject, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val body = JSONObject()
            .put("kind", kind)
            .put("entity_id", entityId)
            .put("project_id", if (settings.activeProjectId.isBlank()) JSONObject.NULL else settings.activeProjectId)
            .put("project_name", if (settings.activeProjectName.isBlank()) JSONObject.NULL else settings.activeProjectName)
            .put("payload", payload)
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/state"), body, apiKey)
        if (response.first in 200..299) "Sincronizzato con Hub State." else "Hub State non disponibile: HTTP ${response.first}"
    } catch (ex: Exception) {
        "Hub State non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun syncConversationsToHub(context: Context, settings: AppSettings, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val items = conversationsToJsonArray(loadConversations(context, includeDeleted = true))
        val payload = JSONObject().put("items", items)
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/conversations/import"), payload, apiKey)
        if (response.first !in 200..299) {
            return@withContext "Archivio server non disponibile: HTTP ${response.first} ${extractHumanError(response.second)}"
        }
        val merged = runCatching { JSONObject(response.second).optInt("merged", items.length()) }.getOrDefault(items.length())
        "Archivio caricato sul gateway: $merged elementi aggiornati."
    } catch (ex: Exception) {
        "Archivio server non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun restoreConversationsFromHub(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    syncAfterSave: Boolean = true
): String = withContext(Dispatchers.IO) {
    try {
        val response = httpGetResponse(resolveHermesUrl(settings, "/v1/hub/conversations"), apiKey)
        if (response.first !in 200..299) {
            return@withContext "Archivio server non disponibile: HTTP ${response.first} ${extractHumanError(response.second)}"
        }
        val remote = readConversationsFromJsonArray(JSONObject(response.second).optJSONArray("items") ?: JSONArray())
        if (remote.isEmpty()) {
            return@withContext "Archivio server vuoto."
        }
        synchronized(localArchiveLock) {
            val byId = loadConversations(context, includeDeleted = true).associateBy { it.id }.toMutableMap()
            remote.forEach { incoming ->
                val existing = byId[incoming.id]
                if (existing == null || incoming.updatedAt >= existing.updatedAt) {
                    byId[incoming.id] = incoming
                }
            }
            saveConversations(context, byId.values.toList(), syncAfterSave)
        }
        "Archivio scaricato dal gateway: ${remote.size} chat disponibili."
    } catch (ex: Exception) {
        "Archivio server non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun syncRemoteTasks(settings: AppSettings, apiKey: String?): Pair<List<AgentTask>, String> = withContext(Dispatchers.IO) {
    try {
        val body = httpGet(resolveHermesUrl(settings, "/api/jobs"), apiKey)
        val array = findWorkspaceJobsArray(body)
        val tasks = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.extractString("id") ?: obj.extractString("job_id") ?: "job_${i}_${System.currentTimeMillis()}"
                add(
                    AgentTask(
                        id = "remote_$id",
                        remoteId = id,
                        title = obj.optString("title", "Hermes job $id"),
                        mode = "Job",
                        status = obj.optString("status", "sincronizzato"),
                        detail = obj.optString("instructions", obj.optString("detail", obj.toString().limitText(600))),
                        requiresApproval = obj.optBoolean("requiresApproval", obj.optBoolean("approvalRequired", false)),
                        source = "Hermes Jobs",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }.sortedByDescending { it.updatedAt }
        tasks to "Sincronizzati ${tasks.size} job da Hermes."
    } catch (ex: Exception) {
        emptyList<AgentTask>() to "Sync Jobs fallita: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun supportsResponsesApi(settings: AppSettings, apiKey: String?): Boolean = withContext(Dispatchers.IO) {
    try {
        val body = httpGet("${settings.gatewayUrl.trimEnd('/')}/capabilities", apiKey)
        body.isBlank() || body.contains("responses", ignoreCase = true)
    } catch (_: Exception) {
        true
    }
}

internal fun hermesRoot(settings: AppSettings): String {
    val api = settings.gatewayUrl.trimEnd('/')
    return if (api.endsWith("/v1", ignoreCase = true)) api.removeSuffix("/v1") else api
}

internal fun hasConfiguredHermesEndpoint(settings: AppSettings): Boolean {
    return runCatching {
        val uri = URI(settings.gatewayUrl.trim())
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

internal val OPERATOR_PRESETS = listOf(
    OperatorPreset("Dashboard", "Health", "GET /health", ""),
    OperatorPreset("Dashboard", "Health detailed", "GET /health/detailed", ""),
    OperatorPreset("Dashboard", "Capabilities", "GET /v1/capabilities", ""),
    OperatorPreset("Modelli", "Lista modelli", "GET /v1/models", ""),
    OperatorPreset("Tecnico", "Crea run", "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"Controlla stato operativo e riassumi.\"}"),
    OperatorPreset("Cron", "Lista cron", "GET /api/jobs", ""),
    OperatorPreset("Cron", "Crea cron", "POST /api/jobs", "{\"name\":\"Controllo operativo\",\"schedule\":\"0 8 * * *\",\"prompt\":\"Controlla stato Hermes e segnala problemi.\"}")
)

internal fun String.limitText(maxLength: Int): String {
    return if (length <= maxLength) this else take(maxLength) + "..."
}

internal fun String.jsonEscaped(): String {
    return replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
}

internal const val EXTRACT_ASSISTANT_TEXT_MAX_DEPTH = 10

internal fun extractAssistantText(body: String, depth: Int = 0): String {
    if (depth >= EXTRACT_ASSISTANT_TEXT_MAX_DEPTH) return ""
    val trimmed = body.trim()
    if (trimmed.isBlank()) {
        return ""
    }

    if (trimmed.contains("data:", ignoreCase = true)) {
        val builder = StringBuilder()
        trimmed.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("data:", ignoreCase = true)) return@forEach
            val payload = line.removePrefix("data:").trim()
            if (payload.isBlank() || payload == "[DONE]") return@forEach
            builder.append(extractAssistantText(payload, depth + 1))
        }
        return builder.toString().trim()
    }

    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
        return trimmed
    }

    return try {
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            buildString {
                for (i in 0 until array.length()) {
                    append(extractJsonText(array.opt(i)))
                }
            }.trim()
        } else {
            extractJsonText(JSONObject(trimmed)).trim()
        }
    } catch (_: Exception) {
        trimmed
    }
}

internal const val EXTRACT_JSON_TEXT_MAX_DEPTH = 10

internal fun extractJsonText(value: Any?, depth: Int = 0): String {
    if (depth >= EXTRACT_JSON_TEXT_MAX_DEPTH) return ""
    return when (value) {
        is String -> value
        is JSONObject -> {
            listOf("output_text", "text", "content", "message", "reply").forEach { key ->
                val text = extractJsonText(value.opt(key), depth + 1)
                if (text.isNotBlank()) {
                    return text
                }
            }

            val choices = value.optJSONArray("choices")
            if (choices != null) {
                for (i in 0 until choices.length()) {
                    val text = extractJsonText(choices.opt(i), depth + 1)
                    if (text.isNotBlank()) {
                        return text
                    }
                }
            }

            listOf("delta", "choice", "data").forEach { key ->
                val text = extractJsonText(value.opt(key), depth + 1)
                if (text.isNotBlank()) {
                    return text
                }
            }
            ""
        }
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                append(extractJsonText(value.opt(i), depth + 1))
            }
        }
        else -> ""
    }
}

internal fun extractVisualBlocks(body: String): List<VisualBlock> {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{") || trimmed.toByteArray(Charsets.UTF_8).size > VISUAL_BLOCKS_MAX_PAYLOAD_BYTES * 3) {
        return emptyList()
    }

    return try {
        val root = JSONObject(trimmed)
        val version = root.optInt("visual_blocks_version", VISUAL_BLOCKS_VERSION)
        if (version < VISUAL_BLOCKS_VERSION) {
            return emptyList()
        }
        val array = findJsonArray(root, "visual_blocks") ?: return emptyList()
        if (array.toString().toByteArray(Charsets.UTF_8).size > VISUAL_BLOCKS_MAX_PAYLOAD_BYTES) {
            return emptyList()
        }
        buildList {
            for (i in 0 until minOf(array.length(), VISUAL_BLOCKS_MAX_BLOCKS)) {
                val obj = array.optJSONObject(i) ?: continue
                val block = readVisualBlock(obj)
                if (block.isValidVisualBlock()) {
                    add(block)
                } else {
                    add(toUnknownVisualBlock(obj))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun toUnknownVisualBlock(obj: JSONObject): VisualBlock {
    val rawType = obj.optString("type", "unknown")
    return VisualBlock(
        id = obj.optString("id").ifBlank { "unknown-${System.nanoTime()}" },
        type = "unknown_block",
        title = "Blocco Hermes non renderizzato: $rawType",
        caption = "Payload conservato per compatibilita' forward.",
        rawJson = obj.toString()
    )
}

internal fun findJsonArray(value: Any?, key: String): JSONArray? {
    return when (value) {
        is JSONObject -> {
            value.optJSONArray(key) ?: value.keys().asSequence()
                .mapNotNull { findJsonArray(value.opt(it), key) }
                .firstOrNull()
        }
        is JSONArray -> {
            for (i in 0 until value.length()) {
                findJsonArray(value.opt(i), key)?.let { return it }
            }
            null
        }
        else -> null
    }
}

internal fun readVisualBlock(obj: JSONObject): VisualBlock {
    val type = obj.optString("type")
    val isMediaFile = type.equals("media_file", ignoreCase = true)
    val mediaUrl = if (isMediaFile) {
        firstNonBlank(
            obj.optString("media_url"),
            obj.optString("download_url"),
            obj.optString("downloadUrl"),
            obj.optString("url"),
            obj.optString("file_url"),
            obj.optString("fileUrl")
        ).trimEnd('.', ',', ';', ':')
    } else {
        obj.optString("media_url")
    }
    val filename = if (isMediaFile) {
        firstNonBlank(
            obj.optString("filename"),
            inferVisualBlockFilename(obj.optString("title", "File Hermes"), mediaUrl),
            "download"
        )
    } else {
        obj.optString("filename")
    }
    val mediaKind = if (isMediaFile) normalizeVisualBlockMediaKind(obj.optString("media_kind"), inferVisualBlockMediaKind(filename, mediaUrl)) else obj.optString("media_kind")
    val mimeType = if (isMediaFile) firstNonBlank(obj.optString("mime_type"), inferVisualBlockMimeType(filename, mediaUrl)) else obj.optString("mime_type")
    val alt = if (isMediaFile) firstNonBlank(obj.optString("alt"), obj.optString("title"), filename, "File Hermes") else obj.optString("alt")

    return VisualBlock(
        id = obj.optString("id"),
        type = type,
        title = obj.optString("title"),
        caption = obj.optString("caption"),
        text = obj.optString("text"),
        language = obj.optString("language", "plaintext"),
        filename = filename,
        code = obj.optString("code"),
        highlightLines = readIntArray(obj.optJSONArray("highlight_lines")),
        columns = readVisualColumns(obj.optJSONArray("columns") ?: JSONArray()),
        rows = readVisualRows(obj.optJSONArray("rows") ?: JSONArray()),
        chartType = obj.optString("chart_type"),
        xLabel = obj.optString("x_label"),
        yLabel = obj.optString("y_label"),
        unit = obj.optString("unit"),
        summary = obj.optString("summary"),
        value = obj.opt("value")?.toString().orEmpty(),
        progressValue = obj.opt("progress").let { value ->
            when (value) {
                is Number -> value.toDouble()
                else -> null
            }
        },
        status = obj.optString("status"),
        action = obj.optString("action"),
        deviceName = obj.optString("device_name"),
        deviceKind = obj.optString("device_kind"),
        deviceStatus = obj.optString("device_status"),
        batteryPercent = obj.opt("battery_percent").let { value ->
            when (value) {
                is Number -> value.toDouble()
                else -> null
            }
        },
        series = readVisualSeries(obj.optJSONArray("series") ?: JSONArray()),
        sourceFormat = obj.optString("source_format"),
        source = obj.optString("source"),
        renderedMediaUrl = obj.optString("rendered_media_url"),
        mediaUrl = mediaUrl,
        mediaKind = mediaKind,
        mimeType = mimeType,
        sizeBytes = obj.optLongOrNull("size_bytes"),
        durationMs = obj.optLongOrNull("duration_ms"),
        thumbnailUrl = obj.optString("thumbnail_url"),
        localDataUrl = obj.optString("local_data_url"),
        alt = alt,
        layout = obj.optString("layout"),
        images = readVisualImages(obj.optJSONArray("images") ?: JSONArray()),
        variant = obj.optString("variant"),
        rawJson = obj.optString("raw_json", obj.toString())
    )
}

internal fun readVisualColumns(array: JSONArray): List<VisualTableColumn> = buildList {
    for (i in 0 until minOf(array.length(), 12)) {
        val obj = array.optJSONObject(i) ?: continue
        add(
            VisualTableColumn(
                key = obj.optString("key"),
                label = obj.optString("label"),
                align = obj.optString("align", "left"),
                format = obj.optString("format", "text"),
                sortable = obj.optBoolean("sortable", false)
            )
        )
    }
}

internal fun readVisualRows(array: JSONArray): List<Map<String, String>> = buildList {
    for (i in 0 until minOf(array.length(), 100)) {
        val obj = array.optJSONObject(i) ?: continue
        add(obj.keys().asSequence().associateWith { key -> obj.opt(key)?.toString().orEmpty() })
    }
}

internal fun readVisualSeries(array: JSONArray): List<VisualChartSeries> = buildList {
    for (i in 0 until minOf(array.length(), 8)) {
        val obj = array.optJSONObject(i) ?: continue
        val points = obj.optJSONArray("points") ?: JSONArray()
        add(
            VisualChartSeries(
                name = obj.optString("name"),
                points = buildList {
                    for (pointIndex in 0 until minOf(points.length(), 200)) {
                        val point = points.optJSONObject(pointIndex) ?: continue
                        add(VisualChartPoint(point.opt("x")?.toString().orEmpty(), point.optDouble("y")))
                    }
                }
            )
        )
    }
}

internal fun readVisualImages(array: JSONArray): List<VisualGalleryImage> = buildList {
    for (i in 0 until minOf(array.length(), 12)) {
        val obj = array.optJSONObject(i) ?: continue
        add(VisualGalleryImage(obj.optString("media_url"), obj.optString("alt"), obj.optString("caption")))
    }
}

internal fun readIntArray(array: JSONArray?): List<Int> = buildList {
    if (array == null) return@buildList
    for (i in 0 until minOf(array.length(), 80)) {
        add(array.optInt(i))
    }
}

internal fun VisualBlock.isValidVisualBlock(): Boolean {
    if (id.isBlank()) return false
    return when (type.lowercase()) {
        "markdown" -> text.isNotBlank()
        "code" -> code.isNotBlank() && language in ALLOWED_CODE_LANGUAGES
        "table" -> columns.isNotEmpty() && columns.size <= 12 && rows.size <= 100
        "chart" -> chartType in setOf("bar", "line") && summary.isNotBlank() && series.isNotEmpty() && series.size <= 8 && series.all { it.points.isNotEmpty() && it.points.size <= 200 }
        "diagram" -> sourceFormat == "mermaid" && source.isNotBlank() && alt.isNotBlank()
        "image_gallery" -> images.isNotEmpty() && images.size <= 12 && images.all { it.mediaUrl.isNotBlank() && it.alt.isNotBlank() }
        "media_file" -> mediaKind in setOf("image", "video", "audio", "document") &&
            (mediaUrl.isNotBlank() || isValidLocalAttachmentSource(localDataUrl)) && alt.isNotBlank()
        "callout" -> variant in setOf("info", "warning", "error", "success") && text.isNotBlank()
        "metric" -> value.isNotBlank()
        "progress" -> progressValue?.let { it in 0.0..1.0 } == true &&
            (status.isBlank() || status in setOf("pending", "active", "complete", "blocked", "failed"))
        "approval" -> status in setOf("pending", "approved", "rejected") && action.isNotBlank()
        "device" -> deviceName.isNotBlank() &&
            deviceStatus in setOf("connected", "connecting", "disconnected", "unknown") &&
            (batteryPercent == null || batteryPercent in 0.0..100.0)
        "unknown_block" -> rawJson.isNotBlank()
        else -> false
    }
}

internal fun isSafeMediaUrl(value: String): Boolean {
    if (value.isBlank()) return false
    if (value.startsWith("/v1/media/", ignoreCase = true)) return true
    return try {
        val uri = URI(value)
        (uri.scheme == "http" || uri.scheme == "https") && uri.path.startsWith("/v1/media/")
    } catch (_: Exception) {
        false
    }
}

internal fun isValidLocalAttachmentSource(value: String): Boolean {
    if (value.startsWith("data:", ignoreCase = true)) return true
    return value.isNotBlank() && File(value).isFile
}

internal fun firstNonBlank(vararg values: String?): String {
    return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
}

internal fun inferVisualBlockFilename(label: String, url: String): String {
    val candidate = label.takeIf { it.contains('.') } ?: url.substringAfterLast('/').substringAfterLast('\\')
    return candidate.substringBefore('?').substringBefore('#').take(180).ifBlank { "download" }
}

internal fun inferVisualBlockMediaKind(filename: String, url: String): String {
    val value = "$filename $url".lowercase()
    return when {
        listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { value.contains(it) } -> "image"
        listOf(".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".m2ts", ".3gp", ".ogv").any { value.contains(it) } -> "video"
        listOf(".mp3", ".wav", ".m4a", ".flac", ".ogg").any { value.contains(it) } -> "audio"
        else -> "document"
    }
}

internal fun normalizeVisualBlockMediaKind(value: String?, inferred: String): String {
    return when (value.orEmpty().trim().lowercase().replace("_", "-")) {
        "image", "video", "audio", "document" -> value.orEmpty().trim().lowercase()
        "file", "attachment", "download", "binary" -> "document"
        else -> inferred.ifBlank { "document" }
    }
}

internal fun inferVisualBlockMimeType(filename: String, url: String): String {
    val value = "$filename $url".lowercase()
    return when {
        value.contains(".png") -> "image/png"
        value.contains(".jpg") || value.contains(".jpeg") -> "image/jpeg"
        value.contains(".webp") -> "image/webp"
        value.contains(".gif") -> "image/gif"
        value.contains(".mp4") || value.contains(".m4v") -> "video/mp4"
        value.contains(".mov") -> "video/quicktime"
        value.contains(".webm") -> "video/webm"
        value.contains(".mkv") -> "video/x-matroska"
        value.contains(".avi") -> "video/x-msvideo"
        value.contains(".wmv") -> "video/x-ms-wmv"
        value.contains(".flv") -> "video/x-flv"
        value.contains(".mpg") || value.contains(".mpeg") -> "video/mpeg"
        value.contains(".ts") || value.contains(".m2ts") -> "video/mp2t"
        value.contains(".3gp") -> "video/3gpp"
        value.contains(".ogv") -> "video/ogg"
        value.contains(".mp3") -> "audio/mpeg"
        value.contains(".wav") -> "audio/wav"
        value.contains(".m4a") -> "audio/mp4"
        value.contains(".flac") -> "audio/flac"
        value.contains(".ogg") -> "audio/ogg"
        value.contains(".pdf") -> "application/pdf"
        value.contains(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        value.contains(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        value.contains(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        value.contains(".svg") -> "image/svg+xml"
        value.contains(".zip") -> "application/zip"
        value.contains(".md") || value.contains(".markdown") -> "text/markdown"
        value.contains(".txt") -> "text/plain"
        value.contains(".csv") -> "text/csv"
        value.contains(".json") -> "application/json"
        value.contains(".html") || value.contains(".htm") -> "text/html"
        else -> ""
    }
}

internal fun shouldAttachVisualBlocks(settings: AppSettings, prompt: String): Boolean {
    if (settings.visualBlocksMode == "never") return false
    if (settings.visualBlocksMode == "always") return true
    return prompt.contains("visual", ignoreCase = true) ||
        prompt.contains("immagine", ignoreCase = true) ||
        prompt.contains("immagini", ignoreCase = true) ||
        prompt.contains("image", ignoreCase = true) ||
        prompt.contains("foto", ignoreCase = true) ||
        prompt.contains("file", ignoreCase = true) ||
        prompt.contains("condivid", ignoreCase = true) ||
        prompt.contains("scaric", ignoreCase = true) ||
        prompt.contains("inviami", ignoreCase = true) ||
        prompt.contains("diagram", ignoreCase = true) ||
        prompt.contains("grafico", ignoreCase = true) ||
        prompt.contains("tabella", ignoreCase = true) ||
        prompt.contains("spiegazione visiva", ignoreCase = true)
}

internal fun visualBlockFixtures(): List<VisualBlock> {
    return listOf(
        VisualBlock(
            id = "fixture-markdown",
            type = "markdown",
            title = "Schema operativo",
            text = "## Hermes Visual Blocks\n- Testo fallback sempre completo\n- Blocchi tipizzati validati\n- Renderer statici sicuri"
        ),
        VisualBlock(
            id = "fixture-code",
            type = "code",
            title = "Esempio payload",
            language = "json",
            filename = "visual-response.json",
            code = "{\n  \"output_text\": \"Risposta completa.\",\n  \"visual_blocks_version\": 1\n}",
            highlightLines = listOf(2)
        ),
        VisualBlock(
            id = "fixture-table",
            type = "table",
            title = "Limiti v1",
            columns = listOf(VisualTableColumn("item", "Elemento"), VisualTableColumn("limit", "Limite", "right")),
            rows = listOf(
                mapOf("item" to "Blocchi", "limit" to "20"),
                mapOf("item" to "Payload", "limit" to "500 KB"),
                mapOf("item" to "Chart", "limit" to "8x200")
            )
        ),
        VisualBlock(
            id = "fixture-chart",
            type = "chart",
            title = "Esempio chart",
            chartType = "bar",
            xLabel = "Piattaforma",
            yLabel = "Copertura",
            unit = "%",
            summary = "Windows e Android usano lo stesso contratto Visual Blocks v1.",
            series = listOf(
                VisualChartSeries(
                    "Copertura",
                    listOf(VisualChartPoint("Windows", 100.0), VisualChartPoint("Android", 100.0), VisualChartPoint("Fallback", 100.0))
                )
            )
        ),
        VisualBlock(
            id = "fixture-diagram",
            type = "diagram",
            title = "Flusso",
            sourceFormat = "mermaid",
            source = "graph TD; User-->HermesHub; HermesHub-->HermesAgent; HermesAgent-->VisualBlocks;",
            alt = "Utente verso Hermes Hub, Hermes Agent e Visual Blocks"
        ),
        VisualBlock(
            id = "fixture-gallery",
            type = "image_gallery",
            title = "Media proxy",
            layout = "grid",
            images = listOf(VisualGalleryImage("/v1/media/example.webp", "Esempio asset da proxy Hermes", "Placeholder proxy"))
        ),
        VisualBlock(
            id = "fixture-media",
            type = "media_file",
            title = "File multimediale",
            mediaUrl = "/v1/media/video-demo.mp4",
            mediaKind = "video",
            mimeType = "video/mp4",
            filename = "video-demo.mp4",
            sizeBytes = 1_048_576,
            durationMs = 12_000,
            thumbnailUrl = "/v1/media/video-demo-thumb.webp",
            alt = "Anteprima video demo",
            caption = "Video condiviso dall'agente via proxy Hermes"
        ),
        VisualBlock(
            id = "fixture-callout",
            type = "callout",
            variant = "info",
            title = "Sicurezza",
            text = "Niente HTML, niente JS, niente SVG client-side."
        ),
        VisualBlock(
            id = "fixture-metric",
            type = "metric",
            title = "Copertura contratto",
            value = "100",
            unit = "%",
            status = "verified"
        ),
        VisualBlock(
            id = "fixture-progress",
            type = "progress",
            title = "Migrazione",
            progressValue = 0.75,
            status = "active",
            unit = "%",
            summary = "Tre quarti dei dati sono stati verificati."
        ),
        VisualBlock(
            id = "fixture-approval",
            type = "approval",
            title = "Conferma operazione",
            status = "pending",
            action = "Approvare la sincronizzazione",
            text = "L'operazione richiede conferma esplicita."
        ),
        VisualBlock(
            id = "fixture-device",
            type = "device",
            title = "Dispositivo DAT",
            deviceName = "Ray-Ban Meta",
            deviceKind = "wearable",
            deviceStatus = "connected",
            batteryPercent = 82.0,
            summary = "Camera connessa; audio sul telefono."
        )
    )
}

internal fun extractTaskId(body: String): String? {
    return try {
        val json = JSONObject(body)
        json.extractString("id")
            ?: json.extractString("taskId")
            ?: json.extractString("job_id")
            ?: json.extractString("jobId")
            ?: json.extractNestedString("task", "id")
            ?: json.extractNestedString("job", "id")
    } catch (_: Exception) {
        null
    }
}

internal fun extractTaskStatus(body: String): String? {
    return try {
        val json = JSONObject(body)
        json.extractString("status")
            ?: json.extractNestedString("task", "status")
            ?: json.extractNestedString("job", "status")
    } catch (_: Exception) {
        null
    }
}

internal fun extractResponseId(body: String): String? {
    return try {
        val json = JSONObject(body)
        json.extractString("id")
            ?: json.extractString("response_id")
            ?: json.extractString("responseId")
            ?: json.extractNestedString("response", "id")
    } catch (_: Exception) {
        null
    }
}

internal fun extractHumanError(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isBlank()) {
        return "errore sconosciuto"
    }

    return try {
        val json = JSONObject(trimmed)
        json.extractString("error")
            ?: json.extractString("message")
            ?: json.extractString("detail")
            ?: trimmed
    } catch (_: Exception) {
        trimmed
    }
}

internal fun JSONObject.extractString(key: String): String? {
    val value = opt(key)
    return when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject, is JSONArray -> extractJsonText(value).takeIf { it.isNotBlank() }
        else -> null
    }
}

internal fun JSONObject.extractNestedString(parentKey: String, childKey: String): String? {
    return optJSONObject(parentKey)?.extractString(childKey)
}

internal fun JSONObject.optFiniteDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.takeIf { it.isFinite() }
}

internal fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return try { getLong(key) } catch (_: Exception) { null }
}

internal fun JSONArray.toStringList(limit: Int = 100): List<String> = buildList {
    for (index in 0 until minOf(length(), limit)) {
        val value = optString(index).trim()
        if (value.isNotBlank() && none { it.equals(value, ignoreCase = true) }) add(value)
    }
}

internal fun buildFallbackReply(settings: AppSettings, mode: String, reason: String): String {
    val prefix = if (mode == "Agente") {
        "Hermes assente: preparo un job locale e tengo il contesto pronto."
    } else {
        "Hermes assente: uso la risposta locale di emergenza senza perdere la conversazione."
    }

    return "$prefix Preset: API ${settings.gatewayUrl}, modello ${settings.model}, protocollo ${settings.preferredApi}. Motivo: $reason."
}

internal fun fallbackTaskResult(settings: AppSettings, task: AgentTask, message: String): GatewayTaskResult {
    return if (settings.demoMode) {
        GatewayTaskResult(
            task.copy(
                mode = "Locale",
                status = if (task.requiresApproval) "In attesa approvazione" else "Pronto",
                source = "Fallback locale",
                updatedAt = System.currentTimeMillis()
            ),
            "$message Salvo il task in locale."
        )
    } else {
        GatewayTaskResult(
            task.copy(
                status = "Errore Hermes",
                source = "Errore Hermes",
                updatedAt = System.currentTimeMillis()
            ),
            message
        )
    }
}

internal fun replaceTask(tasks: MutableList<AgentTask>, updatedTask: AgentTask) {
    val index = tasks.indexOfFirst { it.id == updatedTask.id }
    if (index >= 0) {
        tasks[index] = updatedTask
    } else {
        tasks.add(0, updatedTask)
    }
}

internal suspend fun checkGithubUpdate(localVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(AppDefaults.latestReleaseApi)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HermesHub-Android")
            .get()
            .build()
        updateHttpClient.newCall(request).execute().use { response ->
            val code = response.code
            if (code !in 200..299) {
                return@withContext UpdateCheckResult(
                    hasUpdate = false,
                    latestVersion = null,
                    message = "Nessuna release GitHub trovata. Crea una release con tag vX.Y.Z e asset APK.",
                    releaseUrl = AppDefaults.releasesPage,
                    assetUrl = null,
                    releaseSummary = ""
                )
            }

            val body = response.body.byteStream().readUtf8Bounded()
            val json = JSONObject(body)
            val latest = normalizeVersion(json.optString("tag_name"))
            val releaseUrl = json.optString("html_url", AppDefaults.releasesPage)
            val releaseSummary = summarizeReleaseNotes(json.optString("body"))
            val assetUrl = findReleaseAsset(json.optJSONArray("assets") ?: JSONArray(), ".apk")
            val hasUpdate = compareVersions(latest, localVersion) > 0
            var message = if (hasUpdate) {
                "Aggiornamento disponibile: $localVersion -> $latest."
            } else {
                "App aggiornata. Versione locale: $localVersion, GitHub: $latest."
            }
            if (hasUpdate && assetUrl == null) {
                message += " Release trovata, ma manca asset Android .apk."
            }

            UpdateCheckResult(
                hasUpdate = hasUpdate,
                latestVersion = latest,
                message = message,
                releaseUrl = releaseUrl,
                assetUrl = assetUrl,
                releaseSummary = releaseSummary
            )
        }
    } catch (ex: Exception) {
        UpdateCheckResult(
            hasUpdate = false,
            latestVersion = null,
            message = "Controllo update non riuscito: ${ex.message ?: ex.javaClass.simpleName}",
            releaseUrl = AppDefaults.releasesPage,
            assetUrl = null,
            releaseSummary = ""
        )
    }
}

internal fun summarizeReleaseNotes(body: String): String {
    return body.trim().limitText(4000)
}

internal fun findReleaseAsset(assets: JSONArray, suffix: String): String? {
    var fallback: String? = null
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name")
        val url = asset.optString("browser_download_url")
        if (name.endsWith(suffix, ignoreCase = true) && url.isNotBlank()) {
            if (fallback == null && !name.contains("debug", ignoreCase = true)) fallback = url
            if (name.startsWith("HermesHub-", ignoreCase = true) &&
                name.contains("android", ignoreCase = true) &&
                !name.contains("debug", ignoreCase = true)
            ) return url
        }
    }
    return fallback
}

internal fun compareVersions(latest: String, local: String): Int {
    val latestParts = parseVersionParts(latest)
    val localParts = parseVersionParts(local)
    for (i in 0 until maxOf(latestParts.size, localParts.size)) {
        val left = latestParts.getOrElse(i) { 0 }
        val right = localParts.getOrElse(i) { 0 }
        if (left != right) return left.compareTo(right)
    }

    return 0
}

internal fun parseVersionParts(value: String): List<Int> {
    return normalizeVersion(value)
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
}

internal fun normalizeVersion(value: String): String {
    return value.trim().trimStart('v', 'V')
}

internal fun downloadProgressLabel(progress: Float?, label: String): String {
    val safe = (progress ?: 0f).coerceIn(0f, 1f)
    val percent = "${(safe * 100).toInt()}%"
    return if (label.isBlank()) percent else "$percent  $label"
}

internal const val MAX_UPDATE_APK_BYTES = 250L * 1024L * 1024L

internal fun isAdvertisedUpdateApkSizeRejected(contentLength: Long): Boolean =
    contentLength > MAX_UPDATE_APK_BYTES

internal fun wouldExceedUpdateApkSizeLimit(downloadedBytes: Long, nextChunkBytes: Int): Boolean =
    nextChunkBytes > 0 && downloadedBytes > MAX_UPDATE_APK_BYTES - nextChunkBytes.toLong()

internal suspend fun downloadUpdateApk(
    context: Context,
    assetUrl: String,
    version: String,
    onProgress: (Float, String, String) -> Unit
): File? = withContext(Dispatchers.IO) {
    val targetDirectory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "exports").apply { mkdirs() }
    val targetFile = File(targetDirectory, "HermesHub-${normalizeVersion(version)}.apk")
    val partialFile = File(targetDirectory, "${targetFile.name}.part")
    partialFile.delete()
    val request = Request.Builder()
        .url(assetUrl)
        .header("Accept", "application/octet-stream")
        .header("User-Agent", "HermesHub-Android")
        .build()

    try {
        updateHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext null
            }

            val body = response.body
            val totalBytes = body.contentLength()
            if (isAdvertisedUpdateApkSizeRejected(totalBytes)) {
                withContext(Dispatchers.Main) {
                    onProgress(0f, "APK rifiutato: dimensione superiore a 250 MiB.", "")
                }
                return@withContext null
            }
            var downloadedBytes = 0L
            var lastPercent = -1
            var exceededSizeLimit = false

            body.byteStream().use { input ->
                FileOutputStream(partialFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        if (wouldExceedUpdateApkSizeLimit(downloadedBytes, read)) {
                            exceededSizeLimit = true
                            break
                        }

                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        if (totalBytes > 0) {
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            val percent = (progress * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                val sizeLabel = if (totalBytes > 0L) {
                                    "${downloadedBytes.toReadableFileSize()} / ${totalBytes.toReadableFileSize()}"
                                } else {
                                    downloadedBytes.toReadableFileSize()
                                }
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, "Scaricamento APK in corso... $percent%", sizeLabel)
                                }
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (exceededSizeLimit) {
                partialFile.delete()
                withContext(Dispatchers.Main) {
                    onProgress(0f, "APK rifiutato: download superiore a 250 MiB.", "")
                }
                return@withContext null
            }
            if (totalBytes > 0L && downloadedBytes != totalBytes) {
                partialFile.delete()
                return@withContext null
            }
        }

        val validationError = validateUpdateApk(context, partialFile, version)
        if (validationError != null) {
            partialFile.delete()
            withContext(Dispatchers.Main) { onProgress(0f, "APK rifiutato: $validationError", "") }
            return@withContext null
        }
        if (targetFile.exists() && !targetFile.delete()) {
            partialFile.delete()
            return@withContext null
        }
        if (!partialFile.renameTo(targetFile)) {
            partialFile.delete()
            return@withContext null
        }

        withContext(Dispatchers.Main) {
            onProgress(1f, "Download completato. APK pronto per l'installazione.", targetFile.length().toReadableFileSize())
        }
        targetFile
    } catch (_: Exception) {
        partialFile.delete()
        null
    }
}

internal fun installDownloadedApk(context: Context, apkPath: String): String {
    val apkFile = File(apkPath)
    if (!apkFile.exists()) {
        return "APK non trovato. Riscarica l'aggiornamento."
    }
    val expectedVersion = apkFile.name.removePrefix("HermesHub-").removeSuffix(".apk")
    validateUpdateApk(context, apkFile, expectedVersion)?.let {
        runCatching { apkFile.delete() }
        return "APK non valido: $it. Riscarica l'aggiornamento."
    }

    if (!context.packageManager.canRequestPackageInstalls()) {
        openAndroidIntent(
            context,
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            )
        )
        return "Consenti a Hermes Hub di installare APK sconosciuti, poi premi di nuovo Aggiorna."
    }

    val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    val installIntent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    return if (openAndroidIntent(context, installIntent)) {
        "Installer Android aperto. Conferma l'aggiornamento."
    } else {
        "Impossibile aprire l'installer Android."
    }
}

internal fun findDownloadedUpdateApk(context: Context, version: String): File? {
    val normalizedVersion = normalizeVersion(version)
    val targetDirectory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "exports")
    val targetFile = File(targetDirectory, "HermesHub-$normalizedVersion.apk")
    if (!targetFile.isFile) return null
    val error = validateUpdateApk(context, targetFile, version)
    if (error != null) {
        runCatching { targetFile.delete() }
        return null
    }
    return targetFile
}

internal fun validateUpdateApk(context: Context, apkFile: File, expectedVersion: String): String? {
    if (!apkFile.isFile || apkFile.length() <= 0L) return "file vuoto"
    if (apkFile.length() > MAX_UPDATE_APK_BYTES) return "file superiore a 250 MiB"
    val packageManager = context.packageManager
    return runCatching {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return "APK non leggibile"
        if (archive.packageName != context.packageName) return "applicationId inatteso: ${archive.packageName}"
        if (normalizeVersion(archive.versionName.orEmpty()) != normalizeVersion(expectedVersion)) {
            return "versione ${archive.versionName.orEmpty()} diversa da ${normalizeVersion(expectedVersion)}"
        }
        @Suppress("DEPRECATION")
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION") archive.signatures.orEmpty()
        }
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.signingCertificateHistory.orEmpty()
        } else {
            @Suppress("DEPRECATION") installed.signatures.orEmpty()
        }
        if (archiveSignatures.isEmpty() || installedSignatures.isEmpty() ||
            archiveSignatures.none { candidate -> installedSignatures.any { it.toByteArray().contentEquals(candidate.toByteArray()) } }
        ) return "firma diversa dall'app installata"
        null
    }.getOrElse { "verifica APK fallita: ${it.message ?: it.javaClass.simpleName}" }
}

internal fun Long.toReadableFileSize(): String {
    if (this <= 0L) {
        return "0 B"
    }

    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var size = this.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024.0
        unitIndex++
    }

    return if (unitIndex == 0) {
        "${size.toLong()} ${units[unitIndex]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", size, units[unitIndex])
    }
}

internal fun formatHardwareUptime(seconds: Long): String {
    if (seconds <= 0L) return "n/d"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return if (days > 0) "${days}g ${hours}h" else "${hours}h ${minutes}m"
}

internal fun formatMhz(value: Double?): String {
    return if (value != null && value > 0.0) "${value.roundToInt()} MHz" else "n/d"
}

internal fun formatTemperature(value: Double?): String {
    return if (value != null) "${String.format(java.util.Locale.US, "%.1f", value)} C" else "n/d"
}

internal fun formatWatts(value: Double?): String {
    return if (value != null) "${String.format(java.util.Locale.US, "%.0f", value)} W" else "n/d"
}

internal fun loadWorkspaceRequests(context: Context, kind: String): List<WorkspaceRequest> {
    val raw = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
    return try {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    WorkspaceRequest(
                        id = obj.optString("id"),
                        kind = obj.optString("kind"),
                        title = obj.optString("title", "Nuova richiesta"),
                        prompt = obj.optString("prompt"),
                        result = obj.optString("result"),
                        source = obj.optString("source"),
                        status = obj.optString("status"),
                        remoteId = obj.optString("remoteId").ifBlank { null },
                        streamUrl = obj.optString("streamUrl"),
                        downloadUrl = obj.optString("downloadUrl"),
                        feedback = obj.optString("feedback"),
                        updatedAt = obj.optLong("updatedAt")
                    )
                )
            }
        }
            .filter { it.kind.equals(kind, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
            .take(80)
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun saveWorkspaceRequest(
    context: Context,
    kind: String,
    prompt: String,
    result: String,
    source: String,
    status: String,
    remoteId: String? = null,
    title: String = makeTitle(prompt),
    streamUrl: String = "",
    downloadUrl: String = "",
    feedback: String = ""
) {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val existingRaw = prefs.getString("items", "[]") ?: "[]"
    val existing = try {
        JSONArray(existingRaw)
    } catch (_: Exception) {
        JSONArray()
    }
    val now = System.currentTimeMillis()
    val all = mutableListOf<JSONObject>()
    all.add(
        JSONObject()
            .put("id", "workspace_$now")
            .put("kind", kind)
            .put("title", title)
            .put("prompt", prompt)
            .put("result", result)
            .put("source", source)
            .put("status", status)
            .put("remoteId", remoteId ?: JSONObject.NULL)
            .put("streamUrl", streamUrl)
            .put("downloadUrl", downloadUrl)
            .put("feedback", feedback)
            .put("updatedAt", now)
    )
    for (i in 0 until existing.length()) {
        existing.optJSONObject(i)?.let { all.add(it) }
    }
    val array = JSONArray()
    all.sortedByDescending { it.optLong("updatedAt") }.take(200).forEach { array.put(it) }
    prefs.edit { putString("items", array.toString()) }
}

internal fun saveWorkspaceFeedback(context: Context, id: String, feedback: String, status: String) {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val existing = try {
        JSONArray(prefs.getString("items", "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
    val array = JSONArray()
    for (i in 0 until existing.length()) {
        val obj = existing.optJSONObject(i) ?: continue
        if (obj.optString("id") == id) {
            obj.put("feedback", feedback)
            obj.put("status", status)
            obj.put("updatedAt", System.currentTimeMillis())
        }
        array.put(obj)
    }
    prefs.edit { putString("items", array.toString()) }
}

internal suspend fun syncWorkspaceJobs(context: Context, settings: AppSettings, kind: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val body = httpGet("${hermesRoot(settings)}/api/jobs", apiKey)
        val array = findWorkspaceJobsArray(body)
        var imported = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val text = obj.toString()
            if (!text.contains(kind, ignoreCase = true) && !text.contains("\"workspace\":\"${kind.lowercase()}\"", ignoreCase = true)) continue
            val artifact = parseWorkspaceArtifact(kind, text)
            saveWorkspaceRequest(
                context = context,
                kind = kind,
                prompt = obj.optString("instructions", obj.optString("detail", artifact.result)),
                result = artifact.result.ifBlank { obj.optString("summary", text.limitText(900)) },
                source = "Hermes Jobs",
                status = artifact.status.ifBlank { obj.optString("status", "Sincronizzato da Hermes.") },
                remoteId = extractTaskId(text),
                title = artifact.title.ifBlank { obj.optString("title", "$kind Hermes") },
                streamUrl = artifact.streamUrl,
                downloadUrl = artifact.downloadUrl
            )
            imported++
        }
        "Sincronizzazione completata: $imported elementi $kind importati/aggiornati."
    } catch (ex: Exception) {
        "Sincronizzazione fallita: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun sendWorkspaceFeedback(settings: AppSettings, item: WorkspaceRequest, feedback: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    val instructions = """
        Feedback utente per ${item.kind} '${item.title}':
        $feedback

        Aggiorna il contenuto rispettando preferenze utente. Se e' Video, migliora editing/contenuto e mantieni output sul PC con stream_url/download_url. Se e' News, aggiorna articolo e fonti.
        Se il feedback contiene una preferenza stabile, salvala o incorporala nella memoria agente condivisa Hermes/CLI/app, non solo in questo item.
        Job originale: ${item.remoteId ?: "non disponibile"}
    """.trimIndent()
    try {
        postHubState(
            settings,
            "${item.kind.lowercase()}_feedback",
            item.id,
            JSONObject()
                .put("title", item.title)
                .put("feedback", feedback)
                .put("read", true)
                .put("status", item.status),
            apiKey
        )
        if (item.remoteId != null) {
            val response = postJson(
                "${hermesRoot(settings)}/api/jobs/${item.remoteId}",
                JSONObject().put("feedback", feedback).put("instructions", instructions),
                apiKey,
                "PATCH"
            )
            if (response.first in 200..299) {
                return@withContext "Feedback inviato a Hermes Jobs."
            }
        }
        val result = sendWorkspaceRunRequest(settings, item.kind, instructions, apiKey)
        "Feedback inviato a Hermes: ${result.status}"
    } catch (ex: Exception) {
        "Feedback salvato localmente; invio Hermes fallito: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal fun loadVideoFeedback(context: Context, id: String): String {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString("video_feedback", "{}") ?: "{}"
    return try {
        JSONObject(raw).optJSONObject(id)?.optString("feedback").orEmpty()
    } catch (_: Exception) {
        ""
    }
}

internal fun loadVideoReaction(context: Context, id: String): String {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString("video_feedback", "{}") ?: "{}"
    return try {
        JSONObject(raw).optJSONObject(id)?.optString("reaction").orEmpty()
    } catch (_: Exception) {
        ""
    }
}

internal fun saveVideoFeedback(context: Context, id: String, feedback: String, reaction: String, status: String) {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val root = try {
        JSONObject(prefs.getString("video_feedback", "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }
    root.put(
        id,
        JSONObject()
            .put("feedback", feedback)
            .put("reaction", reaction)
            .put("status", status)
            .put("updatedAt", System.currentTimeMillis())
    )
    prefs.edit { putString("video_feedback", root.toString()) }
}

internal suspend fun sendVideoLibraryFeedback(settings: AppSettings, item: VideoLibraryItem, feedback: String, reaction: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    val reactionLabel = when (reaction) {
        "like" -> "like / mi piace"
        "dislike" -> "dislike / non mi piace"
        else -> "nessuna reazione rapida"
    }
    val instructions = """
        Feedback editoriale su video Hermes Hub.
        Video: ${item.title}
        File: ${item.filename}
        Path server: ${item.path}
        Reazione rapida: $reactionLabel

        Commento utente:
        ${feedback.ifBlank { "nessun commento scritto" }}

        Interpreta like/dislike come feedback primario rapido. Il commento scritto e' un rinforzo qualitativo opzionale.
        Usa questo feedback come memoria editoriale condivisa Hermes/CLI/app quando e' stabile.
        Nei video futuri migliora ritmo, hook, chiarezza, montaggio, durata, tono, musica, voce e struttura in base a queste note.
        Non creare un nuovo video ora a meno che l'utente lo chieda esplicitamente.
    """.trimIndent()
    val payload = JSONObject()
        .put("model", settings.model)
        .put("input", instructions)
        .put(
            "metadata",
            JSONObject()
                .put("client", "hermes-hub")
                .put("client_surface", "android-app")
                .put("workspace", "video")
                .put("source", "video-feedback")
                .put("memory_scope", "shared-hermes-agent-memory")
                .put("share_with_cli", true)
        )
    try {
        postHubState(
            settings,
            "video_feedback",
            item.id,
            JSONObject()
                .put("title", item.title)
                .put("filename", item.filename)
                .put("feedback", feedback)
                .put("reaction", reaction)
                .put("path", item.path),
            apiKey
        )
        val run = postJson(resolveHermesUrl(settings, "/v1/runs"), payload, apiKey)
        if (run.first in 200..299) {
            return@withContext "Feedback inviato a Hermes."
        }
        val chatPayload = JSONObject()
            .put("model", settings.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", hermesHubAgentInstructions() + projectContextInstructions(settings)))
                    .put(JSONObject().put("role", "user").put("content", instructions))
            )
            .put("stream", false)
            .put("metadata", payload.getJSONObject("metadata"))
        val chat = postJson(resolveHermesUrl(settings, "/v1/chat/completions"), chatPayload, apiKey)
        if (chat.first in 200..299) "Feedback inviato a Hermes." else "Feedback salvato localmente; Hermes HTTP ${chat.first}: ${extractHumanError(chat.second)}"
    } catch (ex: Exception) {
        "Feedback salvato localmente; invio Hermes fallito: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun runWorkspaceJobAction(settings: AppSettings, item: WorkspaceRequest, action: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    val id = item.remoteId ?: return@withContext "Nessun job Hermes collegato."
    return@withContext try {
        val response = postJson("${hermesRoot(settings)}/api/jobs/$id/$action", JSONObject(), apiKey)
        if (response.first in 200..299) "Job Hermes aggiornato." else "Hermes HTTP ${response.first}: ${extractHumanError(response.second)}"
    } catch (ex: Exception) {
        "Azione job fallita: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal fun findWorkspaceJobsArray(body: String): JSONArray {
    val trimmed = body.trim()
    if (trimmed.startsWith("[")) return JSONArray(trimmed)
    val root = JSONObject(trimmed)
    return root.optJSONArray("jobs")
        ?: root.optJSONArray("items")
        ?: root.optJSONArray("data")
        ?: JSONArray().put(root)
}

internal fun saveConversationExchange(
    context: Context,
    conversationId: String?,
    mode: String,
    prompt: String,
    response: String,
    source: String,
    responseId: String? = null,
    visualBlocks: List<VisualBlock> = emptyList(),
    visualBlocksVersion: Int? = null
): LocalConversation {
    synchronized(localArchiveLock) {
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        val index = conversations.indexOfFirst { it.id == conversationId && it.deletedAt == null }
        val now = System.currentTimeMillis()
        val newMessages = listOf(
            ChatMessage("Tu", prompt, fromUser = true),
            ChatMessage("Hermes", response, fromUser = false, visualBlocksVersion = visualBlocksVersion, visualBlocks = visualBlocks)
        )
        val newConversationId = conversationId?.takeIf { it.isNotBlank() } ?: "conv_$now"

        val conversation = if (index >= 0) {
            val current = conversations[index]
            current.copy(
                kind = if (mode == "Agente") "Task" else current.kind,
                description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
                prompt = prompt,
                updatedAt = now,
                messages = current.messages + newMessages,
                previousResponseId = responseId ?: current.previousResponseId,
                serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, current.id)
            )
        } else {
            LocalConversation(
                id = newConversationId,
                title = UNTITLED_CHAT_TITLE,
                kind = if (mode == "Agente") "Task" else "Chat",
                description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
                prompt = prompt,
                updatedAt = now,
                messages = newMessages,
                previousResponseId = responseId,
                serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, newConversationId)
            )
        }

        if (index >= 0) {
            conversations[index] = conversation
        } else {
            conversations.add(0, conversation)
        }
        saveConversations(context, conversations)
        return conversation
    }
}

internal fun saveConversationSnapshot(
    context: Context,
    conversationId: String?,
    mode: String,
    prompt: String,
    messages: List<ChatMessage>,
    source: String,
    responseId: String? = null,
    projectId: String? = null,
    syncAfterSave: Boolean = true
): LocalConversation {
    synchronized(localArchiveLock) {
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        val index = conversations.indexOfFirst { it.id == conversationId && it.deletedAt == null }
        val now = System.currentTimeMillis()
        val newConversationId = conversationId?.takeIf { it.isNotBlank() } ?: "conv_$now"
        val conversation = if (index >= 0) {
            val current = conversations[index]
            current.copy(
                kind = if (mode == "Agente") "Task" else current.kind,
                description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
                prompt = prompt,
                updatedAt = now,
                messages = messages,
                previousResponseId = responseId ?: current.previousResponseId,
                serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, current.id),
                projectId = current.projectId.ifBlank { projectId.orEmpty() }
            )
        } else {
            LocalConversation(
                id = newConversationId,
                title = UNTITLED_CHAT_TITLE,
                kind = if (mode == "Agente") "Task" else "Chat",
                description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
                prompt = prompt,
                updatedAt = now,
                messages = messages,
                previousResponseId = responseId,
                serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, newConversationId),
                projectId = projectId.orEmpty()
            )
        }

        if (index >= 0) {
            conversations[index] = conversation
        } else {
            conversations.add(0, conversation)
        }
        materializeArtifacts(conversations, conversation)
        saveConversations(context, conversations, syncAfterSave = syncAfterSave)
        return conversation
    }
}

internal fun materializeArtifacts(items: MutableList<LocalConversation>, conversation: LocalConversation) {
    if (conversation.kind == "Artifact") return
    val types = setOf("media_file", "image_gallery", "code", "diagram", "markdown", "table", "chart")
    conversation.messages.forEach { message ->
        message.visualBlocks.filter { it.type in types }.forEach { block ->
            val blockKey = block.id.ifBlank { block.rawJson.hashCode().toUInt().toString(16) }
            val id = "artifact_${conversation.id}_${message.id}_$blockKey"
            if (items.any { it.id == id }) return@forEach
            val title = block.title.ifBlank { block.filename.ifBlank { "${block.type} · ${conversation.title}" } }
            val grouping = block.filename.ifBlank { title }
            val version = items.count { it.kind == "Artifact" && it.projectId == conversation.projectId && (it.artifactFileName.equals(grouping, true) || it.title.equals(grouping, true)) } + 1
            items.add(0, LocalConversation(
                id = id,
                title = title,
                kind = "Artifact",
                description = block.caption.ifBlank { block.summary },
                prompt = "",
                updatedAt = conversation.updatedAt,
                messages = emptyList(),
                projectId = conversation.projectId,
                artifactType = block.type,
                artifactUrl = block.mediaUrl.ifBlank { block.renderedMediaUrl },
                artifactFileName = block.filename,
                artifactMimeType = block.mimeType,
                sourceConversationId = conversation.id,
                sourceRunId = "",
                version = version
            ))
        }
    }
}

internal fun saveProjectConversation(
    context: Context,
    title: String,
    description: String,
    prompt: String
): LocalConversation {
    synchronized(localArchiveLock) {
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        val now = System.currentTimeMillis()
        val index = conversations.indexOfFirst { it.deletedAt == null && it.kind == "Progetto" && it.title.equals(title, ignoreCase = true) }
        val project = if (index >= 0) {
            conversations[index].copy(description = description, prompt = prompt, projectId = conversations[index].id, updatedAt = now)
        } else {
            LocalConversation(
                id = "project_$now",
                title = title,
                kind = "Progetto",
                description = description,
                prompt = prompt,
                updatedAt = now,
                messages = emptyList(),
                projectId = "project_$now"
            )
        }

        if (index >= 0) {
            conversations[index] = project
        } else {
            conversations.add(0, project)
        }
        saveConversations(context, conversations)
        return project
    }
}

internal fun saveProjectWorkspace(
    context: Context,
    projectId: String?,
    title: String,
    description: String,
    workspacePath: String,
    repositoryUrl: String,
    instructions: String,
    memory: String,
    authorizedTools: List<String>
): LocalConversation {
    synchronized(localArchiveLock) {
        val normalizedTitle = title.trim().take(180)
        require(normalizedTitle.isNotBlank()) { "Nome progetto obbligatorio." }
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        var index = projectId?.let { id -> conversations.indexOfFirst { it.deletedAt == null && it.kind == "Progetto" && it.id == id } } ?: -1
        if (index < 0) {
            index = conversations.indexOfFirst { it.deletedAt == null && it.kind == "Progetto" && it.title.equals(normalizedTitle, ignoreCase = true) }
        }
        val id = if (index >= 0) conversations[index].id else "project_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
        val existing = if (index >= 0) conversations[index] else null
        val project = LocalConversation(
            id = id,
            title = normalizedTitle,
            kind = "Progetto",
            description = description.trim().take(4_000),
            prompt = instructions.trim().take(20_000),
            updatedAt = System.currentTimeMillis(),
            messages = existing?.messages ?: emptyList(),
            projectId = id,
            workspacePath = workspacePath.trim().take(1_024),
            repositoryUrl = repositoryUrl.trim().take(2_048),
            projectInstructions = instructions.trim().take(20_000),
            projectMemory = memory.trim().take(20_000),
            authorizedTools = authorizedTools.map { it.trim().take(100) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(100)
        )
        if (index >= 0) conversations[index] = project else conversations.add(0, project)
        saveConversations(context, conversations)
        return project
    }
}

internal fun renameConversation(context: Context, id: String, newTitle: String): Boolean {
    if (newTitle.isBlank()) return false

    synchronized(localArchiveLock) {
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        val index = conversations.indexOfFirst { it.id == id && it.deletedAt == null }
        if (index < 0) return false

        conversations[index] = conversations[index].copy(title = newTitle, updatedAt = System.currentTimeMillis())
        saveConversations(context, conversations)
        return true
    }
}

internal fun deleteConversation(context: Context, id: String): Boolean {
    synchronized(localArchiveLock) {
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        val index = conversations.indexOfFirst { it.id == id && it.deletedAt == null }
        if (index < 0) return false
        val now = System.currentTimeMillis()
        conversations[index] = conversations[index].copy(
            title = "Chat eliminata",
            kind = "Deleted",
            description = "",
            prompt = "",
            updatedAt = now,
            messages = emptyList(),
            previousResponseId = null,
            serverConversationId = null,
            deletedAt = now
        )
        saveConversations(context, conversations)
        return true
    }
}

internal fun copyArchiveToClipboard(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("chatclaw-archive", exportArchiveText(context)))
}

internal fun importArchiveFromClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: return "Appunti vuoti."
    val imported = parseArchiveExportText(text)
    if (imported.isEmpty()) {
        return "Nessuna chat riconosciuta negli appunti."
    }
    val current = loadConversations(context)
    val seen = current.map { "${it.title}\n${it.prompt}" }.toMutableSet()
    val merged = current.toMutableList()
    var added = 0
    imported.forEach { conversation ->
        if (seen.add("${conversation.title}\n${conversation.prompt}")) {
            merged.add(conversation)
            added++
        }
    }
    if (added > 0) {
        saveConversations(context, merged)
    }
    return "Import da appunti: $added chat aggiunte."
}

internal fun exportArchiveText(context: Context): String {
    val conversations = loadConversations(context)
    if (conversations.isEmpty()) {
        return "Archivio Hermes Hub vuoto."
    }

    return conversations.joinToString("\n\n") { conversation ->
        val messages = conversation.messages.joinToString("\n") { message ->
            "${message.author}: ${message.text}"
        }
        "## ${conversation.title}\nTipo: ${conversation.kind}\nPrompt: ${conversation.prompt}\n$messages"
    }
}

internal fun parseArchiveExportText(text: String): List<LocalConversation> {
    val blocks = Regex("(?m)^## ").split(text).drop(1)
    val now = System.currentTimeMillis()
    return blocks.mapIndexedNotNull { index, block ->
        val lines = block.lines()
        val title = lines.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        var kind = "Chat"
        var prompt = ""
        val messages = mutableListOf<ChatMessage>()
        var currentAuthor: String? = null
        val currentText = StringBuilder()

        fun flushMessage() {
            val author = currentAuthor ?: return
            messages.add(
                ChatMessage(
                    author = author,
                    text = currentText.toString().trimEnd(),
                    fromUser = author.equals("Tu", ignoreCase = true) || author.equals("User", ignoreCase = true)
                )
            )
            currentAuthor = null
            currentText.clear()
        }

        lines.drop(1).forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.startsWith("Tipo: ") -> kind = line.removePrefix("Tipo: ").trim().ifBlank { "Chat" }
                line.startsWith("Prompt: ") -> prompt = line.removePrefix("Prompt: ").trim()
                else -> {
                    val match = Regex("^([^:]{1,40}):\\s?(.*)$").matchEntire(line)
                    if (match != null) {
                        flushMessage()
                        currentAuthor = match.groupValues[1].trim()
                        currentText.append(match.groupValues[2])
                    } else if (currentAuthor != null) {
                        currentText.append('\n').append(line)
                    }
                }
            }
        }
        flushMessage()
        LocalConversation(
            id = "import_${now}_$index",
            title = title,
            kind = kind,
            description = "Importato da export testo.",
            prompt = prompt,
            updatedAt = now - index,
            messages = messages
        )
    }
}

internal fun loadTasks(context: Context): List<AgentTask> {
    synchronized(localTasksLock) {
        val raw = migratePrefs(context, CURRENT_TASKS_PREFS, LEGACY_TASKS_PREFS).getString("items", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        AgentTask(
                            id = obj.optString("id"),
                            remoteId = obj.optString("remoteId").ifBlank { null },
                            title = obj.optString("title"),
                            mode = obj.optString("mode", "Locale"),
                            status = obj.optString("status", "Pronto"),
                            detail = obj.optString("detail"),
                            requiresApproval = obj.optBoolean("requiresApproval", true),
                            source = obj.optString("source", "Locale"),
                            updatedAt = obj.optLong("updatedAt")
                        )
                    )
                }
            }.sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

internal suspend fun sendChatRequest(
    settings: AppSettings,
    mode: String,
    prompt: String,
    history: List<ChatMessage>,
    conversationId: String?,
    previousResponseId: String?,
    apiKey: String?
): GatewayChatResult = withContext(Dispatchers.IO) {
    var lastError = "errore sconosciuto"
    val serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId)

    if (shouldUseResponsesFirst(settings, mode) && supportsResponsesApi(settings, apiKey)) {
        try {
            val payload = JSONObject()
                .put("model", settings.model)
                .put("input", prompt)
                .put("store", true)
                .put("conversation", serverConversationId ?: JSONObject.NULL)
                .put("previous_response_id", if (serverConversationId == null) previousResponseId ?: JSONObject.NULL else JSONObject.NULL)
                .put("metadata", visualBlocksMetadata(settings, conversationId))
            payload.put(
                "instructions",
                (if (isHermesNative(settings)) hermesNativeInstructions(mode) else if (mode.equals("Agente", ignoreCase = true)) {
                    hermesHubAgentInstructions()
                } else {
                    hermesHubChatInstructions()
                }) + projectContextInstructions(settings)
            )
            val response = postJson("${settings.gatewayUrl.trimEnd('/')}/responses", payload, apiKey, allowCompatAuth = !(isHermesNative(settings) && settings.strictNativeMode))
            if (response.first in 200..299) {
                val text = extractAssistantText(response.second)
                if (text.isNotBlank()) {
                    return@withContext GatewayChatResult(
                        text = text,
                        source = "Hermes",
                        statusMessage = "Risposta ricevuta da Hermes Responses API.",
                        usedFallback = false,
                        responseId = extractResponseId(response.second),
                        visualBlocks = extractVisualBlocks(response.second),
                        visualBlocksVersion = VISUAL_BLOCKS_VERSION
                    )
                }
                lastError = "Hermes Responses API raggiunta ma senza contenuto utile"
            } else {
                lastError = "Responses API HTTP ${response.first}: ${extractHumanError(response.second)}"
            }
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
        }

        if (settings.strictNativeMode && isHermesNative(settings)) {
            return@withContext GatewayChatResult(
                text = "Hermes native non disponibile: $lastError.",
                source = "Errore Hermes Native",
                statusMessage = "Strict native mode: nessun fallback compat eseguito. $lastError",
                usedFallback = false
            )
        }
    }

    try {
        val payload = JSONObject()
            .put("model", settings.model)
            .put("stream", false)
            .put("session_id", serverConversationId ?: JSONObject.NULL)
            .put("metadata", visualBlocksMetadata(settings, conversationId))
            .put("messages", JSONArray().apply {
                if (!isHermesNative(settings)) {
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", (if (mode.equals("Agente", ignoreCase = true)) hermesHubAgentInstructions() else hermesHubChatInstructions()) + projectContextInstructions(settings))
                    )
                }
                val compatHistory = if (isHermesNative(settings)) emptyList() else history
                compatHistory.filter { !it.isAction }.forEach { message ->
                    put(
                        JSONObject()
                            .put("role", if (message.fromUser) "user" else "assistant")
                            .put("content", message.text)
                    )
                }
            })
        val response = postJson("${settings.gatewayUrl.trimEnd('/')}/chat/completions", payload, apiKey, allowCompatAuth = !(isHermesNative(settings) && settings.strictNativeMode), sessionId = serverConversationId)
        if (response.first in 200..299) {
            val text = extractAssistantText(response.second)
            if (text.isNotBlank()) {
                return@withContext GatewayChatResult(
                    text = text,
                    source = "Hermes",
                    statusMessage = "Risposta ricevuta da Hermes Chat Completions.",
                    usedFallback = false,
                    responseId = extractResponseId(response.second),
                    visualBlocks = extractVisualBlocks(response.second),
                    visualBlocksVersion = VISUAL_BLOCKS_VERSION
                )
            }
            lastError = "Hermes Chat Completions raggiunta ma senza contenuto utile"
        } else {
            lastError = "Chat Completions HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
    } catch (ex: Exception) {
        lastError = ex.message ?: ex.javaClass.simpleName
    }

    if (settings.demoMode) {
        GatewayChatResult(
            text = buildFallbackReply(settings, mode, lastError),
            source = "Fallback locale",
            statusMessage = "Hermes non disponibile, uso fallback locale: $lastError.",
            usedFallback = true,
            visualBlocks = if (shouldAttachVisualBlocks(settings, prompt)) visualBlockFixtures() else emptyList(),
            visualBlocksVersion = VISUAL_BLOCKS_VERSION
        )
    } else {
        GatewayChatResult(
            text = "Hermes non raggiungibile: $lastError.",
            source = "Errore Hermes",
            statusMessage = "Invio fallito: $lastError.",
            usedFallback = false,
            visualBlocks = if (shouldAttachVisualBlocks(settings, prompt)) visualBlockFixtures() else emptyList(),
            visualBlocksVersion = VISUAL_BLOCKS_VERSION
        )
    }
}

internal fun saveTasks(context: Context, tasks: List<AgentTask>) {
    synchronized(localTasksLock) {
        val array = JSONArray()
        tasks.sortedByDescending { it.updatedAt }
            .take(200)
            .forEach { task ->
                array.put(
                    JSONObject()
                        .put("id", task.id)
                        .put("remoteId", task.remoteId)
                        .put("title", task.title)
                        .put("mode", task.mode)
                        .put("status", task.status)
                        .put("detail", task.detail)
                        .put("requiresApproval", task.requiresApproval)
                        .put("source", task.source)
                        .put("updatedAt", task.updatedAt)
                )
            }

        context.getSharedPreferences(CURRENT_TASKS_PREFS, Context.MODE_PRIVATE).edit {
            putString("items", array.toString())
        }
    }
}

internal fun readAssistantActivityTimeline(array: JSONArray): List<AssistantActivity> = buildList {
    for (i in 0 until minOf(array.length(), 256)) {
        val item = array.optJSONObject(i) ?: continue
        val kind = when (item.optString("kind", item.optString("Kind")).lowercase()) {
            "reasoning" -> AssistantActivity.Kind.Reasoning
            "progress", "promptprogress" -> AssistantActivity.Kind.PromptProgress
            "tool" -> AssistantActivity.Kind.Tool
            else -> null
        } ?: continue
        // Accept the previous Android nested form and the canonical flat wire form.
        val toolObject = item.optJSONObject("tool") ?: item.optJSONObject("Tool")
        val tool = (toolObject ?: item).let {
            val id = it.optString("id", it.optString("toolId", it.optString("ToolId")))
            if (id.isBlank()) null else ToolCallState(
                id = id,
                name = it.optString("name", it.optString("toolName", it.optString("ToolName", id))),
                args = it.optString("args", it.optString("toolArguments", it.optString("ToolArguments"))),
                status = it.optString("status", it.optString("toolStatus", it.optString("ToolStatus", "in esecuzione…"))),
                result = it.optString("result", it.optString("toolResult", it.optString("ToolResult"))).takeIf(String::isNotEmpty)
            )
        }
        if (kind != AssistantActivity.Kind.Tool || tool != null) {
            add(AssistantActivity(kind, item.optString("text", item.optString("Text")), tool?.let(::safeToolCall)))
        }
    }
}

internal fun writeAssistantActivityTimeline(items: List<AssistantActivity>): JSONArray = JSONArray().also { array ->
    items.takeLast(256).forEach { item ->
        val tool = item.tool?.let(::safeToolCall)
        array.put(JSONObject()
            .put("kind", activityKindWireName(item.kind))
            .put("text", item.text)
            .put("toolId", tool?.id ?: JSONObject.NULL)
            .put("toolName", tool?.name ?: JSONObject.NULL)
            .put("toolArguments", tool?.args ?: JSONObject.NULL)
            .put("toolResult", tool?.result ?: JSONObject.NULL)
            .put("toolStatus", tool?.status ?: JSONObject.NULL))
    }
}

internal fun activityKindWireName(kind: AssistantActivity.Kind): String = when (kind) {
    AssistantActivity.Kind.Reasoning -> "reasoning"
    AssistantActivity.Kind.PromptProgress -> "progress"
    AssistantActivity.Kind.Tool -> "tool"
}

internal fun normalizeGeneratedConversationTitle(value: String?, fallback: String): String {
    var title = value.orEmpty()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
        .replace(Regex("^\\s*(?:titolo|title)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        .trim(' ', '\t', '"', '\'', '`', '#', '*', '.', ':', ';', '-', '–', '—')
    if (title.isBlank()) title = fallback
    title = title.replace(MULTI_WHITESPACE_REGEX, " ").trim()
    return if (title.length <= 70) title else title.take(70).trimEnd() + "…"
}

internal fun formatDateTime(millis: Long): String {
    return java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
}

internal fun notificationChatPrompt(item: HubNotification): String {
    return item.conversationPrompt.ifBlank {
        "Riprendiamo da questa notifica Hermes:\n\nTitolo: ${item.title}\nMessaggio: ${item.message}\n\nVoglio chiederti una cosa su questa notifica."
    }
}



internal fun ensureHermesNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        HERMES_NOTIFICATION_CHANNEL,
        "Hermes Hub",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Avvisi da cron e agenti Hermes."
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

class HermesNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            if (applicationContext.getSharedPreferences("notification_settings", Context.MODE_PRIVATE).getBoolean("dnd", false)) return Result.success()
            ensureHermesNotificationChannel(applicationContext)
            val settings = loadSettings(applicationContext)
            val apiKey = loadGatewaySecret(applicationContext)
            val result = loadHubNotifications(settings, apiKey, unreadOnly = true)
            val prefs = applicationContext.getSharedPreferences(CURRENT_SETTINGS_PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet("seenHubNotifications", emptySet())?.toMutableSet() ?: mutableSetOf()
            var changed = false
            result.first.sortedBy { it.createdAt }.forEach { item ->
                if (item.archived || item.snoozedUntil > System.currentTimeMillis()) return@forEach
                if (!seen.contains(item.id) && showHermesSystemNotification(applicationContext, item)) {
                    seen.add(item.id)
                    changed = true
                }
            }
            if (changed) {
                prefs.edit { putStringSet("seenHubNotifications", seen.toList().takeLast(300).toSet()) }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

internal fun showHermesSystemNotification(context: Context, item: HubNotification): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(
        context,
        item.id.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val replyIntent = Intent(context, HermesNotificationReplyReceiver::class.java).putExtra("notification_id", item.id)
    val replyPending = PendingIntent.getBroadcast(context, item.id.hashCode() xor 0x4862, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    val remoteInput = androidx.core.app.RemoteInput.Builder("hermes_reply").setLabel("Rispondi a Hermes").build()
    val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_launcher_monochrome, "Rispondi", replyPending).addRemoteInput(remoteInput).build()
    val notification = NotificationCompat.Builder(context, HERMES_NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(item.title.ifBlank { "Hermes" })
        .setContentText(item.message.take(180))
        .setStyle(NotificationCompat.BigTextStyle().bigText(item.message.take(1200)))
        .setContentIntent(pending)
        .addAction(replyAction)
        .setAutoCancel(true)
        .setGroup(item.automationId.ifBlank { "hermes-${item.category}" })
        .setOngoing(item.kind.equals("long_run", true) && item.readAt <= 0L)
        .setPriority(when (notificationPriorityRank(item.priority)) { 4 -> NotificationCompat.PRIORITY_MAX; 3 -> NotificationCompat.PRIORITY_HIGH; 1 -> NotificationCompat.PRIORITY_LOW; else -> NotificationCompat.PRIORITY_DEFAULT })
        .build()
    NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification)
    return true
}

internal fun appVersion(context: Context): String {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "debug"
    } catch (_: Exception) {
        "debug"
    }
}

internal fun openAndroidIntent(context: Context, intent: Intent): Boolean {
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}

private val localTasksLock = Any()

private const val CURRENT_TASKS_PREFS = "chatclaw_tasks"

private const val LEGACY_TASKS_PREFS = "nemoclaw_tasks"

internal const val VISUAL_BLOCKS_VERSION = 1
private const val VISUAL_BLOCKS_MAX_PAYLOAD_BYTES = 500 * 1024
private val ALLOWED_CODE_LANGUAGES = setOf(
    "plaintext",
    "mermaid",
    "powershell",
    "bash",
    "json",
    "xml",
    "csharp",
    "kotlin",
    "python",
    "javascript",
    "typescript",
    "sql",
    "yaml",
    "markdown"
)

private const val CURRENT_WORKSPACE_PREFS = "chatclaw_workspace_requests"
internal const val SHOW_RAW_HERMES_EVENTS_IN_CHAT = false
internal const val CHAT_HISTORY_MAX_MESSAGES = 30
internal const val STREAMING_CHECKPOINT_INTERVAL_MS = 5000L
internal const val DEFAULT_CONTEXT_WINDOW_TOKENS = 90000
internal const val CONTEXT_SYSTEM_OVERHEAD_TOKENS = 900
internal const val MESSAGE_CONTEXT_OVERHEAD_TOKENS = 6
