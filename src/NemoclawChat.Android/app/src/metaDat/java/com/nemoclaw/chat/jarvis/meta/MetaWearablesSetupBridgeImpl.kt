package com.nemoclaw.chat.jarvis.meta

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class MetaWearablesSetupBridgeImpl : MetaWearablesSetupBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var permissionLauncher: ActivityResultLauncher<Permission>? = null
    private var statusCallback: ((String) -> Unit)? = null

    override fun initialize(activity: ComponentActivity, onStatus: (String) -> Unit) {
        statusCallback = onStatus
        permissionLauncher = activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val value = result.getOrDefault(PermissionStatus.Denied)
            onStatus(if (value == PermissionStatus.Granted) "Permesso fotocamera occhiali concesso." else "Permesso fotocamera occhiali negato.")
        }
        Wearables.initialize(activity.applicationContext)
        monitor?.cancel()
        monitor = scope.launch {
            Wearables.registrationState.collect { state -> onStatus("Registrazione Meta: $state") }
        }
    }

    override fun startRegistration(activity: ComponentActivity) {
        Wearables.startRegistration(activity)
        statusCallback?.invoke("Apro Meta AI per la registrazione...")
    }

    override fun requestCameraPermission() {
        val launcher = permissionLauncher ?: error("Bridge Meta non inizializzato.")
        scope.launch {
            val current = Wearables.checkPermissionStatus(Permission.CAMERA)
            if (current.getOrNull() == PermissionStatus.Granted) {
                statusCallback?.invoke("Permesso fotocamera occhiali gia concesso.")
            } else {
                launcher.launch(Permission.CAMERA)
            }
        }
    }

    override fun enableMockPhoneCamera(activity: ComponentActivity) {
        val kit = MockDeviceKit.getInstance(activity.applicationContext)
        kit.enable()
        scope.launch {
            kit.pairGlasses(GlassesModel.RAYBAN_META)
                .onSuccess { glasses ->
                    glasses.powerOn()
                    glasses.unfold()
                    glasses.don()
                    glasses.services.camera.setCameraFeed(CameraFacing.BACK)
                    statusCallback?.invoke("Mock Ray-Ban Meta attivo con fotocamera posteriore.")
                }
                .onFailure { error, _ -> statusCallback?.invoke("Mock DAT non disponibile: ${error.description}") }
        }
    }

    override fun close() {
        monitor?.cancel()
        monitor = null
        permissionLauncher = null
        statusCallback = null
        scope.cancel()
    }
}
