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
internal fun StartupLoadingScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppColors.Background
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("Avvio Hermes Hub…", color = AppColors.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun SectionTopBar(tab: Tab, onOpenSidebar: () -> Unit, onBackToChat: () -> Unit) {
    Surface(
        color = AppColors.Background,
        border = BorderStroke(0.dp, Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(68.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.chatclaw_logo),
                contentDescription = "Apri navigazione",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .clickable(onClick = onOpenSidebar)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(tab.label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("Hermes Hub", color = AppColors.Faint, fontSize = 11.sp)
            }
            IconButton(onClick = onBackToChat, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "Torna alla chat", tint = AppColors.Muted)
            }
        }
    }
    HorizontalDivider(color = AppColors.Border.copy(alpha = 0.8f))
}

@Composable
internal fun HermesSidebar(
    context: Context,
    selectedTab: Tab,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenTab: (Tab) -> Unit
) {
    val conversations = remember { loadConversations(context).sortedByDescending { it.updatedAt } }
    Surface(
        modifier = Modifier
            .width(320.dp)
            .fillMaxSize()
            .clickable { },
        color = AppColors.Sidebar
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
                    selected = false,
                    onClick = onNewChat
                )
            }
            item {
                SidebarSectionLabel("OPERATIVITA")
            }
            item {
                SidebarTabRow(Tab.Chat, selectedTab == Tab.Chat, onOpenTab)
            }
            item {
                SidebarTabRow(Tab.Voice, selectedTab == Tab.Voice, onOpenTab)
            }
            item {
                SidebarTabRow(Tab.Jarvis, selectedTab == Tab.Jarvis, onOpenTab)
            }
            item {
                SidebarTabRow(Tab.Projects, selectedTab == Tab.Projects, onOpenTab)
            }
            item {
                SidebarTabRow(Tab.Bots, selectedTab == Tab.Bots, onOpenTab)
            }
            item {
                SidebarTabRow(Tab.Artifacts, selectedTab == Tab.Artifacts, onOpenTab)
            }
            item {
                SidebarTabRow(Tab.Search, selectedTab == Tab.Search, onOpenTab)
            }
            item {
                SidebarTabRow(Tab.Archive, selectedTab == Tab.Archive, onOpenTab)
            }
            item {
                SidebarSectionLabel("CONTROLLO")
            }
            items(listOf(Tab.Server, Tab.Hardware, Tab.Health, Tab.Cron, Tab.Notifications, Tab.Continuity, Tab.Audit), key = { "control-${it.name}" }) { tab ->
                SidebarTabRow(tab, selectedTab == tab, onOpenTab)
            }
            item {
                SidebarSectionLabel("CONTENUTI")
            }
            items(listOf(Tab.News, Tab.Video), key = { "content-${it.name}" }) { tab ->
                SidebarTabRow(tab, selectedTab == tab, onOpenTab)
            }
            item {
                SidebarSectionLabel("ACCOUNT")
            }
            items(listOf(Tab.Settings, Tab.Profile), key = { "account-${it.name}" }) { tab ->
                SidebarTabRow(tab, selectedTab == tab, onOpenTab)
            }
            item {
                HorizontalDivider(color = AppColors.Border, modifier = Modifier.padding(vertical = 8.dp))
                Text("RECENTI", color = AppColors.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
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
internal fun SidebarRow(icon: ImageVector, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) AppColors.NavIndicator else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = title, tint = if (selected) AppColors.Accent else AppColors.Muted, modifier = Modifier.size(19.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AppColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SidebarSectionLabel(title: String) {
    Text(
        title,
        color = AppColors.Faint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
internal fun SidebarTabRow(tab: Tab, selected: Boolean, onOpenTab: (Tab) -> Unit) {
    val subtitle = when (tab) {
        Tab.Chat -> "Conversazione principale"
        Tab.Voice -> "Interazione vocale continua"
        Tab.Jarvis -> "Vista e assistenza temporanea"
        Tab.Projects -> "Workspace e contesto operativo"
        Tab.Bots -> "Profili reali e Bot Chat canoniche"
        Tab.Artifacts -> "Output persistenti e versioni"
        Tab.Search -> "Ricerca su tutto Hermes Hub"
        Tab.Archive -> "Chat e progetti salvati"
        Tab.Continuity -> "Handoff, clipboard e file"
        Tab.Audit -> "Timeline operazioni e rischio"
        Tab.Server -> "Gateway e diagnostica"
        Tab.Hardware -> "Metriche del server"
        Tab.Health -> "Dati Galaxy Watch e trend"
        Tab.Cron -> "Automazioni programmate"
        Tab.Notifications -> "Avvisi Hermes"
        Tab.News -> "Articoli generati"
        Tab.Video -> "Libreria e rendering"
        Tab.Settings -> "Connessione e comportamento"
        Tab.Profile -> "Identita e informazioni"
    }
    SidebarRow(tab.icon, tab.label, subtitle, selected = selected) { onOpenTab(tab) }
}
