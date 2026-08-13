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
internal fun ArchiveScreen(
    context: Context,
    onOpenConversation: (String?, String) -> Unit
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val archive = remember(refreshKey) { loadArchiveItems(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf("Tutto") }
    var status by remember { mutableStateOf("Pronto.") }
    var pendingDelete by remember { mutableStateOf<ArchiveItem?>(null) }
    var managingConversationId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val savedConversations = remember(refreshKey) { loadConversations(context) }
    val savedProjects = savedConversations.count { it.kind == "Progetto" }
    val savedChats = savedConversations.count { it.kind == "Chat" || it.kind == "Task" }
    LaunchedEffect(Unit) {
        val syncStatus = ConversationArchiveAutoSync.pullFromHub(context)
        if (syncStatus != null && !syncStatus.contains("vuoto", ignoreCase = true)) {
            status = syncStatus
            refreshKey++
        }
    }
    val results = archive.filter { item ->
        (filter == "Tutto" || item.kind == filter) &&
            (query.isBlank() ||
                item.title.contains(query, ignoreCase = true) ||
                item.description.contains(query, ignoreCase = true) ||
                item.prompt.contains(query, ignoreCase = true))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Archivio", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Ricerca locale persistente per chat, progetti e task recenti.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stato archivio", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Conversazioni: $savedChats | Progetti: $savedProjects | Totale salvati: ${savedConversations.size}", color = AppColors.Muted)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = {
                            copyArchiveToClipboard(context)
                            status = "Archivio copiato negli appunti."
                        }) {
                            Text("Export")
                        }
                        Button(onClick = {
                            status = importArchiveFromClipboard(context)
                            refreshKey++
                        }) {
                            Text("Importa appunti")
                        }
                        Button(onClick = {
                            status = "Carico archivio sul gateway..."
                            scope.launch {
                                status = syncConversationsToHub(context, loadSettings(context), loadGatewaySecret(context))
                            }
                        }) {
                            Text("Carica server")
                        }
                        Button(onClick = {
                            status = "Scarico archivio dal gateway..."
                            scope.launch {
                                status = restoreConversationsFromHub(context, loadSettings(context), loadGatewaySecret(context))
                                refreshKey++
                            }
                        }) {
                            Text("Scarica server")
                        }
                        Button(onClick = {
                            filter = "Progetto"
                            status = "Filtro: Progetto"
                        }) {
                            Text("Progetti")
                        }
                        Button(onClick = {
                            filter = "Chat"
                            status = "Filtro: Chat"
                        }) {
                            Text("Recenti")
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsField("Cerca", query, { query = it })
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Tutto", "Chat", "Progetto", "Task", "Server").forEach { option ->
                            Button(
                                onClick = {
                                    filter = option
                                    status = "Filtro: $option"
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (filter == option) AppColors.Accent else AppColors.AssistantBubble,
                                    contentColor = Color.White
                                )
                            ) {
                                Text(option, fontSize = 12.sp)
                            }
                        }
                    }
                    Text(status, color = AppColors.Muted)
                }
            }
        }
        if (results.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Archivio vuoto.", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Le conversazioni vengono salvate qui automaticamente. Inizia una nuova chat dalla sidebar.", color = AppColors.Muted, fontSize = 13.sp)
                }
            }
        }
        items(results) { item ->
            ArchiveCard(
                item = item,
                onOpen = {
                    status = "Prompt aperto in chat: ${item.title}"
                    onOpenConversation(item.id, item.prompt)
                },
                onPin = {
                    val saved = saveProjectConversation(context, item.title, item.description, item.prompt)
                    status = "Progetto salvato localmente: ${saved.title}"
                    refreshKey++
                },
                onRename = { newTitle ->
                    if (item.id == null) {
                        status = "Apri o salva prima di rinominare."
                    } else if (renameConversation(context, item.id, newTitle)) {
                        status = "Rinominato: $newTitle"
                        refreshKey++
                    } else {
                        status = "Elemento non trovato."
                    }
                },
                onManage = { if (item.id != null) managingConversationId = item.id },
                onDelete = {
                    if (item.id == null) {
                        status = "Template non eliminabile."
                    } else {
                        pendingDelete = item
                    }
                }
            )
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            ),
            containerColor = AppColors.Surface,
            title = {
                Text("Conferma eliminazione", color = Color.White, fontWeight = FontWeight.SemiBold)
            },
            text = {
                val safeTitle = item.title.replace('\n', ' ').replace('\r', ' ').let {
                    if (it.length > 60) it.take(60).trimEnd() + "..." else it
                }
                Text(
                    "Vuoi eliminare davvero \"$safeTitle\" dall'archivio locale?",
                    color = AppColors.Muted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        if (item.id != null && deleteConversation(context, item.id)) {
                            status = "Eliminato: ${item.title}"
                            refreshKey++
                        } else {
                            status = "Elemento non trovato."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8E2E3F),
                        contentColor = Color.White
                    )
                ) {
                    Text("Elimina")
                }
            },
            dismissButton = {
                Button(
                    onClick = { pendingDelete = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.AssistantBubble,
                        contentColor = Color.White
                    )
                ) {
                    Text("Annulla")
                }
            }
        )
    }

    managingConversationId?.let { id ->
        ConversationManagerDialog(
            context = context,
            conversationId = id,
            onClose = { managingConversationId = null; refreshKey++ },
            onContinue = { branchId, prompt -> managingConversationId = null; onOpenConversation(branchId, prompt) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArchiveCard(
    item: ArchiveItem,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRename: (String) -> Unit,
    onManage: () -> Unit,
    onDelete: () -> Unit
) {
    var renameText by remember(item.id, item.title) { mutableStateOf(item.title) }

    Card(colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(item.kind, color = AppColors.Accent, fontSize = 12.sp)
                if (item.id != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Elimina elemento archivio",
                            tint = Color(0xFFFF7B8E)
                        )
                    }
                }
            }
            Text(item.description, color = AppColors.Muted)
            Text(item.prompt, color = Color.White, fontSize = 13.sp)
            if (item.id != null) {
                SettingsField("Rinomina", renameText, { renameText = it })
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onOpen) { Text("Apri") }
                Button(onClick = onPin) { Text("Segna") }
                if (item.id != null) {
                    Button(onClick = onManage) { Text("Gestisci") }
                    Button(onClick = { onRename(renameText.trim()) }) { Text("Rinomina") }
                    Button(onClick = onDelete) { Text("Elimina") }
                }
            }
        }
    }
}
@Composable
internal fun ConversationManagerDialog(
    context: Context,
    conversationId: String,
    onClose: () -> Unit,
    onContinue: (String?, String) -> Unit
) {
    var refresh by remember { mutableIntStateOf(0) }
    val conversation = remember(conversationId, refresh) { loadConversation(context, conversationId) }
    if (conversation == null) { onClose(); return }
    var folder by remember(conversationId, refresh) { mutableStateOf(conversation.folder) }
    var tags by remember(conversationId, refresh) { mutableStateOf(conversation.tags.joinToString(", ")) }
    var project by remember(conversationId, refresh) { mutableStateOf(conversation.projectId) }
    var links by remember(conversationId, refresh) { mutableStateOf(conversation.linkedConversationIds.joinToString(", ")) }
    var summary by remember(conversationId, refresh) { mutableStateOf(conversation.summary) }
    val selected = remember(conversationId) { mutableStateListOf<String>() }
    var status by remember { mutableStateOf("Modifica, ramifica o esporta la chat.") }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = AppColors.Surface,
        title = { Text("Gestisci · ${conversation.title}", color = Color.White) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsField("Cartella", folder, { folder = it })
                        SettingsField("Tag", tags, { tags = it })
                        SettingsField("Progetto", project, { project = it })
                        SettingsField("Chat collegate (ID)", links, { links = it })
                        SettingsField("Riepilogo", summary, { summary = it })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                updateLocalConversation(context, conversationId) { item -> item.copy(folder = folder.trim(), tags = splitMetadata(tags), projectId = project.trim(), linkedConversationIds = splitMetadata(links).filter { it != conversationId }, summary = summary.trim(), updatedAt = System.currentTimeMillis()) }
                                status = "Metadata salvati."; refresh++
                            }) { Text("Salva") }
                            Button(onClick = {
                                val transcript = conversation.messages.takeLast(40).joinToString("\n") { "${it.author}: ${it.text}" }
                                onContinue(conversation.id, "Riassumi questa conversazione con decisioni e attività aperte:\n\n$transcript")
                            }) { Text("Riassumi") }
                            listOf("md", "json", "html", "pdf").forEach { format -> Button(onClick = { shareConversationExport(context, conversation, format) }) { Text(format.uppercase()) } }
                        }
                        Text(status, color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
                items(conversation.messages, key = { it.id }) { message ->
                    var text by remember(message.id, refresh) { mutableStateOf(message.text) }
                    Card(colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("${message.author}${if (message.isBookmarked) " · ★" else ""}", color = Color.White, fontWeight = FontWeight.SemiBold)
                            SettingsField("Testo", text, { text = it })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = selected.contains(message.id), onCheckedChange = { checked -> if (checked) selected.add(message.id) else selected.remove(message.id) })
                                Text("Seleziona", color = AppColors.Muted)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Button(onClick = { updateLocalConversation(context, conversationId) { item -> item.copy(messages = item.messages.map { if (it.id == message.id) it.copy(text = text) else it }, updatedAt = System.currentTimeMillis()) }; refresh++ }) { Text("Modifica") }
                                Button(onClick = { updateLocalConversation(context, conversationId) { item -> item.copy(messages = item.messages.map { if (it.id == message.id) it.copy(isBookmarked = !it.isBookmarked) else it }, updatedAt = System.currentTimeMillis()) }; refresh++ }) { Text("Segnalibro") }
                                Button(onClick = { val branch = createLocalBranch(context, conversation, message.id); status = "Ramo ${branch.title} creato."; refresh++ }) { Text("Ramo") }
                                Button(onClick = { val branch = createLocalBranch(context, conversation, message.id, "${conversation.title} · alternativa"); onContinue(branch.id, "Rigenera una risposta alternativa all'ultimo messaggio.") }) { Text("Alternativa") }
                                Button(onClick = { onContinue(null, "Continua da questo messaggio:\n\n${message.text}") }) { Text("Nuova chat") }
                                Button(onClick = { saveProjectConversation(context, "Progetto da ${conversation.title}", message.text, message.text); status = "Nuovo progetto creato." }) { Text("Progetto") }
                            }
                        }
                    }
                }
                item {
                    Button(onClick = {
                        updateLocalConversation(context, conversationId) { item -> item.copy(messages = item.messages.filterNot { selected.contains(it.id) }, updatedAt = System.currentTimeMillis()) }
                        selected.clear(); status = "Porzione eliminata."; refresh++
                    }, enabled = selected.isNotEmpty()) { Text("Elimina messaggi selezionati") }
                    val branches = loadConversations(context).filter { it.parentConversationId == conversation.id || conversation.linkedConversationIds.contains(it.id) }
                    branches.forEach { branch -> Text("Ramo: ${branch.title} · ${branch.messages.size} messaggi", color = AppColors.Muted, modifier = Modifier.clickable { onContinue(branch.id, "") }.padding(6.dp)) }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("Chiudi") } }
    )
}

