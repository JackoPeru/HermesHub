package com.nemoclaw.chat.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PhoneCameraFrameSource(private val context: Context) : JarvisFrameSource, Closeable {
    override val label: String = "Fotocamera telefono"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest? = null
    private var onFrame: (suspend (ByteArray, Long) -> Unit)? = null

    @SuppressLint("MissingPermission")
    override suspend fun start(
        onFrame: suspend (ByteArray, Long) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Permesso fotocamera non disponibile."
        }
        check(!closed.get()) { "Sorgente fotocamera gia chiusa." }
        this.onFrame = onFrame
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull() ?: error("Nessuna fotocamera disponibile.")

        val worker = HandlerThread("HermesJarvisCamera").also { it.start() }
        thread = worker
        val workerHandler = Handler(worker.looper)
        handler = workerHandler
        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val callback = this.onFrame ?: return@setOnImageAvailableListener
                scope.launch { callback(bytes, System.currentTimeMillis()) }
            } finally {
                image.close()
            }
        }, workerHandler)

        val opened = suspendCancellableCoroutine<CameraDevice> { continuation ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (continuation.isActive) continuation.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Fotocamera disconnessa."))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Errore fotocamera $error."))
                }
            }, workerHandler)
            continuation.invokeOnCancellation { close() }
        }
        device = opened
        val captureSession = suspendCancellableCoroutine<CameraCaptureSession> { continuation ->
            opened.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        if (continuation.isActive) continuation.resume(value) else value.close()
                    }

                    override fun onConfigureFailed(value: CameraCaptureSession) {
                        value.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Configurazione fotocamera fallita."))
                    }
                },
                workerHandler
            )
        }
        session = captureSession
        request = opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }.build()
        captureSession.setRepeatingRequest(checkNotNull(request), null, workerHandler)
    }

    override suspend fun pause() {
        runCatching { session?.stopRepeating() }
    }

    override suspend fun resume() {
        val activeSession = session ?: return
        val activeRequest = request ?: return
        activeSession.setRepeatingRequest(activeRequest, null, handler)
    }

    override suspend fun stop() = close()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onFrame = null
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        session = null
        request = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        handler = null
        thread?.quitSafely()
        thread = null
        scope.cancel()
    }
}
