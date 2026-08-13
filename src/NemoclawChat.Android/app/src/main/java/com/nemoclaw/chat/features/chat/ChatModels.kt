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

@androidx.compose.runtime.Immutable
data class ChatMessage(
    val author: String,
    val text: String,
    val fromUser: Boolean,
    val isAction: Boolean = false,
    val thinking: String = "",
    val activityTimeline: List<AssistantActivity> = emptyList(),
    val visualBlocksVersion: Int? = null,
    val visualBlocks: List<VisualBlock> = emptyList(),
    val stats: ChatStreamStats? = null,
    val rawEvents: List<HermesRawEvent> = emptyList(),
    val id: String = java.util.UUID.randomUUID().toString(),
    val isBookmarked: Boolean = false
)

@androidx.compose.runtime.Immutable
data class HermesRawEvent(
    val name: String,
    val json: String,
    val timestamp: Long = System.currentTimeMillis()
)

internal const val SAFE_RAW_EVENT_NAME = "hermes.event"

// private fun safeRawHermesEvent — implemented as an internal cross-file
// boundary because the archive repository shares this package.
internal fun safeRawHermesEvent(timestamp: Long = System.currentTimeMillis()): HermesRawEvent =
    HermesRawEvent(SAFE_RAW_EVENT_NAME, SAFE_RAW_EVENT_JSON, timestamp)

@androidx.compose.runtime.Immutable
data class VisualBlock(
    val id: String,
    val type: String,
    val title: String = "",
    val caption: String = "",
    val text: String = "",
    val language: String = "plaintext",
    val filename: String = "",
    val code: String = "",
    val highlightLines: List<Int> = emptyList(),
    val columns: List<VisualTableColumn> = emptyList(),
    val rows: List<Map<String, String>> = emptyList(),
    val chartType: String = "",
    val xLabel: String = "",
    val yLabel: String = "",
    val unit: String = "",
    val summary: String = "",
    val value: String = "",
    val progressValue: Double? = null,
    val status: String = "",
    val action: String = "",
    val deviceName: String = "",
    val deviceKind: String = "",
    val deviceStatus: String = "",
    val batteryPercent: Double? = null,
    val series: List<VisualChartSeries> = emptyList(),
    val sourceFormat: String = "",
    val source: String = "",
    val renderedMediaUrl: String = "",
    val mediaUrl: String = "",
    val mediaKind: String = "",
    val mimeType: String = "",
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String = "",
    val alt: String = "",
    val localDataUrl: String = "",
    val layout: String = "",
    val images: List<VisualGalleryImage> = emptyList(),
    val variant: String = "",
    val rawJson: String = ""
)

@androidx.compose.runtime.Immutable
data class VisualTableColumn(
    val key: String,
    val label: String,
    val align: String = "left",
    val format: String = "text",
    val sortable: Boolean = false
)

@androidx.compose.runtime.Immutable
data class VisualChartSeries(
    val name: String,
    val points: List<VisualChartPoint>
)

@androidx.compose.runtime.Immutable
data class VisualChartPoint(
    val x: String,
    val y: Double
)

@androidx.compose.runtime.Immutable
data class VisualGalleryImage(
    val mediaUrl: String,
    val alt: String,
    val caption: String = ""
)

data class AgentTask(
    val id: String,
    val remoteId: String? = null,
    val title: String,
    val mode: String,
    val status: String,
    val detail: String,
    val requiresApproval: Boolean = true,
    val source: String = "Locale",
    val updatedAt: Long = System.currentTimeMillis()
)

internal data class ArchiveItem(
    val id: String?,
    val title: String,
    val kind: String,
    val description: String,
    val prompt: String
)

data class LocalConversation(
    val id: String,
    val title: String,
    val kind: String,
    val description: String,
    val prompt: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    val previousResponseId: String? = null,
    val serverConversationId: String? = null,
    val deletedAt: Long? = null,
    val projectId: String = "",
    val workspacePath: String = "",
    val repositoryUrl: String = "",
    val projectInstructions: String = "",
    val projectMemory: String = "",
    val authorizedTools: List<String> = emptyList(),
    val artifactType: String = "",
    val artifactUrl: String = "",
    val artifactFileName: String = "",
    val artifactMimeType: String = "",
    val sourceConversationId: String = "",
    val sourceRunId: String = "",
    val version: Int = 0,
    val tags: List<String> = emptyList(),
    val folder: String = "",
    val summary: String = "",
    val parentConversationId: String = "",
    val branchFromMessageId: String = "",
    val linkedConversationIds: List<String> = emptyList()
)

