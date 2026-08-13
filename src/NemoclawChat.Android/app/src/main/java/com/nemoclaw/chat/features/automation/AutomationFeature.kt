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

@Composable
internal fun CronScreen(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<CronJob>>(emptyList()) }
    var status by remember { mutableStateOf("Carico cron Hermes...") }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var taskPrompt by rememberSaveable { mutableStateOf("") }
    var frequency by rememberSaveable { mutableStateOf("Giornaliera") }
    var time by rememberSaveable { mutableStateOf("08:00") }
    var days by rememberSaveable { mutableStateOf("1,2,3,4,5") }
    var advancedCron by rememberSaveable { mutableStateOf("") }
    var condition by rememberSaveable { mutableStateOf("") }
    var deliver by rememberSaveable { mutableStateOf("local") }
    var timeout by rememberSaveable { mutableStateOf("900") }
    var retry by rememberSaveable { mutableStateOf("0") }
    var notificationTemplate by rememberSaveable { mutableStateOf("") }
    var projectId by rememberSaveable { mutableStateOf(settings.activeProjectId) }
    var dependencies by rememberSaveable { mutableStateOf("") }

    fun schedule(): String {
        if (frequency == "Ogni ora") return "0 * * * *"
        if (frequency == "Cron avanzato") return advancedCron.trim()
        val parts = time.trim().split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: return ""
        val minute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: return ""
        return if (frequency == "Settimanale") "$minute $hour * * ${days.trim()}" else "$minute $hour * * *"
    }

    fun definition() = AutomationDefinition(
        taskPrompt, condition, timeout.toIntOrNull() ?: 900, retry.toIntOrNull() ?: 0,
        notificationTemplate, projectId, dependencies
    )

    fun clearEditor() {
        editingId = ""; name = ""; taskPrompt = ""; frequency = "Giornaliera"; time = "08:00"
        days = "1,2,3,4,5"; advancedCron = ""; condition = ""; deliver = "local"; timeout = "900"
        retry = "0"; notificationTemplate = ""; projectId = settings.activeProjectId; dependencies = ""
    }

    fun edit(job: CronJob, duplicate: Boolean = false) {
        val decoded = decodeAutomationPrompt(job.prompt)
        editingId = if (duplicate) "" else job.id
        name = if (duplicate) "${job.name} copia" else job.name
        taskPrompt = decoded.taskPrompt; condition = decoded.condition; timeout = decoded.timeoutSeconds.toString()
        retry = decoded.retryCount.toString(); notificationTemplate = decoded.notificationTemplate
        projectId = decoded.projectId; dependencies = decoded.dependencies; deliver = job.deliver.ifBlank { "local" }
        frequency = "Cron avanzato"; advancedCron = job.schedule
        status = if (duplicate) "Copia pronta: modifica e salva." else "Modifica ${job.name}."
    }

    LaunchedEffect(settings.gatewayUrl, refreshNonce) {
        val result = loadCronJobs(settings, loadGatewaySecret(context))
        jobs = result.first
        status = result.second
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Automation Studio", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Crea, modifica, prova e controlla automazioni Hermes sul gateway.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (editingId.isBlank()) "Nuova automazione" else "Modifica automazione", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Button(onClick = { clearEditor() }) { Text("Nuova") }
                    }
                    SettingsField("Nome", name, { name = it })
                    SettingsField("Attività", taskPrompt, { taskPrompt = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Ogni ora", "Giornaliera", "Settimanale", "Cron avanzato").forEach { option ->
                            VideoFeedChip(option, selected = frequency == option) { frequency = option }
                        }
                    }
                    if (frequency == "Giornaliera" || frequency == "Settimanale") SettingsField("Ora HH:mm", time, { time = it })
                    if (frequency == "Settimanale") SettingsField("Giorni cron", days, { days = it })
                    if (frequency == "Cron avanzato") SettingsField("Espressione cron", advancedCron, { advancedCron = it })
                    Text("Espressione: ${schedule().ifBlank { "non valida" }}", color = AppColors.Muted, fontSize = 12.sp)
                    SettingsField("Condizione opzionale", condition, { condition = it })
                    SettingsField("Destinazione", deliver, { deliver = it })
                    SettingsField("Timeout secondi", timeout, { timeout = it })
                    SettingsField("Retry", retry, { retry = it })
                    SettingsField("Modello notifica", notificationTemplate, { notificationTemplate = it })
                    SettingsField("Progetto associato", projectId, { projectId = it })
                    SettingsField("Dipendenze job", dependencies, { dependencies = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val cron = schedule()
                            if (name.isBlank() || taskPrompt.isBlank() || cron.isBlank()) {
                                status = "Nome, attività e programmazione obbligatori."
                            } else scope.launch {
                                status = saveCronJob(settings, editingId.ifBlank { null }, name, cron, encodeAutomationPrompt(definition()), deliver, loadGatewaySecret(context))
                                if (!status.startsWith("Automazione non")) { clearEditor(); refreshNonce++ }
                            }
                        }) { Text("Salva") }
                        Button(onClick = {
                            if (taskPrompt.isBlank()) status = "Attività obbligatoria per prova." else scope.launch {
                                status = "Prova in corso, job non salvato..."
                                val result = sendWorkspaceRunRequest(settings, "Automation", encodeAutomationPrompt(definition()), loadGatewaySecret(context))
                                status = "${result.status} ${result.result}".trim()
                            }
                        }) { Text("Prova senza salvare") }
                        Button(onClick = { editingId = ""; name = if (name.isBlank()) "" else "$name copia"; status = "Copia pronta." }) { Text("Duplica") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(status, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Fonte: GET /api/jobs?type=cron&include_disabled=1. I cron in pausa restano visibili.", color = AppColors.Muted, fontSize = 12.sp)
                    Button(onClick = {
                        status = "Aggiorno cron..."
                        refreshNonce++
                    }) { Text("Aggiorna") }
                }
            }
        }
        if (jobs.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nessun cron trovato.", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Esempio in chat: programma un briefing ogni mattina alle 8.", color = AppColors.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        items(jobs, key = { it.id.ifBlank { it.name } }) { job ->
            CronCard(
                job = job,
                onEdit = { edit(job) },
                onDuplicate = { edit(job, duplicate = true) },
                onRun = {
                    scope.launch {
                        status = cronAction(settings, job.id, "run", loadGatewaySecret(context))
                        refreshNonce++
                    }
                },
                onPauseResume = {
                    scope.launch {
                        status = cronAction(settings, job.id, if (job.enabled) "pause" else "resume", loadGatewaySecret(context))
                        refreshNonce++
                    }
                },
                onDelete = {
                    scope.launch {
                        status = cronAction(settings, job.id, "delete", loadGatewaySecret(context))
                        refreshNonce++
                    }
                }
            )
        }
    }
}
@Composable
internal fun CronCard(
    job: CronJob,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRun: () -> Unit,
    onPauseResume: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(job.name, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (job.enabled) "Attivo" else "Pausa", color = if (job.enabled) AppColors.Accent else Color(0xFFFFB020), fontSize = 12.sp)
            }
            CronDetail("ID", job.id)
            CronDetail("Programmazione", job.schedule)
            CronDetail("Prossima esecuzione", job.nextRunAt)
            CronDetail("Ultima esecuzione", job.lastRunAt)
            CronDetail("Stato", job.state)
            CronDetail("Consegna", job.deliver)
            CronDetail("Origine", job.origin)
            CronDetail("Ultimo output", job.lastStatus)
            Text(job.prompt.ifBlank { "Prompt non disponibile." }, color = Color.White)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onEdit) { Text("Modifica") }
                Button(onClick = onDuplicate) { Text("Duplica") }
                Button(onClick = onRun) { Text("Esegui ora") }
                Button(onClick = onPauseResume) { Text(if (job.enabled) "Pausa" else "Riprendi") }
                Button(onClick = onDelete) { Text("Elimina") }
            }
        }
    }
}

