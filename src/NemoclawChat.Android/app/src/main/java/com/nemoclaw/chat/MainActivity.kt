package com.nemoclaw.chat

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.provider.MediaStore
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayCircle
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.nemoclaw.chat.ui.theme.ChatClawTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ChatClawTheme {
                ChatApp()
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Chat("Chat", Icons.Rounded.ChatBubbleOutline),
    Archive("Archivio", Icons.Rounded.FolderOpen),
    Tasks("Jobs", Icons.Rounded.TaskAlt),
    Server("Hermes", Icons.Rounded.Dns),
    Operator("Runs", Icons.Rounded.Terminal),
    Voice("Voce", Icons.Rounded.GraphicEq),
    Video("Video", Icons.Rounded.PlayCircle),
    News("News", Icons.AutoMirrored.Rounded.Article),
    Settings("Imposta", Icons.Rounded.Tune),
    Profile("Profilo", Icons.Rounded.AccountCircle)
}

private object HermesStreamRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

@androidx.compose.runtime.Immutable
data class ChatMessage(
    val author: String,
    val text: String,
    val fromUser: Boolean,
    val isAction: Boolean = false,
    val visualBlocksVersion: Int? = null,
    val visualBlocks: List<VisualBlock> = emptyList(),
    val stats: ChatStreamStats? = null,
    val rawEvents: List<HermesRawEvent> = emptyList(),
    val id: String = java.util.UUID.randomUUID().toString()
)

@androidx.compose.runtime.Immutable
data class HermesRawEvent(
    val name: String,
    val json: String,
    val timestamp: Long = System.currentTimeMillis()
)

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

private data class ArchiveItem(
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
    val previousResponseId: String? = null
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
    val thumbnailUrl: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val modifiedAt: Long
)

private data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val latestVersion: String?,
    val message: String,
    val releaseUrl: String,
    val assetUrl: String?,
    val releaseSummary: String = ""
)

