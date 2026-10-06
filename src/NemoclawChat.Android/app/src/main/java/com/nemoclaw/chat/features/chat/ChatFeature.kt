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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.nemoclaw.chat.core.WorkLimits
import com.nemoclaw.chat.features.bots.isLinkedArchiveId
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

/**
 * Id stabile riga transcript per dedup live: prova id/message_id/seq nel raw
 * (int diretto o stringa numerica), poi created_at + hash del contenuto,
 * infine fallback deterministico positivo da indice. Mai 0.
 */
internal fun stableMessageId(raw: JSONObject?, fallbackIndex: Int): Int {
    if (raw != null) {
        for (key in arrayOf("id", "message_id", "seq")) {
            if (raw.isNull(key)) continue
            val value = raw.opt(key) ?: continue
            val asInt: Int? = when (value) {
                is Number -> value.toInt().takeIf { it != 0 }
                is String -> value.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it != 0 }
                else -> null
            }
            if (asInt != null) return asInt
        }
        val created = raw.optString("created_at", "").orEmpty().trim()
        if (created.isNotEmpty()) {
            val hash = (created + "|" + raw.optString("role", "") + "|" + raw.optString("content", "").take(256)).hashCode()
            if (hash != 0) {
                if (hash == Int.MIN_VALUE) return Int.MAX_VALUE
                val abs = abs(hash)
                if (abs != 0) return abs
            }
        }
    }
    return 1_000_000 + fallbackIndex.coerceAtLeast(0)
}

