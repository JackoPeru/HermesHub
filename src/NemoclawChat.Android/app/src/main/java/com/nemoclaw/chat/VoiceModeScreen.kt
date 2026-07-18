package com.nemoclaw.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val VoiceSampleRate = 16_000
private const val VoiceFrameMillis = 20
private const val VoiceFrameBytes = VoiceSampleRate * VoiceFrameMillis / 1000 * 2
private const val VoicePreRollFrames = 15
private const val VoiceEndSilenceMillis = 680L
private const val VoiceMaxUtteranceMillis = 18_000L

private val voiceHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

private enum class VoiceCallPhase {
    Idle,
    Connecting,
    Listening,
    Transcribing,
    Thinking,
    Speaking,
    Error
}

private data class VoiceConversationContext(
    val conversationId: String,
    var previousResponseId: String? = null
)

private object VoiceTurnController {
    var job: Job? = null
    var player: MediaPlayer? = null
    fun interrupt() { job?.cancel(); job = null; runCatching { player?.stop() }; player?.release(); player = null }
}

internal val SupportedVoiceNames = listOf("if_sara", "im_nicola")
internal const val SphereParticleShape = "sphere"
internal const val NeuralCoreParticleShape = "neural-core"
internal const val HermesHeadParticleShape = "hermes-head"
internal const val DefaultWakePhrase = "Hermes"
internal val SupportedParticleShapes = listOf(SphereParticleShape, NeuralCoreParticleShape, HermesHeadParticleShape)
internal val SupportedWakePhrases = listOf(DefaultWakePhrase, "Ehi Hermes", "Ok Hermes")
private val WakeTokenRegex = Regex("[\\p{L}\\p{N}]+")

internal object VoiceProfileEvents {
    var revision by mutableStateOf(0L)
        private set

    fun notifyChanged() {
        revision++
    }
}

internal fun particleShapeLabel(shape: String): String = when (shape) {
    NeuralCoreParticleShape -> "Nucleo neurale"
    HermesHeadParticleShape -> "Testa Hermes"
    else -> "Sfera"
}

internal data class VoiceProfile(
    val voice: String = "if_sara",
    val speed: Float = 1.08f,
    val wakeWord: Boolean = false,
    val wakePhrase: String = DefaultWakePhrase,
    val pushToTalk: Boolean = false,
    val showTranscript: Boolean = false,
    val bluetooth: Boolean = false,
    val particleShape: String = SphereParticleShape
)

internal fun loadVoiceProfile(context: Context, projectId: String): VoiceProfile {
    val preferences = context.getSharedPreferences("voice_profiles", Context.MODE_PRIVATE)
    val keySuffix = projectId.ifBlank { "general" }
    val storedVoice = preferences.getString("voice:$keySuffix", "if_sara").orEmpty()
    val safeVoice = storedVoice.takeIf { it in SupportedVoiceNames } ?: "if_sara"
    val storedParticleShape = preferences.getString("particles:$keySuffix", SphereParticleShape).orEmpty()
    val safeParticleShape = storedParticleShape.takeIf { it in SupportedParticleShapes } ?: SphereParticleShape
    val storedWakePhrase = preferences.getString("wakePhrase:$keySuffix", DefaultWakePhrase).orEmpty()
    val safeWakePhrase = normalizeWakePhrase(storedWakePhrase)
    if (safeVoice != storedVoice) preferences.edit { putString("voice:$keySuffix", safeVoice) }
    if (safeParticleShape != storedParticleShape) preferences.edit { putString("particles:$keySuffix", safeParticleShape) }
    if (safeWakePhrase != storedWakePhrase) preferences.edit { putString("wakePhrase:$keySuffix", safeWakePhrase) }
    return VoiceProfile(
        voice = safeVoice,
        speed = preferences.getFloat("speed:$keySuffix", 1.08f).coerceIn(0.75f, 1.35f),
        wakeWord = preferences.getBoolean("wake:$keySuffix", false),
        wakePhrase = safeWakePhrase,
        pushToTalk = preferences.getBoolean("ptt:$keySuffix", false),
        showTranscript = preferences.getBoolean("transcript:$keySuffix", false),
        bluetooth = preferences.getBoolean("bluetooth:$keySuffix", false),
        particleShape = safeParticleShape
    )
}

internal fun saveVoiceProfile(context: Context, projectId: String, profile: VoiceProfile) {
    val keySuffix = projectId.ifBlank { "general" }
    val safeVoice = profile.voice.takeIf { it in SupportedVoiceNames } ?: "if_sara"
    val safeParticleShape = profile.particleShape.takeIf { it in SupportedParticleShapes } ?: SphereParticleShape
    val safeWakePhrase = normalizeWakePhrase(profile.wakePhrase)
    context.getSharedPreferences("voice_profiles", Context.MODE_PRIVATE).edit {
        putString("voice:$keySuffix", safeVoice)
        putFloat("speed:$keySuffix", profile.speed.coerceIn(0.75f, 1.35f))
        putBoolean("wake:$keySuffix", profile.wakeWord)
        putString("wakePhrase:$keySuffix", safeWakePhrase)
        putBoolean("ptt:$keySuffix", profile.pushToTalk)
        putBoolean("transcript:$keySuffix", profile.showTranscript)
        putBoolean("bluetooth:$keySuffix", profile.bluetooth)
        putString("particles:$keySuffix", safeParticleShape)
    }
    VoiceProfileEvents.notifyChanged()
}

