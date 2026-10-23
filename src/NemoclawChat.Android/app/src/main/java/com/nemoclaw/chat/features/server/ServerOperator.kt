package com.nemoclaw.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder

@Composable
internal fun OperatorScreen(context: Context, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf("GET /health") }
    var params by remember { mutableStateOf("") }
    var approvalId by remember { mutableStateOf("") }
    var baseHash by remember { mutableStateOf("") }
    var configPatch by remember { mutableStateOf("{\"ops\":[]}") }
    var workspacePath by rememberSaveable { mutableStateOf("") }
    var workspaceText by rememberSaveable { mutableStateOf("") }
    var quickRunText by rememberSaveable { mutableStateOf("Controlla lo stato operativo e riassumi cosa richiede attenzione.") }
    var status by remember { mutableStateOf("Pronto.") }
    var summary by remember { mutableStateOf("Nessuna risposta.") }
    var raw by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Cron", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Automazioni Hermes programmate sul gateway.", color = AppColors.Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Avvia lavoro in background", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Scrivi cosa deve fare Hermes. Non serve conoscere endpoint, JSON o ID tecnici.", color = AppColors.Muted, fontSize = 13.sp)
                    SettingsField("Cosa deve fare Hermes?", quickRunText, { quickRunText = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(onClick = {
                            val input = quickRunText.ifBlank { "Controlla stato operativo Hermes e riassumi." }
                            runOperatorRpc(scope, context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${input.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it })
                        }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Avvia lavoro", tint = Color.White) }
                        IconButton(onClick = {
                            val input = "Crea o prepara un video per l'utente. Salva il file finale nella cartella video configurata sul server cosi appare nella sezione Video."
                            quickRunText = input
                            runOperatorRpc(scope, context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${input.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it })
                        }) { Icon(Icons.Rounded.Videocam, contentDescription = "Crea video", tint = Color.White) }
                        IconButton(onClick = {
                            runOperatorRpc(scope, context, settings, "GET /api/jobs", "", { status = it }, { summary = it }, { raw = it })
                        }) { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Vedi lavori", tint = Color.White) }
                    }
                    Text(status, color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Preset Hermes", color = Color.White, fontWeight = FontWeight.SemiBold)
                    OPERATOR_PRESETS.groupBy { it.group }.forEach { (group, presets) ->
                        Text(group, color = AppColors.Muted, fontSize = 12.sp)
                        presets.forEach { preset ->
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    method = preset.method
                                    params = preset.params
                                    status = "${preset.method}..."
                                    summary = "Attesa risposta Hermes..."
                                    raw = ""
                                    scope.launch {
                                        val result = hermesHttpCall(settings, loadGatewaySecret(context), preset.method, preset.params)
                                        status = result.status
                                        summary = result.summary
                                        raw = result.rawJson.ifBlank { result.summary }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Elevated, contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(preset.label)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cron", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Cron ID", approvalId, { approvalId = it })
                    OperatorActionButton("Lista") { runOperatorRpc(scope, context, settings, "GET /api/jobs", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Run") { runOperatorRpc(scope, context, settings, "POST /api/jobs/${URLEncoder.encode(approvalId.trim(), "UTF-8")}/run", "{}", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Pausa") { runOperatorRpc(scope, context, settings, "POST /api/jobs/${URLEncoder.encode(approvalId.trim(), "UTF-8")}/pause", "{}", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Elimina") { runOperatorRpc(scope, context, settings, "DELETE /api/jobs/${URLEncoder.encode(approvalId.trim(), "UTF-8")}", "", { status = it }, { summary = it }, { raw = it }) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Run tecnico", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Run ID", baseHash, { baseHash = it })
                    SettingsField("Input run", configPatch, { configPatch = it })
                    OperatorActionButton("Capabilities") { runOperatorRpc(scope, context, settings, "GET /v1/capabilities", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Crea run") { runOperatorRpc(scope, context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${configPatch.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Models") { runOperatorRpc(scope, context, settings, "GET /v1/models", "", { status = it }, { summary = it }, { raw = it }) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Diagnostica", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Filtro cron", workspacePath, { workspacePath = it })
                    SettingsField("Input run rapido", workspaceText, { workspaceText = it })
                    OperatorActionButton("Cron") { runOperatorRpc(scope, context, settings, "GET /api/jobs", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Health") { runOperatorRpc(scope, context, settings, "GET /health/detailed", "", { status = it }, { summary = it }, { raw = it }) }
                    OperatorActionButton("Run") { runOperatorRpc(scope, context, settings, "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"${workspaceText.jsonEscaped()}\"}", { status = it }, { summary = it }, { raw = it }) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Endpoint manuale", color = Color.White, fontWeight = FontWeight.SemiBold)
                    SettingsField("Metodo + path", method, { method = it })
                    SettingsField("Body JSON", params, { params = it })
                    IconButton(onClick = {
                        status = "${method.trim()}..."
                        summary = "Attesa risposta Hermes..."
                        raw = ""
                        scope.launch {
                            val result = hermesHttpCall(settings, loadGatewaySecret(context), method, params)
                            status = result.status
                            summary = result.summary
                            raw = result.rawJson.ifBlank { result.summary }
                        }
                    }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Esegui chiamata", tint = Color.White)
                    }
                    Text(status, color = AppColors.Muted)
                    Text(summary, color = AppColors.Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = raw.ifBlank { "Nessuna risposta." },
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun OperatorActionButton(label: String, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Elevated, contentColor = Color.White),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(label)
    }
}


internal fun runOperatorRpc(
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    settings: AppSettings,
    method: String,
    params: String,
    setStatus: (String) -> Unit,
    setSummary: (String) -> Unit,
    setRaw: (String) -> Unit
) {
    setStatus("$method...")
    setSummary("Attesa risposta Hermes...")
    setRaw("")
    scope.launch {
        val result = hermesHttpCall(settings, loadGatewaySecret(context), method, params)
        setStatus(result.status)
        setSummary(result.summary)
        setRaw(result.rawJson.ifBlank { result.summary })
    }
}