@Composable
internal fun ChatScreen(
    context: Context,
    settings: AppSettings,
    state: ChatStateHolder,
    scope: kotlinx.coroutines.CoroutineScope,
    conversationId: String? = null,
    botProfile: String? = null,
    botSessionId: String? = null,
    botDisplayName: String? = null,
    botMultiplexEnabled: Boolean = false,
    botConnectionId: String? = null,
    botEndpoint: String? = null,
    onNewChat: () -> Unit = { state.resetForNewChat() },
    initialPrompt: String = "",
    onInitialPromptConsumed: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
    onSwitchTab: (Tab) -> Unit = {},
    // Selettore [Chat | Bot] in alto alla chat (stato issato in AppRoot).
    chatBotActive: Boolean = false,
    // True mentre la sezione sta aprendo la chat del bot (niente home
    // generica nel mentre: si mostra l'attesa bot).
    chatBotOpening: Boolean = false,
    onSelectChat: () -> Unit = {},
    onSelectBot: () -> Unit = {},
    onOpenBotSection: () -> Unit = {}
) {
    val remoteBot = !botConnectionId.isNullOrBlank() && !botConnectionId.equals("primary", true)
    val botSettings = if (remoteBot && !botEndpoint.isNullOrBlank()) {
        settings.copy(
            gatewayUrl = botEndpoint.trimEnd('/'),
            gatewayWsUrl = "",
            inferenceEndpoint = botEndpoint.trimEnd('/'),
            adminBridgeUrl = botEndpoint.trimEnd('/')
        )
    } else {
        settings
    }
    val botApiKey = if (remoteBot) {
        loadGatewayConnectionSecret(context, botConnectionId.orEmpty())
    } else {
        loadGatewaySecret(context)
    }
    val botAllowCompatAuth = !remoteBot
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var quickPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    // Invio mentre Hermes lavora: scelta Accoda/Correggi per ogni invio.
    var pendingBusySend by remember { mutableStateOf<PendingBusySend?>(null) }
    var showSendChoiceDialog by remember { mutableStateOf(false) }

    // Svuota la coda prompt quando libero: il prossimo parte da solo via
    // quickPrompt (stesso percorso del tasto invio, niente duplicazioni).
    fun drainQueuedPrompt() {
        val cid = state.activeConversationId ?: return
        if (state.sending || state.activeStreamJob != null) return
        if (state.draft.isNotBlank()) return
        val next = nextQueuedFor(state.queuedPrompts.toList(), cid) ?: return
        state.queuedPrompts.remove(next)
        state.pendingAttachments.addAll(next.attachments.filter { it !in state.pendingAttachments })
        val missingFiles = (next.attachmentCount - next.attachments.size).coerceAtLeast(0)
        if (missingFiles > 0) {
            state.messages.add(
                ChatMessage(
                    "Hermes Hub",
                    "Attenzione: $missingFiles allegati in coda non esistono piu (cache pulita). Parte solo il testo.",
                    fromUser = false,
                    isAction = true
                )
            )
        }
        quickPrompt = next.text
    }

    // "Correggi ora": ferma tutto (come lo stop, ma senza svuotare la coda)
    // e invia subito il nuovo prompt per raddrizzare l'agente a meta run.
    fun correctNow(choice: PendingBusySend) {
        showSendChoiceDialog = false
        pendingBusySend = null
        val cid = state.activeConversationId ?: return
        HermesStreamRuntime.scope.launch {
            state.activeStreams[cid]?.job?.cancel()
            state.activeStreamJob?.cancel()
            state.streamingState?.activeRunId?.let { runId ->
                runCatching {
                    stopHermesRun(botSettings, runId, botApiKey, botProfile, botMultiplexEnabled, botAllowCompatAuth)
                }
            }
            if (state.activeStreams.size <= 1) {
                val stopKey = loadGatewaySecret(context)?.takeIf { it.isNotBlank() }
                if (stopKey != null) {
                    cancelManagerJobsSince(
                        gpuManagerBase(settings.gatewayUrl), stopKey, state.lastTurnStartMs
                    )
                }
            }
            for (i in 0 until 25) {
                if (!state.sending && state.activeStreamJob == null) break
                delay(200)
            }
            if (state.sending || state.activeStreamJob != null) {
                // Ancora occupato: accoda invece di perdersi.
                if (canEnqueuePrompt(state.queuedPrompts.toList(), cid)) {
                    state.queuedPrompts.add(QueuedPrompt(cid, choice.text, choice.attachments))
                    state.draft = ""
                    state.pendingAttachments.clear()
                }
                return@launch
            }
            state.draft = choice.text
            state.pendingAttachments.addAll(choice.attachments.filter { it !in state.pendingAttachments })
            // Stesso percorso del tasto invio (il guard qui sopra e libero).
            quickPrompt = choice.text
        }
    }

    fun enqueueBusySend(choice: PendingBusySend) {
        showSendChoiceDialog = false
        pendingBusySend = null
        val cid = state.activeConversationId ?: return
        if (!canEnqueuePrompt(state.queuedPrompts.toList(), cid)) {
            state.messages.add(
                ChatMessage(
                    "Hermes Hub",
                    "Coda piena ($MAX_QUEUED_PROMPTS_PER_CHAT prompt): aspetta la fine del turno.",
                    fromUser = false,
                    isAction = true
                )
            )
            return
        }
        state.queuedPrompts.add(QueuedPrompt(cid, choice.text, choice.attachments))
        state.draft = ""
        state.pendingAttachments.clear()
        val n = state.queuedPrompts.count { it.conversationId == cid }
        state.messages.add(
            ChatMessage(
                "Hermes Hub",
                "Accodato ($n in coda): parte da solo a fine turno.",
                fromUser = false,
                isAction = true
            )
        )
    }
    // Cronologia caricata da disco per cid: il reattach DONE aggiunge il
    // risultato solo qui dentro, mai su lista vuota/stale (evita duplicati
    // che poi crescono a ogni riapertura).
    var historyLoadedCid by rememberSaveable { mutableStateOf<String?>(null) }
    // True se l'id aperto risulta eliminato (tombstone): evita di mostrare
    // un contenitore vuoto senza spiegazione. Le chat nuove non salvate
    // (es. primo open di un bot) NON alzano il flag.
    var conversationDeletedNotice by remember(conversationId) { mutableStateOf(false) }
    // Chat collegata al desktop (id Hub altrui): gli snapshot preservano i
    // puntatori di continuazione esistenti. Vale anche senza contesto bot
    // (apertura da archivio di entity linkata). Contratto: l'inferenza
    // resta sulla SESSIONE bot (botSessionId), il preserve evita solo il
    // clobber dello storage condiviso.
    // pendingConversationId viene nulllato dopo il primo load: fallback su
    // activeConversationId, altrimenti dal 2o turno preserve diventerebbe
    // false e si distruggerebbe il serverConversationId desktop.
    val preserveRemoteContinuity = remember(botProfile, conversationId, state.activeConversationId) {
        val effective = conversationId?.takeIf { it.isNotBlank() }
            ?: state.activeConversationId?.takeIf { it.isNotBlank() }
        if (effective.isNullOrBlank()) false
        else if (!botProfile.isNullOrBlank()) !isBotConversationId(effective)
        else isLinkedArchiveId(context, effective)
    }

    // Contatore ricaricamento manuale (pulsante Riprova dello stato vuoto):
    // incluso nelle chiavi del load sotto, forza transcript+cache da zero.
    var botLoadNonce by remember(botProfile, botSessionId) { mutableIntStateOf(0) }

    LaunchedEffect(conversationId, initialPrompt, botSettings.gatewayUrl, botSessionId, botEndpoint, botLoadNonce) {
        // Flag solo con id non-blank: al ritorno dalla sezione Bot (o da tab)
        // l'effect rigira con (null, "") e NON deve azzerarlo, altrimenti il
        // gate reattach DONE sopprimerebbe output background legittimi.
        conversationId?.takeIf { it.isNotBlank() }?.let { historyLoadedCid = it }
        if (!conversationId.isNullOrBlank()) {
            val saved = withContext(Dispatchers.IO) { loadConversation(context, conversationId) }
            if (saved != null) {
                state.activeConversationId = saved.id
                val expectedServerConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, saved.id)
                state.previousResponseId = if (botProfile.isNullOrBlank() && saved.serverConversationId == expectedServerConversationId) {
                    saved.previousResponseId
                } else if (preserveRemoteContinuity) {
                    // Chat collegata: conserva la catena esistente invece di
                    // azzerarla (l'invio la usa solo dove ha senso).
                    saved.previousResponseId
                } else {
                    null
                }
                state.hermesSessionId = saved.hermesSessionId
                    ?: if (botProfile.isNullOrBlank()) {
                        withContext(Dispatchers.IO) { loadSessionBinding(context, saved.id, botProfile) }
                    } else {
                        // Bot canonico: i binding pre-migrazione (bot-*) sono
                        // ombre stale, mai usarli per helper run/rename/fork.
                        null
                    }
                state.chatModelOverride = saved.modelOverride
                state.chatProviderOverride = saved.providerOverride
                state.chatReasoningEffort = saved.reasoningEffort
                state.messages.clear()
                val loadedMessages = saved.messages.toMutableList()
                val activeStream = state.activeStreams[saved.id]
                if (activeStream != null && loadedMessages.isNotEmpty() && loadedMessages.last().author == "Hermes") {
                    loadedMessages.removeAt(loadedMessages.lastIndex)
                }
                state.messages.addAll(loadedMessages)
                historyLoadedCid = saved.id
                conversationDeletedNotice = false
                // Rientro con coda pendente: se libero, il prossimo parte da solo.
                drainQueuedPrompt()
            } else {
                // Id noto ma file assente: mostra avviso solo se esiste tombstone
                // (eliminata); le chat nuove non ancora salvate restano silenti.
                val cid = conversationId
                val tombstoned = withContext(Dispatchers.IO) {
                    loadConversations(context, includeDeleted = true)
                        .firstOrNull { it.id == cid }?.deletedAt != null
                }
                conversationDeletedNotice = tombstoned
            }
        }
        // Chat bot canonica: storia autorevole dal server (stessa transcript
        // del desktop). Sostituisce la cache se non vuota; fallback locale
        // in caso di errore/offline. Vale anche al primo open (saved null).
        if (!botProfile.isNullOrBlank() && !botSessionId.isNullOrBlank()) {
            val transcript = withContext(Dispatchers.IO) {
                runCatching {
                    loadCanonicalBotTranscript(
                        botSettings, botApiKey, botProfile, botSessionId, botMultiplexEnabled
                    )
                }.getOrNull()
            }
            if (!transcript.isNullOrEmpty()) {
                state.messages.clear()
                state.messages.addAll(transcript)
                val cid = state.activeConversationId ?: conversationId
                historyLoadedCid = cid
                if (!cid.isNullOrBlank()) {
                    state.activeConversationId = cid
                    withContext(NonCancellable + Dispatchers.IO) {
                        saveConversationSnapshot(
                            context = context,
                            conversationId = cid,
                            mode = "Chat",
                            prompt = "",
                            messages = transcript,
                            source = "Bot Chat condivisa",
                            syncAfterSave = false
                        )
                    }
                }
                drainQueuedPrompt()
            }
        }

        // Apertura chat bot: parti in fondo all'ultima risposta (le chat
        // bot sono lunghe, l'inizio non serve). Solo se la lista e ancora
        // in cima: rispetta la posizione se l'utente ha gia scrollato, e
        // mai durante streaming (quello scrolla da se).
        if (!botProfile.isNullOrBlank() && state.messages.isNotEmpty() &&
            state.streamingState == null &&
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        ) {
            listState.scrollToItem((state.messages.size - 1).coerceAtLeast(0))
        }

        if (initialPrompt.isNotBlank()) {
            state.draft = initialPrompt
        }

        if (!conversationId.isNullOrBlank() || initialPrompt.isNotBlank()) {
            onInitialPromptConsumed()
        }
    }

    val haptics = LocalHapticFeedback.current
    val networkOnline by rememberOnlineState(context)
    var gatewayAvailable by remember(settings.gatewayUrl, settings.inferenceEndpoint, botConnectionId, botEndpoint) {
        mutableStateOf(false)
    }
    // Tri-stato reale: finche la prima probe non risponde, il pallino e
    // grigio ("Verifica gateway…") e NON rosso. Solo dopo un responso
    // negativo si mostra "Rete non disponibile" col motivo (HTTP/timeout),
    // cosi si capisce se e solo UI o davvero giu.
    var gatewayProbed by remember(settings.gatewayUrl, settings.inferenceEndpoint, botConnectionId, botEndpoint) {
        mutableStateOf(false)
    }
    var gatewayProbeDetail by remember(settings.gatewayUrl, settings.inferenceEndpoint, botConnectionId, botEndpoint) {
        mutableStateOf<String?>(null)
    }
    var gatewayRuntime by remember(settings.gatewayUrl, settings.inferenceEndpoint, botConnectionId, botEndpoint) {
        mutableStateOf<GatewayRuntimeStatus?>(null)
    }
    PollWhileStarted(networkOnline, botSettings.gatewayUrl, botSettings.inferenceEndpoint, botApiKey, baseIntervalMs = 5_000L) {
        if (!networkOnline) {
            // Telefono offline: responso negativo vero, non attesa infinita.
            gatewayAvailable = false
            gatewayProbeDetail = "Rete non disponibile"
            gatewayProbed = true
            gatewayRuntime = null
            return@PollWhileStarted true
        }
        val (available, detail) = withContext(Dispatchers.IO) {
            probeHermesGatewayDetailed(botSettings, botApiKey)
        }
        gatewayAvailable = available
        gatewayProbeDetail = detail
        gatewayProbed = true
        gatewayRuntime = if (available) {
            runCatching { withContext(Dispatchers.IO) { loadGatewayRuntimeStatus(botSettings, botApiKey) } }.getOrNull()
        } else {
            null
        }
        if (available) {
            // Successo: 15s totali come prima (5s base poller + 10s qui).
            delay(WorkLimits.WORK_POLL_BASE_MS)
        }
        available
    }
    // Live esterno del bot: lavora altrove (desktop) ma la chat e condivisa.
    // Poll leggero della coda transcript: righe nuove o fresche = in esecuzione.
    // Solo bot chat, mai durante un turno locale (quello ha gia il suo stato).
    // Finestra freschezza 240s: i tool lunghi non producono righe per minuti;
    // meglio un banner che resta che un flicker che fa reinviare duplicati.
    var botLive by remember(botProfile, botSessionId, botLoadNonce) { mutableStateOf<BotLiveActivity?>(null) }
    PollWhileStarted(botProfile, botSessionId, botMultiplexEnabled, botEndpoint, botApiKey, botSettings.gatewayUrl, baseIntervalMs = 5_000L) {
        val profile = botProfile
        val session = botSessionId
        if (profile.isNullOrBlank() || session.isNullOrBlank() || !gatewayAvailable) {
            botLive = null
            return@PollWhileStarted true
        }
        val cidBefore = state.activeConversationId
        val tail = withContext(Dispatchers.IO) {
            runCatching {
                val client = HermesSessionClient(botSettings, botApiKey, profile, botMultiplexEnabled, null)
                val (code, rows) = client.messages(id = session, limit = 5, order = "latest", includeCompacted = false)
                if (code !in 200..299) null else rows
            }.getOrNull()
        }
        if (tail == null) return@PollWhileStarted false
        // Dedup stabile: id/message_id/seq, poi created_at+hash, poi fallback indice.
        val tailWithIds = tail.mapIndexed { index, msg -> msg to stableMessageId(msg.raw, index) }
        // Ordine deterministico per (timestamp, id) prima di asReversed/firstOrNull.
        val orderedWithIds = tailWithIds.sortedWith(
            compareBy({ it.first.raw?.optDouble("timestamp", 0.0) ?: 0.0 }, { it.second })
        )
        val orderedTail = orderedWithIds.map { it.first }
        val maxId = tailWithIds.maxOfOrNull { it.second } ?: 0
        if (state.sending) {
            // Solo baseline: i turni locali non devono mai sembrare nuovi dopo.
            botLive = BotLiveActivity(running = false, status = "", maxRowId = maxId)
            return@PollWhileStarted true
        }
        val prev = botLive
        fun latestTsMs(rows: List<HermesSessionMessage>): Long {
            return rows.mapNotNull { (it.raw?.optDouble("timestamp") ?: 0.0).takeIf { ts -> ts > 0 } }
                .maxOrNull()?.times(1000)?.toLong() ?: 0
        }
        if (prev == null) {
            // Prima lettura: baseline, mai append (la storia completa e gia
            // a video dal load). Running solo se coda fresca.
            val freshTs = latestTsMs(orderedTail)
            val fresh = freshTs > 0 && System.currentTimeMillis() - freshTs < 240_000
            val newest = orderedTail.asReversed().firstOrNull()
            botLive = BotLiveActivity(
                running = fresh && newest != null,
                status = if (fresh && newest != null) botLiveStatusFor(newest) else "",
                maxRowId = maxId
            )
            return@PollWhileStarted true
        }
        val newRowsDesc = orderedWithIds.asReversed().filter { it.second > prev.maxRowId }
        val newRows = newRowsDesc.asReversed().map { it.first }
        val freshTs = latestTsMs(orderedTail)
        val fresh = freshTs > 0 && System.currentTimeMillis() - freshTs < 240_000
        if (!fresh && newRows.isEmpty()) {
            botLive = BotLiveActivity(running = false, status = "", maxRowId = maxId)
            return@PollWhileStarted true
        }
        if (newRows.isNotEmpty()) {
            // Solo se siamo ancora sulla stessa chat (navigazione nel mentre)
            // e senza turno locale: le righe sono latest-first, il fold vuole
            // cronologico (newRows e gia ordinato stabile).
            if (cidBefore != null && state.activeConversationId == cidBefore && !state.sending) {
                // Live-follow: solo se si era gia in fondo (stessa logica di
                // showJumpToBottom), mai strappare chi legge sopra.
                val wasAtBottom = run {
                    val info = listState.layoutInfo
                    val total = info.totalItemsCount
                    if (total <= 1) {
                        true
                    } else {
                        val last = info.visibleItemsInfo.lastOrNull() ?: return@run true
                        !((total - 1 - last.index) >= 1 ||
                            (last.index == total - 1 && last.offset + last.size > info.viewportEndOffset + 150))
                    }
                }
                state.messages.addAll(foldTranscriptToChat(newRows))
                if (wasAtBottom) {
                    listState.scrollToItem((state.messages.size - 1).coerceAtLeast(0))
                }
            }
        }
        // La piu nuova e la prima della coda latest-first ordinata.
        val newest = newRowsDesc.firstOrNull()?.first
            ?: orderedTail.asReversed().firstOrNull()
        botLive = BotLiveActivity(
            running = true,
            status = newest?.let { botLiveStatusFor(it) } ?: "Sta lavorando…",
            maxRowId = maxId
        )
        true
    }
    // Capabilities + model catalog in background (fonte capability-driven, mai version check).
    LaunchedEffect(botSettings.gatewayUrl, botApiKey) {
        if (botSettings.gatewayUrl.isBlank()) return@LaunchedEffect
        val caps = withContext(Dispatchers.IO) {
            runCatching { loadHermesCapabilitiesCached(botSettings, botApiKey) }.getOrNull()
        } ?: return@LaunchedEffect
        state.chatCapabilities = caps
        if (caps.supportsModelOptions()) {
            val catalog = withContext(Dispatchers.IO) {
                runCatching {
                    val body = httpGet("${botSettings.gatewayUrl.trimEnd('/')}/api/model/options", botApiKey)
                    parseModelOptionsPayload(body)
                }.getOrElse {
                    runCatching {
                        val fallback = httpGet("${botSettings.gatewayUrl.trimEnd('/')}/v1/models", botApiKey)
                        parseV1ModelsFallback(fallback)
                    }.getOrNull()
                }
            }
            if (catalog != null && (catalog.models.isNotEmpty() || catalog.providers.isNotEmpty())) state.chatModelCatalog = catalog
        } else {
            val fallback = withContext(Dispatchers.IO) {
                runCatching { httpGet("${botSettings.gatewayUrl.trimEnd('/')}/v1/models", botApiKey) }.getOrNull()
            }?.let { parseV1ModelsFallback(it) }
            if (fallback != null && fallback.models.isNotEmpty()) state.chatModelCatalog = fallback
        }
    }
    val isStreaming = state.streamingState != null
    val archivedBotWithoutContext = botProfile.isNullOrBlank() &&
        isBotConversationId(conversationId ?: state.activeConversationId)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val attachment = withContext(Dispatchers.IO) { createAttachmentFromUri(context, uri, settings.maxAttachmentMb) }
                if (attachment != null) {
                    state.pendingAttachments.add(attachment)
                } else {
                    state.messages.add(ChatMessage("Allegato", "File vuoto, non leggibile o troppo grande. Limite attuale: ${settings.maxAttachmentMb} MB.", fromUser = false, isAction = true))
                }
            }
        }
    }
    var scanUri by remember { mutableStateOf<Uri?>(null) }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = scanUri
        if (ok && uri != null) scope.launch { createAttachmentFromUri(context, uri, settings.maxAttachmentMb)?.let { attachment -> state.pendingAttachments.add(attachment.copy(filename = "scansione-${System.currentTimeMillis()}.jpg")) } }
    }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        if (ok && uri != null) {
            scope.launch {
                val attachment = withContext(Dispatchers.IO) { createAttachmentFromUri(context, uri, settings.maxAttachmentMb) }
                if (attachment != null) {
                    state.pendingAttachments.add(attachment.copy(filename = "foto-${System.currentTimeMillis()}.jpg"))
                } else {
                    android.widget.Toast.makeText(context, "Scatto non riuscito.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // Preriscalda LLM: se il manager e' in AUTO e il modello e' scarico
    // (dopo job media), chiedi il caricamento appena apri la chat cosi' e'
    // pronto quando invii. Silenzioso, una sola volta per apertura.
    // Il manager richiede Bearer (chiave master gateway), mai anonimo.
    val managerKey = remember { loadGatewaySecret(context) }
    LaunchedEffect(settings.gatewayUrl) {
        runCatching {
            val base = gpuManagerBase(settings.gatewayUrl)
            val status = JSONObject(httpGet("$base/status", managerKey))
            if (status.optString("desired_mode") == "AUTO" && !status.optBoolean("llm_loaded", true)) {
                postJson("$base/mode/llm", JSONObject(), managerKey, allowCompatAuth = false)
            }
        }
    }
    // Allegati pending persistenti: rientrando in app (o nella conversazione)
    // la foto allegata al prompt e' ancora li'.
    var restoredPendingFor by remember { mutableStateOf<String?>(null) }
    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = { }
    )
    LaunchedEffect(state.activeConversationId) {
        val cid = state.activeConversationId
        if (restoredPendingFor != cid) {
            if (state.pendingAttachments.isEmpty()) {
                loadPendingAttachments(context, cid).forEach { state.pendingAttachments.add(it) }
            }
            if (state.draft.isBlank()) {
                state.draft = loadDraft(context, cid)
            }
            restoredPendingFor = cid
        }
    }
    LaunchedEffect(state.pendingAttachments.size, state.activeConversationId, state.draft) {
        if (restoredPendingFor == state.activeConversationId) {
            savePendingAttachments(context, state.activeConversationId, state.pendingAttachments.toList())
            saveDraft(context, state.activeConversationId, state.draft)
        }
    }
    LaunchedEffect(state.queuedPrompts.size) {
        // La coda sopravvive al kill dell'app: ripartenza automatica al rientro.
        saveQueuedPrompts(context, state.queuedPrompts.toList())
    }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadQueuedPrompts(context) }
        loaded.filter { it !in state.queuedPrompts }.forEach { state.queuedPrompts.add(it) }
    }
    // Re-attach lavoro background: se per questa conversazione esiste un binding
    // e nessuno stream locale lo sta gia' seguendo, interroga il server e agisci.
    // Il server e' fonte di verita': solo il terminale reale pulisce il binding.
    LaunchedEffect(state.activeConversationId) {
        val cid = state.activeConversationId ?: return@LaunchedEffect
        val binding = withContext(Dispatchers.IO) { loadActiveWorkBinding(context, cid) }
            ?: return@LaunchedEffect
        if (state.streamingState?.activeRunId == binding.runId && state.sending) return@LaunchedEffect
        val client = HermesRunClient(botSettings, botApiKey, botProfile, botMultiplexEnabled)
        val (code, info) = runCatching { client.status(binding.runId) }.getOrElse { 0 to null }
        if (code == 404) {
            withContext(Dispatchers.IO) { clearActiveWorkBinding(context, cid) }
            return@LaunchedEffect
        }
        if (code !in 200..299 || info == null) return@LaunchedEffect
        val approval = parseRunApprovalPayload(info.raw, binding.runId)
        when (backgroundWorkStateFromRun(info, approval != null)) {
            BackgroundWorkState.ACTIVE -> {
                state.backgroundWork = BackgroundWorkUi(
                    runId = binding.runId,
                    goal = binding.goal,
                    statusText = "Hermes continua il lavoro sul gateway…"
                )
                maybeStartBackgroundWork(context, settings, binding, botProfile, botMultiplexEnabled) {
                    notificationsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            BackgroundWorkState.WAITING_FOR_APPROVAL -> {
                val req = approval
                if (req != null) {
                    state.streamingState = (state.streamingState ?: StreamingState()).copy(
                        activeRunId = binding.runId,
                        runStatus = "waiting_for_approval",
                        pendingApprovals = listOf(
                            HermesServerApproval(
                                approvalId = req.approvalId,
                                requestId = req.requestId,
                                runId = binding.runId,
                                tool = req.tool,
                                command = req.command,
                                description = req.description,
                                choices = req.choices
                            )
                        ),
                        status = "In attesa di approvazione."
                    )
                }
                state.backgroundWork = BackgroundWorkUi(
                    runId = binding.runId,
                    goal = binding.goal,
                    statusText = "Approvazione richiesta.",
                    approvalPending = true
                )
                maybeStartBackgroundWork(context, settings, binding, botProfile, botMultiplexEnabled) {
                    notificationsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            BackgroundWorkState.DONE_COMPLETED -> {
                val output = info.output.orEmpty()
                if (output.isNotBlank() && historyLoadedCid == cid &&
                    state.messages.none { !it.fromUser && it.text.contains(output.take(WorkLimits.TRUNC_60)) }
                ) {
                    state.messages.add(ChatMessage("Hermes", output, fromUser = false))
                }
                withContext(Dispatchers.IO) { clearActiveWorkBinding(context, cid) }
                HermesWorkService.stop(context, binding.runId)
                if (state.backgroundWork?.runId == binding.runId) state.backgroundWork = null
            }
            BackgroundWorkState.DONE_FAILED, BackgroundWorkState.DONE_CANCELLED -> {
                withContext(Dispatchers.IO) { clearActiveWorkBinding(context, cid) }
                HermesWorkService.stop(context, binding.runId)
                if (state.backgroundWork?.runId == binding.runId) state.backgroundWork = null
            }
            BackgroundWorkState.GONE, BackgroundWorkState.UNKNOWN -> {
                state.backgroundWork = BackgroundWorkUi(
                    runId = binding.runId,
                    goal = binding.goal,
                    statusText = "Stato lavoro incerto, ricontrollo…"
                )
            }
        }
    }
    LaunchedEffect(isStreaming) {
        if (!isStreaming && state.messages.isNotEmpty()) {
            runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        }
    }
    val streamingTextLen = state.streamingState?.text?.length ?: 0
    LaunchedEffect(isStreaming) {
        if (!isStreaming) {
            return@LaunchedEffect
        }
        val totalItems = state.messages.size + 1
        if (totalItems > 0) {
            listState.scrollToItem(totalItems - 1)
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
        state.streamingState?.stats?.modelPromptTokens,
        streamingTextLen,
        settings.gatewayUrl,
        settings.model,
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
        // In chat bot il titolo e il nome del bot (niente banda separata).
        val topTitle = if (!botProfile.isNullOrBlank()) {
            botDisplayName?.takeIf { it.isNotBlank() } ?: botProfile
        } else {
            "Hermes Hub"
        }
        TopBar(
            settings = settings,
            contextUsage = contextUsage,
            connected = gatewayAvailable,
            probingGateway = !gatewayProbed,
            probeDetail = gatewayProbeDetail,
            title = topTitle,
            gatewayRuntime = gatewayRuntime,
            managerApiKey = managerKey,
            onNewChat = onNewChat,
            onOpenSidebar = onOpenSidebar,
            onOpenArchive = { onSwitchTab(Tab.Archive) }
        )
        ChatBotToggle(
            botActive = chatBotActive,
            onSelectChat = onSelectChat,
            onSelectBot = onSelectBot
        )
        if (archivedBotWithoutContext) {
            Surface(color = Color(0xFF7A3E00), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Chat bot archiviata: riaprila da Bot Hermes",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onOpenBotSection) { Icon(Icons.Rounded.SmartToy, contentDescription = "Apri Bot Hermes", tint = Color.White) }
                }
            }
        }
        if (conversationDeletedNotice) {
            Surface(color = Color(0xFF5A1A1A), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Conversazione eliminata: stai vedendo un contenitore vuoto, i messaggi non torneranno. Creane una nuova.",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { conversationDeletedNotice = false; onNewChat() }) {
                        Text("Nuova chat", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        val backgroundWork = state.backgroundWork
        val locallyStreamingRun = state.streamingState?.activeRunId
        if (backgroundWork != null && locallyStreamingRun != backgroundWork.runId) {
            BackgroundWorkBanner(
                work = backgroundWork,
                onStop = {
                    scope.launch {
                        runCatching {
                            HermesRunClient(botSettings, botApiKey, botProfile, botMultiplexEnabled).stop(backgroundWork.runId)
                            withContext(Dispatchers.IO) {
                                state.activeConversationId?.let { clearActiveWorkBinding(context, it) }
                            }
                            HermesWorkService.stop(context, backgroundWork.runId)
                            if (state.backgroundWork?.runId == backgroundWork.runId) state.backgroundWork = null
                        }
                    }
                }
            )
        }
        Box(modifier = Modifier.weight(1f)) {
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
                    MessageBubble(message, settings)
                }
                state.streamingState?.let { streaming ->
                    item(key = "streaming") {
                        StreamingBubbleView(
                            streaming,
                            settings.showToolCalls,
                            settings.showMessageMetrics,
                            settings.metricFilter(),
                            onSpeakMessage = { text ->
                                scope.launch {
                                    runCatching { speakChatMessage(context, botSettings, text, botApiKey) }
                                        .onFailure { Toast.makeText(context, "TTS Kokoro non disponibile: ${it.message}", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        )
                    }
                }
            }
            if (isEmptyChat) {
                // Empty state must stay above the transparent LazyColumn or the list consumes taps.
                // Mai la home generica in contesto bot: attesa, errore rete o header bot.
                if (chatBotOpening) {
                    BotEmptyState(displayName = null, opening = true, unreachable = false)
                } else if (!botProfile.isNullOrBlank()) {
                    BotEmptyState(
                        displayName = botDisplayName?.takeIf { it.isNotBlank() } ?: botProfile,
                        opening = false,
                        unreachable = !gatewayAvailable && gatewayProbed,
                        detail = gatewayProbeDetail,
                        onRetry = { botLoadNonce++; gatewayProbed = false }
                    )
                } else {
                    EmptyState(onPrompt = { quickPrompt = it })
                }
            }
            val showJumpToBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val total = info.totalItemsCount
                    if (total <= 1) {
                        false
                    } else {
                        val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
                        (total - 1 - last.index) >= 1 ||
                            (last.index == total - 1 && last.offset + last.size > info.viewportEndOffset + 150)
                    }
                }
            }
            if (showJumpToBottom) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) {
                                listState.animateScrollToItem(total - 1)
                                val info = listState.layoutInfo
                                val last = info.visibleItemsInfo.lastOrNull()
                                if (last != null) {
                                    val remaining = last.offset + last.size - info.viewportEndOffset
                                    if (remaining > 0) listState.animateScrollBy(remaining.toFloat())
                                }
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp),
                    containerColor = AppColors.Elevated,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Vai alla fine della chat")
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
        if (!networkOnline) {
            Surface(color = Color(0xFF7A3E00), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
                Text(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    text = "Rete Internet non validata. Provo comunque Hermes via LAN/Tailnet.",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
        var mediaRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
        var voiceRecordStartMs by remember { mutableLongStateOf(0L) }
        fun releaseVoiceRecorder(deleteTempFile: Boolean) {
            val recorder = mediaRecorder
            mediaRecorder = null
            state.isRecordingVoiceNote = false
            if (recorder != null) {
                runCatching { recorder.stop() }
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
            }
            if (deleteTempFile) {
                state.tempVoiceNoteFile?.let { runCatching { it.delete() } }
                state.tempVoiceNoteFile = null
            }
        }
        val startVoiceRecording: () -> Unit = {
            try {
                val tempFile = File.createTempFile("voice_note", ".m4a", context.cacheDir)
                state.tempVoiceNoteFile = tempFile
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.media.MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.MediaRecorder()
                }
                mediaRecorder = recorder
                recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                recorder.setOutputFile(tempFile.absolutePath)
                recorder.prepare()
                recorder.start()
                voiceRecordStartMs = System.currentTimeMillis()
                state.isRecordingVoiceNote = true
            } catch (ex: Exception) {
                releaseVoiceRecorder(deleteTempFile = true)
                state.messages.add(ChatMessage("Errore Voce", "Impossibile registrare audio: ${ex.message ?: ex.javaClass.simpleName}", fromUser = false, isAction = true))
            }
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                if (isGranted) {
                    startVoiceRecording()
                } else {
                    android.widget.Toast.makeText(context, "Permesso microfono negato.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
        DisposableEffect(Unit) {
            onDispose {
                // Rotazione: l'effect si ri-registra, non buttare la registrazione in corso.
                if ((context as? Activity)?.isChangingConfigurations != true) {
                    releaseVoiceRecorder(deleteTempFile = true)
                }
            }
        }
        // Uscendo davvero dalla chat (non per rotazione), cancella gli
        // stream attivi: niente job orfani che scrivono snapshot fuori schermo.
        // Chiave sul context: a rotazione l'effect si ri-registra sul nuovo lifecycle.
        DisposableEffect(context) {
            val activity = context as? Activity
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY &&
                    activity?.isChangingConfigurations != true
                ) {
                    state.activeStreams.values.forEach { it.job?.cancel() }
                }
            }
            val lifecycle = (context as? ComponentActivity)?.lifecycle
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }
        // Cambio gateway/endpoint/profilo/sessione/chiave: gli stream puntano
        // al vecchio backend, cancellali (niente job orfani che scrivono
        // snapshot fuori schermo). Vale anche tra bot sulla stessa gateway.
        DisposableEffect(botSettings.gatewayUrl, botProfile, botSessionId, botEndpoint, botApiKey) {
            // Solo la propria conversazione: le altre chat tengono i loro stream.
            onDispose {
                conversationId?.let { cid ->
                    state.activeStreams[cid]?.job?.cancel()
                    state.activeStreams.remove(cid)
                }
            }
        }

        ChatModelSessionBar(
            state = state,
            settings = settings,
            context = context,
            botSettings = botSettings,
            botApiKey = botApiKey,
            botProfile = botProfile,
            botMultiplexEnabled = botMultiplexEnabled,
            scope = scope,
            autoApproveMode = resolveAutoApproveMode(
                botProfile,
                runCatching { loadBotAutoApproveMap(context) }.getOrDefault(emptyMap()),
                settings.autoApprove
            )
        )
        ChatApprovalCards(
            state = state,
            botSettings = botSettings,
            botApiKey = botApiKey,
            botProfile = botProfile,
            botMultiplexEnabled = botMultiplexEnabled,
            scope = scope,
            context = context,
            autoApproveMode = resolveAutoApproveMode(
                botProfile,
                runCatching { loadBotAutoApproveMap(context) }.getOrDefault(emptyMap()),
                botSettings.autoApprove
            )
        )

        val reasoningLadder = remember(state.chatModelCatalog, state.chatCapabilities, state.chatModelOverride, state.chatProviderOverride) {
            val caps = state.chatCapabilities
            val catalog = state.chatModelCatalog
            val selected = catalog.models.firstOrNull {
                it.id == state.chatModelOverride && (state.chatProviderOverride.isBlank() || it.provider == state.chatProviderOverride)
            }
            when {
                selected != null && selected.reasoningEfforts.isNotEmpty() && caps?.reasoningEfforts?.isNotEmpty() == true ->
                    selected.reasoningEfforts.filter { eff -> caps.supportsReasoningEffort(eff) }
                caps?.reasoningEfforts?.isNotEmpty() == true -> caps.reasoningEfforts
                else -> FALLBACK_REASONING_EFFORTS
            }
        }
        val queuedHere = state.queuedPrompts.count { it.conversationId == state.activeConversationId }
        if (queuedHere > 0) {
            Text(
                "$queuedHere prompt in coda: partiranno da soli a fine turno.",
                color = AppColors.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        val busyChoice = pendingBusySend
        // Live esterno bot: quadratino stop acceso + banner shimmer in fondo.
        val botLiveNow = botLive
        if (botLiveNow?.running == true && !state.sending) {
            BotLiveBanner(botLiveNow.status)
        }
        if (showSendChoiceDialog && busyChoice != null) {
            AlertDialog(
                onDismissRequest = { showSendChoiceDialog = false; pendingBusySend = null },
                title = { Text("Hermes sta lavorando") },
                text = { Text("Accoda il prompt (parte da solo alla fine) oppure ferma tutto e correggi subito?") },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { enqueueBusySend(busyChoice) }) { Text("Accoda") }
                        TextButton(onClick = { correctNow(busyChoice) }) { Text("Ferma e correggi") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSendChoiceDialog = false; pendingBusySend = null }) { Text("Annulla") }
                }
            )
        }
        Composer(
            value = state.draft,
            attachments = state.pendingAttachments,
            onValueChange = { state.draft = it },
            onAttachImage = { filePicker.launch("*/*") },
            onTakePhoto = {
                val directory = File(context.cacheDir, "attachments").apply { mkdirs() }
                val file = File(directory, "foto-${System.currentTimeMillis()}.jpg")
                photoUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                photoUri?.let { photoLauncher.launch(it) }
            },
            onScanDocument = {
                val directory = File(context.cacheDir, "attachments").apply { mkdirs() }
                val file = File(directory, "scan-${System.currentTimeMillis()}.jpg")
                scanUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                scanUri?.let { scanLauncher.launch(it) }
            },
            onRemoveAttachment = { state.pendingAttachments.remove(it) },
            reasoningEffort = state.chatReasoningEffort,
            reasoningOptions = reasoningLadder,
            onReasoningChange = {
                state.chatReasoningEffort = it
                persistChatOverrides(context, state, preserveRemoteContinuity)
            },
            quickPrompt = quickPrompt,
            onQuickPromptConsumed = { quickPrompt = null },
            onSend = {
                var text = state.draft.trim()
        if (archivedBotWithoutContext) {
                    state.messages.add(
                        ChatMessage(
                            "Hermes Hub",
                            "Chat bot archiviata: riaprila da Bot Hermes",
                            fromUser = false,
                            isAction = true
                        )
                    )
                    return@Composer
                }
                val sendCid = state.activeConversationId
                val sendBusy = state.sending || state.activeStreamJob != null
                if ((text.isNotEmpty() || state.pendingAttachments.isNotEmpty()) && sendBusy && sendCid != null) {
                    // Turno in corso: l'utente decide per ogni invio (dialog Accoda/Correggi).
                    pendingBusySend = PendingBusySend(text, state.pendingAttachments.toList())
                    showSendChoiceDialog = true
                    return@Composer
                }
                if ((text.isNotEmpty() || state.pendingAttachments.isNotEmpty()) && !state.sending && state.activeStreamJob == null) {
                    // No fallback prompt required when only sending attachments
                    val attachments = state.pendingAttachments.toList()
                    state.pendingAttachments.clear()
                    val displayText = if (attachments.isEmpty()) {
                        text
                    } else {
                        text.ifBlank { "Media condiviso." }
                    }
                    val localHistory = state.messages.toMutableList()
                    // Stessa istanza con blocchi: il messaggio salvato conserva
                    // le immagini anche dopo il riavvio (il payload gateway usa
                    // solo il testo, i blocchi non alterano la richiesta).
                    val userMessage = ChatMessage("Tu", displayText, true, visualBlocks = createLocalAttachmentBlocks(attachments))
                    localHistory.add(userMessage)

                    state.messages.add(userMessage)
                    state.draft = ""
                    // Marca inizio turno: lo stop cancellera anche i job media nati dopo.
                    state.lastTurnStartMs = System.currentTimeMillis()
                    // Cronologia presente (messaggio utente in lista): il reattach
                    // DONE puo scrivere qui dentro da ora in poi.
                    historyLoadedCid = state.activeConversationId
                    val streamCid = state.activeConversationId
                        ?: "conv_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
                    state.activeConversationId = streamCid
                    // Un invio sopra uno stream esistente deve prima cancellarlo,
                    // altrimenti il vecchio collector resta orfano e la sua finally
                    // ripulisce lo stato del nuovo stream. Bug del blocco post-stop.
                    state.activeStreams[streamCid]?.job?.cancel()
                    state.activeStreams[streamCid] = ActiveStreamState(StreamingState(), null)

                    val job = HermesStreamRuntime.scope.launch {
                        val collectorJob = coroutineContext[kotlinx.coroutines.Job]
                        var localState = StreamingState()
                        val mode = state.mode
                        val convId = streamCid
                        val prevId = state.previousResponseId
                        var interrupted = false
                        var lastCheckpointAt = 0L
                        var boundRunIdForTurn: String? = null
                        val rawEvents = mutableListOf<HermesRawEvent>()
                        val initialConversation = withContext(NonCancellable + Dispatchers.IO) {
                            saveConversationSnapshot(
                                context = context,
                                conversationId = convId,
                                mode = mode,
                                prompt = displayText,
                                messages = localHistory.toList(),
                                source = "Hermes in corso",
                                responseId = prevId,
                                projectId = settings.activeProjectId,
                                preserveRemoteContinuity = preserveRemoteContinuity,
                                syncAfterSave = false
                            )
                        }
                        // Mai rinominare la chat condivisa col desktop: titolo e
                        // rename restano quelli esistenti. Mai rinominare una
                        // bot chat canonica: il titolo esatto "Bot Chat" e
                        // l'identita del registro, cambiarlo forkerebbe.
                        val shouldGenerateTitle = initialConversation.title == UNTITLED_CHAT_TITLE &&
                            !preserveRemoteContinuity && botProfile.isNullOrBlank()
                        val persistedStreamCid = initialConversation.id
                        if (persistedStreamCid != streamCid) {
                            state.activeStreams.remove(streamCid)?.let { state.activeStreams[persistedStreamCid] = it }
                            if (state.activeConversationId == streamCid) state.activeConversationId = persistedStreamCid
                        }
                        val activeStreamCid = persistedStreamCid
                        val initialActiveState = state.activeStreams[streamCid] ?: ActiveStreamState(null, null)
                        state.activeStreams[activeStreamCid] = initialActiveState.copy(streamingState = localState, job = coroutineContext[kotlinx.coroutines.Job])

                        // Fast path media: con allegati si tenta /jobs/smart prima del
                        // turn agentico (triage -> prompt-only -> submit). Rifiutato o
                        // fallito -> flusso normale invariato.
                        var smartJob: SmartAccepted? = null
                        var smartHandled = false
                        var smartStatus: ChatMessage? = null
                        if (attachments.isNotEmpty()) {
                            smartJob = trySmartMediaSend(
                                context, settings, botApiKey, botProfile,
                                botMultiplexEnabled, botAllowCompatAuth, text, attachments
                            )
                            // Guardia invio globale (activeStreamJob non viene mai
                            // settato dal flusso normale): secondo invio bloccato
                            // finche il poll smart e attivo.
                            if (smartJob != null) state.activeStreamJob = collectorJob
                        }

                        // Percorso primario: Sessions API quando capability presente, altriment legacy.
                        // Nessun fallback invisibile su 401/403 profile scope.
                        // Chat bot canonica: botSessionId e la forever-chat condivisa
                        // (mai ensure/crea: si invia nella stessa sessione del desktop).
                        val capsSnapshot = state.chatCapabilities
                        val canonicalBotSession =
                            if (!botProfile.isNullOrBlank() && !botSessionId.isNullOrBlank()) botSessionId else null
                        val useSessions = capsSnapshot?.supportsModernSessions() == true &&
                            (botSessionId.isNullOrBlank() || canonicalBotSession != null)
                        var sessionIdForTurn: String? = null
                        // La forever-chat canonica esiste di certo (aperta e con
                        // transcript letta): non serve il gate capabilities per
                        // inviarci — eventuali errori arrivano veri dal server.
                        if (smartJob == null && (useSessions || canonicalBotSession != null)) {
                            if (canonicalBotSession != null) {
                                state.sessionRoute = "sessions"
                                sessionIdForTurn = canonicalBotSession
                                // La forever-chat non si binda come sessione
                                // effimera, ma hermesSessionId serve al turno
                                // (binding run, checkpoint). Niente saveSessionBinding.
                                state.hermesSessionId = canonicalBotSession
                            } else {
                            sessionIdForTurn = try {
                                withContext(Dispatchers.IO) {
                                    ensureHermesChatSession(
                                        context, botSettings, botApiKey, activeStreamCid,
                                        botProfile, botMultiplexEnabled, capsSnapshot, displayText.take(WorkLimits.TRUNC_80)
                                    )
                                }
                            } catch (se: SecurityException) {
                                localState = localState.applyEvent(
                                    ChatStreamEvent.Error(se.message ?: "Hermes ha rifiutato la chiave per il profilo.")
                                )
                                if (state.activeConversationId == activeStreamCid) state.streamingState = localState
                                null
                            }
                            if (sessionIdForTurn != null) {
                                state.hermesSessionId = sessionIdForTurn
                                withContext(NonCancellable + Dispatchers.IO) {
                                    saveSessionBinding(context, activeStreamCid, botProfile, sessionIdForTurn)
                                }
                            }
                            }
                        } else {
                            state.sessionRoute = "legacy"
                        }
                        val effModel = state.chatModelOverride.ifBlank { botSettings.model }
                        val effProvider = state.chatProviderOverride.ifBlank { botSettings.provider }
                        val effReasoning = state.chatReasoningEffort.ifBlank { botSettings.reasoningEffort }.ifBlank {
                            // Auto reale: se il server pubblicizza "auto" lo inviamo
                            // esplicito, altrimenti omettiamo (default server).
                            if (capsSnapshot?.reasoningEfforts?.any { it.equals("auto", ignoreCase = true) } == true) "auto" else ""
                        }

                        suspend fun collectFlow(flow: kotlinx.coroutines.flow.Flow<ChatStreamEvent>) {
                            flow.collect { event ->
                                if (event is ChatStreamEvent.RunId && event.id.isNotBlank() && boundRunIdForTurn != event.id) {
                                    // La run vive sul server: indirizzo persistito subito, prima ancora
                                    // di sapere come finira'. Se il client muore, il service/notifica e
                                    // il re-attach la ritrovano da qui. Solo il terminale reale cancella.
                                    boundRunIdForTurn = event.id
                                    val binding = ActiveWorkBinding(
                                        conversationId = activeStreamCid,
                                        runId = event.id,
                                        sessionId = sessionIdForTurn ?: state.hermesSessionId,
                                        goal = displayText.take(WorkLimits.TRUNC_140),
                                        startedAtMs = System.currentTimeMillis()
                                    )
                                    withContext(Dispatchers.IO) { saveActiveWorkBinding(context, binding) }
                                    maybeStartBackgroundWork(context, settings, binding, botProfile, botMultiplexEnabled) {
                                        notificationsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                if (event is ChatStreamEvent.ApprovalResolved) {
                                    // Risoluzione già applicata allo stato via applyEvent; notifica leggera.
                                }
                                if (event is ChatStreamEvent.RawHermesEvent) {
                                    if (SHOW_RAW_HERMES_EVENTS_IN_CHAT) {
                                        rawEvents += safeRawHermesEvent()
                                        if (rawEvents.size > 200) {
                                            rawEvents.subList(0, rawEvents.size - 200).clear()
                                        }
                                    } else {
                                        // Applica comunque metadata/run tracking senza mostrare raw.
                                        localState = localState.applyEvent(event)
                                        if (state.activeConversationId == activeStreamCid) {
                                            state.streamingState = localState
                                        }
                                        return@collect
                                    }
                                }
                                localState = localState.applyEvent(event)
                                if (state.activeConversationId == activeStreamCid) {
                                    state.streamingState = localState
                                } else {
                                    val existing = state.activeStreams[activeStreamCid]
                                    if (existing != null) {
                                        state.activeStreams[activeStreamCid] = existing.copy(streamingState = localState)
                                    }
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastCheckpointAt >= STREAMING_CHECKPOINT_INTERVAL_MS &&
                                    (localState.activityTimeline.isNotEmpty() || localState.text.isNotBlank() || localState.visualBlocks.isNotEmpty())) {
                                    lastCheckpointAt = now
                                    withContext(Dispatchers.IO) {
                                        saveConversationSnapshot(
                                            context = context,
                                            conversationId = activeStreamCid,
                                            mode = mode,
                                            prompt = displayText,
                                            messages = localHistory.toList() + ChatMessage(
                                                "Hermes",
                                                localState.text.streamingCheckpointPreview().ifBlank { "Hermes sta lavorando..." },
                                                fromUser = false,
                                                thinking = localState.thinking,
                                                activityTimeline = localState.activityTimeline,
                                                visualBlocksVersion = localState.visualBlocksVersion,
                                                visualBlocks = localState.visualBlocks,
                                                stats = localState.stats,
                                                rawEvents = rawEvents.toList()
                                            ),
                                            source = if (sessionIdForTurn != null) "Sessione Hermes" else "Hermes in corso",
                                            responseId = localState.responseId ?: prevId,
                                            hermesSessionId = sessionIdForTurn ?: state.hermesSessionId,
                                            modelOverride = state.chatModelOverride,
                                            providerOverride = state.chatProviderOverride,
                                            reasoningEffort = state.chatReasoningEffort,
                                            preserveRemoteContinuity = preserveRemoteContinuity,
                                            syncAfterSave = false
                                        )
                                    }
                                }
                            }
                        }

                        try {
                            val smartKey = loadGatewaySecret(context)?.takeIf { it.isNotBlank() }
                            if (smartJob != null && smartKey == null) {
                                // Chiave sparita a meta strada: torna al flusso normale
                                // (lo snapshot attachments e intatto, niente hang).
                                smartJob = null
                            }
                            if (smartJob != null && smartKey != null) {
                                smartHandled = runSmartCompletion(
                                    context, state, settings,
                                    gpuManagerBase(settings.gatewayUrl), smartKey,
                                    activeStreamCid, localHistory.toList(), mode, displayText, prevId,
                                    smartJob, attachments
                                ) { smartStatus = it }
                            } else if (sessionIdForTurn != null) {
                                val sessionSettings = botSettings.copy(
                                    model = effModel,
                                    provider = effProvider,
                                    reasoningEffort = effReasoning
                                )
                                collectFlow(
                                    streamHermesSessionChat(
                                        sessionSettings,
                                        sessionIdForTurn,
                                        text,
                                        botApiKey,
                                        botProfile,
                                        botMultiplexEnabled,
                                        model = effModel,
                                        provider = effProvider.takeIf { it.isNotBlank() && !it.equals("hermes-agent", true) },
                                        modelOptions = buildHermesModelOptions(effReasoning, botSettings.serviceTier, capsSnapshot),
                                        sessionKey = botSettings.hermesSessionKey.takeIf { isValidHermesSessionKey(it) },
                                        allowCompatAuth = botAllowCompatAuth
                                    )
                                )
                            } else if (canonicalBotSession != null && localState.error == null) {
                                // Forever-chat canonica prima del fallback legacy (es. smart
                                // declinato dopo aver saltato l'ensure): esiste di certo,
                                // si prova l'invio diretto. Niente fallback legacy silenzioso
                                // (scriverebbe fuori dalla chat condivisa);
                                // eventuali errori arrivano veri dal server.
                                sessionIdForTurn = canonicalBotSession
                                state.sessionRoute = "sessions"
                                state.hermesSessionId = canonicalBotSession
                                collectFlow(
                                    streamHermesSessionChat(
                                        botSettings.copy(model = effModel, provider = effProvider, reasoningEffort = effReasoning),
                                        canonicalBotSession,
                                        text,
                                        botApiKey,
                                        botProfile,
                                        botMultiplexEnabled,
                                        model = effModel,
                                        provider = effProvider.takeIf { it.isNotBlank() && !it.equals("hermes-agent", true) },
                                        modelOptions = buildHermesModelOptions(effReasoning, botSettings.serviceTier, capsSnapshot),
                                        sessionKey = botSettings.hermesSessionKey.takeIf { isValidHermesSessionKey(it) },
                                        allowCompatAuth = botAllowCompatAuth
                                    )
                                )
                            } else if (useSessions && localState.error == null) {
                                // Sessions dichiarate ma creazione fallita: errore esplicito, un solo fallback legacy
                                // solo se non è un problema auth/profile.
                                localState = localState.applyEvent(
                                    ChatStreamEvent.Status("Sessions non disponibili, fallback legacy esplicito...")
                                )
                                if (state.activeConversationId == activeStreamCid) state.streamingState = localState
                                state.sessionRoute = "legacy-fallback"
                                collectFlow(
                                    streamChatRequest(
                                        botSettings.copy(model = effModel, provider = effProvider, reasoningEffort = effReasoning),
                                        mode,
                                        text,
                                        localHistory.takeLast(CHAT_HISTORY_MAX_MESSAGES).toList(),
                                        activeStreamCid,
                                        prevId,
                                        attachments,
                                        botApiKey,
                                        botProfile,
                                        botSessionId,
                                        botMultiplexEnabled,
                                        botAllowCompatAuth
                                    )
                                )
                            } else if (localState.error == null) {
                                collectFlow(
                                    streamChatRequest(
                                        botSettings.copy(model = effModel, provider = effProvider, reasoningEffort = effReasoning),
                                        mode,
                                        text,
                                        localHistory.takeLast(CHAT_HISTORY_MAX_MESSAGES).toList(),
                                        activeStreamCid,
                                        prevId,
                                        attachments,
                                        botApiKey,
                                        botProfile,
                                        botSessionId,
                                        botMultiplexEnabled,
                                        botAllowCompatAuth
                                    )
                                )
                            }
                        } catch (_: CancellationException) {
                            interrupted = true
                            if (!smartHandled) {
                                // Stop prima/durante il fast path (F7: anche con smartJob
                                // ancora null): via lo stato, allegati ripristinati
                                // senza duplicati, job cancellato se esiste.
                                smartStatus?.let { state.messages.remove(it) }
                                state.pendingAttachments.addAll(
                                    attachments.filter { it !in state.pendingAttachments }
                                )
                                val sJob = smartJob
                                if (sJob != null) {
                                    val sKey = loadGatewaySecret(context)?.takeIf { it.isNotBlank() }
                                    if (sKey != null) {
                                        cancelSmartJob(
                                            gpuManagerBase(settings.gatewayUrl), sKey, sJob.jobId
                                        )
                                    }
                                }
                            }
                        } catch (ex: Exception) {
                            if (!smartHandled) {
                                // Throw non-cancel prima/durante il fast path: come sopra,
                                // senza lasciare status orfani.
                                smartStatus?.let { state.messages.remove(it) }
                                state.pendingAttachments.addAll(
                                    attachments.filter { it !in state.pendingAttachments }
                                )
                            }
                            val message = ex.message?.takeIf { it.isNotBlank() } ?: ex.javaClass.simpleName
                            localState = localState.applyEvent(ChatStreamEvent.Error("Errore runtime Hermes: $message"))
                            if (state.activeConversationId == activeStreamCid) {
                                state.streamingState = localState
                            } else {
                                state.activeStreams[activeStreamCid]?.let {
                                    state.activeStreams[activeStreamCid] = it.copy(streamingState = localState)
                                }
                            }
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
                                finalState.error != null && partialText.isNotEmpty() -> "$partialText\n\n_Risposta troncata per errore: ${finalState.error}._"
                                else -> finalState.text.ifEmpty { finalState.error ?: "" }
                            }

                            val serverTerminalRun = finalState.runStatus.lowercase() in setOf("completed", "failed", "cancelled")
                            if (boundRunIdForTurn != null && !interrupted) {
                                // Mai su stop locale (interrupted): il POST dello stop decide
                                // (clear), e ri-salvare qui resusciterebbe il binding in gara.
                                if (serverTerminalRun) {
                                    // Terminale reale dal server: niente da continuare, pulizia.
                                    withContext(NonCancellable + Dispatchers.IO) { clearActiveWorkBinding(context, activeStreamCid) }
                                    HermesWorkService.stop(context, boundRunIdForTurn!!)
                                    if (state.backgroundWork?.runId == boundRunIdForTurn) state.backgroundWork = null
                                } else {
                                    // Stream finito senza terminale server (kill client, rete, stop
                                    // locale non confermato): la run CONTINUA sul gateway grazie a
                                    // continue_on_disconnect. Il binding resta, il service polla.
                                    val keepBinding = ActiveWorkBinding(
                                        conversationId = activeStreamCid,
                                        runId = boundRunIdForTurn!!,
                                        sessionId = sessionIdForTurn ?: state.hermesSessionId,
                                        goal = displayText.take(WorkLimits.TRUNC_140),
                                        startedAtMs = System.currentTimeMillis()
                                    )
                                    withContext(NonCancellable + Dispatchers.IO) { saveActiveWorkBinding(context, keepBinding) }
                                    maybeStartBackgroundWork(context, settings, keepBinding, botProfile, botMultiplexEnabled, requestNotificationsPermission = null)
                                }
                            }

                            // Fast path gestito: messaggi + snapshot gia fatti in
                            // runSmartCompletion, qui solo cleanup sotto.
                            if (!smartHandled) {
                            val newMessagesToAppend = mutableListOf<ChatMessage>()

                            if (finalState.activityTimeline.isNotEmpty() || finalText.isNotEmpty() || finalState.visualBlocks.isNotEmpty()) {
                                newMessagesToAppend.add(
                                    ChatMessage(
                                        if (interrupted && partialText.isEmpty()) "Stato" else "Hermes",
                                        finalText,
                                        fromUser = false,
                                        isAction = interrupted && partialText.isEmpty(),
                                        thinking = finalState.thinking,
                                        activityTimeline = finalState.activityTimeline,
                                        visualBlocksVersion = finalState.visualBlocksVersion,
                                        visualBlocks = finalState.visualBlocks,
                                        stats = finalState.stats,
                                        rawEvents = rawEvents.toList()
                                    )
                                )
                            }
                            if (finalState.error != null && finalText.isEmpty()) {
                                newMessagesToAppend.add(ChatMessage("Stato", finalState.error, fromUser = false, isAction = true))
                            }
                            val workspaceKind = if (!interrupted && mode == "Agente") detectWorkspaceIntent(text) else null
                            if (workspaceKind != null) {
                                val workspaceResult = sendWorkspaceRunRequest(settings, workspaceKind, text, loadGatewaySecret(context))
                                withContext(Dispatchers.IO) {
                                    saveWorkspaceRequest(
                                        context = context,
                                        kind = workspaceKind,
                                        prompt = displayText,
                                        result = workspaceResult.result.ifBlank { finalText },
                                        source = workspaceResult.source,
                                        status = workspaceResult.status,
                                        remoteId = workspaceResult.remoteId,
                                        title = workspaceResult.title.ifBlank { makeTitle(text) },
                                        streamUrl = workspaceResult.streamUrl,
                                        downloadUrl = workspaceResult.downloadUrl
                                    )
                                }
                                newMessagesToAppend.add(
                                    ChatMessage(
                                        "Hermes Hub",
                                        "${workspaceKind}: aggiunto alla sezione dedicata. ${workspaceResult.status}",
                                        fromUser = false,
                                        isAction = true
                                    )
                                )
                            }

                            localHistory.addAll(newMessagesToAppend)
                            if (state.activeConversationId == activeStreamCid) {
                                state.messages.addAll(newMessagesToAppend)
                            }
                            } // fine append normali (smart: gia fatti in runSmartCompletion)

                            // Snapshot solo flusso normale: lo smart persiste da se con
                            // i messaggi giusti (mai state.messages globale, che dopo
                            // un cambio chat apparterrebbe all'altra conversazione).
                            if (smartHandled) {
                                // Cleanup fast path: solo reset stato.
                                if (state.activeConversationId == activeStreamCid) {
                                    val current = state.activeStreams[activeStreamCid]
                                    if (current == null || current.job == null || current.job === collectorJob) {
                                        state.streamingState = null
                                        state.activeStreamJob = null
                                    }
                                } else {
                                    state.activeStreams.remove(activeStreamCid)
                                }
                            } else {
                                // Snapshot finale: su chat collegata unisci i messaggi
                                // remoti arrivati durante il turno (niente turni persi).
                                val finalMessages = if (preserveRemoteContinuity) {
                                    val fresh = withContext(NonCancellable + Dispatchers.IO) {
                                        loadConversation(context, activeStreamCid)
                                    }
                                    unionChatMessages(
                                        fresh?.messages.orEmpty(),
                                        localHistory.toList()
                                    )
                                } else {
                                    localHistory.toList()
                                }
                                val saved = withContext(NonCancellable + Dispatchers.IO) {
                                    saveConversationSnapshot(
                                        context = context,
                                        conversationId = activeStreamCid,
                                        mode = mode,
                                        prompt = displayText,
                                        messages = finalMessages,
                                        source = if (interrupted) "Hermes interrotto" else if (finalState.error != null) "Errore Hermes" else if (state.sessionRoute == "sessions") "Sessione Hermes" else "Hermes",
                                        responseId = finalState.responseId ?: prevId,
                                        hermesSessionId = state.hermesSessionId,
                                        modelOverride = state.chatModelOverride,
                                        providerOverride = state.chatProviderOverride,
                                        reasoningEffort = state.chatReasoningEffort,
                                        preserveRemoteContinuity = preserveRemoteContinuity,
                                        // Su stop non spingere subito sul gateway: la rete in finally
                                        // allungherebbe lo sblocco del composer; ci pensa l'autosync.
                                        syncAfterSave = !interrupted
                                    )
                                }
                                if (state.activeConversationId == activeStreamCid) {
                                    // Ripulisci solo se nessun invio successivo ha preso il posto di
                                    // questo stream: altrimenti cancelleresti lo stato del nuovo turno.
                                    val current = state.activeStreams[activeStreamCid]
                                    if (current == null || current.job == null || current.job === collectorJob) {
                                        state.activeConversationId = saved.id
                                        state.previousResponseId = saved.previousResponseId
                                        state.streamingState = null
                                        state.activeStreamJob = null
                                    }
                                } else {
                                    state.activeStreams.remove(activeStreamCid)
                                }
                                if (shouldGenerateTitle && !interrupted && finalState.error == null && finalText.isNotBlank()) {
                                    val generatedTitle = generateConversationTitle(
                                        settings = settings,
                                        firstPrompt = displayText,
                                        firstAnswer = finalText,
                                        apiKey = loadGatewaySecret(context)
                                    )
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        // Propaga rename alla sessione server quando disponibile.
                                        // Se il server rifiuta/fallisce, il titolo resta da generare
                                        // al prossimo turno: niente falso successo, niente divergenza.
                                        runCatching {
                                            renameHermesSessionForConversation(
                                                context, botSettings, botApiKey, saved.id,
                                                generatedTitle, botProfile, botMultiplexEnabled
                                            )
                                        }.getOrNull()
                                    }
                                }
                            }
                            // Turno finito senza stop: eventuale coda parte da sola.
                            if (!interrupted) drainQueuedPrompt()
                        }
                    }
                    state.activeStreams[streamCid] = (state.activeStreams[streamCid] ?: ActiveStreamState(StreamingState(), null)).copy(job = job)
                }
            },
            onStop = {
                // Solo live esterno, nessuno stream locale e nessun run
                // posseduto: niente interrupt server possibile (il turno
                // vive sul desktop), solo avviso onesto. Il quadrato resta
                // perche segnala che il bot sta lavorando.
                if (!state.sending) {
                    Toast.makeText(context, "Turno avviato dal desktop: stop solo dal desktop.", Toast.LENGTH_SHORT).show()
                    return@Composer
                }
                val activeRunId = state.streamingState?.activeRunId
                // Stop VERO: cancella il collector locale (prima non lo faceva:
                // la generazione continuava e il composer restava bloccato).
                val cid = state.activeConversationId
                state.activeStreams[cid]?.job?.cancel()
                state.activeStreamJob?.cancel()
                // Stop cancella anche la coda prompt (niente partenze a sorpresa).
                state.queuedPrompts.removeAll { it.conversationId == cid }
                // Job media nati in questo turno (invisibili al client): cancellali,
                // ma solo se non ci sono altri stream attivi a usar le GPU.
                if (state.activeStreams.size <= 1) {
                    val stopKey = loadGatewaySecret(context)?.takeIf { it.isNotBlank() }
                    if (stopKey != null) {
                        HermesStreamRuntime.scope.launch {
                            cancelManagerJobsSince(
                                gpuManagerBase(settings.gatewayUrl), stopKey, state.lastTurnStartMs
                            )
                        }
                    }
                }
                // Aggiornamento UI immediato + vero POST /v1/runs/{id}/stop (non solo cancel locale).
                state.streamingState = state.streamingState?.copy(
                    status = "Interruzione richiesta. Chiudo stream Hermes...",
                    error = null
                )
                if (!activeRunId.isNullOrBlank()) {
                    HermesStreamRuntime.scope.launch {
                        val (code, body) = runCatching {
                            stopHermesRun(botSettings, activeRunId, botApiKey, botProfile, botMultiplexEnabled, botAllowCompatAuth)
                        }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) }
                        if (code !in 200..299) {
                            val msg = when (code) {
                                401, 403 -> "Stop rifiutato (HTTP $code): chiave non valida per il profilo."
                                404 -> "Run non trovato sul server (404)."
                                else -> "Stop HTTP $code: ${body.take(160)}"
                            }
                            if (state.activeConversationId == cid) {
                                state.messages.add(ChatMessage("Hermes Hub", msg, fromUser = false, isAction = true))
                            }
                        } else {
                            // Stop confermato dal server: niente da continuare, pulizia subito.
                            // (Se il POST fallisce, il binding resta e sara' il poll a decidere.)
                            // Usa il cid catturato al tap, non quello corrente: se nel mentre
                            // l'utente ha cambiato chat, non toccare il binding innocente.
                            runCatching {
                                if (cid != null) clearActiveWorkBinding(context, cid)
                                HermesWorkService.stop(context, activeRunId)
                                if (state.backgroundWork?.runId == activeRunId) state.backgroundWork = null
                            }
                        }
                    }
                }
                state.activeStreamJob?.cancel()
            },
            isBusy = (state.sending || botLive?.running == true) && state.streamingState?.status?.contains("Interruzione") != true,
            isRecordingVoiceNote = state.isRecordingVoiceNote,
            onToggleVoiceNote = {
                if (!state.isRecordingVoiceNote) {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        return@Composer
                    }
                    startVoiceRecording()
                } else {
                    val recorder = mediaRecorder
                    mediaRecorder = null
                    state.isRecordingVoiceNote = false
                    val file = state.tempVoiceNoteFile
                    val recordMs = System.currentTimeMillis() - voiceRecordStartMs
                    voiceRecordStartMs = 0L
                    if (recordMs in 1..699) {
                        // Troppo breve: stop() lancerebbe RuntimeException e il file
                        // sarebbe corrotto. Scarta con messaggio esplicito.
                        runCatching { recorder?.reset() }
                        runCatching { recorder?.release() }
                        file?.let { runCatching { it.delete() } }
                        state.tempVoiceNoteFile = null
                        state.messages.add(ChatMessage("Errore Voce", "Registrazione troppo breve, riprova.", fromUser = false, isAction = true))
                        return@Composer
                    }
                    try {
                        recorder?.stop()
                        if (file != null && file.exists()) {
                            scope.launch {
                                try {
                                    val secret = loadGatewaySecret(context) ?: ""
                                    val baseUrl = settings.gatewayUrl.trimEnd('/')

                                    val requestBody = okhttp3.MultipartBody.Builder()
                                        .setType(okhttp3.MultipartBody.FORM)
                                        .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaTypeOrNull()))
                                        .build()

                                    val requestBuilder = okhttp3.Request.Builder()
                                        .url("$baseUrl/audio/transcriptions")
                                        .post(requestBody)

                                    HermesHubProtocol.addCorrelationHeaders(
                                        requestBuilder,
                                        HermesHubProtocol.newCorrelationContext()
                                    )

                                    if (secret.isNotEmpty()) {
                                        requestBuilder.header("Authorization", "Bearer $secret")
                                    }

                                    withContext(Dispatchers.IO) { voiceNoteHttpClient.newCall(requestBuilder.build()).execute() }.use { response ->
                                        if (response.isSuccessful) {
                                            val responseStr = response.body.byteStream().readUtf8Bounded()
                                            val json = org.json.JSONObject(responseStr)
                                            val text = json.optString("text", "")
                                            if (text.isNotEmpty()) {
                                                if (state.draft.isNotEmpty() && !state.draft.endsWith(" ")) {
                                                    state.draft += " "
                                                }
                                                state.draft += text
                                            }
                                        } else {
                                            state.messages.add(ChatMessage("Errore Voce", "Trascrizione fallita: ${response.code}", fromUser = false, isAction = true))
                                        }
                                    }
                                } catch (_: Exception) {
                                    state.messages.add(ChatMessage("Errore Voce", "Invio audio fallito.", fromUser = false, isAction = true))
                                } finally {
                                    file.delete()
                                    state.tempVoiceNoteFile = null
                                }
                            }
                        }
                    } catch (e: Exception) {
                        file?.let { runCatching { it.delete() } }
                        state.tempVoiceNoteFile = null
                        state.messages.add(ChatMessage("Errore Voce", "Errore arresto registrazione.", fromUser = false, isAction = true))
                    } finally {
                        runCatching { recorder?.release() }
                    }
                }
            }
        )
    }
}

internal fun executeSlashCommand(
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
        SlashAction.OpenHardware -> onSwitchTab(Tab.Hardware)
        SlashAction.OpenCron -> onSwitchTab(Tab.Cron)
        SlashAction.OpenArchive -> onSwitchTab(Tab.Archive)
        SlashAction.OpenVideo -> onSwitchTab(Tab.Video)
        SlashAction.OpenNews -> onSwitchTab(Tab.News)
        SlashAction.OpenSettings -> onSwitchTab(Tab.Settings)
        SlashAction.OpenAbout -> onSwitchTab(Tab.Profile)
    }
}


@Composable
internal fun BotLiveBanner(statusText: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).background(AppColors.Accent, CircleShape))
        ShimmerText(if (statusText.isBlank()) "Sta lavorando…" else statusText, enabled = true)
    }
}

