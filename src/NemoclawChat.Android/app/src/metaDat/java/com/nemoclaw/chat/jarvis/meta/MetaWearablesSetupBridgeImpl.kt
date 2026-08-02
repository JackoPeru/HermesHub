package com.nemoclaw.chat.jarvis.meta

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.DeviceIdentifier
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
    private var registrationMonitor: Job? = null
    private var registrationErrorMonitor: Job? = null
    private var devicesMonitor: Job? = null
    private val deviceMetadataMonitors = mutableMapOf<DeviceIdentifier, Job>()
    private var permissionLauncher: ActivityResultLauncher<Permission>? = null
    private var statusCallback: ((String) -> Unit)? = null
    private var registrationStatus = "In attesa"
    private val deviceStatuses = linkedMapOf<DeviceIdentifier, String>()

    override fun initialize(activity: ComponentActivity, onStatus: (String) -> Unit) {
        statusCallback = onStatus
        permissionLauncher = activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val value = result.getOrDefault(PermissionStatus.Denied)
            onStatus(if (value == PermissionStatus.Granted) "Permesso fotocamera occhiali concesso." else "Permesso fotocamera occhiali negato.")
        }
        Wearables.initialize(activity.applicationContext)
        registrationMonitor?.cancel()
        registrationMonitor = scope.launch {
            Wearables.registrationState.collect { state ->
                registrationStatus = state.toString()
                publishStatus()
            }
        }
        registrationErrorMonitor?.cancel()
        registrationErrorMonitor = scope.launch {
            Wearables.registrationErrorStream.collect { error ->
                publishStatus("Errore registrazione Meta: ${error.getLocalizedDescription(activity)}")
            }
        }
        devicesMonitor?.cancel()
        devicesMonitor = scope.launch {
            Wearables.devices.collect { deviceIds ->
                val removedDeviceIds = deviceMetadataMonitors.keys.filter { it !in deviceIds }
                removedDeviceIds.forEach { deviceId ->
                    deviceMetadataMonitors.remove(deviceId)?.cancel()
                    deviceStatuses.remove(deviceId)
                }
                if (deviceIds.isEmpty()) {
                    deviceStatuses.clear()
                    publishStatus()
                }
                deviceIds.forEach { deviceId ->
                    if (deviceId in deviceMetadataMonitors) return@forEach
                    val metadata = Wearables.devicesMetadata[deviceId]
                    if (metadata == null) {
                        deviceStatuses[deviceId] = "metadati in attesa"
                        publishStatus()
                        return@forEach
                    }
                    deviceMetadataMonitors[deviceId] = scope.launch {
                        metadata.collect { device ->
                            deviceStatuses[deviceId] = "${device.name}: ${device.linkState} (${device.compatibility})"
                            publishStatus()
                        }
                    }
                }
            }
        }
    }

    override fun startRegistration(activity: ComponentActivity) {
        if (registrationStatus.equals("REGISTERED", ignoreCase = true)) {
            publishStatus("App gia registrata. Meta AI non apre un secondo flusso.")
            return
        }
        Wearables.startRegistration(activity)
        publishStatus("Apro Meta AI per la registrazione...")
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

    override fun openDatGlassesAppUpdate(activity: ComponentActivity) {
        Wearables.openDATGlassesAppUpdate(activity).onFailure { error, _ ->
            publishStatus("Impossibile aprire l'aggiornamento DAT: ${error.description}")
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
        registrationMonitor?.cancel()
        registrationMonitor = null
        registrationErrorMonitor?.cancel()
        registrationErrorMonitor = null
        devicesMonitor?.cancel()
        devicesMonitor = null
        deviceMetadataMonitors.values.forEach(Job::cancel)
        deviceMetadataMonitors.clear()
        deviceStatuses.clear()
        permissionLauncher = null
        statusCallback = null
        scope.cancel()
    }

    private fun publishStatus(prefix: String? = null) {
        val devices = if (deviceStatuses.isEmpty()) {
            "nessun Ray-Ban esposto dal DAT"
        } else {
            deviceStatuses.values.joinToString(separator = " | ")
        }
        statusCallback?.invoke(
            listOfNotNull(prefix, "Registrazione Meta: $registrationStatus", "Occhiali DAT: $devices")
                .joinToString(separator = "\n")
        )
    }
}
