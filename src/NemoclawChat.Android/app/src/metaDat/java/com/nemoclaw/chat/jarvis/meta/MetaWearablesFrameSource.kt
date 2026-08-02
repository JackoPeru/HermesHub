package com.nemoclaw.chat.jarvis.meta

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
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
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.nemoclaw.chat.jarvis.JarvisFrameSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MetaWearablesFrameSource(context: Context) : JarvisFrameSource {
    override val label: String = "Ray-Ban Meta (DAT 0.8.0)"
    private val appContext = context.applicationContext
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val streamStarting = AtomicBoolean(false)
    private val failureDelivered = AtomicBoolean(false)
    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
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
        this.onFrame = onFrame
        this.onError = onError
        withContext(Dispatchers.Main.immediate) {
            Wearables.initialize(appContext).getOrThrow()
        }

        val deviceId = awaitAvailableDevice()
        ensureWearableCameraPermission()
        ready = CompletableDeferred()
        val created = withContext(Dispatchers.Main.immediate) {
            Wearables.createSession(SpecificDeviceSelector(deviceId)).getOrThrow()
        }
        session = created
        monitorSession(created)
        withContext(Dispatchers.Main.immediate) { created.start() }
        try {
            ready.await()
        } catch (error: Throwable) {
            cleanupSession()
            throw error
        }
    }

    private fun monitorSession(created: DeviceSession) {
        sessionErrorJob = sdkScope.launch {
            created.errors.collect { error ->
                deliverFailure(IllegalStateException(describeSessionError(error)))
            }
        }
        sessionStateJob = sdkScope.launch {
            var sessionWasStarted = false
            created.state.collect { state ->
                when (state) {
                    DeviceSessionState.STARTED -> {
                        sessionWasStarted = true
                        if (stream == null && streamStarting.compareAndSet(false, true)) {
                            startStream(created)
                        }
                    }
                    DeviceSessionState.STOPPED -> if (sessionWasStarted) {
                        deliverFailure(IllegalStateException("Gli occhiali hanno chiuso la sessione DAT."))
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun awaitAvailableDevice(): DeviceIdentifier {
        repeat(DEVICE_DISCOVERY_ATTEMPTS) {
            val deviceId = Wearables.devices.value.firstOrNull { candidate ->
                Wearables.devicesMetadata[candidate]?.value?.linkState == LinkState.CONNECTED
            }
            if (deviceId != null) return deviceId
            delay(DEVICE_DISCOVERY_DELAY_MILLIS)
        }
        throw IllegalStateException(
            "Nessun Ray-Ban Meta connesso al DAT. Apri gli occhiali e verifica che Meta AI li mostri connessi."
        )
    }

    private suspend fun ensureWearableCameraPermission() {
        val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrThrow()
        check(status == PermissionStatus.Granted) {
            "Permesso fotocamera degli occhiali mancante. Tocca Configura occhiali Meta e concedilo una sola volta."
        }
    }

    private suspend fun startStream(activeSession: DeviceSession) {
        withContext(Dispatchers.Main.immediate) {
            activeSession.addStream(
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24)
            ).onSuccess { createdStream ->
                stream = createdStream
                monitorStream(createdStream)
                createdStream.start().onFailure { error, _ ->
                    streamStarting.set(false)
                    deliverFailure(IllegalStateException(error.getLocalizedDescription(appContext)))
                }
            }.onFailure { error, _ ->
                streamStarting.set(false)
                deliverFailure(IllegalStateException(error.getLocalizedDescription(appContext)))
            }
        }
    }

    private fun monitorStream(createdStream: Stream) {
        videoJob?.cancel()
        videoJob = workerScope.launch {
            createdStream.videoStream.collect { frame ->
                val jpeg = frame.toJpeg(76) ?: return@collect
                onFrame?.invoke(jpeg, System.currentTimeMillis())
            }
        }
        streamErrorJob?.cancel()
        streamErrorJob = sdkScope.launch {
            createdStream.errorStream.collect { error ->
                if (error != StreamError.STREAM_ERROR) {
                    deliverFailure(IllegalStateException(error.getLocalizedDescription(appContext)))
                }
            }
        }
        streamStateJob?.cancel()
        streamStateJob = sdkScope.launch {
            var streamWasActive = false
            createdStream.state.collect { state ->
                when (state) {
                    StreamState.STARTING, StreamState.STARTED -> streamWasActive = true
                    StreamState.STREAMING -> {
                        streamWasActive = true
                        streamStarting.set(false)
                        if (!ready.isCompleted) ready.complete(Unit)
                    }
                    StreamState.STOPPED, StreamState.CLOSED -> if (streamWasActive) {
                        streamStarting.set(false)
                        deliverFailure(IllegalStateException("Lo stream video DAT si e chiuso."))
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun cleanupStream() {
        videoJob?.cancel()
        videoJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
        withContext(Dispatchers.Main.immediate) {
            stream?.stop()
            stream = null
        }
        streamStarting.set(false)
    }

    private suspend fun cleanupSession() {
        cleanupStream()
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
        if (stream == null && streamStarting.compareAndSet(false, true)) {
            ready = CompletableDeferred()
            startStream(active)
            ready.await()
        }
    }

    override suspend fun stop() {
        if (!closed.compareAndSet(false, true)) return
        onFrame = null
        onError = null
        cleanupSession()
        sdkScope.cancel()
        workerScope.cancel()
    }

    private fun deliverFailure(error: Throwable) {
        if (!ready.isCompleted) {
            ready.completeExceptionally(error)
            return
        }
        if (!closed.get() && failureDelivered.compareAndSet(false, true)) {
            onError?.invoke(error)
        }
    }

    private companion object {
        const val DEVICE_DISCOVERY_ATTEMPTS = 48
        const val DEVICE_DISCOVERY_DELAY_MILLIS = 250L
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