@Composable
internal fun BotEmptyState(
    displayName: String?,
    opening: Boolean,
    unreachable: Boolean = false,
    detail: String? = null,
    onRetry: (() -> Unit)? = null
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = when {
                opening -> "Apro la chat…"
                unreachable -> "Bot non raggiungibile"
                else -> displayName ?: "Bot"
            },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 27.sp,
            lineHeight = 32.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        val detailCapped = detail?.takeIf { it.isNotBlank() }?.take(80)
        Text(
            text = when {
                opening -> "Recupero la storia condivisa con Hermes desktop."
                unreachable -> if (detailCapped == null) {
                    "Il gateway non risponde: controlla la connessione e riprova dal roster."
                } else {
                    "Il gateway non risponde ($detailCapped): controlla la connessione e riprova dal roster."
                }
                else -> "Questa e la chat persistente del bot: la stessa su telefono e desktop. Scrivi per iniziare."
            },
            color = AppColors.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        if (unreachable && onRetry != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Riprova")
            }
        }
    }
}

@Composable
internal fun EmptyState(onPrompt: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Image(
            painter = painterResource(id = R.drawable.chatclaw_logo),
            contentDescription = "Logo Hermes Hub",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(24.dp))
        )
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = "Che vuoi fare oggi?",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 27.sp,
            lineHeight = 32.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Chat live, cron e strumenti Hermes Agent sul tuo home-server.",
            color = AppColors.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(28.dp))
        Text("OPERAZIONI RAPIDE", color = AppColors.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
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

@Composable
internal fun SuggestionButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = AppColors.Panel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.Border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(7.dp).background(AppColors.Accent, CircleShape))
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Text("›", color = AppColors.Faint, fontSize = 22.sp)
        }
    }
}