internal fun normalizeWakePhrase(value: String?): String {
    val normalized = value.orEmpty().replace('|', ' ').trim().replace(Regex("\\s+"), " ").take(48).trimEnd()
    return normalized.takeIf { WakeTokenRegex.containsMatchIn(it) } ?: DefaultWakePhrase
}

internal fun stripWakePhrase(transcript: String, wakePhrase: String?): String? {
    val phraseTokens = WakeTokenRegex.findAll(normalizeWakePhrase(wakePhrase)).toList()
    val transcriptTokens = WakeTokenRegex.findAll(transcript).toList()
    if (phraseTokens.isEmpty() || transcriptTokens.size < phraseTokens.size) return null
    val matchStart = (0..transcriptTokens.size - phraseTokens.size).firstOrNull { transcriptIndex ->
        phraseTokens.indices.all { phraseIndex ->
            wakeTokensEqual(transcriptTokens[transcriptIndex + phraseIndex].value, phraseTokens[phraseIndex].value)
        }
    } ?: return null
    val finalToken = transcriptTokens[matchStart + phraseTokens.lastIndex]
    val end = finalToken.range.last + 1
    return transcript.substring(end).trimStart(' ', ',', ':', ';', '.', '-', '!', '?')
}

private fun wakeTokensEqual(left: String, right: String): Boolean {
    val normalizedLeft = normalizeWakeToken(left)
    val normalizedRight = normalizeWakeToken(right)
    val leftIsHermes = normalizedLeft == "hermes" || normalizedLeft == "ermes"
    val rightIsHermes = normalizedRight == "hermes" || normalizedRight == "ermes"
    return normalizedLeft == normalizedRight || leftIsHermes && rightIsHermes
}

private fun normalizeWakeToken(value: String): String = java.text.Normalizer
    .normalize(value, java.text.Normalizer.Form.NFD)
    .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    .lowercase(java.util.Locale.ROOT)

internal suspend fun previewVoiceProfile(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    voice: String,
    speed: Float
) {
    val file = synthesizeVoiceFile(
        context,
        settings,
        apiKey,
        "Ciao, questa e la mia voce.",
        voice.takeIf { it in SupportedVoiceNames } ?: "if_sara",
        speed.coerceIn(0.75f, 1.35f).toDouble()
    )
    try {
        playVoiceFile(file) {}
    } finally {
        file.delete()
    }
}