@Composable
internal fun CronDetail(label: String, value: String) {
    if (value.isNotBlank()) {
        Text("$label: $value", color = AppColors.Muted, fontSize = 12.sp)
    }
}

@Composable
internal fun NotificationsScreen(context: Context, settings: AppSettings, onOpenChatPrompt: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<HubNotification>>(emptyList()) }
    var status by remember { mutableStateOf("Carico notifiche Hermes...") }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var categoryFilter by rememberSaveable { mutableStateOf("Tutte") }
    var priorityFilter by rememberSaveable { mutableStateOf("Tutte") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var dnd by rememberSaveable { mutableStateOf(context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE).getBoolean("dnd", false)) }

    LaunchedEffect(settings.gatewayUrl, refreshNonce) {
        val result = loadHubNotifications(settings, loadGatewaySecret(context), unreadOnly = false)
        items = result.first
        status = result.second
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Notifiche", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Avvisi autonomi che Hermes lascia quando cron o agenti devono contattarti.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(status, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Android controlla in background periodicamente e mostra notifiche di sistema.", color = AppColors.Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val unread = items.filter { it.readAt <= 0L }
                                if (unread.isNotEmpty()) {
                                    status = "Segnando ${unread.size} notifiche..."
                                    val secret = loadGatewaySecret(context)
                                    unread.forEach { markHubNotificationRead(settings, it.id, secret) }
                                    refreshNonce++
                                }
                            }
                        }) { Text("Segna tutto") }
                        Button(onClick = { refreshNonce++ }) { Text("Aggiorna") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("Tutte", "Automazioni", "Run", "Sistema", "File", "Progetti").forEach { value -> VideoFeedChip(value, categoryFilter == value) { categoryFilter = value } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("Tutte", "Critica", "Alta", "Normale", "Bassa").forEach { value -> VideoFeedChip(value, priorityFilter == value) { priorityFilter = value } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(unreadOnly, { unreadOnly = it }); Text("Solo non lette", color = AppColors.Muted)
                        Switch(showArchived, { showArchived = it }); Text("Archiviate", color = AppColors.Muted)
                        Switch(dnd, { dnd = it; context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE).edit { putBoolean("dnd", it) } }); Text("Non disturbare", color = AppColors.Muted)
                    }
                    Text("Badge: ${items.count { it.readAt <= 0 && !it.archived }} non lette · ${items.count { it.priority.equals("Critica", true) && !it.archived }} critiche", color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
        if (items.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nessuna notifica.", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Quando un cron trova qualcosa, Hermes puo' pubblicare un messaggio qui.", color = AppColors.Muted)
                    }
                }
            }
        }
        val visibleItems = items.filter { item ->
            item.archived == showArchived && (!unreadOnly || item.readAt <= 0L) && item.snoozedUntil <= System.currentTimeMillis() &&
                (categoryFilter == "Tutte" || item.category.equals(categoryFilter, true)) &&
                (priorityFilter == "Tutte" || item.priority.equals(priorityFilter, true))
        }.sortedWith(compareByDescending<HubNotification> { notificationPriorityRank(it.priority) }.thenByDescending { it.createdAt })
        items(visibleItems, key = { it.id }) { item ->
            NotificationCard(
                item = item,
                onRead = {
                    scope.launch {
                        status = markHubNotificationRead(settings, item.id, loadGatewaySecret(context))
                        refreshNonce++
                    }
                },
                onOpenChat = {
                    scope.launch { markHubNotificationRead(settings, item.id, loadGatewaySecret(context)) }
                    onOpenChatPrompt(notificationChatPrompt(item))
                },
                onSnooze = { scope.launch { status = patchHubNotification(settings, item.id, JSONObject().put("snoozed_until", (System.currentTimeMillis() + 3_600_000L) / 1000.0), loadGatewaySecret(context)); refreshNonce++ } },
                onArchive = { scope.launch { status = patchHubNotification(settings, item.id, JSONObject().put("archived", !item.archived), loadGatewaySecret(context)); refreshNonce++ } },
                onReference = {
                    when {
                        item.fileUrl.isNotBlank() -> context.startActivity(Intent(Intent.ACTION_VIEW, item.fileUrl.toUri()))
                        item.projectId.isNotBlank() -> onOpenChatPrompt("Apri e riepiloga il progetto ${item.projectId} collegato alla notifica.")
                        item.automationId.isNotBlank() -> onOpenChatPrompt("Controlla l'automazione ${item.automationId} collegata alla notifica.")
                        item.runId.isNotBlank() -> onOpenChatPrompt("Controlla la run ${item.runId} collegata alla notifica.")
                    }
                }
            )
        }
    }
}