@Composable
internal fun PremiumPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(content = content)
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.78f), thickness = 1.dp)
    }
}

@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    colors: Any? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    PremiumPanel(modifier = modifier, content = content)
}


@Composable
internal fun RawHermesEventsView(events: List<HermesRawEvent>) {
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
internal fun ArchivedActivityDisclosure(
    persisted: List<AssistantActivity>,
    legacyThinking: String,
    showToolCalls: Boolean
) {
    val timeline = remember(persisted, legacyThinking, showToolCalls) {
        val compatible = if (persisted.isEmpty() && legacyThinking.isNotBlank()) {
            listOf(AssistantActivity(AssistantActivity.Kind.Reasoning, text = legacyThinking))
        } else persisted
        compatible.filter { showToolCalls || it.kind != AssistantActivity.Kind.Tool }
    }
    if (timeline.isNotEmpty()) HermesActivityDisclosure(timeline)
}









@Composable
internal fun ChatModelSessionBar(
    state: ChatStateHolder,
    settings: AppSettings,
    context: Context,
    botSettings: AppSettings,
    botApiKey: String?,
    botProfile: String?,
    botMultiplexEnabled: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    autoApproveMode: String = "off"
) {
    val caps = state.chatCapabilities
    var showSteerDialog by remember { mutableStateOf(false) }
    var steerText by remember { mutableStateOf("") }
    var steerStatus by remember { mutableStateOf("") }
    val streaming = state.streamingState
    val runId = streaming?.activeRunId
    val steerable = !runId.isNullOrBlank() && streaming?.isDone == false &&
        (caps?.supportsSteer() ?: false) && streaming?.runStatus !in listOf("completed", "failed", "cancelled")

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val routeLabel = when (state.sessionRoute) {
                "sessions" -> "Sessione Hermes${state.hermesSessionId?.take(8)?.let { " · ${it}" }.orEmpty()}"
                "legacy-fallback" -> "Legacy (sessions non riuscite)"
                else -> "Legacy"
            }
            Text(routeLabel, color = AppColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (autoApproveMode == WorkLimits.AUTO_APPROVE_SESSION || autoApproveMode == WorkLimits.AUTO_APPROVE_ALWAYS) {
                Surface(
                    color = AppColors.Elevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AppColors.Accent)
                ) {
                    Text(
                        if (autoApproveMode == WorkLimits.AUTO_APPROVE_ALWAYS) "AUTO SEMPRE" else "AUTO SESSIONE",
                        color = AppColors.Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            if (steerable) {
                IconButton(onClick = { steerStatus = ""; showSteerDialog = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Edit, contentDescription = "Correggi run in corso", tint = AppColors.Muted, modifier = Modifier.size(20.dp)) }
            }
        }
        if (streaming?.pendingSteer?.isNotBlank() == true) {
            Text("Steer non consegnato: riproponilo come turno successivo.", color = AppColors.Muted, fontSize = 11.sp)
        }
    }

    if (showSteerDialog && runId != null) {
        AlertDialog(
            onDismissRequest = { showSteerDialog = false },
            title = { Text("Correggi run in corso") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inviata al run $runId. Consegna al prossimo tool boundary; non crea un nuovo turno.", fontSize = 12.sp, color = AppColors.Muted)
                    TextField(value = steerText, onValueChange = { steerText = it }, placeholder = { Text("Nuova istruzione...") })
                    if (steerStatus.isNotBlank()) Text(steerStatus, fontSize = 12.sp, color = AppColors.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val payload = steerText.trim()
                    if (payload.isEmpty()) {
                        steerStatus = "Testo obbligatorio."
                        return@TextButton
                    }
                    scope.launch {
                        // Singolo invio, niente retry su esito incerto.
                        val (code, body) = steerHermesRun(botSettings, runId, payload, botApiKey, botProfile, botMultiplexEnabled)
                        steerStatus = when {
                            code in 200..299 -> "Guida accodata (consegna al prossimo tool boundary)."
                            code == 409 -> "Run non più steerable (409): è terminato o in arresto."
                            code == 401 || code == 403 -> "Chiave rifiutata per questo profilo (HTTP $code)."
                            code == 404 -> "Run non trovato (404)."
                            else -> "Steer fallito: HTTP $code ${body.take(160)}"
                        }
                        if (code in 200..299) {
                            steerText = ""
                        }
                    }
                }) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Invia correzione", tint = Color.White) }
            },
            dismissButton = { IconButton(onClick = { showSteerDialog = false }) { Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = Color.White) } }
        )
    }
}