@Composable
internal fun VoiceModeScreen(settings: AppSettings, apiKey: String?, autoStartToken: Long = 0L) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<ChatMessage>() }
    var callActive by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(VoiceCallPhase.Idle) }
    var status by remember { mutableStateOf("Hermes voce pronto.") }
    var callJob by remember { mutableStateOf<Job?>(null) }
    var startRequested by remember { mutableStateOf(false) }
    val waitingTone = remember(context) { WaitingTonePlayer(context.applicationContext) }
    val voiceProfile = remember(settings.activeProjectId) {
        loadVoiceProfile(context, settings.activeProjectId)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRequested = true
        } else {
            Toast.makeText(context, "Permesso microfono negato.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(autoStartToken) {
        if (autoStartToken == 0L || callActive) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRequested = true
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(startRequested) {
        if (!startRequested || callActive) return@LaunchedEffect
        startRequested = false
        callJob?.cancel()
        callActive = true
        phase = VoiceCallPhase.Connecting
        status = "Connessione a Hermes..."
        if (voiceProfile.bluetooth) routeVoiceBluetooth(context, true)
        callJob = scope.launch {
            try {
                verifyVoiceGateway(settings, apiKey)
                if (!callActive) return@launch
                phase = VoiceCallPhase.Listening
                status = "Ti ascolto."
                history.clear()
                startVoiceForegroundService(context)
                runVoiceCallLoop(
                    context = context,
                    settings = settings,
                    apiKey = apiKey,
                    history = history,
                    voiceConversation = VoiceConversationContext("voice_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}"),
                    isCallActive = { callActive },
                    setPhase = { phase = it },
                    setStatus = { status = it },
                    voice = { voiceProfile.voice },
                    speed = { voiceProfile.speed.toDouble() }
                )
            } catch (_: CancellationException) {
            } catch (ex: Exception) {
                if (callActive) {
                    phase = VoiceCallPhase.Error
                    status = "Voce non disponibile: ${ex.message ?: "errore sconosciuto"}"
                    callActive = false
                }
            }
        }
    }

    DisposableEffect(view) {
        val activity = context as? Activity
        val controller = activity?.let { WindowInsetsControllerCompat(it.window, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(Unit) {
        onDispose {
            callActive = false
            callJob?.cancel()
            VoiceTurnController.interrupt()
            stopVoiceForegroundService(context)
            if (voiceProfile.bluetooth) routeVoiceBluetooth(context, false)
            waitingTone.release()
        }
    }

    LaunchedEffect(phase) {
        if (phase == VoiceCallPhase.Thinking) waitingTone.start() else waitingTone.stop()
    }

    val assembled = callActive && phase != VoiceCallPhase.Connecting && phase != VoiceCallPhase.Error
    val speaking = phase == VoiceCallPhase.Speaking

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VoiceParticleField(
            assembled = assembled,
            speaking = speaking,
            particleShape = voiceProfile.particleShape,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 34.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (callActive) {
                        if (voiceProfile.pushToTalk && (phase == VoiceCallPhase.Thinking || phase == VoiceCallPhase.Speaking)) {
                            VoiceTurnController.interrupt()
                            phase = VoiceCallPhase.Listening
                            status = "Hermes interrotto. Ti ascolto."
                        } else {
                            callActive = false
                            VoiceTurnController.interrupt()
                            callJob?.cancel()
                            callJob = null
                            stopVoiceForegroundService(context)
                            if (voiceProfile.bluetooth) routeVoiceBluetooth(context, false)
                            phase = VoiceCallPhase.Idle
                            scope.launch { status = saveVoiceCall(context, settings, apiKey, history) }
                        }
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startRequested = true
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (callActive) Color(0xFF9E2424) else Color(0x33222222)
                ),
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .border(1.dp, if (callActive) Color(0xCCFF6F24) else Color(0x66FFFFFF), CircleShape)
            ) {
                Icon(
                    imageVector = if (callActive) Icons.Rounded.CallEnd else Icons.Rounded.Call,
                    contentDescription = if (callActive) "Chiudi chiamata" else "Avvia chiamata",
                    tint = Color.White
                )
            }
            Text(
                text = status,
                color = Color(0xCCFFFFFF),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (voiceProfile.showTranscript) {
            Card(modifier = Modifier.align(Alignment.TopStart).padding(18.dp).fillMaxWidth(0.72f).heightIn(max = 420.dp), colors = CardDefaults.cardColors(containerColor = Color(0xDD151515))) {
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(history.size) { index -> val message = history[index]; Text("${message.author}\n${message.text}", color = Color.White) } }
            }
        }
    }
}

private suspend fun saveVoiceCall(context: Context, settings: AppSettings, apiKey: String?, history: List<ChatMessage>): String {
    if (history.isEmpty()) return "Chiamata chiusa."
    val saved = saveConversationSnapshot(context, null, "Chat", "Chiamata vocale", history.toList(), "voice-call", projectId = settings.activeProjectId)
    val transcript = history.joinToString("\n") { "${it.author}: ${it.text}" }
    var summary = "Chiamata con ${history.size} interventi. Ultimo punto: ${history.last().text}"
    runCatching {
        val builder = StringBuilder()
        streamChatRequest(settings, "Chat", "Riassumi in massimo 5 righe questa chiamata con decisioni e prossimi passi:\n\n$transcript", emptyList(), saved.id, null, emptyList(), apiKey).collect { event ->
            when (event) { is ChatStreamEvent.TextDelta -> builder.append(event.delta); is ChatStreamEvent.TextSnapshot -> { builder.clear(); builder.append(event.text) }; else -> Unit }
        }
        if (builder.isNotBlank()) summary = builder.toString().trim()
    }
    updateLocalConversation(context, saved.id) { it.copy(folder = "Chiamate", tags = (it.tags + "voce").distinct(), summary = summary, updatedAt = System.currentTimeMillis()) }
    return "Chiamata salvata. ${summary.take(150)}"
}

private fun routeVoiceBluetooth(context: Context, enabled: Boolean) {
    val audio = context.getSystemService(AudioManager::class.java)
    audio.mode = if (enabled) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
    if (Build.VERSION.SDK_INT >= 31) {
        val bluetooth = audio.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET }
        if (enabled && bluetooth != null) audio.setCommunicationDevice(bluetooth) else audio.clearCommunicationDevice()
    } else {
        @Suppress("DEPRECATION")
        if (enabled) audio.startBluetoothSco() else audio.stopBluetoothSco()
    }
}

internal fun startVoiceForegroundService(context: Context, mode: String = "call") {
    val intent = Intent(context, VoiceCallService::class.java).setAction("start").putExtra("mode", mode)
    ContextCompat.startForegroundService(context, intent)
}

internal fun stopVoiceForegroundService(context: Context) {
    context.startService(Intent(context, VoiceCallService::class.java).setAction("stop"))
}

@Composable
private fun VoiceParticleField(
    assembled: Boolean,
    speaking: Boolean,
    particleShape: String,
    modifier: Modifier = Modifier
) {
    val particles = remember(particleShape) { buildVoiceParticles(particleShape) }
    var assembly by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(assembled) {
        val target = if (assembled) 1f else 0f
        val duration = if (assembled) 2.8f else 1.2f
        var previousFrame = withFrameNanos { it }
        while (abs(assembly - target) > 0.001f) {
            val frame = withFrameNanos { it }
            val step = ((frame - previousFrame) / 1_000_000_000f) / duration
            previousFrame = frame
            assembly = if (target > assembly) min(target, assembly + step) else max(target, assembly - step)
        }
        assembly = target
    }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) time = (withFrameNanos { it } - start) / 1_000_000_000f
    }

    Canvas(modifier = modifier.background(Color.Black)) {
        drawRect(Color.Black)
        val gatherProgress = ((assembly - 0.08f) / 0.92f).coerceIn(0f, 1f)
        val eased = gatherProgress * gatherProgress * (3f - 2f * gatherProgress)
        val speechBeat = if (speaking) {
            val primary = max(0f, sin(time * 10.8f))
            val secondary = max(0f, sin(time * 17.1f + 0.8f))
            primary * primary * 0.105f + secondary * secondary * 0.035f
        } else 0f
        val quietBreath = if (assembled) sin(time * 1.35f) * 0.012f else 0f
        val shapeScale = if (particleShape == NeuralCoreParticleShape) 1.08f else 1f
        val scale = min(size.width, size.height) * (0.365f + quietBreath + speechBeat) * shapeScale
        val camera = 4.1f
        val spin = if (particleShape == HermesHeadParticleShape) {
            sin(time * 0.28f) * 0.34f
        } else {
            time * (0.17f + eased * if (particleShape == NeuralCoreParticleShape) 0.52f else 0.38f)
        }
        val tilt = sin(time * 0.31f) * 0.16f
        val center = Offset(size.width * 0.5f, size.height * 0.46f)

        for (particle in particles) {
            val idleX = particle.idleX + sin(time * particle.speed + particle.phase) * 0.14f
            val idleY = particle.idleY + cos(time * particle.speed * 0.73f + particle.phase) * 0.11f
            val idleZ = particle.idleZ + sin(time * particle.speed * 0.41f + particle.phase) * 0.19f
            val gatherArc = sin(PI.toFloat() * eased) * 0.46f
            val x = lerpFloat(idleX, particle.targetX, eased) + sin(particle.phase + eased * PI.toFloat() * 2f) * gatherArc
            val y = lerpFloat(idleY, particle.targetY, eased) + cos(particle.phase * 0.73f + eased * PI.toFloat() * 2f) * gatherArc * 0.65f
            val z = lerpFloat(idleZ, particle.targetZ, eased)

            val cosSpin = cos(spin)
            val sinSpin = sin(spin)
            val spunX = x * cosSpin - z * sinSpin
            val spunZ = x * sinSpin + z * cosSpin
            val cosTilt = cos(tilt)
            val sinTilt = sin(tilt)
            val rotatedY = y * cosTilt - spunZ * sinTilt
            val rotatedZ = y * sinTilt + spunZ * cosTilt
            val perspective = camera / max(0.65f, camera + rotatedZ)
            val px = center.x + spunX * scale * perspective
            val py = center.y + rotatedY * scale * perspective
            val dot = max(1.5f, particle.size * (0.7f + perspective * 0.55f + eased * 0.35f))
            val alpha = (0.36f + perspective * 0.2f + eased * 0.28f).coerceIn(0.34f, 1f)
            val glow = if (particle.hot) Color(0xFFFFB13A) else Color(0xFFFF6F12)
            val core = if (particle.hot) Color(0xFFFFF0A8) else Color(0xFFFF9B2E)

            drawCircle(glow.copy(alpha = alpha * (0.1f + eased * 0.08f)), dot * (4.8f + speechBeat * 18f), Offset(px, py))
            drawCircle(glow.copy(alpha = alpha * 0.42f), dot * 2.05f, Offset(px, py))
            drawCircle(core.copy(alpha = alpha), dot, Offset(px, py))
        }
    }
}