@Composable
internal fun NotificationCard(item: HubNotification, onRead: () -> Unit, onOpenChat: () -> Unit, onSnooze: () -> Unit, onArchive: () -> Unit, onReference: () -> Unit) {
    val unread = item.readAt <= 0L
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (unread) "Nuova" else "Letta", color = if (unread) AppColors.Accent else AppColors.Muted, fontSize = 12.sp)
            }
            Text("${item.category} · ${item.priority} · ${item.source} · ${formatDateTime(item.createdAt)}", color = AppColors.Muted, fontSize = 12.sp)
            Text(item.message, color = Color.White)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenChat) { Text("Apri chat") }
                Button(onClick = onRead) { Text("Segna letta") }
                Button(onClick = onSnooze) { Text("Tra 1 ora") }
                Button(onClick = onArchive) { Text(if (item.archived) "Ripristina" else "Archivia") }
                if (item.fileUrl.isNotBlank() || item.projectId.isNotBlank() || item.automationId.isNotBlank() || item.runId.isNotBlank()) Button(onClick = onReference) { Text("Riferimento") }
            }
        }
    }
}

internal fun notificationPriorityRank(value: String): Int = when (value.lowercase()) { "critica", "critical" -> 4; "alta", "high" -> 3; "normale", "normal" -> 2; else -> 1 }