data class WorkspaceRequest(
    val id: String,
    val kind: String,
    val title: String,
    val prompt: String,
    val result: String,
    val source: String,
    val status: String,
    val remoteId: String? = null,
    val streamUrl: String = "",
    val downloadUrl: String = "",
    val feedback: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

data class VideoLibraryItem(
    val id: String,
    val title: String,
    val filename: String,
    val mediaUrl: String,
    val playbackUrl: String = "",
    val compatUrl: String = "",
    val thumbnailUrl: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val modifiedAt: Long
)

data class NewsHtmlItem(
    val id: String,
    val title: String,
    val filename: String,
    val url: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

internal data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val latestVersion: String?,
    val message: String,
    val releaseUrl: String,
    val assetUrl: String?,
    val releaseSummary: String = ""
)

internal data class UpdateDownloadState(
    val status: String = "Controlla GitHub Releases per nuove versioni.",
    val releaseAssetUrl: String? = null,
    val latestVersion: String? = null,
    val hasUpdate: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float? = null,
    val downloadLabel: String = "",
    val downloadedApkPath: String? = null,
    val releaseSummary: String = ""
)

internal data class HubMemoryState(
    val videoPreferences: String = "",
    val newsPreferences: String = "",
    val responseStyle: String = "",
    val projectRules: String = "",
    val generalNotes: String = ""
)

internal data class DiagnosticCheck(
    val label: String,
    val endpoint: String,
    val ok: Boolean,
    val message: String,
    val action: String
)

internal data class GatewayChatResult(
    val text: String,
    val source: String,
    val statusMessage: String,
    val usedFallback: Boolean,
    val responseId: String? = null,
    val visualBlocks: List<VisualBlock> = emptyList(),
    val visualBlocksVersion: Int? = null
)

internal data class ContextUsage(
    val tokens: Int,
    val maxTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    val percent: Int,
    val delegatedToHermes: Boolean = false
)

internal data class GatewayTaskResult(
    val task: AgentTask,
    val message: String
)

internal data class ServerSnapshot(
    val gateway: String,
    val model: String,
    val providerDetail: String,
    val inferenceEndpoint: String,
    val policy: String,
    val statusMessage: String,
    val videoLibraryPath: String
)

internal data class HardwareDisk(
    val device: String,
    val mountpoint: String,
    val fileSystem: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val percent: Double
)

internal data class HardwareDiskGroup(
    val key: String,
    val isSsd: Boolean,
    val subtitle: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val percent: Double,
    val partitionsText: String,
    val devicesText: String
)

internal data class HardwareTemperature(
    val name: String,
    val label: String,
    val currentC: Double,
    val highC: Double?,
    val criticalC: Double?
)

internal data class HardwareTemperatureView(
    val title: String,
    val source: String,
    val currentC: Double,
    val highC: Double?,
    val criticalC: Double?,
    val sortKey: Int
)

internal data class HardwareGpu(
    val index: Int,
    val name: String,
    val utilizationPercent: Double,
    val memoryUtilizationPercent: Double,
    val memoryUsedBytes: Long,
    val memoryTotalBytes: Long,
    val temperatureC: Double?,
    val powerDrawWatts: Double?,
    val powerLimitWatts: Double?,
    val driverVersion: String
)

internal data class HardwareComponentView(
    val id: String,
    val title: String,
    val subtitle: String,
    val primaryValue: String,
    val utilizationPercent: Double,
    val temperatureC: Double?,
    val stats: List<HardwareStatView>
)

internal data class HardwareStatView(
    val label: String,
    val value: String
)

internal data class HardwareHistoryPoint(
    val utilizationPercent: Double,
    val temperatureC: Double?
)

internal data class HardwareSnapshot(
    val status: String = "loading",
    val timestampMs: Long = System.currentTimeMillis(),
    val hostname: String = "-",
    val operatingSystem: String = "-",
    val platform: String = "-",
    val architecture: String = "-",
    val processor: String = "-",
    val uptimeSeconds: Long = 0,
    val cpuPercent: Double = 0.0,
    val physicalCores: Int = 0,
    val logicalCores: Int = 0,
    val currentMhz: Double? = null,
    val maxMhz: Double? = null,
    val memoryPercent: Double = 0.0,
    val memoryTotalBytes: Long = 0,
    val memoryUsedBytes: Long = 0,
    val memoryAvailableBytes: Long = 0,
    val swapPercent: Double = 0.0,
    val swapTotalBytes: Long = 0,
    val swapUsedBytes: Long = 0,
    val networkBytesSent: Long = 0,
    val networkBytesReceived: Long = 0,
    val processCount: Int = 0,
    val temperatureSupport: String = "unavailable",
    val disks: List<HardwareDisk> = emptyList(),
    val temperatures: List<HardwareTemperature> = emptyList(),
    val gpus: List<HardwareGpu> = emptyList(),
    val message: String = "Caricamento hardware..."
)

internal data class GatewayWsProbe(
    val wsUrl: String,
    val connected: Boolean,
    val status: String,
    val detail: String,
    val capabilityLines: List<String> = emptyList()
)

internal data class GatewayRpcCallResult(
    val method: String,
    val success: Boolean,
    val status: String,
    val rawJson: String,
    val summary: String
)

internal data class CronJob(
    val id: String,
    val name: String,
    val prompt: String,
    val schedule: String,
    val state: String,
    val enabled: Boolean,
    val nextRunAt: String,
    val lastRunAt: String,
    val lastStatus: String,
    val deliver: String,
    val origin: String
)

internal data class AutomationDefinition(
    val taskPrompt: String,
    val condition: String = "",
    val timeoutSeconds: Int = 900,
    val retryCount: Int = 0,
    val notificationTemplate: String = "",
    val projectId: String = "",
    val dependencies: String = ""
)

internal const val AUTOMATION_PROMPT_PREFIX = "<!-- HERMES_HUB_AUTOMATION_V1:"
internal const val AUTOMATION_PROMPT_SUFFIX = " -->"

internal fun encodeAutomationPrompt(definition: AutomationDefinition): String {
    val normalized = definition.copy(
        taskPrompt = definition.taskPrompt.trim().take(12_000),
        condition = definition.condition.trim().take(2_000),
        timeoutSeconds = definition.timeoutSeconds.coerceIn(10, 86_400),
        retryCount = definition.retryCount.coerceIn(0, 10),
        notificationTemplate = definition.notificationTemplate.trim().take(2_000),
        projectId = definition.projectId.trim().take(200),
        dependencies = definition.dependencies.trim().take(2_000)
    )
    val json = JSONObject()
        .put("taskPrompt", normalized.taskPrompt)
        .put("condition", normalized.condition)
        .put("timeoutSeconds", normalized.timeoutSeconds)
        .put("retryCount", normalized.retryCount)
        .put("notificationTemplate", normalized.notificationTemplate)
        .put("projectId", normalized.projectId)
        .put("dependencies", normalized.dependencies)
    val metadata = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return """
        $AUTOMATION_PROMPT_PREFIX$metadata$AUTOMATION_PROMPT_SUFFIX
        Regole Automation Studio Hermes Hub:
        - Condizione: ${normalized.condition.ifBlank { "sempre" }}
        - Timeout massimo: ${normalized.timeoutSeconds} secondi
        - Retry massimi: ${normalized.retryCount}
        - Progetto: ${normalized.projectId.ifBlank { "nessuno" }}
        - Dipendenze job: ${normalized.dependencies.ifBlank { "nessuna" }}
        - Notifica finale: ${normalized.notificationTemplate.ifBlank { "riepilogo standard" }}
        Valuta condizione prima di agire. Se falsa, termina senza mutazioni e registra skipped. Rispetta timeout, retry, dipendenze e invia notifica tramite /v1/hub/notifications.

        Attività:
        ${normalized.taskPrompt}
    """.trimIndent()
}

internal fun decodeAutomationPrompt(prompt: String): AutomationDefinition {
    if (prompt.startsWith(AUTOMATION_PROMPT_PREFIX)) {
        val end = prompt.indexOf(AUTOMATION_PROMPT_SUFFIX, AUTOMATION_PROMPT_PREFIX.length)
        if (end > AUTOMATION_PROMPT_PREFIX.length) {
            runCatching {
                val encoded = prompt.substring(AUTOMATION_PROMPT_PREFIX.length, end)
                val obj = JSONObject(String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8))
                return AutomationDefinition(
                    taskPrompt = obj.optString("taskPrompt"),
                    condition = obj.optString("condition"),
                    timeoutSeconds = obj.optInt("timeoutSeconds", 900),
                    retryCount = obj.optInt("retryCount", 0),
                    notificationTemplate = obj.optString("notificationTemplate"),
                    projectId = obj.optString("projectId"),
                    dependencies = obj.optString("dependencies")
                )
            }
        }
    }
    return AutomationDefinition(prompt)
}

internal data class HubNotification(
    val id: String,
    val title: String,
    val message: String,
    val kind: String,
    val severity: String,
    val source: String,
    val conversationPrompt: String,
    val createdAt: Long,
    val readAt: Long,
    val category: String = "Generale",
    val priority: String = "Normale",
    val archived: Boolean = false,
    val snoozedUntil: Long = 0L,
    val automationId: String = "",
    val runId: String = "",
    val fileUrl: String = "",
    val projectId: String = ""
)

internal data class WorkspaceRunResult(
    val result: String,
    val source: String,
    val status: String,
    val remoteId: String? = null,
    val title: String = "",
    val streamUrl: String = "",
    val downloadUrl: String = ""
)

internal data class WorkspaceArtifact(
    val title: String = "",
    val result: String = "",
    val status: String = "",
    val streamUrl: String = "",
    val downloadUrl: String = ""
)

internal data class OperatorPreset(
    val group: String,
    val label: String,
    val method: String,
    val params: String
)