private suspend fun runVoiceCallLoop(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    history: MutableList<ChatMessage>,
    voiceConversation: VoiceConversationContext,
    isCallActive: () -> Boolean,
    setPhase: (VoiceCallPhase) -> Unit,
    setStatus: (String) -> Unit,
    voice: () -> String,
    speed: () -> Double
) {
    var lastTranscript = ""
    var lastTranscriptAt = 0L
    while (coroutineContext.isActive && isCallActive()) {
        try {
            setPhase(VoiceCallPhase.Listening)
            setStatus("Ti ascolto.")
            val file = captureVoiceUtterance(context) ?: continue
            setPhase(VoiceCallPhase.Transcribing)
            setStatus("Trascrivo...")
            val text = try {
                transcribeVoiceFile(settings, apiKey, file).trim()
            } finally {
                file.delete()
            }
            val now = System.currentTimeMillis()
            if (!isUsefulTranscript(text) || (text.equals(lastTranscript, ignoreCase = true) && now - lastTranscriptAt < 8_000)) {
                continue
            }
            lastTranscript = text
            lastTranscriptAt = now
            setStatus("Tu: ${text.trimForStatus()}")
            coroutineScope {
                val turn = async { runVoiceTurn(context, settings, apiKey, history, voiceConversation, text, setPhase, setStatus, voice(), speed()) }
                VoiceTurnController.job = turn
                try { turn.await() } catch (_: CancellationException) { if (coroutineContext.isActive) { setPhase(VoiceCallPhase.Listening); setStatus("Interrotto. Ti ascolto.") } } finally { if (VoiceTurnController.job === turn) VoiceTurnController.job = null }
            }
        } catch (_: CancellationException) {
            break
        } catch (ex: Exception) {
            setPhase(VoiceCallPhase.Error)
            setStatus("Errore voce: ${ex.message ?: "errore sconosciuto"}")
            kotlinx.coroutines.delay(900)
        }
    }
}

