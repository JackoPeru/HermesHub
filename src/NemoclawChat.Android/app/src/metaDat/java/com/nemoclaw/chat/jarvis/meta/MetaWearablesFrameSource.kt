package com.nemoclaw.chat.jarvis.meta

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.removeStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.nemoclaw.chat.jarvis.FrameSampler
import com.nemoclaw.chat.jarvis.JarvisFrameSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal class MetaWearablesFrameSource(context: Context) : JarvisFrameSource {
    override val label: String = "Ray-Ban Meta (DAT 0.8.0)"
    private val appContext = context.applicationContext
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val streamStarting = AtomicBoolean(false)
    private val failureDelivered = AtomicBoolean(false)
    private val operationMutex = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)
    private val streamGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val rawFrameSampler = FrameSampler()
    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var deviceLinkJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var videoJob: Job? = null
    private var onFrame: (suspend (ByteArray, Long) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var ready = CompletableDeferred<Unit>()

    override suspend fun start(
        onFrame: suspend (ByteArray, Long) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        check(!closed.get()) { "Sessione DAT gia chiusa." }
        val callbackGeneration = generation.incrementAndGet()
        failureDelivered.set(false)
        this.onFrame = onFrame
        this.onError = onError
        rawFrameSampler.reset()
        withContext(Dispatchers.Main.immediate) {
            MetaWearablesRuntime.initialize(appContext)
        }

        awaitRegistration()
        val deviceId = awaitAvailableDevice()
        ensureWearableCameraPermission()
        ready = CompletableDeferred()
        val created = withContext(Dispatchers.Main.immediate) {
            Wearables.createSession(SpecificDeviceSelector(deviceId)).getOrThrow()
        }
        session = created
        monitorDeviceLink(deviceId, callbackGeneration)
        monitorSession(created, callbackGeneration)
        withContext(Dispatchers.Main.immediate) { created.start() }
        try {
            withTimeout(STREAM_READY_TIMEOUT_MILLIS) { ready.await() }
        } catch (error: TimeoutCancellationException) {
            cleanupSession()
            throw IllegalStateException(
                "Il video degli occhiali non e diventato attivo entro ${STREAM_READY_TIMEOUT_MILLIS / 1_000} secondi. " +
                    "Verifica che gli occhiali restino indossati e connessi in Meta AI."
            )
        } catch (error: Throwable) {
            cleanupSession()
            throw error
        }
    }

    private suspend fun awaitRegistration() {
        val registered = withTimeoutOrNull(REGISTRATION_READY_TIMEOUT_MILLIS) {
            Wearables.registrationState.first { it == RegistrationState.REGISTERED }
        }
        check(registered != null) {
            "App Meta non registrata. Apri Configura occhiali Meta, completa la registrazione e poi riprova."
        }
    }

    private fun monitorSession(created: DeviceSession, callbackGeneration: Long) {
        sessionErrorJob = sdkScope.launch {
            created.errors.collect { error ->
                deliverFailure(IllegalStateException(describeSessionError(error)), callbackGeneration)
            }
        }
        sessionStateJob = sdkScope.launch {
            var sessionWasStarted = false
            created.state.collect { state ->
                if (!isCurrentSession(callbackGeneration)) return@collect
                when (state) {
                    DeviceSessionState.STARTED -> {
                        sessionWasStarted = true
                        if (stream == null && streamStarting.compareAndSet(false, true)) {
                            startStream(created, callbackGeneration)
                        }
                    }
                    DeviceSessionState.PAUSED -> {
                        // DAT can pause the session independently of the client.
                        // Detach the stopped capability before STARTED is allowed
                        // to create a fresh stream.
                        if (stream != null || streamStarting.get()) {
                            cleanupStream()
                        }
                    }
                    DeviceSessionState.STOPPED -> if (sessionWasStarted) {
                        deliverFailure(IllegalStateException("Gli occhiali hanno chiuso la sessione DAT."), callbackGeneration)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun monitorDeviceLink(deviceId: DeviceIdentifier, callbackGeneration: Long) {
        deviceLinkJob?.cancel()
        val metadata = Wearables.devicesMetadata[deviceId] ?: return
        deviceLinkJob = sdkScope.launch {
            var wasConnected = false
            metadata.collect { device ->
                if (!isCurrentSession(callbackGeneration)) return@collect
                if (device.linkState == LinkState.CONNECTED) {
                    wasConnected = true
                } else if (wasConnected) {
                    deliverFailure(IllegalStateException("Il collegamento DAT con gli occhiali si e interrotto."), callbackGeneration)
                }
            }
        }
    }

    private suspend fun awaitAvailableDevice(): DeviceIdentifier {
        return withTimeoutOrNull(DEVICE_READY_TIMEOUT_MILLIS) {
            while (true) {
                val deviceId = awaitConnectedDeviceEvent()
                delay(LINK_STABILIZATION_MILLIS)
                val stillConnected = deviceId in Wearables.devices.value &&
                    isEligibleDevice(deviceId)
                if (stillConnected) return@withTimeoutOrNull deviceId
            }
            error("Attesa connessione DAT terminata senza dispositivo.")
        } ?: throw IllegalStateException(
            "Nessun Ray-Ban Meta connesso al DAT. Apri gli occhiali e verifica che Meta AI li mostri connessi."
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitConnectedDeviceEvent(): DeviceIdentifier {
        return Wearables.devices.flatMapLatest(::connectedDeviceEvents)
            .filterNotNull()
            .first()
    }

    private fun connectedDeviceEvents(deviceIds: Set<DeviceIdentifier>) = flow {
        while (true) {
            val metadataFlows = deviceIds.mapNotNull { deviceId ->
                Wearables.devicesMetadata[deviceId]?.map { device ->
                    if (device.linkState == LinkState.CONNECTED &&
                        device.compatibility == DeviceCompatibility.COMPATIBLE &&
                        device.deviceType == DeviceType.RAYBAN_META
                    ) deviceId else null
                }
            }
            if (metadataFlows.isNotEmpty()) {
                emitAll(merge(*metadataFlows.toTypedArray()))
            }
            // The SDK exposes device metadata in a per-device StateFlow. Wait briefly only
            // for that flow to be published. A devices change cancels this branch and rebuilds
            // it, so a newly exposed Ray-Ban cannot be hidden by an older disconnected device.
            delay(METADATA_FLOW_WAIT_MILLIS)
        }
    }

    private suspend fun ensureWearableCameraPermission() {
        val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrThrow()
        check(status == PermissionStatus.Granted) {
            "Permesso fotocamera degli occhiali mancante. Tocca Configura occhiali Meta e concedilo una sola volta."
        }
    }

    private suspend fun startStream(activeSession: DeviceSession, callbackGeneration: Long) = operationMutex.withLock {
        if (!isCurrentSession(callbackGeneration) || activeSession.state.value != DeviceSessionState.STARTED) {
            streamStarting.set(false)
            return@withLock
        }
        val streamCallbackGeneration = streamGeneration.incrementAndGet()
        withContext(Dispatchers.Main.immediate) {
            activeSession.addStream(
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = DAT_FRAME_RATE)
            ).onSuccess { createdStream ->
                if (!isCurrentStream(callbackGeneration, streamCallbackGeneration) || activeSession.state.value != DeviceSessionState.STARTED) {
                    runCatching { createdStream.stop() }
                    sdkScope.launch(Dispatchers.Main.immediate) { runCatching { activeSession.removeStream() } }
                    streamStarting.set(false)
                    return@onSuccess
                }
                stream = createdStream
                monitorStream(createdStream, callbackGeneration, streamCallbackGeneration)
                createdStream.start().onFailure { error, _ ->
                    streamStarting.set(false)
                    deliverStreamFailure(IllegalStateException(error.getLocalizedDescription(appContext)), callbackGeneration, streamCallbackGeneration)
                }
            }.onFailure { error, _ ->
                streamStarting.set(false)
                deliverStreamFailure(IllegalStateException(error.getLocalizedDescription(appContext)), callbackGeneration, streamCallbackGeneration)
            }
        }
    }

    private fun monitorStream(createdStream: Stream, callbackGeneration: Long, streamCallbackGeneration: Long) {
        videoJob?.cancel()
        videoJob = workerScope.launch {
            createdStream.videoStream.collect { frame ->
                val capturedAt = System.currentTimeMillis()
                if (!rawFrameSampler.shouldAccept(capturedAt, frame.lumaSignature())) return@collect
                val jpeg = frame.toJpeg(76) ?: return@collect
                onFrame?.invoke(jpeg, capturedAt)
            }
        }
        streamErrorJob?.cancel()
        streamErrorJob = sdkScope.launch {
            createdStream.errorStream.collect { error ->
                if (isCurrentStream(callbackGeneration, streamCallbackGeneration)) {
                    deliverStreamFailure(IllegalStateException(error.getLocalizedDescription(appContext)), callbackGeneration, streamCallbackGeneration)
                }
            }
        }
        streamStateJob?.cancel()
        streamStateJob = sdkScope.launch {
            var streamWasActive = false
            createdStream.state.collect { state ->
                if (!isCurrentStream(callbackGeneration, streamCallbackGeneration)) return@collect
                when (state) {
                    StreamState.STARTING, StreamState.STARTED -> streamWasActive = true
                    StreamState.STREAMING -> {
                        streamWasActive = true
                        streamStarting.set(false)
                        if (!ready.isCompleted) ready.complete(Unit)
                    }
                    StreamState.PAUSED -> Unit
                    StreamState.STOPPED, StreamState.CLOSED -> if (streamWasActive) {
                        streamStarting.set(false)
                        deliverStreamFailure(IllegalStateException("Lo stream video DAT si e chiuso."), callbackGeneration, streamCallbackGeneration)
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun cleanupStream() {
        operationMutex.withLock {
            streamGeneration.incrementAndGet()
            videoJob?.cancel()
            videoJob = null
            streamStateJob?.cancel()
            streamStateJob = null
            streamErrorJob?.cancel()
            streamErrorJob = null
            val oldStream = stream
            val oldSession = session
            stream = null
            withContext(Dispatchers.Main.immediate) {
                runCatching { oldStream?.stop() }
                if (oldStream != null && oldSession != null) {
                    runCatching { oldSession.removeStream() }
                }
            }
            streamStarting.set(false)
        }
    }

    private suspend fun cleanupSession() {
        generation.incrementAndGet()
        cleanupStream()
        deviceLinkJob?.cancel()
        deviceLinkJob = null
        sessionStateJob?.cancel()
        sessionStateJob = null
        sessionErrorJob?.cancel()
        sessionErrorJob = null
        withContext(Dispatchers.Main.immediate) {
            session?.stop()
            session = null
        }
    }

    override suspend fun pause() {
        cleanupStream()
    }

    override suspend fun resume() {
        val active = session ?: return
        val callbackGeneration = try {
            operationMutex.withLock {
                if (closed.get() || stream != null || !streamStarting.compareAndSet(false, true)) return@withLock null
                val nextGeneration = generation.get()
                rawFrameSampler.reset()
                ready = CompletableDeferred()
                val state = withTimeoutOrNull(RESUME_SESSION_READY_TIMEOUT_MILLIS) {
                    active.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED }
                } ?: throw IllegalStateException("La sessione DAT non e tornata pronta entro ${RESUME_SESSION_READY_TIMEOUT_MILLIS / 1_000} secondi.")
                check(state == DeviceSessionState.STARTED) { "La sessione DAT e stata chiusa durante la ripresa." }
                nextGeneration
            }
        } catch (error: Throwable) {
            streamStarting.set(false)
            throw error
        } ?: return
        try {
            startStream(active, callbackGeneration)
            withTimeout(STREAM_READY_TIMEOUT_MILLIS) { ready.await() }
        } catch (error: TimeoutCancellationException) {
            cleanupStream()
            throw IllegalStateException(
                "Il video degli occhiali non e diventato attivo entro ${STREAM_READY_TIMEOUT_MILLIS / 1_000} secondi."
            )
        } catch (error: Throwable) {
            cleanupStream()
            throw error
        }
    }

    override suspend fun stop() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        onFrame = null
        onError = null
        cleanupSession()
        sdkScope.cancel()
        workerScope.cancel()
    }

    private fun deliverFailure(error: Throwable, callbackGeneration: Long = generation.get()) {
        if (!isCurrentSession(callbackGeneration) || closed.get() || !failureDelivered.compareAndSet(false, true)) return
        if (!ready.isCompleted) ready.completeExceptionally(error) else onError?.invoke(error)
    }

    private fun isCurrentSession(callbackGeneration: Long): Boolean =
        !closed.get() && generation.get() == callbackGeneration

    private fun isCurrentStream(callbackGeneration: Long, streamCallbackGeneration: Long): Boolean =
        isCurrentSession(callbackGeneration) && streamGeneration.get() == streamCallbackGeneration

    private fun deliverStreamFailure(error: Throwable, callbackGeneration: Long, streamCallbackGeneration: Long) {
        if (isCurrentStream(callbackGeneration, streamCallbackGeneration)) {
            deliverFailure(error, callbackGeneration)
        }
    }

    private fun isEligibleDevice(deviceId: DeviceIdentifier): Boolean {
        val device = Wearables.devicesMetadata[deviceId]?.value ?: return false
        return device.linkState == LinkState.CONNECTED &&
            device.compatibility == DeviceCompatibility.COMPATIBLE &&
            device.deviceType == DeviceType.RAYBAN_META
    }

    private companion object {
        const val REGISTRATION_READY_TIMEOUT_MILLIS = 8_000L
        const val DEVICE_READY_TIMEOUT_MILLIS = 18_000L
        const val METADATA_FLOW_WAIT_MILLIS = 100L
        const val LINK_STABILIZATION_MILLIS = 750L
        const val STREAM_READY_TIMEOUT_MILLIS = 18_000L
        const val RESUME_SESSION_READY_TIMEOUT_MILLIS = 8_000L
        const val DAT_FRAME_RATE = 7
    }
}

private fun describeSessionError(error: DeviceSessionError): String = when (error) {
    DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
        "Il componente DAT sugli occhiali deve essere aggiornato. Apri Configura occhiali Meta e tocca Aggiorna DAT."
    DeviceSessionError.SESSION_ENDED_BY_DEVICE ->
        "Gli occhiali hanno rifiutato la sessione DAT. Verifica che questa build sia registrata nella stessa modalita usata in Meta AI."
    else -> error.description
}

private fun VideoFrame.toJpeg(quality: Int): ByteArray? {
    val ySize = width * height
    val uvSize = ySize / 4
    if (ySize <= 0 || buffer.remaining() < ySize + uvSize * 2) return null
    val source = buffer.duplicate().apply { rewind() }
    val i420 = ByteArray(ySize + uvSize * 2)
    source.get(i420)
    val nv21 = ByteArray(i420.size)
    System.arraycopy(i420, 0, nv21, 0, ySize)
    val uOffset = ySize
    val vOffset = ySize + uvSize
    for (index in 0 until uvSize) {
        nv21[ySize + index * 2] = i420[vOffset + index]
        nv21[ySize + index * 2 + 1] = i420[uOffset + index]
    }
    return ByteArrayOutputStream().use { output ->
        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        if (!image.compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(45, 90), output)) null
        else output.toByteArray()
    }
}

private fun VideoFrame.lumaSignature(): Long {
    if (width <= 0 || height <= 0) return 0L
    val ySize = width * height
    if (buffer.remaining() < ySize) return 0L
    val source = buffer.duplicate().apply { rewind() }
    val values = IntArray(64)
    for (cellY in 0 until 8) {
        val y = ((cellY + 0.5) * height / 8.0).toInt().coerceIn(0, height - 1)
        for (cellX in 0 until 8) {
            val x = ((cellX + 0.5) * width / 8.0).toInt().coerceIn(0, width - 1)
            values[cellY * 8 + cellX] = source.get(y * width + x).toInt() and 0xff
        }
    }
    val average = values.average()
    var signature = 0L
    values.forEachIndexed { index, value ->
        if (value >= average) signature = signature or (1L shl index)
    }
    return signature
}
