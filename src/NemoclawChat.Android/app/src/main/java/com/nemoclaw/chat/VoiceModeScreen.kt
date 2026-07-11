package com.nemoclaw.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

@Composable
internal fun VoiceModeScreen(settings: AppSettings, apiKey: String?) {
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

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRequested = true
        } else {
            Toast.makeText(context, "Permesso microfono negato.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(startRequested) {
        if (!startRequested || callActive) return@LaunchedEffect
        startRequested = false
        callJob?.cancel()
        callActive = true
        phase = VoiceCallPhase.Connecting
        status = "Connessione a Hermes..."
        callJob = scope.launch {
            try {
                verifyVoiceGateway(settings, apiKey)
                if (!callActive) return@launch
                phase = VoiceCallPhase.Listening
                status = "Ti ascolto."
                runVoiceCallLoop(
                    context = context,
                    settings = settings,
                    apiKey = apiKey,
                    history = history,
                    isCallActive = { callActive },
                    setPhase = { phase = it },
                    setStatus = { status = it }
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
                        callActive = false
                        callJob?.cancel()
                        callJob = null
                        phase = VoiceCallPhase.Idle
                        status = "Chiamata chiusa."
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
    }
}

@Composable
private fun VoiceParticleField(
    assembled: Boolean,
    speaking: Boolean,
    modifier: Modifier = Modifier
) {
    val particles = remember { buildVoiceParticles() }
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
    var time by remember { mutableStateOf(0f) }
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
        val scale = min(size.width, size.height) * (0.365f + quietBreath + speechBeat)
        val camera = 4.1f
        val spin = time * (0.17f + eased * 0.38f)
        val tilt = sin(time * 0.31f) * 0.16f
        val center = Offset(size.width * 0.5f, size.height * 0.46f)

        for (particle in particles) {
            val idleX = particle.idleX + sin(time * particle.speed + particle.phase) * 0.14f
            val idleY = particle.idleY + cos(time * particle.speed * 0.73f + particle.phase) * 0.11f
            val idleZ = particle.idleZ + sin(time * particle.speed * 0.41f + particle.phase) * 0.19f
            val gatherArc = sin(PI.toFloat() * eased) * 0.46f
            val x = lerpFloat(idleX, particle.sphereX, eased) + sin(particle.phase + eased * PI.toFloat() * 2f) * gatherArc
            val y = lerpFloat(idleY, particle.sphereY, eased) + cos(particle.phase * 0.73f + eased * PI.toFloat() * 2f) * gatherArc * 0.65f
            val z = lerpFloat(idleZ, particle.sphereZ, eased)

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
    isCallActive: () -> Boolean,
    setPhase: (VoiceCallPhase) -> Unit,
    setStatus: (String) -> Unit
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
            runVoiceTurn(context, settings, apiKey, history, text, setPhase, setStatus)
        } catch (_: CancellationException) {
            break
        } catch (ex: Exception) {
            setPhase(VoiceCallPhase.Error)
            setStatus("Errore voce: ${ex.message ?: "errore sconosciuto"}")
            kotlinx.coroutines.delay(900)
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
    check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Impossibile inizializzare il microfono." }

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
    prompt: String,
    setPhase: (VoiceCallPhase) -> Unit,
    setStatus: (String) -> Unit
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
        conversationId = null,
        previousResponseId = null,
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
                    playback = queueSpeechSegment(context, settings, apiKey, segment, playback, this) {
                        setPhase(VoiceCallPhase.Speaking)
                    }
                }
            }
            is ChatStreamEvent.Error -> throw java.io.IOException(event.message)
            else -> Unit
        }
    }

    for (segment in drainSpeechSegments(speechBuffer, flush = true)) {
        playback = queueSpeechSegment(context, settings, apiKey, segment, playback, this) {
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
    onPlaybackStarted: () -> Unit
): Deferred<Unit> = scope.async {
    previous.await()
    val file = synthesizeVoiceFile(context, settings, apiKey, segment)
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
    text: String
): File = withContext(Dispatchers.IO) {
    val payload = JSONObject()
        .put("input", text.trim())
        .put("voice", "if_sara")
        .put("lang", "it")
        .put("speed", 1.08)
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
                val bytes = it.body.bytes()
                if (it.isSuccessful && bytes.isNotEmpty()) {
                    return@withContext File(context.cacheDir, "voice-tts-${System.nanoTime()}.wav").apply { writeBytes(bytes) }
                }
                lastError = "HTTP ${it.code}: ${bytes.decodeToString().take(160)}"
                if (it.code != 401) break
            }
        }
    }
    throw java.io.IOException(lastError)
}

private suspend fun playVoiceFile(file: File, onPlaybackStarted: () -> Unit): Unit = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val player = MediaPlayer()
        val finished = AtomicBoolean(false)
        fun complete(error: Throwable? = null) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { player.stop() }
            player.release()
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
                val responseBody = it.body.string()
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
        val host = uri.host?.takeIf { it.isNotBlank() } ?: "100.94.223.14"
        val port = if (uri.port > 0) uri.port else 8642
        val path = uri.rawPath?.trimEnd('/')?.takeIf { it.isNotBlank() && it != "/" } ?: "/v1"
        roots += URI(scheme, null, host, port, path, null, null).toString().trimEnd('/')
    }
    roots += "http://100.94.223.14:8642/v1"
    roots += "http://hermes:8642/v1"
    roots += "http://hermes.local:8642/v1"
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

private fun buildVoiceParticles(): List<VoiceParticle> {
    val random = Random(8642)
    return List(680) {
        val theta = (PI * 2.0 * random.nextDouble()).toFloat()
        val phi = acos(2.0 * random.nextDouble() - 1.0).toFloat()
        val radius = random.nextFloat(0.91f, 1.09f)
        VoiceParticle(
            idleX = random.nextFloat(-1.35f, 1.35f),
            idleY = random.nextFloat(-2.4f, 2.4f),
            idleZ = random.nextFloat(-2.4f, 2.4f),
            sphereX = radius * sin(phi) * cos(theta),
            sphereY = radius * cos(phi),
            sphereZ = radius * sin(phi) * sin(theta),
            phase = random.nextFloat(0f, (PI * 2.0).toFloat()),
            speed = random.nextFloat(0.42f, 1.58f),
            size = random.nextFloat(1.45f, 3.75f),
            hot = random.nextFloat() > 0.72f
        )
    }
}

private data class VoiceParticle(
    val idleX: Float,
    val idleY: Float,
    val idleZ: Float,
    val sphereX: Float,
    val sphereY: Float,
    val sphereZ: Float,
    val phase: Float,
    val speed: Float,
    val size: Float,
    val hot: Boolean
)

private fun Random.nextFloat(minimum: Float, maximum: Float): Float = minimum + nextFloat() * (maximum - minimum)
private fun lerpFloat(start: Float, stop: Float, amount: Float): Float = start + (stop - start) * amount

private fun String.trimForStatus(): String {
    val clean = replace('\n', ' ').replace('\r', ' ').trim()
    return if (clean.length > 110) clean.take(110) + "..." else clean
}