internal data class ContinuityItem(val id: String, val type: String, val device: String, val value: String, val conversationId: String, val projectId: String, val fileUrl: String, val fileName: String, val updatedAt: Long, val status: String)

@Composable
internal fun ContinuityScreen(context: Context, settings: AppSettings, onOpenConversation: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<ContinuityItem>>(emptyList()) }
    var status by remember { mutableStateOf("Carico continuità...") }
    var clipboardText by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val deviceId = remember { "android-${Build.MODEL}-${Build.ID}".lowercase().replace(' ', '-') }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { status = uploadContinuityFile(context, settings, uri, deviceId, loadGatewaySecret(context)); refresh++ }
    }
    LaunchedEffect(settings.gatewayUrl, refresh) { val loaded = loadContinuityItems(settings, loadGatewaySecret(context)); items = loaded.first; status = loaded.second }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Continuità dispositivi", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold); Text("Presenza, ripresa chat, handoff voce, clipboard, file, coda offline e conflitti.", color = AppColors.Muted) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("$deviceId · Android", color = Color.White, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { status = publishContinuity(context, settings, deviceId, "continuity.presence", statusValue = "online", apiKey = loadGatewaySecret(context)); refresh++ } }) { Text("Presenza") }
                        Button(onClick = { val latest = loadConversations(context).firstOrNull { it.kind in setOf("Chat", "Task") }; if (latest != null) { scope.launch { publishContinuity(context, settings, deviceId, "continuity.chat", latest.title, latest.id, latest.projectId, apiKey = loadGatewaySecret(context)); onOpenConversation(latest.id) } } }) { Text("Riprendi chat") }
                        Button(onClick = { scope.launch { status = publishContinuity(context, settings, deviceId, "continuity.voice", "handoff_requested", statusValue = "ringing", apiKey = loadGatewaySecret(context)); refresh++ } }) { Text("Trasferisci voce") }
                        Button(onClick = { scope.launch { status = flushContinuityQueue(context, settings, loadGatewaySecret(context)); refresh++ } }) { Text("Sincronizza") }
                    }
                    SettingsField("Clipboard condivisa", clipboardText, { clipboardText = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { val clipboard = context.getSystemService(ClipboardManager::class.java); val value = clipboardText.ifBlank { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty() }; scope.launch { status = publishContinuity(context, settings, deviceId, "continuity.clipboard", value, apiKey = loadGatewaySecret(context)); refresh++ } }) { Text("Invia clipboard") }
                        Button(onClick = { val remote = items.firstOrNull { it.type == "continuity.clipboard" && it.device != deviceId }; if (remote != null) { clipboardText = remote.value; context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hermes Hub", remote.value)); status = "Clipboard ricevuta." } }) { Text("Ricevi") }
                        Button(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Invia file") }
                    }
                    Text(status, color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
        items(items.take(80), key = { it.id + it.updatedAt }) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble)) { Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("${item.type} · ${item.device}", color = AppColors.Accent); Text(item.value.ifBlank { item.fileName }, color = Color.White); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (item.conversationId.isNotBlank()) Button(onClick = { onOpenConversation(item.conversationId) }) { Text("Apri chat") }; if (item.type == "continuity.clipboard") Button(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hermes Hub", item.value)) }) { Text("Copia") }; if (item.fileUrl.isNotBlank()) Button(onClick = { resolveMediaUrl(settings, item.fileUrl, allowExternalMedia = true)?.let { url -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }) { Text("Apri file") } } } }
        }
        item {
            val conflicts = items.groupBy { it.type }.filterValues { values -> values.map { it.device }.distinct().size > 1 && (values.maxOfOrNull { it.updatedAt } ?: 0L) - (values.minOfOrNull { it.updatedAt } ?: 0L) < 300_000L }
            Text("Conflitti", color = Color.White, fontWeight = FontWeight.SemiBold)
            if (conflicts.isEmpty()) Text("Nessun conflitto.", color = AppColors.Muted) else conflicts.forEach { (type, values) -> Text("$type: modifiche concorrenti da ${values.map { it.device }.distinct().joinToString()}; prevale la più recente.", color = AppColors.Muted) }
        }
    }
}

internal suspend fun loadContinuityItems(settings: AppSettings, apiKey: String?): Pair<List<ContinuityItem>, String> = withContext(Dispatchers.IO) {
    try { val response = httpGetResponse(resolveHermesUrl(settings, "/v1/hub/state"), apiKey); if (response.first !in 200..299) return@withContext emptyList<ContinuityItem>() to "Offline: HTTP ${response.first}"; val array = JSONObject(response.second).optJSONArray("items") ?: JSONArray(); val items = buildList { for (index in 0 until array.length()) { val item = array.optJSONObject(index) ?: continue; val type = item.optString("type"); if (!type.startsWith("continuity.")) continue; add(ContinuityItem(item.optString("id"), type, item.optString("device"), item.optString("value"), item.optString("conversation_id"), item.optString("project_id"), item.optString("file_url"), item.optString("file_name"), (item.optDouble("updated_at", item.optDouble("created_at", 0.0)) * 1000).toLong(), item.optString("status"))) } }.sortedByDescending { it.updatedAt }; items to "Sincronizzato: ${items.size} stati." } catch (ex: Exception) { emptyList<ContinuityItem>() to "Offline: ${ex.message}" }
}

internal suspend fun publishContinuity(context: Context, settings: AppSettings, device: String, type: String, value: String = "", conversationId: String = "", projectId: String = "", fileUrl: String = "", fileName: String = "", statusValue: String = "available", apiKey: String?): String = withContext(Dispatchers.IO) {
    val payload = JSONObject().put("id", "$type:$device").put("type", type).put("device", device).put("value", value).put("conversation_id", conversationId).put("project_id", projectId).put("file_url", fileUrl).put("file_name", fileName).put("status", statusValue).put("updated_at", System.currentTimeMillis() / 1000.0)
    try { val result = postJson(resolveHermesUrl(settings, "/v1/hub/state"), payload, apiKey); if (result.first in 200..299) "Stato pubblicato." else { enqueueContinuity(context, payload.toString()); "Offline: operazione accodata." } } catch (ex: Exception) { enqueueContinuity(context, payload.toString()); "Offline: operazione accodata (${ex.message})." }
}

internal fun enqueueContinuity(context: Context, payload: String) { val prefs = context.getSharedPreferences("continuity_queue", Context.MODE_PRIVATE); val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }; array.put(payload); while (array.length() > 100) array.remove(0); prefs.edit { putString("items", array.toString()) } }

internal suspend fun flushContinuityQueue(context: Context, settings: AppSettings, apiKey: String?): String = withContext(Dispatchers.IO) { val prefs = context.getSharedPreferences("continuity_queue", Context.MODE_PRIVATE); val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }; val remaining = JSONArray(); for (index in 0 until array.length()) { val raw = array.optString(index); val response = runCatching { postJson(resolveHermesUrl(settings, "/v1/hub/state"), JSONObject(raw), apiKey) }.getOrNull(); if (response == null || response.first !in 200..299) remaining.put(raw) }; prefs.edit { putString("items", remaining.toString()) }; if (remaining.length() == 0) "Coda offline sincronizzata." else "${remaining.length()} operazioni ancora in coda." }