private fun persistChatOverrides(context: Context, state: ChatStateHolder, preserveRemoteContinuity: Boolean = false) {
    val cid = state.activeConversationId ?: return
    runCatching {
        val current = loadConversation(context, cid) ?: return
        saveConversationSnapshot(
            context = context,
            conversationId = cid,
            mode = "Chat",
            prompt = current.prompt,
            messages = current.messages,
            source = current.description,
            responseId = current.previousResponseId,
            hermesSessionId = state.hermesSessionId ?: current.hermesSessionId,
            modelOverride = state.chatModelOverride,
            providerOverride = state.chatProviderOverride,
            reasoningEffort = state.chatReasoningEffort,
            preserveRemoteContinuity = preserveRemoteContinuity,
            syncAfterSave = false
        )
    }
}

@Composable
internal fun BackgroundWorkBanner(
    work: BackgroundWorkUi,
    onStop: () -> Unit
) {
    val ctx = LocalContext.current
    Surface(
        color = AppColors.Elevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.Border),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = null,
                tint = AppColors.Accent,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (work.goal.isBlank()) "Hermes al lavoro in background" else "Hermes al lavoro: ${work.goal.take(WorkLimits.TRUNC_80)}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(work.statusText, color = AppColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(
                onClick = {
                    onStop()
                    Toast.makeText(ctx, "Interruzione richiesta.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = "Ferma lavoro", tint = Color.White)
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}



@Composable
internal fun ProjectsScreen(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onNewChat: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenConversation: (String) -> Unit
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val projects = remember(refreshKey) {
        loadConversations(context).filter { it.kind == "Progetto" }.sortedByDescending { it.updatedAt }
    }
    var selectedId by rememberSaveable { mutableStateOf(settings.activeProjectId.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id.orEmpty()) }
    val selected = projects.firstOrNull { it.id == selectedId }
    var title by rememberSaveable { mutableStateOf(selected?.title.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(selected?.projectInstructions.orEmpty()) }
    var status by remember { mutableStateOf("Pronto.") }

    fun edit(project: LocalConversation?) {
        selectedId = project?.id.orEmpty()
        title = project?.title.orEmpty()
        instructions = project?.projectInstructions.orEmpty()
        if (project != null) {
            onSettingsChanged(settings.withActiveProject(project))
            status = "Progetto selezionato. Contesto applicato automaticamente."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Progetti", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Scegli un nome e, se serve, un system prompt personalizzato. Il resto e' automatico.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("I tuoi progetti", color = Color.White, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { edit(null); status = "Inserisci nome e system prompt facoltativo." }) { Icon(Icons.Rounded.Add, contentDescription = "Nuovo progetto", tint = Color.White) }
                    }
                    if (projects.isEmpty()) {
                        Text("Nessun progetto.", color = AppColors.Muted)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            projects.forEach { project ->
                                VideoFeedChip(project.title, selected = project.id == selectedId) { edit(project) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (selected == null) "Nuovo progetto" else selected.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (selected?.id == settings.activeProjectId) Text("PROGETTO ATTIVO", color = AppColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    SettingsField("Nome progetto", title, { title = it })
                    SettingsField("System prompt (facoltativo)", instructions, { instructions = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = {
                            runCatching {
                                saveProjectWorkspace(
                                    context = context,
                                    projectId = selectedId.ifBlank { null },
                                    title = title,
                                    description = selected?.description.orEmpty(),
                                    workspacePath = selected?.workspacePath.orEmpty(),
                                    repositoryUrl = selected?.repositoryUrl.orEmpty(),
                                    instructions = instructions,
                                    memory = selected?.projectMemory.orEmpty(),
                                    authorizedTools = selected?.authorizedTools.orEmpty()
                                )
                            }.onSuccess { saved ->
                                selectedId = saved.id
                                refreshKey++
                                onSettingsChanged(settings.withActiveProject(saved))
                                status = "Progetto salvato e attivato."
                            }.onFailure { status = it.message ?: "Salvataggio progetto fallito." }
                        }) { Icon(Icons.Rounded.Save, contentDescription = "Salva progetto", tint = Color.White) }
                        IconButton(enabled = selected != null, onClick = {
                            selected?.let {
                                onSettingsChanged(settings.withActiveProject(it))
                                onNewChat()
                            }
                        }) { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "Nuova chat nel progetto", tint = Color.White) }
                    }
                    Text(status, color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

internal fun AppSettings.withActiveProject(project: LocalConversation): AppSettings = copy(
    activeProjectId = project.id,
    activeProjectName = project.title,
    activeProjectWorkspacePath = project.workspacePath,
    activeProjectRepositoryUrl = project.repositoryUrl,
    activeProjectInstructions = project.projectInstructions,
    activeProjectMemory = project.projectMemory,
    activeProjectTools = project.authorizedTools.joinToString("\n")
)

internal fun AppSettings.clearActiveProject(): AppSettings = copy(
    activeProjectId = "",
    activeProjectName = "",
    activeProjectWorkspacePath = "",
    activeProjectRepositoryUrl = "",
    activeProjectInstructions = "",
    activeProjectMemory = "",
    activeProjectTools = ""
)

@Composable
internal fun UniversalSearchScreen(context: Context, settings: AppSettings, onOpen: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Inserisci almeno 2 caratteri.") }
    var results by remember { mutableStateOf<List<Triple<String, String, Pair<String, String>>>>(emptyList()) }

    fun runSearch() {
        val needle = query.trim()
        if (needle.length < 2) { status = "Inserisci almeno 2 caratteri."; return }
        status = "Ricerca in corso..."
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                val list = mutableListOf<Triple<String, String, Pair<String, String>>>()
                loadConversations(context).forEach { item ->
                    val body = buildString {
                        append(item.title).append(' ').append(item.description).append(' ').append(item.prompt).append(' ')
                        append(item.projectMemory).append(' ').append(item.projectInstructions).append(' ').append(item.artifactFileName).append(' ').append(item.tags.joinToString(" "))
                        item.messages.forEach { message ->
                            append(' ').append(message.text)
                            message.rawEvents.forEach { append(' ').append(it.json) }
                            message.visualBlocks.forEach { append(' ').append(it.title).append(' ').append(it.caption).append(' ').append(it.text).append(' ').append(it.code).append(' ').append(it.filename) }
                        }
                    }
                    if (body.contains(needle, true)) list += Triple(item.kind, item.id, item.title to body.replace('\n', ' ').take(260))
                }
                val apiKey = loadGatewaySecret(context)
                loadCronJobs(settings, apiKey).first.filter { "${it.name} ${it.prompt} ${it.lastStatus}".contains(needle, true) }
                    .forEach { list += Triple("Cron", it.id, it.name to "${it.prompt} ${it.lastStatus}".take(260)) }
                loadHubNotifications(settings, apiKey, false).first.filter { "${it.title} ${it.message}".contains(needle, true) }
                    .forEach { list += Triple("Notifica", it.id, it.title to it.message.take(260)) }
                val memory = loadHubMemory(settings, apiKey).first
                val memoryText = "${memory.videoPreferences} ${memory.newsPreferences} ${memory.responseStyle} ${memory.projectRules} ${memory.generalNotes}"
                if (memoryText.contains(needle, true)) list += Triple("Memoria", "hub-memory", "Memoria Hermes" to memoryText.take(260))
                list
            }
            results = found
            status = "${found.size} risultati."
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ricerca universale", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold); Text("Chat, tool call, artifact, progetti, memoria, cron e notifiche.", color = AppColors.Muted) }
        item { Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { SettingsField("Cerca ovunque", query, { query = it }); Button(onClick = { runSearch() }) { Text("Cerca") }; Text(status, color = AppColors.Muted, fontSize = 12.sp) } } }
        items(results, key = { "${it.first}-${it.second}" }) { result ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(result.first, result.second) }, colors = CardDefaults.cardColors(containerColor = AppColors.AssistantBubble), shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("${result.first} · ${result.third.first}", color = Color.White, fontWeight = FontWeight.SemiBold); Text(result.third.second, color = AppColors.Muted, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
internal fun ArtifactLibraryScreen(
    context: Context,
    settings: AppSettings,
    onOpenConversation: (String) -> Unit,
    onRegenerate: (String) -> Unit
) {
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectFilter by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var rename by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Pronto.") }
    val artifacts = remember(refresh, query, projectFilter) {
        loadConversations(context).filter { item ->
            item.kind == "Artifact" &&
                (projectFilter.isBlank() || item.projectId.contains(projectFilter, true)) &&
                (query.isBlank() || item.title.contains(query, true) || item.artifactFileName.contains(query, true) || item.tags.any { it.contains(query, true) })
        }.sortedByDescending { it.updatedAt }
    }
    val selected = artifacts.firstOrNull { it.id == selectedId }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Artifact", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text("Output persistenti prodotti da chat, run e automazioni.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsField("Cerca nome, file o tag", query, { query = it })
                    SettingsField("Filtra progetto", projectFilter, { projectFilter = it })
                    Text("${artifacts.size} artifact", color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
        items(artifacts, key = { it.id }) { artifact ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedId = artifact.id; rename = artifact.title; tags = artifact.tags.joinToString(", ")
                },
                colors = CardDefaults.cardColors(containerColor = if (artifact.id == selectedId) AppColors.Elevated else AppColors.AssistantBubble),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(artifact.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${artifact.artifactType} · v${artifact.version} · ${artifact.projectId}", color = AppColors.Muted, fontSize = 12.sp)
                    Text(artifact.description, color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (selected != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Dettaglio · v${selected.version}", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("File: ${selected.artifactFileName}\nMIME: ${selected.artifactMimeType}\nChat: ${selected.sourceConversationId}\nRun: ${selected.sourceRunId}", color = AppColors.Muted, fontSize = 12.sp)
                        SettingsField("Nome", rename, { rename = it })
                        SettingsField("Tag", tags, { tags = it })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = {
                                saveArtifactMetadata(context, selected.id, rename, tags.split(',', ';').map { it.trim() }.filter { it.isNotBlank() })
                                refresh++; status = "Metadata salvati; sync gateway in coda."
                            }) { Icon(Icons.Rounded.Save, contentDescription = "Salva metadata", tint = Color.White) }
                            IconButton(onClick = { if (selected.sourceConversationId.isNotBlank()) onOpenConversation(selected.sourceConversationId) }) { Icon(Icons.Rounded.Visibility, contentDescription = "Anteprima origine", tint = Color.White) }
                            IconButton(onClick = {
                                if (selected.artifactUrl.isBlank()) status = "Artifact senza URL apribile." else runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, resolveHermesUrl(settings, selected.artifactUrl).toUri()))
                                }.onFailure { status = "Apertura fallita: ${it.message}" }
                            }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Apri artifact", tint = Color.White) }
                            IconButton(onClick = { onRegenerate("Rigenera artifact '${selected.title}' versione ${selected.version}, progetto ${selected.projectId}, sorgente chat ${selected.sourceConversationId}.") }) { Icon(Icons.Rounded.Refresh, contentDescription = "Rigenera artifact", tint = Color.White) }
                        }
                        Text("Versioni", color = Color.White, fontWeight = FontWeight.SemiBold)
                        val key = selected.artifactFileName.ifBlank { selected.title }
                        loadConversations(context).filter { it.kind == "Artifact" && it.artifactFileName.ifBlank { it.title }.equals(key, true) }
                            .sortedByDescending { it.version }.forEach { version ->
                                Text("v${version.version} · ${formatDateTime(version.updatedAt)} · ${version.description}", color = AppColors.Muted, fontSize = 12.sp)
                            }
                        Text(status, color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

internal fun saveArtifactMetadata(context: Context, id: String, title: String, tags: List<String>) {
    synchronized(localArchiveLock) {
        val items = loadConversations(context, includeDeleted = true).toMutableList()
        val index = items.indexOfFirst { it.id == id && it.kind == "Artifact" && it.deletedAt == null }
        if (index < 0) return
        items[index] = items[index].copy(
            title = title.trim().take(180).ifBlank { items[index].title },
            tags = tags.map { it.take(WorkLimits.TRUNC_80) }.distinctBy { it.lowercase() }.take(30),
            updatedAt = System.currentTimeMillis()
        )
        saveConversations(context, items)
    }
}