private data class UpdateDownloadState(
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

data class AppSettings(
    val gatewayUrl: String = AppDefaults.gatewayUrl,
    val gatewayWsUrl: String = AppDefaults.gatewayWsUrl,
    val adminBridgeUrl: String = AppDefaults.adminBridgeUrl,
    val provider: String = AppDefaults.provider,
    val inferenceEndpoint: String = AppDefaults.inferenceEndpoint,
    val preferredApi: String = AppDefaults.preferredApi,
    val model: String = AppDefaults.model,
    val accessMode: String = AppDefaults.accessMode,
    val visualBlocksMode: String = AppDefaults.visualBlocksMode,
    val videoLibraryPath: String = AppDefaults.videoLibraryPath,
    val activeProjectId: String = AppDefaults.activeProjectId,
    val activeProjectName: String = AppDefaults.activeProjectName,
    val fontScale: Float = AppDefaults.fontScale,
    val strictNativeMode: Boolean = AppDefaults.strictNativeMode,
    val demoMode: Boolean = AppDefaults.demoMode
)

private data class HubMemoryState(
    val videoPreferences: String = "",
    val newsPreferences: String = "",
    val responseStyle: String = "",
    val projectRules: String = "",
    val generalNotes: String = ""
)

private data class ProjectRecord(
    val id: String,
    val name: String,
    val updatedAt: Long = System.currentTimeMillis()
)

private data class DiagnosticCheck(
    val label: String,
    val endpoint: String,
    val ok: Boolean,
    val message: String,
    val action: String
)

private data class GatewayChatResult(
    val text: String,
    val source: String,
    val statusMessage: String,
    val usedFallback: Boolean,
    val responseId: String? = null,
    val visualBlocks: List<VisualBlock> = emptyList(),
    val visualBlocksVersion: Int? = null
)

private data class ContextUsage(
    val tokens: Int,
    val maxTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    val percent: Int,
    val delegatedToHermes: Boolean = false
)

private data class GatewayTaskResult(
    val task: AgentTask,
    val message: String
)

private data class ServerSnapshot(
    val gateway: String,
    val model: String,
    val providerDetail: String,
    val inferenceEndpoint: String,
    val policy: String,
    val statusMessage: String,
    val videoLibraryPath: String
)

private data class GatewayWsProbe(
    val wsUrl: String,
    val connected: Boolean,
    val status: String,
    val detail: String,
    val capabilityLines: List<String> = emptyList()
)

private data class GatewayRpcCallResult(
    val method: String,
    val success: Boolean,
    val status: String,
    val rawJson: String,
    val summary: String
)

private data class WorkspaceRunResult(
    val result: String,
    val source: String,
    val status: String,
    val remoteId: String? = null,
    val title: String = "",
    val streamUrl: String = "",
    val downloadUrl: String = ""
)

private data class WorkspaceArtifact(
    val title: String = "",
    val result: String = "",
    val status: String = "",
    val streamUrl: String = "",
    val downloadUrl: String = ""
)

private data class OperatorPreset(
    val group: String,
    val label: String,
    val method: String,
    val params: String
)

@Composable
private fun ChatApp() {
    val context = LocalContext.current
    var selectedTabName by rememberSaveable { mutableStateOf(Tab.Chat.name) }
    val selectedTab = remember(selectedTabName) {
        runCatching { Tab.valueOf(selectedTabName) }.getOrDefault(Tab.Chat)
    }
    var tabHistory by rememberSaveable { mutableStateOf(listOf(Tab.Chat.name)) }
    val setSelectedTab: (Tab) -> Unit = { tab ->
        if (tab.name != selectedTabName) {
            tabHistory = (tabHistory + tab.name).takeLast(10)
            selectedTabName = tab.name
        }
    }
    var settings by remember { mutableStateOf(loadSettings(context)) }
    var pendingPrompt by rememberSaveable { mutableStateOf("") }
    var pendingConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }
    var savedDraft by rememberSaveable { mutableStateOf("") }
    val chatState = remember { ChatStateHolder().apply { draft = savedDraft } }
    LaunchedEffect(chatState.draft) {
        if (chatState.draft != savedDraft) {
            savedDraft = chatState.draft
        }
    }
    val chatScope = rememberCoroutineScope()
    val baseDensity = LocalDensity.current
    val rawFontScale = settings.fontScale
    val safeFontScale = if (rawFontScale.isFinite()) {
        rawFontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    } else {
        1f
    }
    val appDensity = Density(
        density = baseDensity.density,
        fontScale = safeFontScale
    )

    BackHandler(enabled = sidebarOpen || tabHistory.size > 1) {
        if (sidebarOpen) {
            sidebarOpen = false
            return@BackHandler
        }
        if (tabHistory.size > 1) {
            val popped = tabHistory.dropLast(1)
            tabHistory = popped
            selectedTabName = popped.last()
        }
    }

    CompositionLocalProvider(LocalDensity provides appDensity) {
        Scaffold(
            containerColor = AppColors.Background,
            bottomBar = {
                if (selectedTab != Tab.Voice) {
                    val bottomTabs = remember {
                        listOf(Tab.Chat, Tab.Operator, Tab.Voice, Tab.Video, Tab.Profile)
                    }
                    NavigationBar(containerColor = AppColors.Sidebar) {
                        bottomTabs.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { setSelectedTab(tab) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = Color.White,
                                    indicatorColor = AppColors.NavIndicator,
                                    unselectedIconColor = AppColors.Muted,
                                    unselectedTextColor = AppColors.Muted
                                ),
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (selectedTab == Tab.Voice) PaddingValues(0.dp) else padding)
                    .background(AppColors.Background)
            ) {
                when (selectedTab) {
                    Tab.Chat -> ChatScreen(
                        context = context,
                        settings = settings,
                        state = chatState,
                        scope = chatScope,
                        conversationId = pendingConversationId,
                        initialPrompt = pendingPrompt,
                        onInitialPromptConsumed = {
                            pendingPrompt = ""
                            pendingConversationId = null
                        },
                        onOpenSidebar = { sidebarOpen = true },
                        onSwitchTab = { tab -> setSelectedTab(tab) }
                    )
                    Tab.Archive -> ArchiveScreen(
                        context = context,
                        onOpenConversation = { id, _ ->
                            pendingConversationId = id
                            pendingPrompt = ""
                            setSelectedTab(Tab.Chat)
                        }
                    )
                    Tab.Tasks -> TasksScreen(context, settings)
                    Tab.Server -> ServerScreen(context, settings)
                    Tab.Operator -> OperatorScreen(context, settings)
                    Tab.Voice -> VoiceModeScreen()
                    Tab.Video -> VideoScreen(context, settings) { prompt ->
                        pendingPrompt = prompt
                        setSelectedTab(Tab.Chat)
                    }
                    Tab.News -> NewsScreen(context, settings) { prompt ->
                        pendingPrompt = prompt
                        setSelectedTab(Tab.Chat)
                    }
                    Tab.Settings -> SettingsScreen(
                        settings = settings,
                        onSave = { newSettings ->
                            settings = newSettings
                            chatScope.launch(Dispatchers.IO) { saveSettings(context, newSettings) }
                        },
                        onReset = {
                            val reset = AppSettings()
                            settings = reset
                            chatScope.launch(Dispatchers.IO) {
                                saveSettings(context, reset)
                                saveGatewaySecret(context, HERMES_FALLBACK_API_KEY)
                            }
                        }
                    )
                    Tab.Profile -> ProfileScreen(
                        context = context,
                        settings = settings,
                        onSettingsChanged = { updated ->
                            settings = updated
                            saveSettings(context, updated)
                        },
                        onOpenTab = { tab -> setSelectedTab(tab) }
                    )
                }
                if (sidebarOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x99000000))
                            .clickable { sidebarOpen = false }
                    ) {
                        HermesSidebar(
                            context = context,
                            onClose = { sidebarOpen = false },
                            onNewChat = {
                                chatState.resetForNewChat()
                                setSelectedTab(Tab.Chat)
                                sidebarOpen = false
                            },
                            onOpenConversation = { id ->
                                pendingConversationId = id
                                pendingPrompt = ""
                                setSelectedTab(Tab.Chat)
                                sidebarOpen = false
                            },
                            onOpenArchive = {
                                setSelectedTab(Tab.Archive)
                                sidebarOpen = false
                            },
                            onOpenJobs = {
                                setSelectedTab(Tab.Tasks)
                                sidebarOpen = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HermesSidebar(
    context: Context,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenJobs: () -> Unit
) {
    val conversations = remember { loadConversations(context).sortedByDescending { it.updatedAt } }
    Surface(
        modifier = Modifier
            .width(292.dp)
            .fillMaxSize()
            .clickable { },
        color = Color(0xFF080A0E)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.chatclaw_logo),
                        contentDescription = "Hermes Hub",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Text(
                        "Hermes Hub",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Chiudi",
                        color = AppColors.Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(onClick = onClose)
                    )
                }
            }
            item {
                SidebarRow(
                    icon = Icons.Rounded.Edit,
                    title = "Nuova chat",
                    subtitle = "Pulisci contesto corrente",
                    onClick = onNewChat
                )
            }
            item {
                SidebarRow(
                    icon = Icons.Rounded.FolderOpen,
                    title = "Archivio",
                    subtitle = "Conversazioni e progetti salvati",
                    onClick = onOpenArchive
                )
            }
            item {
                SidebarRow(
                    icon = Icons.Rounded.TaskAlt,
                    title = "Jobs",
                    subtitle = "Coda lavori Hermes",
                    onClick = onOpenJobs
                )
            }
            item {
                HorizontalDivider(color = AppColors.Border, modifier = Modifier.padding(vertical = 8.dp))
                Text("Recenti", color = AppColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (conversations.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Nessuna chat ancora.", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Tocca + per iniziarne una.", color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
            } else {
                items(conversations.take(40), key = { it.id }) { conversation ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenConversation(conversation.id) }
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(
                            conversation.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            conversation.description.ifBlank { conversation.prompt },
                            color = AppColors.Muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AppColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChatScreen(
    context: Context,
    settings: AppSettings,
    state: ChatStateHolder,
    scope: kotlinx.coroutines.CoroutineScope,
    conversationId: String? = null,
    initialPrompt: String = "",
    onInitialPromptConsumed: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
    onSwitchTab: (Tab) -> Unit = {}
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(conversationId, initialPrompt) {
        if (!conversationId.isNullOrBlank()) {
            val saved = withContext(Dispatchers.IO) { loadConversation(context, conversationId) }
            if (saved != null) {
                state.activeConversationId = saved.id
                state.previousResponseId = saved.previousResponseId
                state.messages.clear()
                state.messages.addAll(saved.messages)
            }
        }

        if (initialPrompt.isNotBlank()) {
            state.draft = initialPrompt
        }

        if (!conversationId.isNullOrBlank() || initialPrompt.isNotBlank()) {
            onInitialPromptConsumed()
        }
    }

    val haptics = LocalHapticFeedback.current
    val online by rememberOnlineState(context)
    val isStreaming = state.streamingState != null
    LaunchedEffect(isStreaming) {
        if (!isStreaming && state.messages.isNotEmpty()) {
            runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        }
    }
    val streamingTextLen = state.streamingState?.text?.length ?: 0
    LaunchedEffect(state.messages.size, streamingTextLen, state.streamingState != null) {
        val totalItems = state.messages.size + if (state.streamingState != null) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }
    val contextUsage = remember(
        state.messages.size,
        state.draft,
        state.streamingState?.stats?.promptTokens,
        state.streamingState?.stats?.tokensOut,
        state.streamingState?.stats?.contextTokens,
        state.streamingState?.stats?.contextLength,
        state.streamingState?.stats?.contextPercent,
        streamingTextLen,
        settings.preferredApi
    ) {
        estimateChatContextUsage(
            settings = settings,
            messages = state.messages.toList(),
            draft = state.draft,
            streamingState = state.streamingState
        )
    }

    val isEmptyChat = state.messages.isEmpty() && state.streamingState == null
    val emptyChatBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0x34F5A524),
                Color(0x241F1710),
                Color(0x12181510),
                AppColors.Background,
                AppColors.Background
            ),
            startY = -260f,
            endY = 1440f
        )
    }
    val solidBrush = remember {
        Brush.verticalGradient(listOf(AppColors.Background, AppColors.Background))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isEmptyChat) emptyChatBrush else solidBrush)
    ) {
        TopBar(
            contextUsage = contextUsage,
            onNewChat = { state.resetForNewChat() },
            onOpenSidebar = onOpenSidebar
        )
        Box(modifier = Modifier.weight(1f)) {
            if (isEmptyChat) {
                EmptyState(onPrompt = { state.draft = it })
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(
                    state.messages,
                    key = { message -> message.id }
                ) { message ->
                    MessageBubble(message)
                }
                state.streamingState?.let { streaming ->
                    item(key = "streaming") { StreamingBubbleView(streaming) }
                }
            }
        }
        val slashMatches = remember(state.draft) { filterSlashCommands(state.draft) }
        if (slashMatches.isNotEmpty() && !state.sending) {
            SlashCommandList(commands = slashMatches) { cmd ->
                state.draft = ""
                executeSlashCommand(
                    command = cmd,
                    setMode = { state.mode = it },
                    clear = { state.resetForNewChat() },
                    setDraft = { state.draft = it },
                    addAction = { title, body ->
                        state.messages.add(ChatMessage(title, body, fromUser = false, isAction = true))
                    },
                    onSwitchTab = onSwitchTab
                )
            }
        }
        if (!online) {
            Surface(color = Color(0xFF7A3E00), modifier = Modifier.fillMaxWidth()) {
                Text(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    text = "Offline. Hermes non raggiungibile.",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
        Composer(
            context = context,
            value = state.draft,
            onValueChange = { state.draft = it },
            onAction = { title, text, prompt ->
                state.messages.add(ChatMessage(title, text, fromUser = false, isAction = true))
                if (prompt.isNotBlank()) {
                    state.draft = appendPrompt(state.draft, prompt)
                }
            },
            onModeChange = { state.mode = it },
            onSend = {
                val text = state.draft.trim()
                if (!online) {
                    state.messages.add(ChatMessage("Stato", "Offline. Hermes non raggiungibile.", fromUser = false, isAction = true))
                    return@Composer
                }
                if (text.isNotEmpty() && !state.sending && state.activeStreamJob == null) {
                    state.sending = true
                    state.messages.add(ChatMessage("Tu", text, true))
                    state.draft = ""
                    state.streamingState = StreamingState()

                    val job = HermesStreamRuntime.scope.launch {
                        var localState = StreamingState()
                        val mode = state.mode
                        val convId = state.activeConversationId
                        val prevId = state.previousResponseId
                        var interrupted = false
                        var lastCheckpointAt = 0L
                        val rawEvents = mutableListOf<HermesRawEvent>()
                        val initialConversation = withContext(NonCancellable + Dispatchers.IO) {
                            saveConversationSnapshot(
                                context = context,
                                conversationId = convId,
                                mode = mode,
                                prompt = text,
                                messages = state.messages.toList(),
                                source = "Hermes in corso",
                                responseId = prevId
                            )
                        }
                        state.activeConversationId = initialConversation.id
                        try {
                            streamChatRequest(settings, mode, text, state.messages.takeLast(CHAT_HISTORY_MAX_MESSAGES).toList(), state.activeConversationId, prevId, loadGatewaySecret(context))
                                .collect { event ->
                                    if (event is ChatStreamEvent.RawHermesEvent) {
                                        rawEvents += HermesRawEvent(event.name, event.json)
                                    }
                                    localState = localState.applyEvent(event)
                                    state.streamingState = localState
                                    val now = System.currentTimeMillis()
                                    if (now - lastCheckpointAt >= 2000L && (localState.text.isNotBlank() || localState.visualBlocks.isNotEmpty())) {
                                        lastCheckpointAt = now
                                        withContext(Dispatchers.IO) {
                                            saveConversationSnapshot(
                                                context = context,
                                                conversationId = state.activeConversationId,
                                                mode = mode,
                                                prompt = text,
                                                messages = state.messages.toList() + ChatMessage(
                                                    "Hermes",
                                                    localState.text.ifBlank { "Hermes sta lavorando..." },
                                                    fromUser = false,
                                                    visualBlocksVersion = localState.visualBlocksVersion,
                                                    visualBlocks = localState.visualBlocks,
                                                    stats = localState.stats,
                                                    rawEvents = rawEvents.toList()
                                                ),
                                                source = "Hermes in corso",
                                                responseId = localState.responseId ?: prevId
                                            )
                                        }
                                    }
                                }
                        } catch (_: CancellationException) {
                            interrupted = true
                        } finally {
                            val finalState = localState
                            val partialText = finalState.text.trimEnd()
                            val transportDetached = finalState.error?.contains("connection abort", ignoreCase = true) == true ||
                                finalState.error?.contains("software caused connection abort", ignoreCase = true) == true
                            val finalText = when {
                                interrupted && partialText.isNotEmpty() -> "$partialText\n\n_Interrotto._"
                                interrupted -> "Generazione interrotta."
                                transportDetached && partialText.isNotEmpty() -> "$partialText\n\n_Stream scollegato: Hermes potrebbe continuare il lavoro sul gateway._"
                                transportDetached -> ""
                                else -> finalState.text.ifEmpty { finalState.error ?: "" }
                            }
                            if (finalText.isNotEmpty() || finalState.visualBlocks.isNotEmpty()) {
                                state.messages.add(
                                    ChatMessage(
                                        if (interrupted && partialText.isEmpty()) "Stato" else "Hermes",
                                        finalText,
                                        fromUser = false,
                                        isAction = interrupted && partialText.isEmpty(),
                                        visualBlocksVersion = finalState.visualBlocksVersion,
                                        visualBlocks = finalState.visualBlocks,
                                        stats = finalState.stats,
                                        rawEvents = rawEvents.toList()
                                    )
                                )
                            }
                            if (finalState.error != null && finalText.isEmpty()) {
                                state.messages.add(ChatMessage("Stato", finalState.error, fromUser = false, isAction = true))
                            }
                            val workspaceKind = if (!interrupted) detectWorkspaceIntent(text) else null
                            if (workspaceKind != null) {
                                val workspaceResult = sendWorkspaceRunRequest(settings, workspaceKind, text, loadGatewaySecret(context))
                                withContext(Dispatchers.IO) {
                                    saveWorkspaceRequest(
                                        context = context,
                                        kind = workspaceKind,
                                        prompt = text,
                                        result = workspaceResult.result.ifBlank { finalText },
                                        source = workspaceResult.source,
                                        status = workspaceResult.status,
                                        remoteId = workspaceResult.remoteId,
                                        title = workspaceResult.title.ifBlank { makeTitle(text) },
                                        streamUrl = workspaceResult.streamUrl,
                                        downloadUrl = workspaceResult.downloadUrl
                                    )
                                }
                                state.messages.add(
                                    ChatMessage(
                                        "Hermes Hub",
                                        "${workspaceKind}: aggiunto alla sezione dedicata. ${workspaceResult.status}",
                                        fromUser = false,
                                        isAction = true
                                    )
                                )
                            }
                            val saved = withContext(NonCancellable + Dispatchers.IO) {
                                saveConversationSnapshot(
                                    context = context,
                                    conversationId = state.activeConversationId,
                                    mode = mode,
                                    prompt = text,
                                    messages = state.messages.toList(),
                                    source = if (interrupted) "Hermes interrotto" else if (finalState.error != null) "Errore Hermes" else "Hermes",
                                    responseId = finalState.responseId ?: prevId
                                )
                            }
                            state.activeConversationId = saved.id
                            state.previousResponseId = saved.previousResponseId
                            state.streamingState = null
                            state.sending = false
                            state.activeStreamJob = null
                        }
                    }
                    state.activeStreamJob = job
                }
            },
            onStop = {
                state.streamingState = state.streamingState?.copy(
                    status = "Interruzione richiesta. Chiudo stream Hermes...",
                    error = null
                )
                state.activeStreamJob?.cancel()
            },
            isBusy = state.sending
        )
    }
}

private fun executeSlashCommand(
    command: SlashCommand,
    setMode: (String) -> Unit,
    clear: () -> Unit,
    setDraft: (String) -> Unit,
    addAction: (String, String) -> Unit,
    onSwitchTab: (Tab) -> Unit
) {
    when (command.action) {
        SlashAction.ModeChat -> {
            setMode("Chat"); addAction("Modalita", "Chat attiva.")
        }
        SlashAction.ModeAgent -> {
            setMode("Agente"); addAction("Modalita", "Agente attivo.")
        }
        SlashAction.Clear -> clear()
        SlashAction.Help -> {
            val lines = slashCommands().distinctBy { it.display }.joinToString("\n") { "${it.display} — ${it.title}" }
            addAction("Comandi", lines)
        }
        SlashAction.PromptSetup -> setDraft("Preparami i passaggi per avviare Hermes Agent API Server su Tailscale/LAN.")
        SlashAction.PromptVisual -> setDraft("Spiega con blocchi visuali (tabella, diagramma, chart o callout) mantenendo output_text completo.")
        SlashAction.PromptResearch -> setDraft("Esegui una ricerca approfondita citando fonti e chiedendo conferma prima di uscire dalla LAN/VPN.")
        SlashAction.PromptWeb -> setDraft("Cerca sul web informazioni aggiornate, chiedendo conferma prima di uscire dalla LAN/VPN.")
        SlashAction.PromptImage -> setDraft("Prepara una richiesta di generazione immagine, chiedendo conferma prima di usare tool esterni.")
        SlashAction.PromptVideo -> setDraft("Crea un job video per la sezione Video di Hermes Hub: ")
        SlashAction.PromptNews -> setDraft("Crea un articolo per la sezione News di Hermes Hub: ")
        SlashAction.Health -> setDraft("Controlla stato Hermes, modello disponibile e capabilities API.")
        SlashAction.OpenServer -> onSwitchTab(Tab.Server)
        SlashAction.OpenOperator -> onSwitchTab(Tab.Operator)
        SlashAction.OpenArchive -> onSwitchTab(Tab.Archive)
        SlashAction.OpenTasks -> onSwitchTab(Tab.Tasks)
        SlashAction.OpenVoice -> onSwitchTab(Tab.Voice)
        SlashAction.OpenVideo -> onSwitchTab(Tab.Video)
        SlashAction.OpenNews -> onSwitchTab(Tab.News)
        SlashAction.OpenSettings -> onSwitchTab(Tab.Settings)
        SlashAction.OpenAbout -> onSwitchTab(Tab.Profile)
    }
}

@Composable
private fun TopBar(contextUsage: ContextUsage, onNewChat: () -> Unit = {}, onOpenSidebar: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(72.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.chatclaw_logo),
            contentDescription = "Hermes Hub",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenSidebar)
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = "Hermes Hub",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clickable(onClick = onNewChat)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.Edit, contentDescription = "Nuova chat", tint = AppColors.Accent, modifier = Modifier.size(16.dp))
            Text(text = "Nuova", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(modifier = Modifier.size(8.dp))
        ContextMeter(
            usage = contextUsage,
            modifier = Modifier
                .size(50.dp)
        )
    }
}

@Composable
private fun ContextMeter(usage: ContextUsage, modifier: Modifier = Modifier) {
    val fill = (usage.percent.coerceIn(0, 100) / 100f)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 2.2.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawCircle(
                color = AppColors.Elevated,
                radius = size.minDimension / 2f,
                center = center
            )
            if (fill > 0f) {
                drawArc(
                    color = AppColors.Accent.copy(alpha = 0.88f),
                    startAngle = -90f,
                    sweepAngle = 360f * fill,
                    useCenter = true,
                    topLeft = Offset(inset, inset),
                    size = arcSize
                )
            }
            drawCircle(
                color = AppColors.Border,
                radius = size.minDimension / 2f - inset,
                center = center,
                style = Stroke(width = strokeWidth)
            )
            drawCircle(
                color = AppColors.Accent.copy(alpha = 0.62f),
                radius = size.minDimension / 2f - (strokeWidth * 1.8f),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        Text(
            text = if (usage.delegatedToHermes && usage.tokens <= 0) "H" else "${usage.percent.coerceIn(0, 100)}%",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

private fun estimateChatContextUsage(
    settings: AppSettings,
    messages: List<ChatMessage>,
    draft: String,
    streamingState: StreamingState?
): ContextUsage {
    val authoritativeStats = streamingState?.stats
        ?: messages.asReversed().firstNotNullOfOrNull { it.stats?.takeIf { stats -> stats.contextTokens() > 0 } }
    val contextWindow = authoritativeStats?.contextLength?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
    val explicitPercent = authoritativeStats?.contextPercent?.takeIf { it in 0..100 }
    val serverContextTokens = authoritativeStats?.contextTokens()
        ?: 0
    if (isHermesNative(settings) && serverContextTokens <= 0) {
        return ContextUsage(tokens = 0, percent = 0, delegatedToHermes = true)
    }
    val historyTokens = messages
        .takeLast(CHAT_HISTORY_MAX_MESSAGES)
        .sumOf { estimateTokenCount(it.author) + estimateTokenCount(it.text) + MESSAGE_CONTEXT_OVERHEAD_TOKENS }
    val draftTokens = draft.trim()
        .takeIf { it.isNotBlank() }
        ?.let { estimateTokenCount(it) + MESSAGE_CONTEXT_OVERHEAD_TOKENS }
        ?: 0
    val estimated = if (historyTokens == 0 && draftTokens == 0) {
        0
    } else {
        CONTEXT_SYSTEM_OVERHEAD_TOKENS + historyTokens + draftTokens
    }
    val tokens = if (isHermesNative(settings)) serverContextTokens else maxOf(estimated, serverContextTokens).coerceAtLeast(0)
    val percent = explicitPercent ?: ((tokens.coerceAtMost(contextWindow).toDouble() / contextWindow) * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
    return ContextUsage(tokens = tokens, maxTokens = contextWindow, percent = percent, delegatedToHermes = isHermesNative(settings))
}

private fun estimateTokenCount(text: String): Int {
    if (text.isBlank()) return 0
    return ((text.length + 3) / 4).coerceAtLeast(1)
}

@Composable
private fun EmptyState(onPrompt: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.chatclaw_logo),
            contentDescription = "Logo Hermes Hub",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(108.dp)
                .clip(RoundedCornerShape(28.dp))
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "Che vuoi fare oggi, Matteo?",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Chat normale, Runs e Jobs verso Hermes Agent sul tuo home-server.",
            color = AppColors.Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SuggestionButton("Prepara setup Hermes") {
                onPrompt("Preparami i passaggi per avviare Hermes Agent API Server su Tailscale/LAN.")
            }
            SuggestionButton("Controlla server") {
                onPrompt("Controlla stato Hermes, modello disponibile e capabilities API.")
            }
            SuggestionButton("Crea ordine agente") {
                onPrompt("Crea un task agente sicuro con richiesta approve/deny prima di ogni azione rischiosa.")
            }
        }
    }
}

private fun createInitialTasks(@Suppress("UNUSED_PARAMETER") settings: AppSettings): List<AgentTask> {
    return emptyList()
}

@Composable
private fun SuggestionButton(text: String, onClick: () -> Unit) {
        Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = AppColors.Surface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            text = text,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun PremiumPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(content = content)
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.78f), thickness = 1.dp)
    }
}

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    colors: Any? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    PremiumPanel(modifier = modifier, content = content)
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    SelectionContainer {
        if (!message.fromUser && !message.isAction) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MarkdownText(message.text, color = Color.White, fontSize = 15.sp)
                if (message.visualBlocks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        message.visualBlocks.filter { it.isValidVisualBlock() }.forEach { block ->
                            VisualBlockView(block)
                        }
                    }
                }
                RawHermesEventsView(message.rawEvents)
                ChatStatsFooter(message.stats)
            }
            return@SelectionContainer
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(if (message.fromUser) 0.86f else 0.92f),
                color = if (message.fromUser) AppColors.UserBubble else AppColors.Panel,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AppColors.Border),
                shadowElevation = 0.dp,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = message.author,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (message.fromUser || message.isAction) {
                        Text(text = message.text, color = Color.White)
                    } else {
                        MarkdownText(message.text, color = Color.White)
                    }
                    if (message.visualBlocks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            message.visualBlocks.filter { it.isValidVisualBlock() }.forEach { block ->
                                VisualBlockView(block)
                            }
                        }
                    }
                    RawHermesEventsView(message.rawEvents)
                    ChatStatsFooter(message.stats)
                }
            }
        }
    }
}