internal suspend fun awaitWakePhrase(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    wakePhrase: String
) {
    while (coroutineContext.isActive) {
        try {
            val file = captureVoiceUtterance(context) ?: continue
            val transcript = try {
                transcribeVoiceFile(settings, apiKey, file).trim()
            } finally {
                file.delete()
            }
            if (stripWakePhrase(transcript, wakePhrase) != null) {
                return
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (_: Exception) {
            kotlinx.coroutines.delay(2_000)
        }
    }
}

private suspend fun captureVoiceUtterance(context: Context): File? = withContext(Dispatchers.IO) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        throw SecurityException("Permesso microfono non disponibile.")
    }
    val minimumBuffer = AudioRecord.getMinBufferSize(
        VoiceSampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    check(minimumBuffer > 0) { "Microfono non disponibile." }
    val recorder = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(VoiceSampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
        )
        .setBufferSizeInBytes(max(minimumBuffer, VoiceFrameBytes * 6))
        .build()
    val preRoll = ArrayDeque<ByteArray>(VoicePreRollFrames)
    val pcm = ByteArrayOutputStream(VoiceSampleRate * 2 * 8)
    val frame = ByteArray(VoiceFrameBytes)
    var noiseFloor = 8.0
    var triggerFrames = 0
    var observedFrames = 0
    var maxObservedRms = 0.0
    var speechStartedAt = 0L
    var lastVoiceAt = 0L
    var heardVoice = false

    try {
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Impossibile inizializzare il microfono." }
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microfono non in registrazione." }
        while (coroutineContext.isActive) {
            val read = recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue
            val chunk = frame.copyOf(read)
            val rms = pcmRms(chunk)
            val peak = pcmPeak(chunk)
            observedFrames++
            maxObservedRms = max(maxObservedRms, rms)
            val threshold = (noiseFloor * 2.5 + 6.0).coerceIn(18.0, 1_800.0)
            val peakThreshold = (noiseFloor * 10.0 + 80.0).coerceIn(100.0, 12_000.0)
            val voiced = rms >= threshold || peak >= peakThreshold
            val now = System.currentTimeMillis()

            if (!heardVoice) {
                preRoll.addLast(chunk)
                while (preRoll.size > VoicePreRollFrames) preRoll.removeFirst()
                if (observedFrames <= VoicePreRollFrames) {
                    noiseFloor = if (observedFrames == 1) rms else noiseFloor * 0.82 + rms * 0.18
                    continue
                }
                if (voiced) {
                    triggerFrames++
                } else {
                    triggerFrames = max(0, triggerFrames - 1)
                    noiseFloor = noiseFloor * 0.92 + min(rms, threshold) * 0.08
                }
                if (triggerFrames >= 2) {
                    heardVoice = true
                    speechStartedAt = now
                    lastVoiceAt = now
                    preRoll.forEach { pcm.write(it) }
                    preRoll.clear()
                }
                if (observedFrames >= 250 && maxObservedRms < 1.5) {
                    error("Il microfono non sta inviando audio.")
                }
                continue
            }

            pcm.write(chunk)
            if (voiced) lastVoiceAt = now
            if (now - speechStartedAt >= VoiceMaxUtteranceMillis) break
            if (now - speechStartedAt >= 320 && now - lastVoiceAt >= VoiceEndSilenceMillis) break
        }
    } finally {
        runCatching { recorder.stop() }
        recorder.release()
    }

    if (!heardVoice || pcm.size() < VoiceSampleRate / 4) return@withContext null
    val file = File(context.cacheDir, "voice-call-${System.currentTimeMillis()}.wav")
    writePcmWav(file, pcm.toByteArray(), VoiceSampleRate)
    file
}

private suspend fun runVoiceTurn(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    history: MutableList<ChatMessage>,
    voiceConversation: VoiceConversationContext,
    prompt: String,
    setPhase: (VoiceCallPhase) -> Unit,
    setStatus: (String) -> Unit,
    voice: String,
    speed: Double
) = coroutineScope {
    val contextHistory = history.takeLast(10)
    history += ChatMessage("Tu", prompt, fromUser = true)
    while (history.size > 20) history.removeAt(0)

    val answer = StringBuilder()
    val speechBuffer = StringBuilder()
    var playback: Deferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }
    setPhase(VoiceCallPhase.Thinking)
    setStatus("Hermes sta pensando...")

    streamChatRequest(
        settings = settings,
        mode = "Chat",
        prompt = "Sei in una chiamata vocale. Rispondi subito in italiano, con tono naturale e frasi brevi. Niente markdown, elenchi o preamboli. La prima frase deve avere al massimo 8 parole. Utente: $prompt",
        history = contextHistory,
        conversationId = voiceConversation.conversationId,
        previousResponseId = voiceConversation.previousResponseId,
        attachments = emptyList(),
        apiKey = apiKey
    ).collect { event ->
        when (event) {
            is ChatStreamEvent.TextDelta -> {
                answer.append(event.delta)
                speechBuffer.append(event.delta)
                setStatus("Hermes: ${answer.toString().trimForStatus()}")
                val queued = drainSpeechSegments(speechBuffer, flush = false)
                for (segment in queued) {
                    playback = queueSpeechSegment(context, settings, apiKey, segment, playback, this, voice, speed) {
                        setPhase(VoiceCallPhase.Speaking)
                    }
                }
            }
            is ChatStreamEvent.TextSnapshot -> {
                val previous = answer.toString()
                val merged = mergeTextSnapshot(previous, event.text)
                answer.clear()
                answer.append(merged)
                if (merged.startsWith(previous) && merged.length > previous.length) {
                    speechBuffer.append(merged.substring(previous.length))
                }
                setStatus("Hermes: ${merged.trimForStatus()}")
            }
            is ChatStreamEvent.ResponseId -> voiceConversation.previousResponseId = event.id.takeIf { it.isNotBlank() }
            is ChatStreamEvent.Error -> throw java.io.IOException(event.message)
            else -> Unit
        }
    }

    for (segment in drainSpeechSegments(speechBuffer, flush = true)) {
        playback = queueSpeechSegment(context, settings, apiKey, segment, playback, this, voice, speed) {
            setPhase(VoiceCallPhase.Speaking)
        }
    }
    playback.await()

    val finalText = answer.toString().trim()
    if (finalText.isNotBlank()) history += ChatMessage("Hermes", finalText, fromUser = false)
    while (history.size > 20) history.removeAt(0)
    setPhase(VoiceCallPhase.Listening)
    setStatus("Ti ascolto.")
}

