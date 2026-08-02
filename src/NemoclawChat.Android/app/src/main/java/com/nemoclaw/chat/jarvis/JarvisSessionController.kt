package com.nemoclaw.chat.jarvis

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.VoiceTurnController
import com.nemoclaw.chat.captureVoiceUtterance
import com.nemoclaw.chat.loadVoiceProfile
import com.nemoclaw.chat.playVoiceFile
import com.nemoclaw.chat.routeVoiceBluetooth
import com.nemoclaw.chat.synthesizeVoiceFile
import com.nemoclaw.chat.transcribeVoiceFile
import com.nemoclaw.chat.stopVoiceForegroundService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal object JarvisSessionController {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val speechMutex = Mutex()
    private val _state = MutableStateFlow(JarvisUiState())
    val state: StateFlow<JarvisUiState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    @Volatile
    private var startupJob: Job? = null
    private var frameUploadJob: Job? = null
    private var speechJob: Job? = null
    private var source: JarvisFrameSource? = null
    private var api: JarvisGatewayApi? = null
    private var settings: AppSettings? = null
    private var apiKey: String? = null
    private var capabilities: JarvisCapabilities? = null
    private val speaking = AtomicBoolean(false)
    private val frameSampler = FrameSampler()
    private val rollingFrames = RollingFrameBuffer(3)

    fun start(
        context: Context,
        settings: AppSettings,
        apiKey: String?,
        mode: JarvisInitiativeMode,
        objective: String,
        preferPhoneDebug: Boolean
    ) {
        startupJob?.cancel()
        val job = controllerScope.launch(start = CoroutineStart.LAZY) {
            lifecycleMutex.withLock {
                stopLocked(context.applicationContext, notifyGateway = true)
                startLocked(context.applicationContext, settings, apiKey, mode, objective, preferPhoneDebug)
            }
        }
        startupJob = job
        job.invokeOnCompletion {
            if (startupJob === job) startupJob = null
        }
        job.start()
    }

    fun stop(context: Context) {
        startupJob?.cancel()
        controllerScope.launch {
            lifecycleMutex.withLock { stopLocked(context.applicationContext, notifyGateway = true) }
        }
    }

    fun rejectStart(message: String) {
        _state.value = JarvisUiState(
            phase = JarvisPhase.ERROR,
            gatewayStatus = "Errore",
            error = message.take(500)
        )
    }

    fun serviceStartFailed(context: Context, error: Throwable) {
        startupJob?.cancel()
        controllerScope.launch {
            lifecycleMutex.withLock {
                reportError(error)
                stopLocked(context.applicationContext, notifyGateway = true, preserveError = true)
            }
        }
    }

    suspend fun stopAndJoin(context: Context) {
        startupJob?.cancel()
        lifecycleMutex.withLock { stopLocked(context.applicationContext, notifyGateway = true) }
    }

    fun pauseView(context: Context) = updateView(context, paused = true)
    fun resumeView(context: Context) = updateView(context, paused = false)

    fun setMode(context: Context, mode: JarvisInitiativeMode) {
        controllerScope.launch {
            val sessionId = _state.value.sessionId ?: return@launch
            val gateway = api ?: return@launch
            runCatching { gateway.patchSession(sessionId, mode = mode) }
                .onSuccess {
                    if (_state.value.sessionId != sessionId || !_state.value.active) return@onSuccess
                    _state.value = _state.value.copy(initiativeMode = mode, error = null)
                    refreshNotification(context)
                }
                .onFailure {
                    if (_state.value.sessionId == sessionId) reportError(it)
                }
        }
    }

    fun sendFeedback(helpful: Boolean) {
        val pending = _state.value
        val sessionId = pending.sessionId ?: return
        val eventId = pending.lastInterventionEventId ?: return
        if (pending.feedbackStatus != null) return
        _state.value = pending.copy(feedbackStatus = "Invio feedback...")
        controllerScope.launch {
            val gateway = api
            if (gateway == null) {
                clearPendingFeedback(sessionId, eventId)
                return@launch
            }
            runCatching { gateway.sendFeedback(sessionId, eventId, helpful) }
                .onSuccess {
                    if (_state.value.sessionId != sessionId ||
                        _state.value.lastInterventionEventId != eventId
                    ) return@onSuccess
                    _state.value = _state.value.copy(
                        feedbackStatus = if (helpful) "Segnalato come utile" else "Segnalato come non utile",
                        error = null
                    )
                }
                .onFailure {
                    clearPendingFeedback(sessionId, eventId)
                    if (_state.value.sessionId == sessionId) reportError(it)
                }
        }
    }

    private suspend fun startLocked(
        context: Context,
        configuredSettings: AppSettings,
        configuredApiKey: String?,
        mode: JarvisInitiativeMode,
        objective: String,
        preferPhoneDebug: Boolean
    ) {
        _state.value = JarvisUiState(
            phase = JarvisPhase.CONNECTING,
            initiativeMode = mode,
            objective = objective,
            gatewayStatus = "Connessione...",
            deviceStatus = "Rilevamento...",
            audioRoute = "Telefono"
        )
        settings = configuredSettings
        apiKey = configuredApiKey
        try {
            val gateway = JarvisGatewayApi(configuredSettings.gatewayUrl, configuredApiKey)
            api = gateway
            // Jarvis owns the microphone/audio route for its lifetime.
            VoiceTurnController.interrupt()
            stopVoiceForegroundService(context)
            // Must happen synchronously before the first network suspension, but
            // only after local validation and runtime permissions have succeeded.
            startService(context)
            val remoteCapabilities = gateway.capabilities()
            check(remoteCapabilities.enabled) { "Jarvis Mode non abilitato sul gateway." }
            check(remoteCapabilities.vision) { "Visione Jarvis non disponibile sul gateway." }
            capabilities = remoteCapabilities
            val remote = gateway.createSession(mode, objective)
            _state.value = _state.value.copy(sessionId = remote.id)
            val frameSource = JarvisFrameSourceFactory.create(context, preferPhoneDebug)
            source = frameSource
            frameSampler.reset()
            rollingFrames.clear()
            withTimeout(FRAME_SOURCE_START_TIMEOUT_MILLIS) {
                frameSource.start(
                    onFrame = { jpeg, capturedAt ->
                        acceptFrame(gateway, remote.id, jpeg, capturedAt)
                    },
                    onError = { frameError ->
                        controllerScope.launch {
                            lifecycleMutex.withLock {
                                reportError(frameError)
                                stopLocked(context, notifyGateway = true, preserveError = true)
                            }
                        }
                    }
                )
            }
            _state.value = _state.value.copy(
                phase = JarvisPhase.ACTIVE,
                active = true,
                sessionId = remote.id,
                initiativeMode = remote.mode,
                visionActive = !remote.viewPaused,
                deviceStatus = frameSource.label,
                audioRoute = "Telefono / Bluetooth Android",
                gatewayStatus = "Raggiungibile",
                singleModel = remoteCapabilities.singleModel,
                fastModelAvailable = remoteCapabilities.fastModelConfigured,
                reasoningModelAvailable = remoteCapabilities.reasoningModelConfigured,
                error = null
            )
            val profile = loadVoiceProfile(context, configuredSettings.activeProjectId)
            if (profile.bluetooth) routeVoiceBluetooth(context, true)
            sessionJob = controllerScope.launch {
                launch { eventLoop(context, gateway, remote.id, configuredSettings, configuredApiKey) }
                launch { voiceLoop(context, gateway, remote.id, configuredSettings, configuredApiKey) }
            }
            refreshNotification(context)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                withContext(NonCancellable) {
                    stopLocked(context, notifyGateway = true)
                }
                throw error
            }
            reportError(error)
            stopLocked(context, notifyGateway = true, preserveError = true)
        }
    }

    private suspend fun acceptFrame(
        gateway: JarvisGatewayApi,
        sessionId: String,
        jpeg: ByteArray,
        capturedAtMillis: Long
    ) {
        val limit = capabilities?.maxFrameBytes ?: 1_000_000
        if (jpeg.isEmpty() || jpeg.size > limit) {
            if (jpeg.size > limit) reportError(IllegalArgumentException("Frame oltre il limite gateway ($limit byte)."))
            return
        }
        val signature = perceptualSignature(jpeg)
        if (!frameSampler.shouldAccept(capturedAtMillis, signature)) return
        rollingFrames.add(SampledFrame(jpeg, capturedAtMillis, signature))
        frameUploadJob?.cancelAndJoin()
        frameUploadJob = controllerScope.launch {
            runCatching { gateway.uploadFrame(sessionId, jpeg, capturedAtMillis) }
                .onFailure { if (currentCoroutineContext().isActive) reportError(it) }
        }
    }

    private suspend fun eventLoop(
        context: Context,
        gateway: JarvisGatewayApi,
        sessionId: String,
        configuredSettings: AppSettings,
        configuredApiKey: String?
    ) {
        var lastEventId: String? = null
        while (currentCoroutineContext().isActive && _state.value.sessionId == sessionId) {
            try {
                gateway.events(sessionId, lastEventId).collect { event ->
                    lastEventId = event.id ?: lastEventId
                    when (event.type) {
                        "observer.result" -> _state.value = _state.value.copy(
                            lastObservation = event.observation,
                            situation = event.situation,
                            currentModel = modelLabel("observer"),
                            lastLatencyMs = event.totalLatencyMs,
                            error = null
                        )
                        "assistant.thinking" -> _state.value = _state.value.copy(currentModel = modelLabel("observer"))
                        "assistant.escalating" -> _state.value = _state.value.copy(currentModel = modelLabel("reasoning"))
                        "memory.summary" -> _state.value = _state.value.copy(
                            shortTermSummary = event.summary,
                            conversationTopic = event.topic,
                            awaitingFollowup = event.openLoop
                        )
                        "assistant.speak" -> if (event.autonomous && !event.text.isNullOrBlank()) {
                            _state.value = _state.value.copy(
                                lastInterventionText = event.text,
                                lastInterventionEventId = event.id,
                                feedbackStatus = null
                            )
                            speechJob?.cancelAndJoin()
                            speechJob = controllerScope.launch {
                                speak(context, gateway, sessionId, configuredSettings, configuredApiKey, event.text)
                            }
                        }
                        "feedback.updated" -> _state.value = _state.value.copy(
                            feedbackStatus = "Feedback acquisito"
                        )
                        "session.paused" -> _state.value = _state.value.copy(phase = JarvisPhase.PAUSED, visionActive = false)
                        "session.error" -> _state.value = _state.value.copy(
                            error = listOfNotNull(event.errorCode, event.errorMessage).joinToString(": ")
                        )
                    }
                    refreshNotification(context)
                }
                // A proxy or gateway may close an SSE response cleanly. Without
                // a delay this loop would reconnect continuously and drain CPU/network.
                if (currentCoroutineContext().isActive && _state.value.sessionId == sessionId) {
                    _state.value = _state.value.copy(gatewayStatus = "Riconnessione SSE...")
                    delay(1_000)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(gatewayStatus = "Riconnessione SSE...")
                delay(1_000)
            }
        }
    }

    private suspend fun voiceLoop(
        context: Context,
        gateway: JarvisGatewayApi,
        sessionId: String,
        configuredSettings: AppSettings,
        configuredApiKey: String?
    ) {
        while (currentCoroutineContext().isActive && _state.value.sessionId == sessionId) {
            if (speaking.get()) {
                delay(100)
                continue
            }
            var recording: File? = null
            try {
                val capture = controllerScope.async { captureVoiceUtterance(context) }
                VoiceTurnController.job = capture
                recording = capture.await()
                if (VoiceTurnController.job === capture) VoiceTurnController.job = null
                if (recording == null) continue
                val transcript = transcribeVoiceFile(configuredSettings, configuredApiKey, recording)
                if (transcript.isBlank()) continue
                _state.value = _state.value.copy(transcript = transcript, currentModel = "Routing", error = null)
                val turn = gateway.sendTurn(sessionId, transcript)
                _state.value = _state.value.copy(
                    currentModel = modelLabel(turn.route),
                    lastLatencyMs = turn.totalLatencyMs
                )
                if (turn.text.isNotBlank()) speak(context, gateway, sessionId, configuredSettings, configuredApiKey, turn.text)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    if (!currentCoroutineContext().isActive) throw error
                } else {
                    reportError(error)
                    delay(1_000)
                }
            } finally {
                recording?.delete()
            }
        }
    }

    private suspend fun speak(
        context: Context,
        gateway: JarvisGatewayApi,
        sessionId: String,
        configuredSettings: AppSettings,
        configuredApiKey: String?,
        text: String
    ) = speechMutex.withLock {
        if (_state.value.sessionId != sessionId) return@withLock
        speaking.set(true)
        VoiceTurnController.job?.cancel()
        runCatching { gateway.patchSession(sessionId, speaking = true) }
        val profile = loadVoiceProfile(context, configuredSettings.activeProjectId)
        var audio: File? = null
        try {
            audio = synthesizeVoiceFile(
                context,
                configuredSettings,
                configuredApiKey,
                text,
                profile.voice,
                profile.speed.toDouble()
            )
            playVoiceFile(audio) { _state.value = _state.value.copy(currentModel = "TTS") }
        } finally {
            audio?.delete()
            runCatching { gateway.patchSession(sessionId, speaking = false) }
            speaking.set(false)
        }
    }

    private fun updateView(context: Context, paused: Boolean) {
        controllerScope.launch {
            val sessionId = _state.value.sessionId ?: return@launch
            val gateway = api ?: return@launch
            val frameSource = source ?: return@launch
            runCatching {
                gateway.patchSession(sessionId, viewPaused = paused, status = if (paused) "paused" else "active")
                if (paused) frameSource.pause() else frameSource.resume()
            }.onSuccess {
                if (_state.value.sessionId != sessionId || !_state.value.active) return@onSuccess
                _state.value = _state.value.copy(
                    phase = if (paused) JarvisPhase.PAUSED else JarvisPhase.ACTIVE,
                    visionActive = !paused,
                    error = null
                )
                refreshNotification(context)
            }.onFailure {
                if (_state.value.sessionId == sessionId) reportError(it)
            }
        }
    }

    private suspend fun stopLocked(
        context: Context,
        notifyGateway: Boolean,
        preserveError: Boolean = false
    ) {
        val old = _state.value
        if (old.active) _state.value = old.copy(phase = JarvisPhase.STOPPING, visionActive = false)
        VoiceTurnController.interrupt()
        frameUploadJob?.cancelAndJoin()
        frameUploadJob = null
        speechJob?.cancelAndJoin()
        speechJob = null
        sessionJob?.cancelAndJoin()
        sessionJob = null
        runCatching { source?.stop() }
        source = null
        val sessionId = old.sessionId
        if (notifyGateway && !sessionId.isNullOrBlank()) runCatching { api?.deleteSession(sessionId) }
        val profile = settings?.let { loadVoiceProfile(context, it.activeProjectId) }
        if (profile?.bluetooth == true) routeVoiceBluetooth(context, false)
        speaking.set(false)
        frameSampler.reset()
        rollingFrames.clear()
        api = null
        settings = null
        apiKey = null
        capabilities = null
        val retainedError = if (preserveError) _state.value.error else null
        _state.value = JarvisUiState(error = retainedError, phase = if (retainedError == null) JarvisPhase.IDLE else JarvisPhase.ERROR)
        stopService(context)
    }

    private fun reportError(error: Throwable) {
        if (error is CancellationException) return
        _state.value = _state.value.copy(
            error = error.message?.take(500) ?: error.javaClass.simpleName,
            gatewayStatus = if (_state.value.active) _state.value.gatewayStatus else "Errore"
        )
    }

    private fun clearPendingFeedback(sessionId: String, eventId: String) {
        val current = _state.value
        if (current.sessionId == sessionId &&
            current.lastInterventionEventId == eventId &&
            current.feedbackStatus == "Invio feedback..."
        ) {
            _state.value = current.copy(feedbackStatus = null)
        }
    }

    private fun modelLabel(route: String): String = if (capabilities?.singleModel != false) {
        "Modello principale"
    } else if (route == "fast" || route == "observer") {
        "Osservatore rapido"
    } else {
        "Modello ragionante"
    }

    private fun startService(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, JarvisSessionService::class.java))
    }

    private fun stopService(context: Context) {
        context.stopService(Intent(context, JarvisSessionService::class.java))
    }

    private fun refreshNotification(context: Context) {
        context.startService(Intent(context, JarvisSessionService::class.java).setAction(JarvisSessionService.ACTION_REFRESH))
    }

    private const val FRAME_SOURCE_START_TIMEOUT_MILLIS = 45_000L
}