internal suspend fun uploadContinuityFile(context: Context, settings: AppSettings, uri: Uri, device: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    val name = continuityDisplayName(context, uri); val temp = File(context.cacheDir, "continuity-${System.currentTimeMillis()}-${name.replace('/', '_')}")
    try { context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } } ?: return@withContext "File non leggibile."; if (temp.length() > 100L * 1024 * 1024) return@withContext "File oltre 100 MB."; val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM).addFormDataPart("file", name, temp.asRequestBody("application/octet-stream".toMediaTypeOrNull())).build(); val request = Request.Builder().url(resolveHermesUrl(settings, "/v1/media/upload")).post(body).apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }.build(); val response = apiHttpClient.newCall(request).execute(); response.use { val text = it.body.string(); if (!it.isSuccessful) return@withContext "Upload HTTP ${it.code}: ${extractHumanError(text)}"; val root = JSONObject(text); val url = root.optString("media_url", root.optString("url", root.optString("file_url"))); if (url.isBlank()) return@withContext "URL file mancante."; publishContinuity(context, settings, device, "continuity.file", name, fileUrl = url, fileName = name, apiKey = apiKey) } } finally { temp.delete() }
}

internal fun continuityDisplayName(context: Context, uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }?.takeIf { it.isNotBlank() } ?: "file"