private fun queueSpeechSegment(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    segment: String,
    previous: Deferred<Unit>,
    scope: CoroutineScope,
    voice: String,
    speed: Double,
    onPlaybackStarted: () -> Unit
): Deferred<Unit> = scope.async {
    previous.await()
    val file = synthesizeVoiceFile(context, settings, apiKey, segment, voice, speed)
    try {
        playVoiceFile(file, onPlaybackStarted)
    } finally {
        file.delete()
    }
}

private suspend fun synthesizeVoiceFile(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    text: String,
    voice: String = "if_sara",
    speed: Double = 1.08
): File = withContext(Dispatchers.IO) {
    val payload = JSONObject()
        .put("input", text.trim())
        .put("voice", voice.takeIf { it in SupportedVoiceNames } ?: "if_sara")
        .put("lang", "it")
        .put("speed", speed.coerceIn(0.75, 1.35))
        .put("response_format", "wav")
        .toString()
        .toRequestBody("application/json; charset=utf-8".toMediaType())
    var lastError = "Sintesi vocale non disponibile."
    for (root in voiceGatewayRoots(settings)) {
        for (token in hermesAuthCandidates(apiKey)) {
            val request = Request.Builder()
                .url("$root/audio/speech")
                .header("Accept", "audio/wav")
                .header("User-Agent", "HermesHub-Android-Voice")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .post(payload)
                .build()
            val response = try {
                voiceHttpClient.newCall(request).execute()
            } catch (ex: Exception) {
                lastError = ex.message ?: ex.javaClass.simpleName
                continue
            }
            response.use {
                val body = it.body
                if (it.isSuccessful) {
                    try {
                        return@withContext streamWavToTempFile(
                            directory = File(context.cacheDir, "voice-tts"),
                            prefix = "voice-tts-",
                            input = body.byteStream(),
                            contentLength = body.contentLength()
                        )
                    } catch (ex: Exception) {
                        lastError = ex.message ?: ex.javaClass.simpleName
                    }
                } else {
                    val errorBody = body.byteStream().readUtf8Bounded()
                    lastError = "HTTP ${it.code}: ${errorBody.take(160)}"
                }
                if (it.code != 401) break
            }
        }
    }
    throw java.io.IOException(lastError)
}

private suspend fun playVoiceFile(file: File, onPlaybackStarted: () -> Unit): Unit = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val player = MediaPlayer()
        VoiceTurnController.player = player
        val finished = AtomicBoolean(false)
        fun complete(error: Throwable? = null) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { player.stop() }
            player.release()
            if (VoiceTurnController.player === player) VoiceTurnController.player = null
            if (!continuation.isActive) return
            if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
        }
        player.setOnPreparedListener {
            onPlaybackStarted()
            it.start()
        }
        player.setOnCompletionListener { complete() }
        player.setOnErrorListener { _, what, extra ->
            complete(java.io.IOException("Riproduzione audio fallita ($what/$extra)."))
            true
        }
        continuation.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post { complete() }
        }
        try {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (ex: Exception) {
            complete(ex)
        }
    }
}

private suspend fun verifyVoiceGateway(settings: AppSettings, apiKey: String?) = withContext(Dispatchers.IO) {
    var lastError = "Gateway Hermes non raggiungibile."
    for (root in voiceGatewayRoots(settings)) {
        for (token in hermesAuthCandidates(apiKey)) {
            val request = Request.Builder()
                .url("$root/capabilities")
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android-Voice")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .get()
                .build()
            val response = try {
                voiceHttpClient.newCall(request).execute()
            } catch (ex: Exception) {
                lastError = ex.message ?: ex.javaClass.simpleName
                continue
            }
            response.use {
                if (it.isSuccessful) return@withContext
                lastError = "HTTP ${it.code}"
                if (it.code != 401) break
            }
        }
    }
    throw java.io.IOException(lastError)
}

private suspend fun transcribeVoiceFile(settings: AppSettings, apiKey: String?, file: File): String = withContext(Dispatchers.IO) {
    var lastError = "Trascrizione non disponibile."
    for (root in voiceGatewayRoots(settings)) {
        for (token in hermesAuthCandidates(apiKey)) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder()
                .url("$root/audio/transcriptions")
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android-Voice")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .post(body)
                .build()
            val response = try {
                voiceHttpClient.newCall(request).execute()
            } catch (ex: Exception) {
                lastError = ex.message ?: ex.javaClass.simpleName
                continue
            }
            response.use {
                val responseBody = it.body.byteStream().readUtf8Bounded()
                if (it.isSuccessful) return@withContext JSONObject(responseBody).optString("text").trim()
                lastError = "HTTP ${it.code}: ${responseBody.take(180)}"
                if (it.code != 401) break
            }
        }
    }
    throw java.io.IOException(lastError)
}

