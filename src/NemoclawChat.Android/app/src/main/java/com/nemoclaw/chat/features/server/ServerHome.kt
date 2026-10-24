package com.nemoclaw.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import com.nemoclaw.chat.ui.apple.AppleGlass
import com.nemoclaw.chat.ui.apple.AppleMotion
import com.nemoclaw.chat.ui.apple.AppleType
import com.nemoclaw.chat.ui.apple.AuroraBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nemoclaw.chat.resolveHermesUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

@Composable
internal fun ServerScreen(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var wsProbe by remember(settings) {
        mutableStateOf(
            GatewayWsProbe(
                wsUrl = "${settings.gatewayUrl.trimEnd('/')}/capabilities",
                connected = false,
                status = "Nessun controllo capabilities eseguito.",
                detail = "Leggi /v1/capabilities e /v1/models per verificare Hermes."
            )
        )
    }
    var snapshot by remember(settings) {
        mutableStateOf(
            ServerSnapshot(
                gateway = settings.gatewayUrl,
                model = settings.model,
                providerDetail = "Provider: ${settings.provider} | API: ${settings.preferredApi}",
                inferenceEndpoint = settings.inferenceEndpoint,
                policy = settings.accessMode,
                statusMessage = if (settings.demoMode) "Fallback locale attivo. Provero' comunque a usare Hermes." else "Solo Hermes. Verifica lo stato del server.",
                videoLibraryPath = settings.videoLibraryPath
            )
        )
    }
    var diagnostics by remember { mutableStateOf<List<DiagnosticCheck>>(emptyList()) }
    var controlService by rememberSaveable { mutableStateOf("gateway") }
    var controlOutput by remember { mutableStateOf("Centro controllo non ancora interrogato.") }
    var logFilter by rememberSaveable { mutableStateOf("") }
    var pendingControlAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingMaintenanceAction by remember { mutableStateOf<String?>(null) }
    var deviceIdState by remember { mutableStateOf(loadDeviceId(context)) }
    var deviceStatus by remember { mutableStateOf("Telefono non registrato: le azioni server usano solo la chiave.") }
    var deviceBusy by remember { mutableStateOf(false) }
    var registeredDevices by remember { mutableStateOf<List<RegisteredDevice>>(emptyList()) }
    var devicesMsg by remember { mutableStateOf("") }
    var pushStatus by remember { mutableStateOf("Notifiche non attive.") }
    var pushBusy by remember { mutableStateOf(false) }
    val pushPermissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    suspend fun postServerControlSigned(
        url: String,
        payload: JSONObject,
        actionLabel: String,
        onDone: (String) -> Unit
    ) {
        val devId = deviceIdState
        if (devId == null) {
            val result = runCatching {
                postJson(url, payload, loadGatewaySecret(context), allowCompatAuth = false).second
            }.getOrElse { it.message ?: "Errore" }
            onDone(result)
            return
        }
        val activity = findFragmentActivity(context)
        if (activity == null) {
            onDone("Activity non disponibile per la conferma biometrica.")
            return
        }
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        authenticateDeviceOwner(
            activity,
            "Conferma $actionLabel",
            "Firma con impronta o PIN per eseguire.",
            onSuccess = { gate.complete(true) },
            onError = { gate.completeExceptionally(IllegalStateException(it)) }
        )
        try {
            gate.await()
        } catch (denied: Exception) {
            onDone(denied.message ?: "Autenticazione annullata.")
            return
        }
        val result = runCatching {
            postDeviceSignedJson(url, payload, devId, loadGatewaySecret(context)).let { (code, body) ->
                if (code in 200..299) body.ifBlank { "Eseguito (firmato)." } else "Server HTTP $code: ${body.take(200)}"
            }
        }.getOrElse { it.message ?: "Errore" }
        onDone(result)
    }

    fun refreshDevices() {
        scope.launch {
            devicesMsg = "Leggo…"
            try {
                val body = httpGet(resolveHermesUrl(settings, "/v1/hub/devices"), loadGatewaySecret(context))
                val arr = JSONObject(body).optJSONArray("devices") ?: JSONArray()
                registeredDevices = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    RegisteredDevice(
                        o.optString("device_id"),
                        o.optString("label"),
                        o.optBoolean("revoked"),
                        o.optBoolean("has_token")
                    )
                }.filter { it.id.isNotBlank() }
                devicesMsg = if (registeredDevices.isEmpty()) "Nessun telefono registrato."
                else "${registeredDevices.size} registrati (un telefono che non riconosci: revocalo)."
            } catch (failed: Exception) {
                devicesMsg = failed.message ?: "Errore lettura."
            }
        }
    }

    fun revokeDevice(id: String) {
        scope.launch {
            devicesMsg = runCatching {
                val (code, _) = postJson(
                    resolveHermesUrl(settings, "/v1/hub/devices/$id"),
                    JSONObject(), loadGatewaySecret(context), method = "DELETE", allowCompatAuth = false
                )
                if (code !in 200..299) "Revoca HTTP $code" else ""
            }.getOrElse { it.message ?: "Errore revoca." }
            if (devicesMsg.isBlank()) {
                if (deviceIdState == id) {
                    clearDeviceId(context)
                    deviceIdState = null
                    deviceStatus = "Telefono non registrato: le azioni server usano solo la chiave."
                }
                refreshDevices()
            }
        }
    }
    val maintenanceLabels = mapOf(
        "update" to "Aggiorna",
        "rollback" to "Ripristina versione",
        "backup" to "Salva backup",
        "restore" to "Ripristina backup",
        "diagnostic" to "Diagnostica"
    )

    LaunchedEffect(settings) {
        snapshot = loadServerSnapshot(context, settings, loadGatewaySecret(context))
        diagnostics = runDiagnostics(settings, loadGatewaySecret(context))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D10))
    ) {
        AuroraBackground(intensity = 1f)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppColors.Accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Dns, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text("Hermes Server", color = Color.White, fontSize = AppleType.ScreenTitle, fontWeight = FontWeight.SemiBold)
                        Text("Dashboard Hermes Agent API: health, detailed health, models e capabilities.", color = AppColors.Faint, fontSize = AppleType.Footnote)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ServerMetric("Hermes API", snapshot.gateway, "Health endpoint: ${hermesRoot(settings)}/health", valueColor = AppColors.Success)
                ServerMetric("Capabilities", wsProbe.wsUrl, wsProbe.status, valueColor = if (wsProbe.status.contains("lette", ignoreCase = true)) AppColors.Success else AppColors.Warning)
                ServerMetric("Modello", snapshot.model, snapshot.providerDetail)
                ServerMetric("API lato server", snapshot.inferenceEndpoint, "Il client parla a Hermes API, non direttamente al runtime modello.")
                ServerMetric("Sicurezza", snapshot.policy, "Client usa solo la API key Bearer salvata dall'utente.")
                ServerMetric("Cartella video Hermes", snapshot.videoLibraryPath.ifBlank { "In attesa di sync server" }, "Hermes decide path e app lo recepisce da /health/detailed.", valueColor = if (snapshot.videoLibraryPath.isBlank()) AppColors.Warning else Color.White)
            }
        }
        item {
            PulseCard(context = context, settings = settings)
        }
        item {
            GpuComputeCard(context = context, settings = settings)
        }
        item {
            AppleGlass(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AZIONI", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(snapshot.statusMessage, color = AppColors.Faint)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val error = validateHttpUrl(settings.gatewayUrl, "Indirizzo server Hermes")
                                if (error != null) {
                                    snapshot = snapshot.copy(statusMessage = error)
                                    return@Button
                                }
                                val healthUrl = "${hermesRoot(settings)}/health"
                                snapshot = snapshot.copy(statusMessage = "Test: $healthUrl")
                                scope.launch {
                                    snapshot = snapshot.copy(statusMessage = testGateway(healthUrl, loadGatewaySecret(context)))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Test Hermes", fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    snapshot = loadServerSnapshot(context, settings, loadGatewaySecret(context))
                                    diagnostics = runDiagnostics(settings, loadGatewaySecret(context))
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                        ) {
                            Text("Aggiorna stato")
                        }
                        TextButton(
                            onClick = {
                                snapshot = snapshot.copy(statusMessage = "Contratto Hermes: GET /health, GET /health/detailed, GET /v1/models, GET /v1/capabilities, POST /v1/responses, POST /v1/chat/completions, POST /v1/runs, GET/POST /api/jobs cron.")
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)
                        ) {
                            Text("Mostra API")
                        }
                    }
                }
            }
        }
        item {
            AppleGlass(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CENTRO CONTROLLO SERVER", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Azioni tipizzate per Hermes, gateway, llama.cpp e Tailscale; nessuna shell arbitraria.", color = AppColors.Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("hermes", "gateway", "llama", "tailscale").forEach { service ->
                            Button(
                                onClick = { controlService = service },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (controlService == service) AppColors.Accent else AppColors.Panel,
                                    contentColor = if (controlService == service) Color(0xFF171009) else Color.White
                                ),
                                border = if (controlService == service) null else BorderStroke(0.5.dp, AppColors.Border),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text(service) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { pendingControlAction = controlService to "start" }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Avvia") }
                        TextButton(onClick = { pendingControlAction = controlService to "stop" }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Ferma") }
                        TextButton(onClick = { pendingControlAction = controlService to "restart" }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Error)) { Text("Riavvia") }
                        TextButton(onClick = { scope.launch { controlOutput = runCatching { httpGet(resolveHermesUrl(settings, "/v1/hub/server/control") + "?filter=${java.net.URLEncoder.encode(logFilter, "UTF-8")}", loadGatewaySecret(context)) }.getOrElse { it.message ?: "Errore" } } }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Aggiorna output") }
                    }
                    SettingsField("Filtro log", logFilter, { logFilter = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("update", "rollback", "backup", "restore", "diagnostic").forEach { operation -> TextButton(onClick = { pendingMaintenanceAction = operation }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text(maintenanceLabels[operation] ?: operation) } }
                    }
                    SelectionContainer { Text(controlOutput, color = AppColors.Faint, fontSize = AppleType.Caption) }
                }
            }
        }
        item {
            AppleGlass(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DIAGNOSTICA GATEWAY", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    if (diagnostics.isEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.NetworkCheck, contentDescription = null, tint = AppColors.Faint, modifier = Modifier.size(40.dp))
                            Text("Nessuna diagnostica eseguita.", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Esegui un controllo per verificare Hermes.", color = AppColors.Muted, fontSize = AppleType.Footnote)
                            Button(
                                onClick = {
                                    scope.launch {
                                        snapshot = loadServerSnapshot(context, settings, loadGatewaySecret(context))
                                        diagnostics = runDiagnostics(settings, loadGatewaySecret(context))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Esegui diagnostica", fontWeight = FontWeight.SemiBold) }
                        }
                    } else {
                        diagnostics.forEach { check ->
                            Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${if (check.ok) "OK" else "Errore"} - ${check.label}", color = if (check.ok) AppColors.Success else AppColors.Error, fontWeight = FontWeight.SemiBold)
                                Text(check.endpoint, color = AppColors.Faint, fontSize = AppleType.Caption)
                                Text(check.message, color = AppColors.Muted, fontSize = AppleType.Footnote)
                                if (!check.ok) Text("Azione: ${check.action}", color = AppColors.Accent, fontSize = AppleType.Footnote)
                            }
                            HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
        item {
            AppleGlass(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CAPABILITIES HERMES", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(wsProbe.detail, color = AppColors.Faint)
                    Button(
                        onClick = {
                            wsProbe = wsProbe.copy(status = "Lettura capabilities...", detail = "Chiamata a /v1/capabilities e /v1/models.")
                            scope.launch {
                                val capabilities = runCatching { httpGet(resolveHermesUrl(settings, "/v1/capabilities"), loadGatewaySecret(context)) }.getOrElse { it.message ?: it.javaClass.simpleName }
                                val models = runCatching { httpGet(resolveHermesUrl(settings, "/v1/models"), loadGatewaySecret(context)) }.getOrElse { it.message ?: it.javaClass.simpleName }
                                wsProbe = GatewayWsProbe(
                                    wsUrl = resolveHermesUrl(settings, "/v1/capabilities"),
                                    connected = true,
                                    status = "Capabilities lette.",
                                    detail = "Capabilities e models letti da Hermes.",
                                    capabilityLines = listOf("Capabilities: ${capabilities.limitText(240)}", "Models: ${models.limitText(240)}")
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = Color(0xFF171009)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Leggi capabilities", fontWeight = FontWeight.SemiBold)
                    }
                    AnimatedVisibility(
                        visible = wsProbe.capabilityLines.isNotEmpty(),
                        enter = AppleMotion.enter(),
                        exit = AppleMotion.exit()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            wsProbe.capabilityLines.forEach { line ->
                                Text(line, color = AppColors.Faint, fontSize = AppleType.Footnote)
                            }
                        }
                    }
                }
            }
        }
        item {
        AppleGlass(shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("TELEFONO SICURO", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    if (deviceIdState == null) deviceStatus
                    else "Registrato: ${deviceIdState!!.take(12)}… · $deviceStatus",
                    color = AppColors.Muted,
                    fontSize = AppleType.RowTitleSmall
                )
                Text(
                    "La chiave firma ogni azione server dopo impronta o PIN. Senza firma il server rifiuta (appena un telefono e' registrato).",
                    color = AppColors.Faint,
                    fontSize = AppleType.Footnote
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                deviceBusy = true
                                deviceStatus = "Genero chiave…"
                                try {
                                    val id = withContext(Dispatchers.IO) {
                                        ensureDeviceKeyPair()
                                        newDeviceId()
                                    }
                                    val pem = withContext(Dispatchers.IO) { devicePublicKeyPem() }
                                    val label = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(80)
                                    val (code, body) = postJson(
                                        resolveHermesUrl(settings, "/v1/hub/devices/register"),
                                        JSONObject().put("device_id", id).put("public_key", pem).put("label", label),
                                        loadGatewaySecret(context),
                                        allowCompatAuth = false
                                    )
                                    if (code in 200..299) {
                                        saveDeviceId(context, id)
                                        deviceIdState = id
                                        val fingerprint = runCatching {
                                            JSONObject(body).optString("fingerprint")
                                        }.getOrDefault("")
                                        deviceStatus = if (fingerprint.isNotBlank()) "impronta $fingerprint." else "registrato."
                                    } else {
                                        deviceStatus = "Registrazione fallita: HTTP $code."
                                    }
                                } catch (denied: Exception) {
                                    deviceStatus = denied.message ?: "Errore registrazione."
                                } finally {
                                    deviceBusy = false
                                }
                            }
                        },
                        enabled = !deviceBusy && deviceIdState == null
                    ) { Text("Registra telefono") }
                    Button(
                        onClick = {
                            scope.launch {
                                deviceBusy = true
                                try {
                                    val id = deviceIdState
                                    if (id != null) {
                                        postJson(
                                            resolveHermesUrl(settings, "/v1/hub/devices/$id"),
                                            JSONObject(),
                                            loadGatewaySecret(context),
                                            method = "DELETE",
                                            allowCompatAuth = false
                                        )
                                        clearDeviceId(context)
                                        deviceIdState = null
                                    }
                                    deviceStatus = "Telefono non registrato: le azioni server usano solo la chiave."
                                } catch (denied: Exception) {
                                    deviceStatus = denied.message ?: "Errore revoca."
                                } finally {
                                    deviceBusy = false
                                }
                            }
                        },
                        enabled = !deviceBusy && deviceIdState != null,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Error, contentColor = Color.White)
                    ) { Text("Revoca") }
                }
                HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Telefoni registrati",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = AppleType.RowTitleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { refreshDevices() }) { Text("Vedi", color = AppColors.Accent) }
                }
                AnimatedVisibility(
                    visible = devicesMsg.isNotBlank(),
                    enter = AppleMotion.enter(),
                    exit = AppleMotion.exit()
                ) {
                    Text(devicesMsg, color = AppColors.Faint, fontSize = AppleType.Footnote)
                }
                registeredDevices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                (device.label.ifBlank { device.id }) + if (device.revoked) " (revocato)" else "",
                                color = if (device.revoked) AppColors.Muted else Color.White,
                                fontSize = AppleType.RowTitleSmall
                            )
                            Text(
                                device.id + if (device.hasToken) " · push" else "",
                                color = AppColors.Faint,
                                fontSize = AppleType.Caption
                            )
                        }
                        if (!device.revoked) {
                            TextButton(onClick = { revokeDevice(device.id) }) {
                                Text("Revoca", color = AppColors.Error)
                            }
                        }
                    }
                    HorizontalDivider(color = AppColors.Border, thickness = 0.5.dp)
                }
            }
        }
    }
    item {
        AppleGlass(shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NOTIFICHE PUSH", color = AppColors.Accent, fontSize = AppleType.Footnote, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(pushStatus, color = AppColors.Faint, fontSize = AppleType.RowTitleSmall)
                Text(
                    "Avvisa a telefono spento quando un job finisce. Il server manda solo il segnale, mai i contenuti.",
                    color = AppColors.Faint,
                    fontSize = AppleType.Footnote
                )
                Button(
                    onClick = {
                        scope.launch {
                            pushBusy = true
                            pushStatus = "Controllo…"
                            try {
                                if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
                                    pushStatus = "Push non disponibile: niente Play Services."
                                    return@launch
                                }
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    pushPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                                val devId = deviceIdState
                                if (devId == null) {
                                    pushStatus = "Registra prima il telefono qui sopra."
                                    return@launch
                                }
                                val configBody = runCatching {
                                    httpGet(resolveHermesUrl(settings, "/v1/hub/push-config"), loadGatewaySecret(context))
                                }.getOrElse {
                                    pushStatus = "Config non letta: ${it.message}"
                                    return@launch
                                }
                                val config = runCatching { JSONObject(configBody) }.getOrElse {
                                    pushStatus = "Config illeggibile."
                                    return@launch
                                }
                                if (!config.optBoolean("configured", false)) {
                                    pushStatus = "Server senza FCM: mancano service account e client JSON."
                                    return@launch
                                }
                                val client = config.optJSONObject("client")
                                if (client == null) {
                                    pushStatus = "Config incompleta."
                                    return@launch
                                }
                                // Default FirebaseApp (niente google-services.json:
                                // init manuale dai dati del server). Default perche'
                                // getInstance(app) nominale e' package-private.
                                val app = withContext(Dispatchers.IO) {
                                    FirebaseApp.getApps(context).firstOrNull()
                                        ?: FirebaseApp.initializeApp(
                                            context,
                                            FirebaseOptions.Builder()
                                                .setApiKey(client.optString("api_key"))
                                                .setApplicationId(client.optString("app_id"))
                                                .setProjectId(client.optString("project_id"))
                                                .setGcmSenderId(client.optString("sender_id"))
                                                .build()
                                        )
                                }
                                if (app == null) {
                                    pushStatus = "Init Firebase fallita."
                                    return@launch
                                }
                                val token = withContext(Dispatchers.IO) {
                                    try {
                                        com.google.android.gms.tasks.Tasks.await(FirebaseMessaging.getInstance().token)
                                    } catch (failed: Exception) {
                                        null
                                    }
                                }
                                if (token.isNullOrBlank()) {
                                    pushStatus = "Token FCM non ottenuto: riprova."
                                    return@launch
                                }
                                savePendingFcmToken(context, token)
                                val (code, _) = postJson(
                                    resolveHermesUrl(settings, "/v1/hub/devices/$devId/token"),
                                    JSONObject().put("fcm_token", token),
                                    loadGatewaySecret(context),
                                    allowCompatAuth = false
                                )
                                pushStatus = if (code in 200..299) "Notifiche attive su questo telefono."
                                else "Server HTTP $code: token non salvato."
                            } catch (failed: Exception) {
                                pushStatus = failed.message ?: "Errore attivazione."
                            } finally {
                                pushBusy = false
                            }
                        }
                    },
                    enabled = !pushBusy
                ) { Text("Attiva notifiche") }
            }
        }
        }
    }
    }
    pendingControlAction?.let { (service, action) ->
        AlertDialog(
            onDismissRequest = { pendingControlAction = null },
            containerColor = AppColors.Surface,
            title = { Text("Conferma $action", color = Color.White) },
            text = { Text("Eseguire $action su $service? Le sessioni attive possono interrompersi.", color = AppColors.Muted) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingControlAction = null
                        scope.launch {
                            postServerControlSigned(
                                resolveHermesUrl(settings, "/v1/hub/server/action"),
                                JSONObject().put("service", service).put("action", action),
                                action
                            ) { controlOutput = it }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (action == "restart") AppColors.Error else AppColors.Accent,
                        contentColor = if (action == "restart") Color.White else Color(0xFF171009)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Conferma") }
            },
            dismissButton = { TextButton(onClick = { pendingControlAction = null }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Annulla") } }
        )
    }

    pendingMaintenanceAction?.let { operation ->
        AlertDialog(
            onDismissRequest = { pendingMaintenanceAction = null },
            containerColor = AppColors.Surface,
            title = { Text("Conferma ${maintenanceLabels[operation] ?: operation}", color = Color.White) },
            text = { Text("Eseguire \"${maintenanceLabels[operation] ?: operation}\" sul server? Update, rollback e restore possono interrompere il servizio.", color = AppColors.Muted) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingMaintenanceAction = null
                        scope.launch {
                            postServerControlSigned(
                                resolveHermesUrl(settings, "/v1/hub/server/maintenance"),
                                JSONObject().put("operation", operation),
                                operation
                            ) { controlOutput = it }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (operation == "update" || operation == "rollback" || operation == "restore") AppColors.Error else AppColors.Accent,
                        contentColor = if (operation == "update" || operation == "rollback" || operation == "restore") Color.White else Color(0xFF171009)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Conferma") }
            },
            dismissButton = { TextButton(onClick = { pendingMaintenanceAction = null }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Accent)) { Text("Annulla") } }
        )
    }
}