internal data class AuditItem(val id: String, val timestamp: Long, val event: String, val summary: String, val project: String, val run: String, val tool: String, val device: String, val risk: String, val status: String)

@Composable
internal fun AuditScreen(context: Context, settings: AppSettings) {
    var project by rememberSaveable { mutableStateOf("") }; var run by rememberSaveable { mutableStateOf("") }; var tool by rememberSaveable { mutableStateOf("") }; var device by rememberSaveable { mutableStateOf("") }; var risk by rememberSaveable { mutableStateOf("") }; var refresh by remember { mutableIntStateOf(0) }; var items by remember { mutableStateOf<List<AuditItem>>(emptyList()) }; var status by remember { mutableStateOf("Carico audit...") }
    LaunchedEffect(settings.gatewayUrl, refresh) { val loaded = loadAuditItems(settings, project, run, tool, device, risk, loadGatewaySecret(context)); items = loaded.first; status = loaded.second }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Timeline audit", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold); Text("Chat, run, tool, automazioni, dispositivi e operazioni server in una cronologia filtrabile.", color = AppColors.Muted) }
        item { Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface)) { Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { SettingsField("Progetto", project, { project = it }); SettingsField("Run", run, { run = it }); SettingsField("Tool", tool, { tool = it }); SettingsField("Dispositivo", device, { device = it }); FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("", "low", "medium", "high", "critical").forEach { value -> VideoFeedChip(value.ifBlank { "Tutti i rischi" }, risk == value) { risk = value } }; Button(onClick = { refresh++ }) { Text("Applica") } }; Text(status, color = AppColors.Muted) } } }
        items(items, key = { it.id }) { item -> Surface(color = AppColors.AssistantBubble, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (item.risk in setOf("high", "critical")) Color(0xFFFF6F3D) else AppColors.Border)) { Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("${formatDateTime(item.timestamp)} · ${item.event} · rischio ${item.risk}", color = AppColors.Accent, fontWeight = FontWeight.SemiBold); Text(item.summary, color = Color.White); Text("Progetto ${item.project} · Run ${item.run} · Tool ${item.tool} · Device ${item.device} · ${item.status}", color = AppColors.Muted, fontSize = 11.sp) } } }
    }
}

internal suspend fun loadAuditItems(settings: AppSettings, project: String, run: String, tool: String, device: String, risk: String, apiKey: String?): Pair<List<AuditItem>, String> = withContext(Dispatchers.IO) {
    try { val query = "?project=${URLEncoder.encode(project, "UTF-8")}&run=${URLEncoder.encode(run, "UTF-8")}&tool=${URLEncoder.encode(tool, "UTF-8")}&device=${URLEncoder.encode(device, "UTF-8")}&risk=${URLEncoder.encode(risk, "UTF-8")}"; val response = httpGetResponse(resolveHermesUrl(settings, "/v1/hub/audit$query"), apiKey); if (response.first !in 200..299) return@withContext emptyList<AuditItem>() to "Audit HTTP ${response.first}"; val array = JSONObject(response.second).optJSONArray("items") ?: JSONArray(); val result = buildList { for (index in 0 until array.length()) { val item = array.optJSONObject(index) ?: continue; add(AuditItem(item.optString("id"), (item.optDouble("timestamp") * 1000).toLong(), item.optString("event"), item.optString("summary"), item.optString("project"), item.optString("run"), item.optString("tool"), item.optString("device"), item.optString("risk", "low"), item.optString("status"))) } }; result to "${result.size} eventi." } catch (ex: Exception) { emptyList<AuditItem>() to "Audit non disponibile: ${ex.message}" }
}