private fun voiceGatewayRoots(settings: AppSettings): List<String> {
    val roots = mutableListOf<String>()
    runCatching {
        val uri = URI(settings.gatewayUrl.trim())
        val scheme = uri.scheme ?: "http"
        val host = uri.host?.takeIf { it.isNotBlank() } ?: error("Configura Hermes API URL")
        val port = if (uri.port > 0) uri.port else 8642
        val path = uri.rawPath?.trimEnd('/')?.takeIf { it.isNotBlank() && it != "/" } ?: "/v1"
        roots += URI(scheme, null, host, port, path, null, null).toString().trimEnd('/')
    }
    return roots.distinctBy { it.lowercase() }
}

private fun drainSpeechSegments(buffer: StringBuilder, flush: Boolean): List<String> {
    val segments = mutableListOf<String>()
    while (true) {
        val cut = findSpeechCut(buffer.toString(), flush)
        if (cut <= 0) return segments
        val segment = buffer.substring(0, cut).trim()
        buffer.delete(0, cut)
        if (segment.length >= 2) segments += segment
    }
}

private fun findSpeechCut(text: String, flush: Boolean): Int {
    if (text.isBlank()) return -1
    val searchLimit = min(text.length, 76)
    for (index in searchLimit - 1 downTo 0) {
        if (text[index] in charArrayOf('.', '!', '?', '\n') && index >= 9) return index + 1
    }
    if (text.length > 46) {
        val soft = text.lastIndexOfAny(charArrayOf(',', ';', ':', ' '), startIndex = min(text.length - 1, 46))
        return if (soft > 25) soft + 1 else 46
    }
    return if (flush) text.length else -1
}

private fun isUsefulTranscript(text: String): Boolean {
    val clean = text.trim()
    if (clean.length < 2) return false
    val normalized = clean.lowercase().trim('.', ',', '!', '?', ' ')
    return normalized !in setOf(
        "grazie",
        "sottotitoli e revisione a cura di qtss",
        "sottotitoli creati dalla comunita amara.org"
    )
}

private fun pcmRms(bytes: ByteArray): Double {
    if (bytes.size < 2) return 0.0
    var sum = 0.0
    var samples = 0
    var index = 0
    while (index + 1 < bytes.size) {
        val value = ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xFF)).toShort().toInt()
        sum += value.toDouble() * value
        samples++
        index += 2
    }
    return if (samples == 0) 0.0 else sqrt(sum / samples)
}

private fun pcmPeak(bytes: ByteArray): Double {
    var peak = 0
    var index = 0
    while (index + 1 < bytes.size) {
        val value = ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xFF)).toShort().toInt()
        peak = max(peak, abs(value))
        index += 2
    }
    return peak.toDouble()
}

private class WaitingTonePlayer(context: Context) {
    private val toneFile = File(context.cacheDir, "hermes-waiting-tone.wav")
    private var player: MediaPlayer? = null

    fun start() {
        if (player?.isPlaying == true) return
        stop()
        runCatching {
            if (!toneFile.exists()) writeWaitingToneWav(toneFile)
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            next.setDataSource(toneFile.absolutePath)
            next.isLooping = true
            next.setVolume(0.25f, 0.25f)
            next.prepare()
            next.start()
        }.onFailure {
            stop()
        }
    }

    fun stop() {
        val active = player ?: return
        player = null
        runCatching { active.stop() }
        active.release()
    }

    fun release() {
        stop()
        toneFile.delete()
    }
}

private const val VoiceWaitingToneSampleRate = 24_000

private fun buildWaitingToneSamples(): ShortArray {
    val samples = ShortArray((VoiceWaitingToneSampleRate * 2.2).toInt())
    for (index in samples.indices) {
        val time = index.toDouble() / VoiceWaitingToneSampleRate
        val sample = waitingNote(time, 0.08, 0.24, 620.0) + waitingNote(time, 0.40, 0.28, 780.0)
        samples[index] = (sample * Short.MAX_VALUE * 0.78).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return samples
}

private fun writeWaitingToneWav(file: File) {
    val samples = buildWaitingToneSamples()
    val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { pcm.putShort(it) }
    writePcmWav(file, pcm.array(), VoiceWaitingToneSampleRate)
}

private fun waitingNote(time: Double, start: Double, duration: Double, frequency: Double): Double {
    val local = time - start
    if (local < 0.0 || local > duration) return 0.0
    val attack = min(1.0, local / 0.035)
    val release = min(1.0, (duration - local) / 0.09)
    val envelope = attack * release * 0.22
    return envelope * (sin(2.0 * PI * frequency * local) + 0.18 * sin(4.0 * PI * frequency * local))
}

private fun writePcmWav(file: File, pcm: ByteArray, sampleRate: Int) {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36 + pcm.size)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1.toShort())
    header.putShort(1.toShort())
    header.putInt(sampleRate)
    header.putInt(sampleRate * 2)
    header.putShort(2.toShort())
    header.putShort(16.toShort())
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(pcm.size)
    FileOutputStream(file).use {
        it.write(header.array())
        it.write(pcm)
    }
}

private fun buildVoiceParticles(particleShape: String): List<VoiceParticle> {
    val random = Random(8642)
    val particleCount = 680
    return List(particleCount) { index ->
        val target = when (particleShape) {
            NeuralCoreParticleShape -> buildNeuralCoreTarget(index, particleCount, random)
            HermesHeadParticleShape -> buildHermesHeadTarget(index, particleCount, random)
            else -> buildSphereTarget(random)
        }
        VoiceParticle(
            idleX = random.nextFloat(-1.35f, 1.35f),
            idleY = random.nextFloat(-2.4f, 2.4f),
            idleZ = random.nextFloat(-2.4f, 2.4f),
            targetX = target.x,
            targetY = target.y,
            targetZ = target.z,
            phase = random.nextFloat(0f, (PI * 2.0).toFloat()),
            speed = random.nextFloat(0.42f, 1.58f),
            size = random.nextFloat(1.45f, 3.75f),
            hot = random.nextFloat() > 0.72f
        )
    }
}

