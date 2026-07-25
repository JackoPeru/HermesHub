package com.nemoclaw.chat.jarvis.meta

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.nemoclaw.chat.jarvis.JarvisFrameSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class MetaWearablesFrameSource(context: Context) : JarvisFrameSource {
    override val label: String = "Ray-Ban Meta (DAT 0.8.0)"
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var sessionStateJob: Job? = null
    private var videoJob: Job? = null
    private var errorJob: Job? = null
    private var onFrame: (suspend (ByteArray, Long) -> Unit)? = null
    private var ready = CompletableDeferred<Unit>()

    override suspend fun start(onFrame: suspend (ByteArray, Long) -> Unit) {
        check(!closed.get()) { "Sessione DAT gia chiusa." }
        this.onFrame = onFrame
        Wearables.initialize(appContext)
        val created = Wearables.createSession(AutoDeviceSelector()).getOrThrow()
        session = created
        errorJob = scope.launch {
            created.errors.collect { error ->
                if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(error.description))
            }
        }
        sessionStateJob = scope.launch {
            created.state.collect { state ->
                if (state == DeviceSessionState.STARTED && stream == null) startStream(created)
            }
        }
        created.start()
        ready.await()
    }

    private fun startStream(activeSession: DeviceSession) {
        activeSession.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 7))
            .onSuccess { createdStream ->
                stream = createdStream
                videoJob?.cancel()
                videoJob = scope.launch {
                    createdStream.videoStream.collect { frame ->
                        val jpeg = frame.toJpeg(76) ?: return@collect
                        onFrame?.invoke(jpeg, System.currentTimeMillis())
                    }
                }
                createdStream.start()
                if (!ready.isCompleted) ready.complete(Unit)
            }
            .onFailure { error, _ ->
                if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(error.description))
            }
    }

    override suspend fun pause() {
        videoJob?.cancel()
        videoJob = null
        stream?.stop()
        stream = null
    }

    override suspend fun resume() {
        val active = session ?: return
        if (stream == null) {
            ready = CompletableDeferred()
            startStream(active)
            ready.await()
        }
    }

    override suspend fun stop() {
        if (!closed.compareAndSet(false, true)) return
        onFrame = null
        videoJob?.cancel()
        videoJob = null
        sessionStateJob?.cancel()
        sessionStateJob = null
        errorJob?.cancel()
        errorJob = null
        stream?.stop()
        stream = null
        session?.stop()
        session = null
        scope.cancel()
    }
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