internal fun updateLocalConversation(context: Context, id: String, transform: (LocalConversation) -> LocalConversation): Boolean {
    val items = loadConversations(context, includeDeleted = true).toMutableList()
    val index = items.indexOfFirst { it.id == id && it.deletedAt == null }
    if (index < 0) return false
    items[index] = transform(items[index])
    saveConversations(context, items)
    return true
}

internal fun createLocalBranch(context: Context, source: LocalConversation, messageId: String, title: String = "${source.title} · ramo"): LocalConversation {
    val index = source.messages.indexOfFirst { it.id == messageId }.coerceAtLeast(0)
    val branch = source.copy(id = "branch_${java.util.UUID.randomUUID()}", title = title, messages = source.messages.take(index + 1), previousResponseId = null, serverConversationId = null, parentConversationId = source.id, branchFromMessageId = messageId, linkedConversationIds = emptyList(), updatedAt = System.currentTimeMillis())
    val items = loadConversations(context, includeDeleted = true).toMutableList()
    val sourceIndex = items.indexOfFirst { it.id == source.id }
    if (sourceIndex >= 0) items[sourceIndex] = items[sourceIndex].copy(linkedConversationIds = (items[sourceIndex].linkedConversationIds + branch.id).distinct(), updatedAt = System.currentTimeMillis())
    items.add(0, branch)
    saveConversations(context, items)
    return branch
}