private fun buildSphereTarget(random: Random): VoiceParticleTarget {
    val theta = (PI * 2.0 * random.nextDouble()).toFloat()
    val phi = acos(2.0 * random.nextDouble() - 1.0).toFloat()
    val radius = 0.91f + random.nextFloat() * 0.18f
    return VoiceParticleTarget(
        radius * sin(phi) * cos(theta),
        radius * cos(phi),
        radius * sin(phi) * sin(theta)
    )
}

private fun buildNeuralCoreTarget(index: Int, count: Int, random: Random): VoiceParticleTarget {
    if (index < count * 0.42f) {
        val around = random.nextFloat() * PI.toFloat() * 2f
        val tube = random.nextFloat() * PI.toFloat() * 2f
        val tubeRadius = 0.105f + random.nextFloat() * 0.075f
        val radius = 0.72f + tubeRadius * cos(tube)
        return VoiceParticleTarget(
            radius * cos(around),
            radius * sin(around),
            tubeRadius * sin(tube)
        )
    }

    if (index < count * 0.74f) {
        val t = random.nextFloat()
        val branch = if (index % 2 == 0) 0f else PI.toFloat()
        val angle = t * PI.toFloat() * 5f + branch
        val radius = 0.27f + (random.nextFloat() - 0.5f) * 0.045f
        return VoiceParticleTarget(
            radius * cos(angle),
            (t - 0.5f) * 1.48f,
            radius * sin(angle)
        )
    }

    val theta = random.nextFloat() * PI.toFloat() * 2f
    val phi = acos(random.nextFloat() * 2f - 1f)
    val radius = 0.19f + random.nextFloat() * 0.17f
    return VoiceParticleTarget(
        radius * sin(phi) * cos(theta),
        radius * cos(phi),
        radius * sin(phi) * sin(theta)
    )
}

private fun buildHermesHeadTarget(index: Int, count: Int, random: Random): VoiceParticleTarget {
    if (index < count * 0.46f) {
        val theta = random.nextFloat() * PI.toFloat() * 2f
        val phi = acos(random.nextFloat() * 2f - 1f)
        val y = 0.08f + 0.78f * cos(phi)
        val jawTaper = 1f - 0.27f * ((y - 0.18f) / 0.68f).coerceIn(0f, 1f)
        return VoiceParticleTarget(
            0.58f * sin(phi) * cos(theta) * jawTaper,
            y,
            0.42f * sin(phi) * sin(theta)
        )
    }

    if (index < count * 0.68f) {
        val polar = random.nextFloat() * PI.toFloat() * 0.5f
        val around = random.nextFloat() * PI.toFloat() * 2f
        return VoiceParticleTarget(
            0.68f * sin(polar) * cos(around),
            -0.46f - 0.60f * cos(polar),
            0.52f * sin(polar) * sin(around)
        )
    }

    if (index < count * 0.84f) {
        val side = if (index % 2 == 0) -1f else 1f
        val t = random.nextFloat()
        val feather = (index % 4) / 3f
        return VoiceParticleTarget(
            side * (0.60f + 0.50f * t),
            -0.54f - (0.20f + feather * 0.24f) * t + feather * 0.035f,
            -0.03f + (random.nextFloat() - 0.5f) * 0.16f
        )
    }

    if (index < count * 0.94f) {
        val side = if (index % 2 == 0) -1f else 1f
        val angle = random.nextFloat() * PI.toFloat()
        return VoiceParticleTarget(
            side * (0.20f + 0.15f * cos(angle)),
            -0.10f + 0.07f * sin(angle),
            -0.405f - 0.035f * sin(angle)
        )
    }

    if (index % 2 == 0) {
        val t = random.nextFloat()
        return VoiceParticleTarget(
            (random.nextFloat() - 0.5f) * 0.045f,
            -0.12f + t * 0.43f,
            -0.43f - sin(t * PI.toFloat()) * 0.10f
        )
    }

    val rim = random.nextFloat() * 2f - 1f
    return VoiceParticleTarget(
        rim * 0.64f,
        -0.46f + abs(rim) * 0.055f,
        -0.31f + (random.nextFloat() - 0.5f) * 0.06f
    )
}

private data class VoiceParticle(
    val idleX: Float,
    val idleY: Float,
    val idleZ: Float,
    val targetX: Float,
    val targetY: Float,
    val targetZ: Float,
    val phase: Float,
    val speed: Float,
    val size: Float,
    val hot: Boolean
)

private data class VoiceParticleTarget(val x: Float, val y: Float, val z: Float)

private fun Random.nextFloat(minimum: Float, maximum: Float): Float = minimum + nextFloat() * (maximum - minimum)
private fun lerpFloat(start: Float, stop: Float, amount: Float): Float = start + (stop - start) * amount

private fun String.trimForStatus(): String {
    val clean = replace('\n', ' ').replace('\r', ' ').trim()
    return if (clean.length > 110) clean.take(110) + "..." else clean
}