@Composable
private fun RawHermesEventsView(events: List<HermesRawEvent>) {
    if (events.isEmpty() || !SHOW_RAW_HERMES_EVENTS_IN_CHAT) return
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = AppColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Eventi Hermes raw: ${events.size}", color = AppColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (expanded) {
                events.take(40).forEach { event ->
                    Text("${event.name}: ${event.json.take(800)}", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun ChatStatsFooter(stats: ChatStreamStats?) {
    val line = remember(stats) { formatChatStatsLine(stats) }
    if (line.isNotBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = line,
            color = AppColors.Muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatChatStatsLine(stats: ChatStreamStats?): String {
    if (stats == null) return ""
    val parts = mutableListOf<String>()
    stats.ttftMs?.takeIf { it > 0 }?.let {
        parts += "TTFT ${String.format(java.util.Locale.US, "%.0f", it)}ms"
    }
    stats.tokensPerSecond?.takeIf { it > 0 }?.let {
        parts += "${String.format(java.util.Locale.US, "%.2f", it)} t/s"
    }
    stats.tokensOut?.takeIf { it > 0 }?.let { parts += "$it tok" }
    stats.promptTokens?.takeIf { it > 0 }?.let { parts += "prompt $it" }
    stats.contextTokens().takeIf { it > 0 }?.let { parts += "ctx $it" }
    stats.contextLength?.takeIf { it > 0 }?.let { parts += "max $it" }
    stats.totalMs?.takeIf { it > 0 }?.let {
        parts += "${String.format(java.util.Locale.US, "%.1f", it / 1000.0)}s"
    }
    return parts.joinToString("  ·  ")
}

@Composable
internal fun VisualBlockView(block: VisualBlock) {
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
                "unknown_block" -> CodeBlock("json", block.rawJson.ifBlank { "{}" }, "hermes-unknown-block.json")
            }
            if (block.caption.isNotBlank()) {
                Text(block.caption, color = AppColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MarkdownBlock(markdown: String) {
    MarkdownText(markdown, color = Color.White, fontSize = 14.sp)
}

@Composable
private fun CodeBlock(language: String, code: String, filename: String) {
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
private fun TableBlock(block: VisualBlock) {
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
private fun TableCell(text: String, header: Boolean) {
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
private fun ChartBlock(block: VisualBlock) {
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
private fun DiagramBlock(block: VisualBlock) {
    CodeBlock("mermaid", block.source, "diagram.mmd")
}

@Composable
private fun GalleryBlock(block: VisualBlock) {
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

@Composable
private fun MediaFileBlock(block: VisualBlock) {
    val context = LocalContext.current
    val settings = remember { loadSettings(context) }
    val allowExternalImage = block.mediaKind == "image"
    val resolvedMediaUrl = remember(settings.gatewayUrl, block.mediaUrl, allowExternalImage) { resolveMediaUrl(settings, block.mediaUrl, allowExternalImage) }
    val previewUrl = remember(settings.gatewayUrl, block.thumbnailUrl, allowExternalImage) { resolveMediaUrl(settings, block.thumbnailUrl, allowExternalImage) }
    val previewSource = when {
        block.mediaKind == "image" && resolvedMediaUrl != null -> block.mediaUrl
        previewUrl != null -> block.thumbnailUrl
        else -> ""
    }
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val canOpen = resolvedMediaUrl != null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (previewSource.isNotBlank()) {
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
                if (!canOpen) {
                    Text("media non proxy rifiutato.", color = AppColors.Muted, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = canOpen,
                        onClick = {
                            val url = resolvedMediaUrl ?: return@Button
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            if (block.mimeType.isNotBlank()) {
                                intent.setDataAndType(Uri.parse(url), block.mimeType)
                            }
                            openAndroidIntent(context, intent)
                        }
                    ) { Text("Apri") }
                    Button(
                        enabled = canOpen,
                        onClick = {
                            val url = resolvedMediaUrl ?: return@Button
                            clipboard.setPrimaryClip(ClipData.newPlainText("hermes-media-url", url))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Elevated)
                    ) { Text("Copia link") }
                }
            }
        }
    }
}

private fun formatMediaBytes(value: Long?): String {
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

private fun formatMediaDuration(value: Long?): String {
    val millis = value?.takeIf { it > 0 } ?: return ""
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun formatVideoDuration(value: Long): String {
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

private fun formatVideoTimestamp(value: Long): String {
    if (value <= 0L) return "data non disponibile"
    return java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.ITALY).format(java.util.Date(value))
}

private fun appendFeedbackSnippet(current: String, snippet: String): String {
    val base = current.trim()
    return if (base.isBlank()) snippet else "$base; $snippet"
}

private fun authHeaders(apiKey: String?): Map<String, String> {
    val token = apiKey?.trim()?.takeIf { it.isNotBlank() } ?: HERMES_FALLBACK_API_KEY
    return mapOf("Authorization" to "Bearer $token", "User-Agent" to "HermesHub-Android")
}

private fun loadVideoThumbnail(url: String, apiKey: String?): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            retriever.setDataSource(url, authHeaders(apiKey))
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

private fun Bitmap.scaleBitmapToMaxWidth(maxWidth: Int): Bitmap {
    if (width <= maxWidth || width <= 0 || height <= 0) return this
    val ratio = maxWidth.toFloat() / width.toFloat()
    val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, maxWidth, targetHeight, true)
}

@Composable
private fun RemoteGalleryImage(settings: AppSettings, image: VisualGalleryImage, allowExternalImage: Boolean = true) {
    val resolved = remember(settings.gatewayUrl, image.mediaUrl, allowExternalImage) { resolveMediaUrl(settings, image.mediaUrl, allowExternalImage) }
    if (resolved == null) {
        Text("${image.alt}: media non proxy rifiutato.", color = AppColors.Muted, fontSize = 13.sp)
        return
    }

    val bitmap by produceState<Bitmap?>(initialValue = null, resolved) {
        value = withContext(Dispatchers.IO) { loadRemoteBitmap(resolved) }
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

private fun resolveMediaUrl(settings: AppSettings, value: String, allowExternalImage: Boolean = false): String? {
    return if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
        try {
            val uri = URI(value)
            val root = URI(hermesRoot(settings))
            val path = uri.path.orEmpty()
            if (
                (uri.scheme == "http" || uri.scheme == "https") &&
                path.startsWith("/v1/media/") &&
                uri.host.equals(root.host, ignoreCase = true)
            ) {
                value
            } else if (
                allowExternalImage &&
                uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                !value.startsWith("file:", ignoreCase = true) &&
                !value.startsWith("data:", ignoreCase = true)
            ) {
                value
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

private const val REMOTE_BITMAP_MAX_BYTES = 10L * 1024 * 1024
private const val REMOTE_BITMAP_MAX_DIMENSION = 2048

private fun loadRemoteBitmap(url: String): Bitmap? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "HermesHub-Android")
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
private fun CalloutBlock(block: VisualBlock) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    context: Context,
    value: String,
    onValueChange: (String) -> Unit,
    onAction: (String, String, String) -> Unit,
    onModeChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isBusy: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    fun queueAction(title: String, detail: String, prompt: String) {
        onAction(title, detail, prompt)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 0.dp)
            .widthIn(max = 1040.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box {
            Surface(
                modifier = Modifier
                    .size(52.dp)
                    .clickable { expanded = true },
                color = AppColors.Surface,
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Rounded.Add, contentDescription = "Apri menu azioni", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = AppColors.Elevated
            ) {
                DropdownMenuItem(
                    text = { Text("Aggiungi file al task", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.AttachFile, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        val opened = openAndroidIntent(
                            context,
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                        )
                        queueAction(
                            "File task",
                            if (opened) "File picker Android aperto. Seleziona il file e descrivilo nel prompt del task." else "Nessun file picker disponibile. Descrivi percorso o contenuto del file nel prompt.",
                            "Allega un file al prossimo job e analizzalo nel contesto Hermes."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Cattura screenshot", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.CropFree, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Screenshot",
                            "Richiesta screenshot aggiunta. Usa la cattura schermo del telefono, poi allega l'immagine dal menu file.",
                            "Usa uno screenshot come contesto visivo per capire app o server."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Scatta foto", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        val opened = openAndroidIntent(context, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                        queueAction(
                            "Foto",
                            if (opened) "Fotocamera Android aperta. Scatta la foto e allegala al task quando pronta." else "Nessuna app fotocamera disponibile. Seleziona una foto esistente dal menu file.",
                            "Acquisisci una foto e usala come allegato per la conversazione."
                        )
                    }
                )
                HorizontalDivider(color = AppColors.Border)
                DropdownMenuItem(
                    text = { Text("Passa a modalita Chat", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        onModeChange("Chat")
                        onAction("Modalita", "Chat attiva: messaggi normali, nessun task agente automatico.", "")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Passa a modalita Agente", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.SmartToy, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        onModeChange("Agente")
                        onAction("Modalita", "Agente attivo: usa Hermes Runs/Jobs se disponibili, altrimenti fallback locale.", "")
                    }
                )
                HorizontalDivider(color = AppColors.Border)
                DropdownMenuItem(
                    text = { Text("Crea immagine", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.Image, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Immagine",
                            "Generazione immagine richiedera' tool Hermes dedicato e conferma prima di chiamate esterne.",
                            "Prepara una richiesta di generazione immagine, ma chiedi conferma prima di usare tool esterni."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Deep Research locale", color = Color.White) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ManageSearch, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Deep Research",
                            "Ricerca approfondita locale; rete solo dopo approvazione esplicita.",
                            "Esegui una ricerca approfondita e cita fonti, usando rete solo dopo approvazione."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Ricerca web autorizzata", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.Language, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Web",
                            "Ricerca web marcata come azione autorizzabile: nessuna rete fuori LAN/VPN senza conferma.",
                            "Cerca sul web informazioni aggiornate, chiedendo conferma prima di uscire dalla LAN/VPN."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Spiegazione visiva", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.Image, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Visuale",
                            "Spiegazione visiva richiesta: Hermes usera' blocchi statici sicuri se disponibili.",
                            "Spiega anche con blocchi visuali se utile: tabella, diagramma, chart o callout. Mantieni output_text completo."
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Progetti e workspace", color = Color.White) },
                    leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, tint = Color.White) },
                    onClick = {
                        expanded = false
                        queueAction(
                            "Workspace",
                            "Workspace/progetti saranno collegati ai Jobs Hermes con audit trail.",
                            "Lavora sul workspace o progetto selezionato e mostra piano prima di modificare file."
                        )
                    }
                )
            }
        }

        val fontScale = LocalDensity.current.fontScale.coerceIn(0.5f, 2.0f)
        Surface(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = (54 * fontScale).dp, max = (156 * fontScale).dp),
            color = AppColors.Composer,
            shape = RoundedCornerShape(28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = (38 * fontScale).dp, max = (138 * fontScale).dp)
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        minLines = 1,
                        maxLines = 5,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 18.sp,
                            lineHeight = 25.sp
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
                        Text("Fai una domanda", color = AppColors.Muted, fontSize = 18.sp)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                val canSend = value.isNotBlank() && !isBusy
                val canPress = isBusy || canSend
                Surface(
                    modifier = Modifier
                        .size(42.dp)
                        .clickable(enabled = canPress) {
                            if (isBusy) onStop() else onSend()
                        },
                    color = if (canPress) Color.White else AppColors.Surface,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isBusy) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                            contentDescription = if (isBusy) "Interrompi generazione" else "Invia",
                            tint = if (canPress) Color.Black else AppColors.Muted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveScreen(
    context: Context,
    onOpenConversation: (String?, String) -> Unit
) {
    var refreshKey by remember { mutableStateOf(0) }
    val archive = remember(refreshKey) { loadArchiveItems(context) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Tutto") }
    var status by remember { mutableStateOf("Pronto.") }
    var pendingDelete by remember { mutableStateOf<ArchiveItem?>(null) }
    val savedConversations = remember(refreshKey) { loadConversations(context) }
    val savedProjects = savedConversations.count { it.kind == "Progetto" }
    val savedChats = savedConversations.count { it.kind == "Chat" || it.kind == "Task" }
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
            properties = androidx.compose.ui.window.DialogProperties(
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArchiveCard(
    item: ArchiveItem,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRename: (String) -> Unit,
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
                    Button(onClick = { onRename(renameText.trim()) }) { Text("Rinomina") }
                    Button(onClick = onDelete) { Text("Elimina") }
                }
            }
        }
    }
}

@Composable
private fun TasksScreen(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val tasks = remember { mutableStateListOf<AgentTask>() }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadTasks(context) }
        tasks.clear()
        tasks.addAll(loaded)
    }
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var requiresApproval by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Pronto.") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Jobs", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Coda Jobs persistente. Usa Hermes Jobs API quando disponibile, altrimenti fallback locale.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Nuovo job", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Titolo", title, { title = it })
                    SettingsField("Istruzioni", detail, { detail = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Richiedi conferma", color = Color.White, modifier = Modifier.weight(1f))
                        Switch(checked = requiresApproval, onCheckedChange = { requiresApproval = it })
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = {
                            if (title.isBlank()) {
                                status = "Titolo obbligatorio."
                                return@Button
                            }
                            val localTask = AgentTask(
                                id = "task_${System.currentTimeMillis()}",
                                title = title.trim(),
                                mode = if (settings.demoMode) "Locale" else "Job",
                                status = if (requiresApproval) "In attesa approvazione" else "Pronto",
                                detail = detail.ifBlank { "Esegui con piano prima di azioni rischiose." }.trim(),
                                requiresApproval = requiresApproval
                            )
                            tasks.add(0, localTask)
                            saveTasks(context, tasks)
                            title = ""
                            detail = ""
                            requiresApproval = true
                            status = "Invio job a Hermes..."

                            scope.launch {
                                val result = queueTaskRequest(settings, localTask, loadGatewaySecret(context))
                                replaceTask(tasks, result.task)
                                saveTasks(context, tasks)
                                status = result.message
                            }
                        }) {
                            Text("Accoda")
                        }
                        Button(onClick = {
                            title = "Analizza workspace"
                            detail = "Ispeziona il progetto con Hermes Agent, individua rischi e proponi un piano operativo."
                            requiresApproval = true
                            status = "Template workspace caricato."
                        }) {
                            Text("Template")
                        }
                        Button(onClick = {
                            title = "Controlla home-server Hermes"
                            detail = "Verifica API Hermes, modello, health e capabilities."
                            requiresApproval = true
                            status = "Template server caricato."
                        }) {
                            Text("Server")
                        }
                        Button(onClick = {
                            status = "Sincronizzo Jobs Hermes..."
                            scope.launch {
                                val result = syncRemoteTasks(settings, loadGatewaySecret(context))
                                tasks.clear()
                                tasks.addAll(result.first)
                                saveTasks(context, tasks)
                                status = result.second
                            }
                        }) {
                            Text("Sincronizza")
                        }
                    }
                    Text(status, color = AppColors.Muted)
                }
            }
        }
        if (tasks.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nessun job in coda.", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Crea un task qui sopra o invialo da chat con /agente. Verra' salvato nella coda Hermes.", color = AppColors.Muted, fontSize = 13.sp)
                }
            }
        }
        items(tasks) { task ->
            TaskCard(
                task = task,
                onApprove = {
                    scope.launch {
                        val result = updateTaskRequest(settings, task, "run", loadGatewaySecret(context))
                        replaceTask(tasks, result.task)
                        saveTasks(context, tasks)
                        status = result.message
                    }
                },
                onDeny = {
                    scope.launch {
                        val result = updateTaskRequest(settings, task, "pause", loadGatewaySecret(context))
                        replaceTask(tasks, result.task)
                        saveTasks(context, tasks)
                        status = result.message
                    }
                },
                onDone = {
                    scope.launch {
                        val result = updateTaskRequest(settings, task, "delete", loadGatewaySecret(context))
                        replaceTask(tasks, result.task)
                        saveTasks(context, tasks)
                        status = result.message
                    }
                }
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: AgentTask,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onDone: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(task.status, color = AppColors.Accent, fontSize = 12.sp)
            }
            Text("Modalita: ${task.mode} | Origine: ${task.source} | Conferma: ${if (task.requiresApproval) "si" else "no"}", color = AppColors.Muted, fontSize = 12.sp)
            Text(task.detail, color = Color.White)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onApprove) { Text("Run") }
                Button(onClick = onDeny) { Text("Pausa") }
                Button(onClick = onDone) { Text("Elimina") }
            }
        }
    }
}

@Composable
private fun ServerScreen(context: Context, settings: AppSettings) {
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

    LaunchedEffect(settings) {
        snapshot = loadServerSnapshot(context, settings, loadGatewaySecret(context))
        diagnostics = runDiagnostics(settings, loadGatewaySecret(context))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Hermes Server", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Dashboard Hermes Agent API: health, detailed health, models e capabilities.", color = AppColors.Muted)
        }
        item {
            ServerMetric("Hermes API", snapshot.gateway, "Health endpoint: ${hermesRoot(settings)}/health")
        }
        item {
            ServerMetric("Capabilities", wsProbe.wsUrl, wsProbe.status)
        }
        item {
            ServerMetric("Modello", snapshot.model, snapshot.providerDetail)
        }
        item {
            ServerMetric("API lato server", snapshot.inferenceEndpoint, "Il client parla a Hermes API, non direttamente al runtime modello.")
        }
        item {
            ServerMetric("Sicurezza", snapshot.policy, "Client usa API key Bearer salvata; default hermes-hub.")
        }
        item {
            ServerMetric("Cartella video Hermes", snapshot.videoLibraryPath.ifBlank { "In attesa di sync server" }, "Hermes decide path e app lo recepisce da /health/detailed.")
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Azioni", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(snapshot.statusMessage, color = AppColors.Muted)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = {
                            val error = validateHttpUrl(settings.gatewayUrl, "Hermes API URL")
                            if (error != null) {
                                snapshot = snapshot.copy(statusMessage = error)
                                return@Button
                            }
                            val healthUrl = "${hermesRoot(settings)}/health"
                            snapshot = snapshot.copy(statusMessage = "Test: $healthUrl")
                            scope.launch {
                                snapshot = snapshot.copy(statusMessage = testGateway(healthUrl, loadGatewaySecret(context)))
                            }
                        }) {
                            Text("Test Hermes")
                        }
                        Button(onClick = {
                            scope.launch {
                                snapshot = loadServerSnapshot(context, settings, loadGatewaySecret(context))
                                diagnostics = runDiagnostics(settings, loadGatewaySecret(context))
                            }
                        }) {
                            Text("Aggiorna stato")
                        }
                        Button(onClick = {
                            snapshot = snapshot.copy(statusMessage = "Contratto Hermes: GET /health, GET /health/detailed, GET /v1/models, GET /v1/capabilities, POST /v1/responses, POST /v1/chat/completions, POST /v1/runs, GET/POST /api/jobs.")
                        }) {
                            Text("Mostra API")
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Diagnostica gateway", color = Color.White, fontWeight = FontWeight.SemiBold)
                    if (diagnostics.isEmpty()) {
                        Text("Nessuna diagnostica eseguita.", color = AppColors.Muted)
                    } else {
                        diagnostics.forEach { check ->
                            Surface(color = AppColors.Panel, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, if (check.ok) AppColors.Accent.copy(alpha = 0.55f) else Color(0xFF8A3A3A))) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${if (check.ok) "OK" else "Errore"} - ${check.label}", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text(check.endpoint, color = AppColors.Faint, fontSize = 11.sp)
                                    Text(check.message, color = AppColors.Muted, fontSize = 12.sp)
                                    if (!check.ok) Text("Azione: ${check.action}", color = AppColors.Accent, fontSize = 12.sp)
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
                    Text("Capabilities Hermes", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(wsProbe.detail, color = AppColors.Muted)
                    Button(onClick = {
                        wsProbe = wsProbe.copy(status = "Lettura capabilities...", detail = "Chiamata a /v1/capabilities e /v1/models.")
                        scope.launch {
                            val capabilities = runCatching { httpGet("${settings.gatewayUrl.trimEnd('/')}/capabilities", loadGatewaySecret(context)) }.getOrElse { it.message ?: it.javaClass.simpleName }
                            val models = runCatching { httpGet("${settings.gatewayUrl.trimEnd('/')}/models", loadGatewaySecret(context)) }.getOrElse { it.message ?: it.javaClass.simpleName }
                            wsProbe = GatewayWsProbe(
                                wsUrl = "${settings.gatewayUrl.trimEnd('/')}/capabilities",
                                connected = true,
                                status = "Capabilities lette.",
                                detail = "Capabilities e models letti da Hermes.",
                                capabilityLines = listOf("Capabilities: ${capabilities.limitText(240)}", "Models: ${models.limitText(240)}")
                            )
                        }
                    }) {
                        Text("Leggi capabilities")
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
    }
}

@Composable
private fun ServerMetric(title: String, value: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(value, color = Color.White)
            Text(detail, color = AppColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OperatorScreen(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf("GET /health") }
    var params by remember { mutableStateOf("") }
    var approvalId by remember { mutableStateOf("") }
    var baseHash by remember { mutableStateOf("") }
    var configPatch by remember { mutableStateOf("{\"ops\":[]}") }
    var workspacePath by remember { mutableStateOf("") }
    var workspaceText by remember { mutableStateOf("") }
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
            Text("Runs", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Operazioni reali verso Hermes Agent API: health, models, capabilities, runs e jobs.", color = AppColors.Muted)
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
                                }
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
                    Text("Jobs", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Job ID", approvalId, { approvalId = it })
                    OperatorActionButton("Lista") { runOperatorRpc(context, settings, "GET /api/jobs", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Run") { runOperatorRpc(context, settings, "POST /api/jobs/${approvalId.jsonEscaped()}/run", "{}", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Pausa") { runOperatorRpc(context, settings, "POST /api/jobs/${approvalId.jsonEscaped()}/pause", "{}", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Elimina") { runOperatorRpc(context, settings, "DELETE /api/jobs/${approvalId.jsonEscaped()}", "", { status = it }, { summary = it }, { raw = it }) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Runs manuali", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Run ID", baseHash, { baseHash = it })
                    SettingsField("Input run", configPatch, { configPatch = it })
                    OperatorActionButton("Capabilities") { runOperatorRpc(context, settings, "GET /v1/capabilities", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Crea run") { runOperatorRpc(context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${configPatch.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Models") { runOperatorRpc(context, settings, "GET /v1/models", "", { status = it }, { summary = it }, { raw = it }) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Diagnostica", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Filtro job", workspacePath, { workspacePath = it })
                    SettingsField("Input run rapido", workspaceText, { workspaceText = it })
                    OperatorActionButton("Jobs") { runOperatorRpc(context, settings, "GET /api/jobs", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Health") { runOperatorRpc(context, settings, "GET /health/detailed", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Run") { runOperatorRpc(context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${workspaceText.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it }) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Endpoint manuale", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Metodo + path", method, { method = it })
                    SettingsField("Body JSON", params, { params = it })
                    Button(onClick = {
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
                        Text("Esegui")
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
private fun OperatorActionButton(label: String, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Text(label)
    }
}

@Composable
private fun VideoScreen(context: Context, settings: AppSettings, onOpenChatPrompt: (String) -> Unit) {
    var refreshKey by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Sincronizzo cartella video Hermes...") }
    var items by remember { mutableStateOf<List<VideoLibraryItem>>(emptyList()) }
    var selectedVideoId by rememberSaveable { mutableStateOf<String?>(null) }
    var videoFilter by rememberSaveable { mutableStateOf("Tutti") }
    val selectedVideo = remember(items, selectedVideoId) { items.firstOrNull { it.id == selectedVideoId } }
    val displayedItems = remember(items, videoFilter) {
        when (videoFilter) {
            "Recenti" -> items.sortedByDescending { it.modifiedAt }
            "Feedback" -> items.filter { loadVideoFeedback(context, it.id).isNotBlank() || loadVideoReaction(context, it.id).isNotBlank() }
            else -> items
        }
    }

    LaunchedEffect(settings.gatewayUrl, settings.videoLibraryPath, refreshKey) {
        val result = loadVideoLibrary(settings, loadGatewaySecret(context))
        items = result.first
        status = result.second
        if (selectedVideoId != null && result.first.none { it.id == selectedVideoId }) {
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
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Hermes Video", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = {
                    onOpenChatPrompt(
                        "Modalita Video Hermes Hub. Usa la cartella video monitorata del PC Hermes: ${settings.videoLibraryPath}. " +
                            "Crea, scarica o prepara un video e salva sempre il file finale in quella cartella, cosi appare automaticamente nella sezione Video. Richiesta: "
                    )
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Nuovo video", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoFeedChip("Tutti", selected = videoFilter == "Tutti") { videoFilter = "Tutti" }
                VideoFeedChip("Recenti", selected = videoFilter == "Recenti") { videoFilter = "Recenti" }
                VideoFeedChip("Feedback", selected = videoFilter == "Feedback") { videoFilter = "Feedback" }
                VideoFeedChip("Aggiorna") {
                    status = "Aggiorno feed video..."
                    refreshKey++
                }
            }
        }
        item {
            Text(status, color = AppColors.Faint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (displayedItems.isEmpty()) {
            item {
                Surface(color = AppColors.Panel, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, AppColors.Border)) {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = if (items.isEmpty()) {
                            "Nessun video trovato. Metti un file .mp4/.mov/.mkv/.webm/.avi nella cartella video Hermes sul PC e premi Aggiorna feed."
                        } else {
                            "Nessun video con feedback salvato."
                        },
                        color = AppColors.Muted
                    )
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
private fun VideoFeedChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (selected) Color.White else AppColors.Panel,
        contentColor = if (selected) Color.Black else Color.White,
        shape = RoundedCornerShape(8.dp),
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
private fun VideoFeedCard(settings: AppSettings, item: VideoLibraryItem, apiKey: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        VideoThumbnail(settings, item, apiKey, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = AppColors.Accent.copy(alpha = 0.18f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayCircle, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Hermes Hub - ${item.sizeBytes.toReadableFileSize()} - ${formatVideoTimestamp(item.modifiedAt)}",
                    color = AppColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VideoThumbnail(settings: AppSettings, item: VideoLibraryItem, apiKey: String?, modifier: Modifier = Modifier) {
    val videoUrl = remember(settings.gatewayUrl, item.mediaUrl) { resolveWorkspaceUrl(settings, item.mediaUrl) }
    val thumbUrl = remember(settings.gatewayUrl, item.thumbnailUrl) {
        item.thumbnailUrl.takeIf { it.isNotBlank() }?.let { resolveWorkspaceUrl(settings, it) }
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, videoUrl, thumbUrl, apiKey) {
        value = withContext(Dispatchers.IO) {
            thumbUrl?.let { loadRemoteBitmap(it) } ?: loadVideoThumbnail(videoUrl, apiKey)
        }
    }
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(4.dp))
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

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun VideoWatchScreen(context: Context, settings: AppSettings, item: VideoLibraryItem, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val apiKey = remember { loadGatewaySecret(context) }
    val videoUrl = remember(settings.gatewayUrl, item.mediaUrl) { resolveWorkspaceUrl(settings, item.mediaUrl) }
    val player = remember(videoUrl, apiKey) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(authHeaders(apiKey))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    var feedback by remember(item.id) { mutableStateOf(loadVideoFeedback(context, item.id)) }
    var reaction by remember(item.id) { mutableStateOf(loadVideoReaction(context, item.id)) }
    var status by remember(item.id) { mutableStateOf("Lascia feedback: Hermes lo usera' come memoria editoriale per i prossimi video.") }
    var fullScreen by rememberSaveable(item.id) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onBack) { Text("Indietro") }
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
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                useController = true
                                this.player = player
                            }
                        },
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
                    onClick = { fullScreen = true }
                ) {
                    Icon(Icons.Rounded.CropFree, contentDescription = "Schermo intero", tint = Color.White)
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Hermes Hub - ${item.filename} - ${item.sizeBytes.toReadableFileSize()} - ${formatVideoTimestamp(item.modifiedAt)}",
                    color = AppColors.Muted,
                    fontSize = 13.sp
                )
                Text(status, color = AppColors.Faint, fontSize = 12.sp)
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
                SettingsField("Feedback per Hermes", feedback, { feedback = it })
                Button(onClick = {
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
                }) { Text("Invia feedback") }
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
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = true
                            this.player = player
                        }
                    },
                    update = { view ->
                        view.player = player
                    }
                )
                Button(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    onClick = { fullScreen = false }
                ) {
                    Text("Chiudi")
                }
            }
        }
    }
}

@Composable
private fun VideoReactionButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) Color.White else AppColors.Panel,
        contentColor = if (selected) Color.Black else Color.White,
        border = if (selected) null else BorderStroke(1.dp, AppColors.Border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NewsScreen(context: Context, settings: AppSettings, onOpenChatPrompt: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Giornale Hermes pronto.") }
    var selectedArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    var newsFilter by rememberSaveable { mutableStateOf("Tutti") }
    val articles = remember(refreshKey) { loadWorkspaceRequests(context, "News") }
    val selectedArticle = remember(articles, selectedArticleId) { articles.firstOrNull { it.id == selectedArticleId } }
    val displayedArticles = remember(articles, newsFilter) {
        when (newsFilter) {
            "Recenti" -> articles.sortedByDescending { it.updatedAt }
            "Feedback" -> articles.filter { it.feedback.isNotBlank() }
            else -> articles
        }
    }

    if (selectedArticle != null) {
        BackHandler { selectedArticleId = null }
        NewsArticleScreen(
            context = context,
            settings = settings,
            article = selectedArticle,
            onBack = { selectedArticleId = null },
            onChanged = {
                refreshKey++
                status = it
            }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Hermes News", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { onOpenChatPrompt("Crea un articolo per la sezione News di Hermes Hub: ") }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Nuovo articolo", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoFeedChip("Tutti", selected = newsFilter == "Tutti") { newsFilter = "Tutti" }
                VideoFeedChip("Recenti", selected = newsFilter == "Recenti") { newsFilter = "Recenti" }
                VideoFeedChip("Feedback", selected = newsFilter == "Feedback") { newsFilter = "Feedback" }
                VideoFeedChip("Sincronizza") {
                    status = "Sincronizzo articoli Hermes..."
                    scope.launch {
                        status = syncWorkspaceJobs(context, settings, "News", loadGatewaySecret(context))
                        refreshKey++
                    }
                }
            }
        }
        item {
            Text(status, color = AppColors.Faint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (displayedArticles.isEmpty()) {
            item {
                Surface(color = AppColors.Panel, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, AppColors.Border)) {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = if (articles.isEmpty()) {
                            "Nessun articolo ancora. Tocca + per chiedere a Hermes di crearne uno o sincronizza i Jobs."
                        } else {
                            "Nessun articolo con feedback salvato."
                        },
                        color = AppColors.Muted
                    )
                }
            }
        }
        items(displayedArticles, key = { it.id }) { article ->
            NewsArticleCard(article = article, onClick = { selectedArticleId = article.id })
        }
    }
}

@Composable
private fun NewsArticleCard(article: WorkspaceRequest, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = AppColors.Panel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = AppColors.Accent.copy(alpha = 0.18f)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        article.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
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
            }
            val preview = article.result.ifBlank { article.prompt }.limitText(420)
            MarkdownText(preview, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            if (article.feedback.isNotBlank()) {
                Text("Feedback salvato", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NewsArticleScreen(
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onBack) { Text("Indietro") }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(article.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${article.source} - ${article.status} - ${formatVideoTimestamp(article.updatedAt)}",
                    color = AppColors.Muted,
                    fontSize = 13.sp
                )
                Text(status, color = AppColors.Faint, fontSize = 12.sp)
                HorizontalDivider(color = AppColors.Border)
                MarkdownText(article.result.ifBlank { article.prompt }, color = Color.White, fontSize = 16.sp)
                HorizontalDivider(color = AppColors.Border)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoFeedChip("Piu' fonti") { feedback = appendFeedbackSnippet(feedback, "Aggiungi piu' fonti verificabili") }
                    VideoFeedChip("Piu' breve") { feedback = appendFeedbackSnippet(feedback, "Sintesi piu' breve e densa") }
                    VideoFeedChip("Piu' profondo") { feedback = appendFeedbackSnippet(feedback, "Analisi piu' profonda e meno superficiale") }
                    VideoFeedChip("Tono") { feedback = appendFeedbackSnippet(feedback, "Tono piu' chiaro, diretto e operativo") }
                }
                SettingsField("Feedback per Hermes", feedback, { feedback = it })
                Button(onClick = {
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
                }) { Text("Invia feedback") }
            }
        }
    }
}

@Composable
private fun WorkspaceFeedScreen(
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
    var refreshKey by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Feed sincronizzato localmente. I nuovi spunti arrivano dalla chat e dai Jobs Hermes.") }
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
                Button(onClick = { onOpenChatPrompt(chatPrompt) }) { Text("Nuovo spunto in chat") }
                Button(onClick = {
                    status = "Sincronizzo lista Jobs Hermes..."
                    scope.launch {
                        status = syncWorkspaceJobs(context, settings, kind, loadGatewaySecret(context))
                        refreshKey++
                    }
                }) { Text("Sincronizza Hermes") }
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
private fun WorkspaceFeedItem(
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
                    Button(onClick = { openAndroidIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(resolveWorkspaceUrl(settings, item.streamUrl)))) }) { Text("Streaming") }
                }
                if (item.downloadUrl.isNotBlank()) {
                    Button(onClick = { openAndroidIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(resolveWorkspaceUrl(settings, item.downloadUrl)))) }) { Text("Scarica") }
                }
                if (item.remoteId != null) {
                    Button(onClick = {
                        scope.launch { onChanged(runWorkspaceJobAction(settings, item, "run", loadGatewaySecret(context))) }
                    }) { Text("Aggiorna") }
                }
            }
            SettingsField("Feedback per Hermes", feedback, { feedback = it })
            Button(onClick = {
                if (feedback.isBlank()) {
                    onChanged("Scrivi un feedback prima di inviarlo.")
                    return@Button
                }
                scope.launch {
                    val result = sendWorkspaceFeedback(settings, item, feedback, loadGatewaySecret(context))
                    saveWorkspaceFeedback(context, item.id, feedback, result)
                    feedback = ""
                    onChanged(result)
                }
            }) { Text("Invia feedback") }
            if (item.feedback.isNotBlank()) {
                Text("Ultimo feedback: ${item.feedback}", color = AppColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

private fun runOperatorRpc(
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
    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
        val result = hermesHttpCall(settings, loadGatewaySecret(context), method, params)
        setStatus(result.status)
        setSummary(result.summary)
        setRaw(result.rawJson.ifBlank { result.summary })
    }
}

@Composable
private fun ProfileScreen(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onOpenTab: (Tab) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val conversations = remember { loadConversations(context) }
    val version = remember { appVersion(context) }
    var updateState by remember { mutableStateOf(UpdateDownloadState()) }
    var memory by remember { mutableStateOf(HubMemoryState()) }
    var memoryStatus by remember { mutableStateOf("Memoria gateway non ancora letta.") }
    var projects by remember { mutableStateOf(loadProjects(context)) }
    var projectName by remember { mutableStateOf("") }
    var activeProjectId by remember(settings.activeProjectId) { mutableStateOf(settings.activeProjectId) }

    LaunchedEffect(settings.gatewayUrl) {
        val loaded = loadHubMemory(settings, loadGatewaySecret(context))
        memory = loaded.first
        memoryStatus = loaded.second
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Profilo", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Profilo locale. Nessun account cloud, nessun token provider nel client.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(24.dp)) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.chatclaw_logo),
                        contentDescription = "Logo Hermes Hub",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                    Column(modifier = Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Matteo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Home-server Hermes Agent locale", color = AppColors.Muted)
                        Text("App: $version", color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
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
            ServerMetric("Parita Windows", "Allineata", "Chat, archivio, progetti/recenti, jobs, Hermes server, runs, settings e profilo presenti anche su Android.")
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Aree rapide", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Schermate secondarie spostate qui per lasciare la barra bassa pulita.", color = AppColors.Muted, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenTab(Tab.Server) }) { Text("Hermes") }
                        Button(onClick = { onOpenTab(Tab.News) }) { Text("News") }
                        Button(onClick = { onOpenTab(Tab.Settings) }) { Text("Impostazioni") }
                        Button(onClick = { onOpenTab(Tab.Archive) }) { Text("Archivio") }
                        Button(onClick = { onOpenTab(Tab.Tasks) }) { Text("Jobs") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Progetto attivo", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(if (activeProjectId.isBlank()) "Nessun progetto selezionato." else "Attivo: ${projects.firstOrNull { it.id == activeProjectId }?.name ?: settings.activeProjectName}", color = AppColors.Muted)
                    SettingsField("Nuovo progetto", projectName, { projectName = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val name = projectName.trim()
                            if (name.isBlank()) return@Button
                            val project = ProjectRecord("project_${System.currentTimeMillis()}", name)
                            projects = (listOf(project) + projects).distinctBy { it.id }.take(50)
                            saveProjects(context, projects)
                            activeProjectId = project.id
                            projectName = ""
                            val updated = settings.copy(activeProjectId = project.id, activeProjectName = project.name)
                            onSettingsChanged(updated)
                            scope.launch { postHubState(settings, "project_active", project.id, JSONObject().put("name", project.name), loadGatewaySecret(context)) }
                        }) { Text("Crea e attiva") }
                        Button(onClick = {
                            activeProjectId = ""
                            val updated = settings.copy(activeProjectId = "", activeProjectName = "")
                            onSettingsChanged(updated)
                            scope.launch { postHubState(settings, "project_active", "none", JSONObject().put("name", ""), loadGatewaySecret(context)) }
                        }) { Text("Nessuno") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        projects.forEach { project ->
                            VideoFeedChip(project.name, selected = project.id == activeProjectId) {
                                activeProjectId = project.id
                                val updated = settings.copy(activeProjectId = project.id, activeProjectName = project.name)
                                onSettingsChanged(updated)
                                scope.launch { postHubState(settings, "project_active", project.id, JSONObject().put("name", project.name), loadGatewaySecret(context)) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Memoria Hermes", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(memoryStatus, color = AppColors.Muted, fontSize = 12.sp)
                    SettingsField("Preferenze video", memory.videoPreferences, { memory = memory.copy(videoPreferences = it) })
                    SettingsField("Preferenze news", memory.newsPreferences, { memory = memory.copy(newsPreferences = it) })
                    SettingsField("Stile risposta", memory.responseStyle, { memory = memory.copy(responseStyle = it) })
                    SettingsField("Regole progetto", memory.projectRules, { memory = memory.copy(projectRules = it) })
                    SettingsField("Note generali", memory.generalNotes, { memory = memory.copy(generalNotes = it) })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            memoryStatus = "Salvo memoria gateway..."
                            scope.launch {
                                memoryStatus = saveHubMemory(settings, memory, loadGatewaySecret(context))
                            }
                        }) { Text("Salva memoria") }
                        Button(onClick = {
                            memory = HubMemoryState()
                            memoryStatus = "Contenuti locali svuotati. Premi Salva memoria per cancellare sul gateway."
                        }) { Text("Svuota") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Aggiornamenti", color = Color.White, fontWeight = FontWeight.SemiBold)
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
                            trackColor = Color(0xFF424242)
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
                        Button(onClick = {
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
                        }) {
                            Text("Controlla")
                        }
                        if (updateState.hasUpdate && updateState.releaseAssetUrl != null && updateState.downloadedApkPath == null && !updateState.isDownloading) {
                            Button(onClick = {
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
                            }) {
                                Text("Scarica")
                            }
                        }
                        if (updateState.downloadedApkPath != null && !updateState.isDownloading) {
                            Button(onClick = {
                                val apkPath = updateState.downloadedApkPath ?: return@Button
                                val status = installDownloadedApk(context, apkPath)
                                updateState = updateState.copy(status = status)
                            }) {
                                Text("Aggiorna")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
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
    var accessMode by remember(settings.accessMode) { mutableStateOf(settings.accessMode) }
    var visualBlocksMode by remember(settings.visualBlocksMode) { mutableStateOf(settings.visualBlocksMode) }
    var videoLibraryPath by remember(settings.videoLibraryPath) { mutableStateOf(settings.videoLibraryPath) }
    var apiKey by remember { mutableStateOf(loadGatewaySecret(context) ?: HERMES_FALLBACK_API_KEY) }
    var fontScale by remember(settings.fontScale) { mutableStateOf(settings.fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)) }
    var strictNativeMode by remember(settings.strictNativeMode) { mutableStateOf(settings.strictNativeMode) }
    var demoMode by remember(settings.demoMode) { mutableStateOf(settings.demoMode) }
    var status by remember { mutableStateOf("Pronto.") }
    var advancedVisible by rememberSaveable { mutableStateOf(false) }

    fun currentSettings(scale: Float = fontScale): AppSettings {
        return AppSettings(
            gatewayUrl = gatewayUrl.trim(),
            gatewayWsUrl = "",
            adminBridgeUrl = hermesRoot(AppSettings(gatewayUrl = gatewayUrl.trim())),
            provider = provider.trim(),
            inferenceEndpoint = inferenceEndpoint.trim(),
            preferredApi = preferredApi.trim(),
            model = model.trim(),
            accessMode = accessMode.trim(),
            visualBlocksMode = visualBlocksMode.trim(),
            videoLibraryPath = videoLibraryPath.trim(),
            activeProjectId = settings.activeProjectId,
            activeProjectName = settings.activeProjectName,
            fontScale = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
            strictNativeMode = strictNativeMode,
            demoMode = demoMode
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
        Text("Impostazioni salvate sul dispositivo. Hermes Hub invia API key Hermes come Bearer token; default hermes-hub.", color = AppColors.Muted)
        Spacer(modifier = Modifier.height(18.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FontScaleControl(
                    value = fontScale,
                    onValueChange = { scale ->
                        fontScale = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                        onSave(currentSettings(fontScale))
                        status = "Dimensione caratteri: ${(fontScale * 100).toInt()}%."
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
                                saveGatewaySecret(context, apiKey)
                                onSave(candidate)
                                status = "Impostazioni salvate. Hermes usa API key Bearer salvata."
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
                            apiKey = HERMES_FALLBACK_API_KEY
                            saveGatewaySecret(context, HERMES_FALLBACK_API_KEY)
                            status = "API key ripristinata a hermes-hub."
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
                            saveGatewaySecret(context, HERMES_FALLBACK_API_KEY)
                            apiKey = HERMES_FALLBACK_API_KEY
                            onReset()
                        }) {
                            Text("Reset")
                        }
                        Button(onClick = {
                            status = runCatching { exportLocalBackup(context, apiKey) }
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
private fun FontScaleControl(
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
private fun SettingsField(label: String, value: String, onValueChange: (String) -> Unit) {
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
private fun SettingsPasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
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

private fun appendPrompt(current: String, addition: String): String {
    val trimmed = current.trim()
    return if (trimmed.isEmpty()) addition else "$trimmed\n$addition"
}

private fun validateSettings(settings: AppSettings): String? {
    return validateHttpUrl(settings.gatewayUrl, "Hermes API URL")
        ?: validateRequired(settings.provider, "Provider")
        ?: validateHttpUrl(settings.inferenceEndpoint, "Endpoint API")
        ?: validatePreferredApi(settings.preferredApi)
        ?: validateRequired(settings.model, "Modello")
        ?: validateRequired(settings.accessMode, "Accesso")
        ?: validateVisualBlocksMode(settings.visualBlocksMode)
}

private fun validateVisualBlocksMode(value: String): String? {
    return if (value == "auto" || value == "always" || value == "never") null else "Modalita visuale deve essere auto, always o never."
}

private fun validatePreferredApi(value: String): String? {
    return if (value == "hermes-native" || value == "openai-completions" || value == "openai-responses") null
    else "API preferita deve essere hermes-native, openai-completions o openai-responses."
}

private fun validateWsUrl(value: String, label: String): String? {
    if (value.isBlank()) return "$label obbligatorio."

    return try {
        val uri = URI(value)
        val scheme = uri.scheme.orEmpty().lowercase()
        if ((scheme == "ws" || scheme == "wss") && uri.host != null) null else "$label deve essere URL ws/wss valido."
    } catch (_: Exception) {
        "$label deve essere URL ws/wss valido."
    }
}

private fun validateHttpUrl(value: String, label: String): String? {
    if (value.isBlank()) return "$label obbligatorio."

    return try {
        val uri = URI(value)
        val scheme = uri.scheme.orEmpty().lowercase()
        if ((scheme == "http" || scheme == "https") && uri.host != null) null else "$label deve essere URL http/https valido."
    } catch (_: Exception) {
        "$label deve essere URL http/https valido."
    }
}

private fun validateRequired(value: String, label: String): String? {
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
        Memoria: app, CLI, jobs, Video e News devono contribuire alla stessa memoria agente/profilo Matteo quando l'informazione e' stabile o utile in futuro. Se esiste un tool di memoria, usalo. Se non esiste, conserva la preferenza nel riepilogo operativo e nel job/artifact server.
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
        - News: feed personale di articoli/briefing con fonti e feedback utente.
        - Jobs/Runs: coda operativa Hermes e lavori programmati.
        - Archivio: storico locale dell'app, non memoria agente principale.
        Video Library: non ignorare la sezione Video. Ogni output video finale destinato all'utente deve finire in video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni file .mp4/.m4v/.mov/.mkv/.webm/.avi in quella cartella appare tramite /v1/video/library. Se lo mostri in chat, usa anche visual_blocks media_file con media_url proxy /v1/media/...
        File multimediali in chat: usa visual_blocks image_gallery per piu' immagini o media_file per singoli asset image/video/audio/document.
        media_url e thumbnail_url devono puntare a proxy Hermes/same-host tipo /v1/media/...; vietati file://, data: e path locali diretti.
        Non scrivere mai markdown `MEDIA:[path](file://...)` o path Windows/Linux nel testo finale. Se un tool produce un file locale, pubblicalo prima tramite proxy Hermes e restituisci solo `/v1/media/...` dentro visual_blocks. Se non puoi pubblicarlo, dillo esplicitamente invece di inviare path locali.
        Durante lavori agente lunghi, inoltra eventi realtime per reasoning, tool call, argomenti tool, risultati tool e chiamate modello intermedie quando il gateway li supporta: Hermes Hub deve mostrare all'utente cosa stai facendo.
    """.trimIndent()
}

internal fun hermesNativeInstructions(mode: String): String {
    val role = if (mode.equals("Agente", ignoreCase = true)) "agent" else "chat"
    return """
        Hermes Hub client surface: android-app.
        Protocol mode: hermes-native/$role.
        Use Hermes Agent server-side memory, planner, tools, jobs, artifacts and policy as source of truth.
        Client history is UI snapshot only; recover conversation context from Hermes conversation/response ids when available.
        Emit realtime Hermes events for planner, memory, retrieval, tool, artifact and model-call state when supported.
        Return user-facing answer plus structured artifacts/media through Hermes-declared capabilities.
    """.trimIndent()
}

private suspend fun sendChatRequest(
    settings: AppSettings,
    mode: String,
    prompt: String,
    history: List<ChatMessage>,
    conversationId: String?,
    previousResponseId: String?,
    apiKey: String?
): GatewayChatResult = withContext(Dispatchers.IO) {
    var lastError = "errore sconosciuto"

    if (shouldUseResponsesFirst(settings, mode) && supportsResponsesApi(settings, apiKey)) {
        try {
            val payload = JSONObject()
                .put("model", settings.model)
                .put("input", prompt)
                .put(
                    "instructions",
                    if (isHermesNative(settings)) {
                        hermesNativeInstructions(mode)
                    } else if (mode.equals("Agente", ignoreCase = true)) {
                        hermesHubAgentInstructions()
                    } else {
                        hermesHubChatInstructions()
                    }
                )
                .put("store", true)
                .put("conversation", conversationId ?: JSONObject.NULL)
                .put("previous_response_id", previousResponseId ?: JSONObject.NULL)
                .put("metadata", visualBlocksMetadata(settings))
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
            .put("metadata", visualBlocksMetadata(settings))
            .put("messages", JSONArray().apply {
                put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", if (isHermesNative(settings)) hermesNativeInstructions(mode) else if (mode.equals("Agente", ignoreCase = true)) hermesHubAgentInstructions() else hermesHubChatInstructions())
                )
                val compatHistory = if (isHermesNative(settings)) emptyList() else history
                compatHistory.filter { !it.isAction }.forEach { message ->
                    put(
                        JSONObject()
                            .put("role", if (message.fromUser) "user" else "assistant")
                            .put("content", message.text)
                    )
                }
            })
        val response = postJson("${settings.gatewayUrl.trimEnd('/')}/chat/completions", payload, apiKey, allowCompatAuth = !(isHermesNative(settings) && settings.strictNativeMode))
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

private fun visualBlocksMetadata(settings: AppSettings): JSONObject {
    return JSONObject()
        .put("client", "hermes-hub")
        .put("client_surface", "android-app")
        .put("requested_protocol", settings.preferredApi)
        .put("strict_native_mode", settings.strictNativeMode)
        .put("profile", "Matteo")
        .put("project_id", settings.activeProjectId)
        .put("project_name", settings.activeProjectName)
        .put("workspace", settings.activeProjectName.ifBlank { "default" })
        .put(
            "memory_policy",
            JSONObject()
                .put("scope", "shared-hermes-agent-memory")
                .put("share_with_cli", true)
                .put("use_server_memory_tools", true)
                .put("do_not_create_app_only_memory", true)
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
                .put("video", "Feed personale video: Hermes conosce video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni video creato/scaricato per Matteo deve essere salvato o registrato li; Android legge /v1/video/library, desktop mostra file locali, app salva feedback e metadata.")
                .put("news", "Feed personale articoli: Hermes produce articoli con fonti, app salva feedback.")
                .put("jobs", "Coda Hermes Jobs condivisa con CLI/server.")
                .put("runs", "Runs operative Hermes.")
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
        )
}

private suspend fun queueTaskRequest(settings: AppSettings, task: AgentTask, apiKey: String?): GatewayTaskResult = withContext(Dispatchers.IO) {
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

private suspend fun updateTaskRequest(settings: AppSettings, task: AgentTask, action: String, apiKey: String?): GatewayTaskResult = withContext(Dispatchers.IO) {
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

private suspend fun loadServerSnapshot(context: Context, settings: AppSettings, apiKey: String?): ServerSnapshot = withContext(Dispatchers.IO) {
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

private suspend fun testGateway(healthUrl: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        var last: Pair<Int, String>? = null
        for (token in hermesAuthCandidates(apiKey)) {
            val response = executeHttpGet(healthUrl, token)
            last = response
            if (!shouldRetryHermesWithBearerAuth(response.first, response.second)) {
                return@withContext if (response.first in 200..299) {
                    "Hermes raggiungibile."
                } else {
                    "Hermes risponde: HTTP ${response.first}"
                }
            }
        }
        val code = last?.first ?: 0
        if (code in 200..299) {
            "Hermes raggiungibile."
        } else {
            "Hermes risponde: HTTP $code"
        }
    } catch (ex: Exception) {
        "Hermes non raggiungibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

private suspend fun hermesHttpCall(
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
        val body = if (verb == "GET") {
            httpGet(url, apiKey)
        } else {
            val payload = if (rawPayload.isBlank()) JSONObject() else JSONObject(rawPayload)
            val response = postJson(url, payload, apiKey, verb)
            if (response.first in 200..299) response.second else "HTTP ${response.first}: ${extractHumanError(response.second)}"
        }

        GatewayRpcCallResult(
            method = "$verb $path",
            success = true,
            status = "Hermes risposta ricevuta.",
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

private suspend fun sendWorkspaceRunRequest(
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

private fun workspaceInstructions(settings: AppSettings, kind: String, prompt: String): String {
    return if (kind.equals("Video", ignoreCase = true)) {
        """
            Destinazione: Hermes Hub / Video.
            Cartella video monitorata: ${settings.videoLibraryPath}
            Memoria: usa la memoria agente condivisa Hermes/CLI/app per preferenze utente, stile, durata, ritmo, fonti e regole editoriali. Se impari una preferenza stabile, salvala lato Hermes se possibile.
            Obiettivo: crea o programma un video personale per Matteo. File finale pensato per comparire automaticamente nella cartella video monitorata; il telefono riceve solo metadati, stream_url e download_url opzionale.
            Produzione: ricerca tema se necessario, crea script, storyboard, asset plan, eventuale Remotion project/render o pipeline IA se disponibile lato server.
            Feedback: usa feedback precedenti per adattare durata, ritmo, editing, tono, fonti, voce, musica e livello tecnico.
            Output JSON richiesto: {"kind":"Video","title":"...","summary":"...","status":"...","job_id":"...","stream_url":"...","download_url":"...","sources":[]}

            Richiesta utente:
            $prompt
        """.trimIndent()
    } else {
        """
            Destinazione: Hermes Hub / News.
            Memoria: usa la memoria agente condivisa Hermes/CLI/app per interessi, fonti preferite, profondita, tono e filtri di qualita. Se impari una preferenza stabile, salvala lato Hermes se possibile.
            Obiettivo: crea un articolo/briefing personale per Matteo con fonti verificabili e sintesi ragionata.
            Produzione: cerca notizie rilevanti, filtra per interesse, cita fonti, separa fatti da inferenze e prepara testo leggibile come giornale personale.
            Feedback: usa feedback precedenti per adattare argomenti, profondita, tono, fonti e frequenza.
            Output JSON richiesto: {"kind":"News","title":"...","summary":"...","status":"...","job_id":"...","sources":[{"title":"...","url":"..."}]}

            Richiesta utente:
            $prompt
        """.trimIndent()
    }
}

private fun workspaceOutputContract(kind: String): JSONObject {
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

private fun parseWorkspaceArtifact(kind: String, body: String): WorkspaceArtifact {
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

private fun findFirstJSONObject(body: String): JSONObject? {
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

private fun detectWorkspaceIntent(prompt: String): String? {
    val text = prompt.lowercase()
    val asksProduction = listOf("crea", "genera", "fammi", "prepara", "programma", "cron", "job", "ogni mattina", "ogni giorno")
        .any { text.contains(it) }
    if (!asksProduction) return null
    if (listOf("video", "remotion", "youtube", "montaggio", "storyboard").any { text.contains(it) }) return "Video"
    if (listOf("news", "notizie", "articolo", "giornale", "fonti", "briefing").any { text.contains(it) }) return "News"
    return null
}

private fun resolveWorkspaceUrl(settings: AppSettings, url: String): String {
    if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
    return "${hermesRoot(settings)}${if (url.startsWith('/')) url else "/$url"}"
}

private fun resolveHermesUrl(settings: AppSettings, path: String): String {
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

private suspend fun httpGet(url: String, apiKey: String? = null): String = withContext(Dispatchers.IO) {
    var last: Pair<Int, String>? = null
    for (token in hermesAuthCandidates(apiKey)) {
        val response = executeHttpGet(url, token)
        last = response
        if (!shouldRetryHermesWithBearerAuth(response.first, response.second)) {
            return@withContext response.second
        }
    }
    last?.second.orEmpty()
}

private suspend fun loadVideoLibrary(settings: AppSettings, apiKey: String?): Pair<List<VideoLibraryItem>, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val body = httpGet(resolveHermesUrl(settings, "/v1/video/library"), apiKey)
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

private val apiHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
}

private suspend fun postJson(
    url: String,
    payload: JSONObject,
    apiKey: String? = null,
    method: String = "POST",
    allowCompatAuth: Boolean = true
): Pair<Int, String> = withContext(Dispatchers.IO) {
    var last: Pair<Int, String>? = null
    for (token in hermesAuthCandidates(apiKey, allowCompatAuth)) {
        val response = executeJsonRequest(url, payload, method, token)
        last = response
        if (!shouldRetryHermesWithBearerAuth(response.first, response.second)) {
            return@withContext response
        }
    }
    last ?: (0 to "")
}

private fun executeHttpGet(url: String, bearerToken: String?): Pair<Int, String> {
    val builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", "HermesHub-Android")
    bearerToken?.let { builder.header("Authorization", "Bearer $it") }
    val request = builder.get().build()

    return apiHttpClient.newCall(request).execute().use { response ->
        response.code to response.body.string()
    }
}

private fun executeJsonRequest(url: String, payload: JSONObject, method: String, bearerToken: String?): Pair<Int, String> {
    val builder = Request.Builder()
        .url(url)
        .header("Accept", "text/event-stream, application/json, text/plain")
        .header("User-Agent", "HermesHub-Android")
    bearerToken?.let { builder.header("Authorization", "Bearer $it") }
    val normalizedMethod = method.uppercase()
    val request = when (normalizedMethod) {
        "DELETE" -> builder.delete().build()
        "PATCH" -> builder.patch(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        else -> builder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }

    return apiHttpClient.newCall(request).execute().use { response ->
        response.code to response.body.string()
    }
}

private suspend fun runDiagnostics(settings: AppSettings, apiKey: String?): List<DiagnosticCheck> = withContext(Dispatchers.IO) {
    val checks = listOf(
        "Tailscale/API" to "/health",
        "Health dettagliata" to "/health/detailed",
        "Modelli" to "/v1/models",
        "Capabilities" to "/v1/capabilities",
        "Media proxy" to "/v1/media/not-a-real-media-id",
        "Video library" to "/v1/video/library",
        "Memoria" to "/v1/hub/memory",
        "Hub state" to "/v1/hub/state"
    )
    checks.map { (label, path) ->
        val endpoint = resolveHermesUrl(settings, path)
        try {
            val response = executeHttpGet(endpoint, hermesAuthCandidates(apiKey).firstOrNull())
            val ok = when (path) {
                "/v1/media/not-a-real-media-id" -> response.first == 404 || response.second.contains("media_not_found")
                else -> response.first in 200..299
            }
            DiagnosticCheck(
                label = label,
                endpoint = endpoint,
                ok = ok,
                message = if (ok) response.second.limitText(180) else "HTTP ${response.first}: ${extractHumanError(response.second)}",
                action = when (label) {
                    "Tailscale/API" -> "Avvia Tailscale e hermes-hub, verifica IP/porta 8642."
                    "Memoria" -> "Aggiorna Hermes Gateway alla build 0.6.42 o riavvia hermes-hub."
                    "Hub state" -> "Aggiorna Hermes Gateway alla build 0.6.42 o riavvia hermes-hub."
                    "Video library" -> "Imposta HERMES_VIDEO_LIBRARY_PATH e riavvia gateway."
                    else -> "Controlla API key, gateway URL e log del terminale hermes-hub."
                }
            )
        } catch (ex: Exception) {
            DiagnosticCheck(label, endpoint, false, ex.message ?: ex.javaClass.simpleName, "Controlla rete, Tailscale, API key e processo hermes-hub.")
        }
    }
}

private suspend fun loadHubMemory(settings: AppSettings, apiKey: String?): Pair<HubMemoryState, String> = withContext(Dispatchers.IO) {
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

private suspend fun saveHubMemory(settings: AppSettings, memory: HubMemoryState, apiKey: String?): String = withContext(Dispatchers.IO) {
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

private suspend fun postHubState(settings: AppSettings, kind: String, entityId: String, payload: JSONObject, apiKey: String?): String = withContext(Dispatchers.IO) {
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

private suspend fun syncRemoteTasks(settings: AppSettings, apiKey: String?): Pair<List<AgentTask>, String> = withContext(Dispatchers.IO) {
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

private suspend fun supportsResponsesApi(settings: AppSettings, apiKey: String?): Boolean = withContext(Dispatchers.IO) {
    try {
        val body = httpGet("${settings.gatewayUrl.trimEnd('/')}/capabilities", apiKey)
        body.isBlank() || body.contains("responses", ignoreCase = true)
    } catch (_: Exception) {
        true
    }
}

private fun hermesRoot(settings: AppSettings): String {
    val api = settings.gatewayUrl.trimEnd('/')
    return if (api.endsWith("/v1", ignoreCase = true)) api.removeSuffix("/v1") else api
}

private val OPERATOR_PRESETS = listOf(
    OperatorPreset("Dashboard", "Health", "GET /health", ""),
    OperatorPreset("Dashboard", "Health detailed", "GET /health/detailed", ""),
    OperatorPreset("Dashboard", "Capabilities", "GET /v1/capabilities", ""),
    OperatorPreset("Modelli", "Lista modelli", "GET /v1/models", ""),
    OperatorPreset("Runs", "Crea run", "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"Controlla stato operativo e riassumi.\"}"),
    OperatorPreset("Jobs", "Lista jobs", "GET /api/jobs", ""),
    OperatorPreset("Jobs", "Crea job", "POST /api/jobs", "{\"title\":\"Controllo operativo\",\"instructions\":\"Controlla stato Hermes e segnala problemi.\"}")
)

private fun String.limitText(maxLength: Int): String {
    return if (length <= maxLength) this else take(maxLength) + "..."
}

private fun String.jsonEscaped(): String {
    return replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
}

private const val EXTRACT_ASSISTANT_TEXT_MAX_DEPTH = 10

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

private const val EXTRACT_JSON_TEXT_MAX_DEPTH = 10

private fun extractJsonText(value: Any?, depth: Int = 0): String {
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

private fun toUnknownVisualBlock(obj: JSONObject): VisualBlock {
    val rawType = obj.optString("type", "unknown")
    return VisualBlock(
        id = obj.optString("id").ifBlank { "unknown-${System.nanoTime()}" },
        type = "unknown_block",
        title = "Blocco Hermes non renderizzato: $rawType",
        caption = "Payload conservato per compatibilita' forward.",
        rawJson = obj.toString()
    )
}

private fun findJsonArray(value: Any?, key: String): JSONArray? {
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

private fun readVisualBlock(obj: JSONObject): VisualBlock {
    return VisualBlock(
        id = obj.optString("id"),
        type = obj.optString("type"),
        title = obj.optString("title"),
        caption = obj.optString("caption"),
        text = obj.optString("text"),
        language = obj.optString("language", "plaintext"),
        filename = obj.optString("filename"),
        code = obj.optString("code"),
        highlightLines = readIntArray(obj.optJSONArray("highlight_lines")),
        columns = readVisualColumns(obj.optJSONArray("columns") ?: JSONArray()),
        rows = readVisualRows(obj.optJSONArray("rows") ?: JSONArray()),
        chartType = obj.optString("chart_type"),
        xLabel = obj.optString("x_label"),
        yLabel = obj.optString("y_label"),
        unit = obj.optString("unit"),
        summary = obj.optString("summary"),
        series = readVisualSeries(obj.optJSONArray("series") ?: JSONArray()),
        sourceFormat = obj.optString("source_format"),
        source = obj.optString("source"),
        renderedMediaUrl = obj.optString("rendered_media_url"),
        mediaUrl = obj.optString("media_url"),
        mediaKind = obj.optString("media_kind"),
        mimeType = obj.optString("mime_type"),
        sizeBytes = obj.optLongOrNull("size_bytes"),
        durationMs = obj.optLongOrNull("duration_ms"),
        thumbnailUrl = obj.optString("thumbnail_url"),
        alt = obj.optString("alt"),
        layout = obj.optString("layout"),
        images = readVisualImages(obj.optJSONArray("images") ?: JSONArray()),
        variant = obj.optString("variant"),
        rawJson = obj.optString("raw_json", obj.toString())
    )
}

private fun readVisualColumns(array: JSONArray): List<VisualTableColumn> = buildList {
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

private fun readVisualRows(array: JSONArray): List<Map<String, String>> = buildList {
    for (i in 0 until minOf(array.length(), 100)) {
        val obj = array.optJSONObject(i) ?: continue
        add(obj.keys().asSequence().associateWith { key -> obj.opt(key)?.toString().orEmpty() })
    }
}

private fun readVisualSeries(array: JSONArray): List<VisualChartSeries> = buildList {
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

private fun readVisualImages(array: JSONArray): List<VisualGalleryImage> = buildList {
    for (i in 0 until minOf(array.length(), 12)) {
        val obj = array.optJSONObject(i) ?: continue
        add(VisualGalleryImage(obj.optString("media_url"), obj.optString("alt"), obj.optString("caption")))
    }
}

private fun readIntArray(array: JSONArray?): List<Int> = buildList {
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
        "media_file" -> mediaKind in setOf("image", "video", "audio", "document") && mediaUrl.isNotBlank() && alt.isNotBlank()
        "callout" -> variant in setOf("info", "warning", "error", "success") && text.isNotBlank()
        "unknown_block" -> rawJson.isNotBlank()
        else -> false
    }
}

private fun isSafeMediaUrl(value: String): Boolean {
    if (value.isBlank()) return false
    if (value.startsWith("/v1/media/", ignoreCase = true)) return true
    return try {
        val uri = URI(value)
        (uri.scheme == "http" || uri.scheme == "https") && uri.path.startsWith("/v1/media/")
    } catch (_: Exception) {
        false
    }
}

private fun shouldAttachVisualBlocks(settings: AppSettings, prompt: String): Boolean {
    if (settings.visualBlocksMode == "never") return false
    if (settings.visualBlocksMode == "always") return true
    return prompt.contains("visual", ignoreCase = true) ||
        prompt.contains("immagine", ignoreCase = true) ||
        prompt.contains("immagini", ignoreCase = true) ||
        prompt.contains("image", ignoreCase = true) ||
        prompt.contains("foto", ignoreCase = true) ||
        prompt.contains("diagram", ignoreCase = true) ||
        prompt.contains("grafico", ignoreCase = true) ||
        prompt.contains("tabella", ignoreCase = true) ||
        prompt.contains("spiegazione visiva", ignoreCase = true)
}

private fun visualBlockFixtures(): List<VisualBlock> {
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
        )
    )
}

private fun extractTaskId(body: String): String? {
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

private fun extractTaskStatus(body: String): String? {
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

private fun extractHumanError(body: String): String {
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

private fun JSONObject.extractString(key: String): String? {
    val value = opt(key)
    return when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject, is JSONArray -> extractJsonText(value).takeIf { it.isNotBlank() }
        else -> null
    }
}

private fun JSONObject.extractNestedString(parentKey: String, childKey: String): String? {
    return optJSONObject(parentKey)?.extractString(childKey)
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return try { getLong(key) } catch (_: Exception) { null }
}

private fun buildFallbackReply(settings: AppSettings, mode: String, reason: String): String {
    val prefix = if (mode == "Agente") {
        "Hermes assente: preparo un job locale e tengo il contesto pronto."
    } else {
        "Hermes assente: uso la risposta locale di emergenza senza perdere la conversazione."
    }

    return "$prefix Preset: API ${settings.gatewayUrl}, modello ${settings.model}, protocollo ${settings.preferredApi}. Motivo: $reason."
}

private fun fallbackTaskResult(settings: AppSettings, task: AgentTask, message: String): GatewayTaskResult {
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

private fun replaceTask(tasks: MutableList<AgentTask>, updatedTask: AgentTask) {
    val index = tasks.indexOfFirst { it.id == updatedTask.id }
    if (index >= 0) {
        tasks[index] = updatedTask
    } else {
        tasks.add(0, updatedTask)
    }
}

private suspend fun checkGithubUpdate(localVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
    try {
        val connection = (URL(AppDefaults.latestReleaseApi).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "HermesHub-Android")
        }

        connection.use {
            val code = it.responseCode
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

            val body = it.inputStream.bufferedReader().use { reader -> reader.readText() }
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

private fun summarizeReleaseNotes(body: String): String {
    return body.trim().limitText(4000)
}

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}

private fun findReleaseAsset(assets: JSONArray, suffix: String): String? {
    for (i in 0 until assets.length()) {
        val asset = assets.getJSONObject(i)
        val name = asset.optString("name")
        if (name.endsWith(suffix, ignoreCase = true)) {
            return asset.optString("browser_download_url")
        }
    }

    return null
}

private fun compareVersions(latest: String, local: String): Int {
    val latestParts = parseVersionParts(latest)
    val localParts = parseVersionParts(local)
    for (i in 0 until maxOf(latestParts.size, localParts.size)) {
        val left = latestParts.getOrElse(i) { 0 }
        val right = localParts.getOrElse(i) { 0 }
        if (left != right) return left.compareTo(right)
    }

    return 0
}

private fun parseVersionParts(value: String): List<Int> {
    return normalizeVersion(value)
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
}

private fun normalizeVersion(value: String): String {
    return value.trim().trimStart('v', 'V')
}

private fun downloadProgressLabel(progress: Float?, label: String): String {
    val safe = (progress ?: 0f).coerceIn(0f, 1f)
    val percent = "${(safe * 100).toInt()}%"
    return if (label.isBlank()) percent else "$percent  $label"
}

private suspend fun downloadUpdateApk(
    context: Context,
    assetUrl: String,
    version: String,
    onProgress: (Float, String, String) -> Unit
): File? = withContext(Dispatchers.IO) {
    val targetDirectory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "exports").apply { mkdirs() }
    val targetFile = File(targetDirectory, "HermesHub-${normalizeVersion(version)}.apk")
    val client = OkHttpClient.Builder().build()
    val request = Request.Builder()
        .url(assetUrl)
        .header("Accept", "application/octet-stream")
        .header("User-Agent", "HermesHub-Android")
        .build()

    try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext null
            }

            val body = response.body
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            var lastPercent = -1

            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
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
                }
            }
        }

        withContext(Dispatchers.Main) {
            onProgress(1f, "Download completato. APK pronto per l'installazione.", targetFile.length().toReadableFileSize())
        }
        targetFile
    } catch (_: Exception) {
        if (targetFile.exists()) {
            targetFile.delete()
        }
        null
    }
}

private fun installDownloadedApk(context: Context, apkPath: String): String {
    val apkFile = File(apkPath)
    if (!apkFile.exists()) {
        return "APK non trovato. Riscarica l'aggiornamento."
    }

    if (!context.packageManager.canRequestPackageInstalls()) {
        openAndroidIntent(
            context,
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
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

private fun findDownloadedUpdateApk(context: Context, version: String): File? {
    val normalizedVersion = normalizeVersion(version)
    val targetDirectory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "exports")
    val targetFile = File(targetDirectory, "HermesHub-$normalizedVersion.apk")
    return targetFile.takeIf { it.exists() }
}

private fun Long.toReadableFileSize(): String {
    if (this <= 0L) {
        return "0 B"
    }

    val units = listOf("B", "KB", "MB", "GB")
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

private fun loadSettings(context: Context): AppSettings {
    val prefs = migratePrefs(context, CURRENT_SETTINGS_PREFS, LEGACY_SETTINGS_PREFS)
    return AppSettings(
        gatewayUrl = prefs.getString("gatewayUrl", AppDefaults.gatewayUrl) ?: AppDefaults.gatewayUrl,
        gatewayWsUrl = prefs.getString("gatewayWsUrl", AppDefaults.gatewayWsUrl) ?: AppDefaults.gatewayWsUrl,
        adminBridgeUrl = prefs.getString("adminBridgeUrl", AppDefaults.adminBridgeUrl) ?: AppDefaults.adminBridgeUrl,
        provider = prefs.getString("provider", AppDefaults.provider) ?: AppDefaults.provider,
        inferenceEndpoint = prefs.getString("inferenceEndpoint", AppDefaults.inferenceEndpoint) ?: AppDefaults.inferenceEndpoint,
        preferredApi = prefs.getString("preferredApi", AppDefaults.preferredApi) ?: AppDefaults.preferredApi,
        model = prefs.getString("model", AppDefaults.model) ?: AppDefaults.model,
        accessMode = prefs.getString("accessMode", AppDefaults.accessMode) ?: AppDefaults.accessMode,
        visualBlocksMode = prefs.getString("visualBlocksMode", AppDefaults.visualBlocksMode) ?: AppDefaults.visualBlocksMode,
        videoLibraryPath = prefs.getString("videoLibraryPath", AppDefaults.videoLibraryPath) ?: AppDefaults.videoLibraryPath,
        activeProjectId = prefs.getString("activeProjectId", AppDefaults.activeProjectId) ?: AppDefaults.activeProjectId,
        activeProjectName = prefs.getString("activeProjectName", AppDefaults.activeProjectName) ?: AppDefaults.activeProjectName,
        fontScale = prefs.getFloat("fontScale", AppDefaults.fontScale).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
        strictNativeMode = prefs.getBoolean("strictNativeMode", AppDefaults.strictNativeMode),
        demoMode = prefs.getBoolean("demoMode", AppDefaults.demoMode)
    )
}

private fun normalizeUrl(value: String): String = value.trim().trimEnd('/')

private fun saveSettings(context: Context, settings: AppSettings) {
    context.getSharedPreferences(CURRENT_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("gatewayUrl", normalizeUrl(settings.gatewayUrl))
        .putString("gatewayWsUrl", normalizeUrl(settings.gatewayWsUrl))
        .putString("adminBridgeUrl", normalizeUrl(settings.adminBridgeUrl))
        .putString("provider", settings.provider.trim())
        .putString("inferenceEndpoint", normalizeUrl(settings.inferenceEndpoint))
        .putString("preferredApi", settings.preferredApi.trim())
        .putString("model", settings.model.trim())
        .putString("accessMode", settings.accessMode.trim())
        .putString("visualBlocksMode", settings.visualBlocksMode.trim())
        .putString("videoLibraryPath", settings.videoLibraryPath.trim())
        .putString("activeProjectId", settings.activeProjectId.trim())
        .putString("activeProjectName", settings.activeProjectName.trim())
        .putFloat("fontScale", settings.fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE))
        .putBoolean("strictNativeMode", settings.strictNativeMode)
        .putBoolean("demoMode", settings.demoMode)
        .apply()
}

private fun loadProjects(context: Context): List<ProjectRecord> {
    val raw = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE).getString("projects", "[]") ?: "[]"
    return try {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) {
                    add(ProjectRecord(id, name, obj.optLong("updatedAt", System.currentTimeMillis())))
                }
            }
        }.sortedByDescending { it.updatedAt }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveProjects(context: Context, projects: List<ProjectRecord>) {
    val array = JSONArray()
    projects.take(50).forEach { project ->
        array.put(JSONObject().put("id", project.id).put("name", project.name).put("updatedAt", project.updatedAt))
    }
    context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("projects", array.toString())
        .apply()
}

private fun loadGatewaySecret(context: Context): String? {
    val stored = context.getSharedPreferences(CURRENT_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(GATEWAY_SECRET_PREF_KEY, null)
        ?: return HERMES_FALLBACK_API_KEY

    return runCatching {
        val parts = stored.split(':', limit = 2)
        if (parts.size != 2) {
            return@runCatching HERMES_FALLBACK_API_KEY
        }

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(GATEWAY_SECRET_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateGatewaySecretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(GATEWAY_SECRET_AAD.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(encrypted), Charsets.UTF_8).trim().ifBlank { HERMES_FALLBACK_API_KEY }
    }.getOrDefault(HERMES_FALLBACK_API_KEY)
}

private fun saveGatewaySecret(context: Context, secret: String?) {
    val normalized = secret?.trim().takeUnless { it.isNullOrEmpty() } ?: HERMES_FALLBACK_API_KEY
    val encoded = runCatching {
        val cipher = Cipher.getInstance(GATEWAY_SECRET_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateGatewaySecretKey())
        cipher.updateAAD(GATEWAY_SECRET_AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
    }.getOrDefault(normalized)

    context.getSharedPreferences(CURRENT_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(GATEWAY_SECRET_PREF_KEY, encoded)
        .apply()
}

private fun getOrCreateGatewaySecretKey(): SecretKey = synchronized(gatewaySecretKeyLock) {
    val keyStore = KeyStore.getInstance(GATEWAY_SECRET_KEYSTORE).apply { load(null) }
    val existing = keyStore.getEntry(GATEWAY_SECRET_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existing != null) {
        return@synchronized existing.secretKey
    }

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, GATEWAY_SECRET_KEYSTORE)
    val spec = KeyGenParameterSpec.Builder(
        GATEWAY_SECRET_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    generator.init(spec)
    generator.generateKey()
}

private fun loadArchiveItems(context: Context): List<ArchiveItem> {
    return loadConversations(context).map {
        ArchiveItem(
            id = it.id,
            title = it.title,
            kind = it.kind,
            description = it.description.ifBlank { "Ultimo aggiornamento locale." },
            prompt = it.prompt
        )
    }
}

private fun loadConversation(context: Context, id: String): LocalConversation? {
    return loadConversations(context).firstOrNull { it.id == id }
}

private fun loadWorkspaceRequests(context: Context, kind: String): List<WorkspaceRequest> {
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

private fun saveWorkspaceRequest(
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
    prefs.edit().putString("items", array.toString()).apply()
}

private fun saveWorkspaceFeedback(context: Context, id: String, feedback: String, status: String) {
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
    prefs.edit().putString("items", array.toString()).apply()
}

private suspend fun syncWorkspaceJobs(context: Context, settings: AppSettings, kind: String, apiKey: String?): String = withContext(Dispatchers.IO) {
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

private suspend fun sendWorkspaceFeedback(settings: AppSettings, item: WorkspaceRequest, feedback: String, apiKey: String?): String = withContext(Dispatchers.IO) {
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

private fun loadVideoFeedback(context: Context, id: String): String {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString("video_feedback", "{}") ?: "{}"
    return try {
        JSONObject(raw).optJSONObject(id)?.optString("feedback").orEmpty()
    } catch (_: Exception) {
        ""
    }
}

private fun loadVideoReaction(context: Context, id: String): String {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString("video_feedback", "{}") ?: "{}"
    return try {
        JSONObject(raw).optJSONObject(id)?.optString("reaction").orEmpty()
    } catch (_: Exception) {
        ""
    }
}

private fun saveVideoFeedback(context: Context, id: String, feedback: String, reaction: String, status: String) {
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
    prefs.edit().putString("video_feedback", root.toString()).apply()
}

private suspend fun sendVideoLibraryFeedback(settings: AppSettings, item: VideoLibraryItem, feedback: String, reaction: String, apiKey: String?): String = withContext(Dispatchers.IO) {
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
                    .put(JSONObject().put("role", "system").put("content", hermesHubAgentInstructions()))
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

private suspend fun runWorkspaceJobAction(settings: AppSettings, item: WorkspaceRequest, action: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    val id = item.remoteId ?: return@withContext "Nessun job Hermes collegato."
    return@withContext try {
        val response = postJson("${hermesRoot(settings)}/api/jobs/$id/$action", JSONObject(), apiKey)
        if (response.first in 200..299) "Job Hermes aggiornato." else "Hermes HTTP ${response.first}: ${extractHumanError(response.second)}"
    } catch (ex: Exception) {
        "Azione job fallita: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

private fun findWorkspaceJobsArray(body: String): JSONArray {
    val trimmed = body.trim()
    if (trimmed.startsWith("[")) return JSONArray(trimmed)
    val root = JSONObject(trimmed)
    return root.optJSONArray("jobs")
        ?: root.optJSONArray("items")
        ?: root.optJSONArray("data")
        ?: JSONArray().put(root)
}

private fun saveConversationExchange(
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
    val conversations = loadConversations(context).toMutableList()
    val index = conversations.indexOfFirst { it.id == conversationId }
    val now = System.currentTimeMillis()
    val newMessages = listOf(
        ChatMessage("Tu", prompt, fromUser = true),
        ChatMessage("Hermes", response, fromUser = false, visualBlocksVersion = visualBlocksVersion, visualBlocks = visualBlocks)
    )

    val conversation = if (index >= 0) {
        val current = conversations[index]
        current.copy(
            kind = if (mode == "Agente") "Task" else current.kind,
            description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
            prompt = prompt,
            updatedAt = now,
            messages = current.messages + newMessages,
            previousResponseId = responseId ?: current.previousResponseId
        )
    } else {
        LocalConversation(
            id = "conv_$now",
            title = makeTitle(prompt),
            kind = if (mode == "Agente") "Task" else "Chat",
            description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
            prompt = prompt,
            updatedAt = now,
            messages = newMessages,
            previousResponseId = responseId
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

private fun saveConversationSnapshot(
    context: Context,
    conversationId: String?,
    mode: String,
    prompt: String,
    messages: List<ChatMessage>,
    source: String,
    responseId: String? = null
): LocalConversation {
    val conversations = loadConversations(context).toMutableList()
    val index = conversations.indexOfFirst { it.id == conversationId }
    val now = System.currentTimeMillis()
    val conversation = if (index >= 0) {
        val current = conversations[index]
        current.copy(
            kind = if (mode == "Agente") "Task" else current.kind,
            description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
            prompt = prompt,
            updatedAt = now,
            messages = messages,
            previousResponseId = responseId ?: current.previousResponseId
        )
    } else {
        LocalConversation(
            id = "conv_$now",
            title = makeTitle(prompt),
            kind = if (mode == "Agente") "Task" else "Chat",
            description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
            prompt = prompt,
            updatedAt = now,
            messages = messages,
            previousResponseId = responseId
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

private fun saveProjectConversation(
    context: Context,
    title: String,
    description: String,
    prompt: String
): LocalConversation {
    val conversations = loadConversations(context).toMutableList()
    val now = System.currentTimeMillis()
    val index = conversations.indexOfFirst { it.kind == "Progetto" && it.title.equals(title, ignoreCase = true) }
    val project = if (index >= 0) {
        conversations[index].copy(description = description, prompt = prompt, updatedAt = now)
    } else {
        LocalConversation(
            id = "project_$now",
            title = title,
            kind = "Progetto",
            description = description,
            prompt = prompt,
            updatedAt = now,
            messages = emptyList()
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

private fun renameConversation(context: Context, id: String, newTitle: String): Boolean {
    if (newTitle.isBlank()) return false

    val conversations = loadConversations(context).toMutableList()
    val index = conversations.indexOfFirst { it.id == id }
    if (index < 0) return false

    conversations[index] = conversations[index].copy(title = newTitle, updatedAt = System.currentTimeMillis())
    saveConversations(context, conversations)
    return true
}

private fun deleteConversation(context: Context, id: String): Boolean {
    val conversations = loadConversations(context).toMutableList()
    val removed = conversations.removeAll { it.id == id }
    if (removed) {
        saveConversations(context, conversations)
    }
    return removed
}

private fun copyArchiveToClipboard(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("chatclaw-archive", exportArchiveText(context)))
}

private fun exportArchiveText(context: Context): String {
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

private fun loadConversations(context: Context): List<LocalConversation> {
    val raw = migratePrefs(context, CURRENT_ARCHIVE_PREFS, LEGACY_ARCHIVE_PREFS).getString("items", "[]") ?: "[]"
    return try {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    LocalConversation(
                        id = obj.optString("id"),
                        title = obj.optString("title", "Nuova chat"),
                        kind = obj.optString("kind", "Chat"),
                        description = obj.optString("description"),
                        prompt = obj.optString("prompt"),
                        updatedAt = obj.optLong("updatedAt"),
                        messages = readMessages(obj.optJSONArray("messages") ?: JSONArray()),
                        previousResponseId = obj.optString("previousResponseId").takeIf { it.isNotBlank() }
                    )
                )
            }
        }.sortedByDescending { it.updatedAt }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveConversations(context: Context, conversations: List<LocalConversation>) {
    val array = JSONArray()
    conversations
        .sortedByDescending { it.updatedAt }
        .take(200)
        .forEach { conversation ->
            array.put(
                JSONObject()
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("kind", conversation.kind)
                    .put("description", conversation.description)
                    .put("prompt", conversation.prompt)
                    .put("updatedAt", conversation.updatedAt)
                    .put("previousResponseId", conversation.previousResponseId ?: JSONObject.NULL)
                    .put("messages", writeMessages(conversation.messages))
            )
        }

    context.getSharedPreferences(CURRENT_ARCHIVE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("items", array.toString())
        .commit()
}

private fun loadTasks(context: Context): List<AgentTask> {
    val raw = migratePrefs(context, CURRENT_TASKS_PREFS, LEGACY_TASKS_PREFS).getString("items", "[]") ?: "[]"
    return try {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
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

private fun saveTasks(context: Context, tasks: List<AgentTask>) {
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

    context.getSharedPreferences(CURRENT_TASKS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("items", array.toString())
        .apply()
}

private fun readMessages(array: JSONArray): List<ChatMessage> {
    return buildList {
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val storedId = obj.optString("id").takeIf { it.isNotBlank() }
            add(
                ChatMessage(
                    author = obj.optString("author"),
                    text = obj.optString("text"),
                    fromUser = obj.optBoolean("fromUser"),
                    isAction = obj.optBoolean("isAction", false),
                    visualBlocksVersion = obj.optInt("visualBlocksVersion").takeIf { obj.has("visualBlocksVersion") },
                    visualBlocks = readVisualBlocks(obj.optJSONArray("visualBlocks") ?: JSONArray()),
                    stats = readChatStats(obj.optJSONObject("stats")),
                    rawEvents = readRawEvents(obj.optJSONArray("rawEvents") ?: JSONArray()),
                    id = storedId ?: java.util.UUID.randomUUID().toString()
                )
            )
        }
    }
}

private fun writeMessages(messages: List<ChatMessage>): JSONArray {
    val array = JSONArray()
    messages.forEach { message ->
        array.put(
            JSONObject()
                .put("id", message.id)
                .put("author", message.author)
                .put("text", message.text)
                .put("fromUser", message.fromUser)
                .put("isAction", message.isAction)
                .put("visualBlocksVersion", message.visualBlocksVersion ?: JSONObject.NULL)
                .put("visualBlocks", writeVisualBlocks(message.visualBlocks))
                .put("stats", writeChatStats(message.stats) ?: JSONObject.NULL)
                .put("rawEvents", writeRawEvents(message.rawEvents))
        )
    }
    return array
}

private fun readRawEvents(array: JSONArray): List<HermesRawEvent> = buildList {
    for (i in 0 until minOf(array.length(), 80)) {
        val obj = array.optJSONObject(i) ?: continue
        add(
            HermesRawEvent(
                name = obj.optString("name", "hermes.event"),
                json = obj.optString("json"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        )
    }
}

private fun writeRawEvents(events: List<HermesRawEvent>): JSONArray {
    return JSONArray(events.take(80).map { event ->
        JSONObject()
            .put("name", event.name)
            .put("json", event.json)
            .put("timestamp", event.timestamp)
    })
}

private fun readChatStats(obj: JSONObject?): ChatStreamStats? {
    if (obj == null) return null
    return ChatStreamStats(
        ttftMs = obj.optNullableDouble("ttftMs"),
        totalMs = obj.optNullableDouble("totalMs"),
        tokensOut = obj.optNullableInt("tokensOut"),
        tokensPerSecond = obj.optNullableDouble("tokensPerSecond"),
        promptTokens = obj.optNullableInt("promptTokens"),
        contextTokens = obj.optNullableInt("contextTokens"),
        contextLength = obj.optNullableInt("contextLength"),
        contextPercent = obj.optNullableInt("contextPercent")
    ).takeIf {
        it.ttftMs != null || it.totalMs != null || it.tokensOut != null ||
            it.tokensPerSecond != null || it.promptTokens != null ||
            it.contextTokens != null || it.contextLength != null || it.contextPercent != null
    }
}

private fun writeChatStats(stats: ChatStreamStats?): JSONObject? {
    if (stats == null) return null
    return JSONObject()
        .put("ttftMs", stats.ttftMs ?: JSONObject.NULL)
        .put("totalMs", stats.totalMs ?: JSONObject.NULL)
        .put("tokensOut", stats.tokensOut ?: JSONObject.NULL)
        .put("tokensPerSecond", stats.tokensPerSecond ?: JSONObject.NULL)
        .put("promptTokens", stats.promptTokens ?: JSONObject.NULL)
        .put("contextTokens", stats.contextTokens ?: JSONObject.NULL)
        .put("contextLength", stats.contextLength ?: JSONObject.NULL)
        .put("contextPercent", stats.contextPercent ?: JSONObject.NULL)
}

private fun JSONObject.optNullableDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return optDouble(name).takeIf { it.isFinite() }
}

private fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name).takeIf { it > 0 }
}

private fun readVisualBlocks(array: JSONArray): List<VisualBlock> = buildList {
    for (i in 0 until minOf(array.length(), VISUAL_BLOCKS_MAX_BLOCKS)) {
        val block = readVisualBlock(array.optJSONObject(i) ?: continue)
        if (block.isValidVisualBlock()) {
            add(block)
        }
    }
}

private fun writeVisualBlocks(blocks: List<VisualBlock>): JSONArray {
    val array = JSONArray()
    blocks.take(VISUAL_BLOCKS_MAX_BLOCKS).filter { it.isValidVisualBlock() }.forEach { block ->
        val obj = JSONObject()
            .put("id", block.id)
            .put("type", block.type)
            .put("title", block.title)
            .put("caption", block.caption)
        when (block.type) {
            "markdown" -> obj.put("text", block.text)
            "code" -> obj.put("language", block.language).put("filename", block.filename).put("code", block.code).put("highlight_lines", JSONArray(block.highlightLines))
            "table" -> obj.put("columns", JSONArray(block.columns.map { JSONObject().put("key", it.key).put("label", it.label).put("align", it.align).put("format", it.format).put("sortable", it.sortable) })).put("rows", JSONArray(block.rows.map { row -> JSONObject(row) }))
            "chart" -> obj.put("chart_type", block.chartType).put("x_label", block.xLabel).put("y_label", block.yLabel).put("unit", block.unit).put("summary", block.summary).put("series", JSONArray(block.series.map { series -> JSONObject().put("name", series.name).put("points", JSONArray(series.points.map { point -> JSONObject().put("x", point.x).put("y", point.y) })) }))
            "diagram" -> obj.put("source_format", block.sourceFormat).put("source", block.source).put("rendered_media_url", block.renderedMediaUrl).put("alt", block.alt)
            "image_gallery" -> obj.put("layout", block.layout).put("images", JSONArray(block.images.map { image -> JSONObject().put("media_url", image.mediaUrl).put("alt", image.alt).put("caption", image.caption) }))
            "media_file" -> obj.put("media_url", block.mediaUrl).put("media_kind", block.mediaKind).put("mime_type", block.mimeType).put("filename", block.filename).put("size_bytes", block.sizeBytes ?: JSONObject.NULL).put("duration_ms", block.durationMs ?: JSONObject.NULL).put("thumbnail_url", block.thumbnailUrl).put("alt", block.alt)
            "callout" -> obj.put("variant", block.variant).put("text", block.text)
            "unknown_block" -> obj.put("raw_json", block.rawJson)
        }
        array.put(obj)
    }
    return array
}

private val MULTI_WHITESPACE_REGEX = Regex("\\s+")
private val BIDI_CONTROL_CHARS = setOf(
    '\u200E', '\u200F',
    '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
    '\u2066', '\u2067', '\u2068', '\u2069'
)

private fun makeTitle(prompt: String): String {
    val cleaned = prompt
        .replace('\u00A0', ' ')
        .filterNot { it in BIDI_CONTROL_CHARS }
        .lines()
        .joinToString(" ") { it.trim() }
        .filter { ch -> ch.isLetterOrDigit() || ch.isWhitespace() || ch in "_-.,:;?!()[]\"'/\\@#%&*+=" }
        .replace(MULTI_WHITESPACE_REGEX, " ")
        .trim()
    if (cleaned.isEmpty()) return "Nuova richiesta"
    return if (cleaned.length <= 46) cleaned else cleaned.take(46).trimEnd() + "..."
}

internal const val VISUAL_BLOCKS_VERSION = 1
private const val VISUAL_BLOCKS_MAX_BLOCKS = 20
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

private fun appVersion(context: Context): String {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "debug"
    } catch (_: Exception) {
        "debug"
    }
}

private fun openAndroidIntent(context: Context, intent: Intent): Boolean {
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}

private val prefsCache = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()
private val migratedPrefs = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

private fun migratePrefs(context: Context, currentName: String, legacyName: String): SharedPreferences {
    val current = prefsCache.getOrPut(currentName) {
        context.applicationContext.getSharedPreferences(currentName, Context.MODE_PRIVATE)
    }

    if (migratedPrefs.contains(currentName)) {
        return current
    }
    migratedPrefs.add(currentName)

    if (current.all.isNotEmpty()) {
        return current
    }

    val legacy = prefsCache.getOrPut(legacyName) {
        context.applicationContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
    }
    if (legacy.all.isNotEmpty()) {
        val editor = current.edit()
        legacy.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.apply()
    }

    return current
}

private const val CURRENT_SETTINGS_PREFS = "chatclaw_settings"
private const val LEGACY_SETTINGS_PREFS = "nemoclaw_settings"
private const val CURRENT_ARCHIVE_PREFS = "chatclaw_archive"
private const val LEGACY_ARCHIVE_PREFS = "nemoclaw_archive"
private const val CURRENT_TASKS_PREFS = "chatclaw_tasks"
private const val LEGACY_TASKS_PREFS = "nemoclaw_tasks"
private const val CURRENT_WORKSPACE_PREFS = "chatclaw_workspace_requests"
private const val GATEWAY_SECRET_PREF_KEY = "gatewaySecretCiphertext"
private const val GATEWAY_SECRET_KEYSTORE = "AndroidKeyStore"
private const val GATEWAY_SECRET_ALIAS = "HermesHubGatewayApiKey"
private const val GATEWAY_SECRET_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GATEWAY_SECRET_AAD = "HermesHub.ApiKey.v1"
private const val MIN_FONT_SCALE = 0.85f
private const val MAX_FONT_SCALE = 1.25f
private const val SHOW_RAW_HERMES_EVENTS_IN_CHAT = false
private const val CHAT_HISTORY_MAX_MESSAGES = 30
private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 90000
private const val CONTEXT_SYSTEM_OVERHEAD_TOKENS = 900
private const val MESSAGE_CONTEXT_OVERHEAD_TOKENS = 6
private const val SETTINGS_FIELD_MAX_LENGTH = 2048
private val gatewaySecretKeyLock = Any()

private object AppDefaults {
    const val gatewayUrl = "http://hermes.local:8642/v1"
    const val gatewayWsUrl = ""
    const val adminBridgeUrl = "http://hermes.local:8642"
    const val provider = "hermes-agent"
    const val inferenceEndpoint = "http://hermes.local:8642/v1"
    const val preferredApi = "hermes-native"
    const val model = "hermes-agent"
    const val accessMode = "Tailscale/LAN"
    const val visualBlocksMode = "auto"
    const val videoLibraryPath = ""
    const val activeProjectId = ""
    const val activeProjectName = ""
    const val fontScale = 1.0f
    const val strictNativeMode = true
    const val demoMode = false
    const val releasesPage = "https://github.com/JackoPeru/app-interazione-nemoclaw/releases"
    const val latestReleaseApi = "https://api.github.com/repos/JackoPeru/app-interazione-nemoclaw/releases/latest"
}

internal object AppColors {
    val Background = Color(0xFF0F1115)
    val Sidebar = Color(0xFF14171D)
    val Composer = Color(0xFF1A1E26)
    val Surface = Color(0xFF1A1E26)
    val Panel = Color(0xFF151922)
    val Elevated = Color(0xFF232831)
    val AssistantBubble = Color(0xFF1F242E)
    val UserBubble = Color(0xFF7A3E00)
    // Muted bumped da #A2ADBF (3.8:1 su Background) a #C8D2E0 (~6.5:1) per WCAG AA.
    val Muted = Color(0xFFC8D2E0)
    val Faint = Color(0xFF8892A2)
    val Accent = Color(0xFFF5A524)
    val NavIndicator = Color(0xFF4A351F)
    val Border = Color(0xFF232932)
}