internal fun splitMetadata(value: String): List<String> = value.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(50)

internal fun shareConversationExport(context: Context, conversation: LocalConversation, format: String) {
    val markdown = buildString { append("# ${conversation.title}\n\n${conversation.summary}\n\n"); conversation.messages.forEach { append("## ${it.author}\n\n${it.text}\n\n") } }
    val normalized = format.lowercase()
    val extension = when (normalized) { "json" -> "json"; "html" -> "html"; "pdf" -> "pdf"; else -> "md" }
    val mime = when (normalized) { "json" -> "application/json"; "html" -> "text/html"; "pdf" -> "application/pdf"; else -> "text/markdown" }
    val safeTitle = conversation.title.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "conversazione-hermes" }.take(80)
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(directory, "$safeTitle-${System.currentTimeMillis()}.$extension")
    runCatching {
        when (normalized) {
            "json" -> file.writeText(conversationsToJsonArray(listOf(conversation)).getJSONObject(0).toString(2), Charsets.UTF_8)
            "html" -> file.writeText("<!doctype html><html><meta charset=\"utf-8\"><title>${conversation.title.htmlEncode()}</title><h1>${conversation.title.htmlEncode()}</h1><pre>${markdown.htmlEncode()}</pre></html>", Charsets.UTF_8)
            "pdf" -> writeConversationPdf(file, conversation)
            else -> file.writeText(markdown, Charsets.UTF_8)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, conversation.title)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(conversation.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Esporta ${normalized.uppercase()}"))
    }.onFailure {
        file.delete()
        Toast.makeText(context, "Esportazione fallita: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

internal fun writeConversationPdf(file: File, conversation: LocalConversation) {
    val document = PdfDocument()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 12f }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 20f; isFakeBoldText = true }
    val pageWidth = 595
    val pageHeight = 842
    val margin = 44f
    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var y = margin

    fun startPage() {
        page?.let(document::finishPage)
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        y = margin
    }

    fun drawWrapped(text: String, sourcePaint: Paint, gapAfter: Float = 8f) {
        val available = pageWidth - margin * 2
        for (paragraph in text.replace("\r", "").split('\n')) {
            val words = paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }
            val lines = mutableListOf<String>()
            var current = ""
            for (word in words) {
                val candidate = if (current.isBlank()) word else "$current $word"
                if (sourcePaint.measureText(candidate) <= available || current.isBlank()) current = candidate
                else { lines += current; current = word }
            }
            if (current.isNotBlank()) lines += current
            if (lines.isEmpty()) lines += " "
            for (line in lines) {
                if (page == null || y > pageHeight - margin) startPage()
                page!!.canvas.drawText(line, margin, y, sourcePaint)
                y += sourcePaint.textSize * 1.35f
            }
        }
        y += gapAfter
    }

    try {
        startPage()
        drawWrapped(conversation.title, titlePaint, 16f)
        if (conversation.summary.isNotBlank()) drawWrapped(conversation.summary, paint, 16f)
        conversation.messages.forEach { message ->
            val authorPaint = Paint(paint).apply { isFakeBoldText = true }
            drawWrapped(message.author, authorPaint, 4f)
            drawWrapped(message.text, paint, 12f)
        }
        page?.let(document::finishPage)
        page = null
        file.outputStream().use { output -> document.writeTo(output) }
    } finally {
        page?.let { openPage -> runCatching { document.finishPage(openPage) } }
        document.close()
    }
}
