package com.nemoclaw.chat

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal fun parseHardwareSnapshot(json: JSONObject): HardwareSnapshot {
    val host = json.optJSONObject("host") ?: JSONObject()
    val cpu = json.optJSONObject("cpu") ?: JSONObject()
    val memory = json.optJSONObject("memory") ?: JSONObject()
    val swap = json.optJSONObject("swap") ?: JSONObject()
    val network = json.optJSONObject("network") ?: JSONObject()
    val timestampMs = (json.optFiniteDouble("timestamp")?.times(1000.0)?.toLong())
        ?.takeIf { it > 0 }
        ?: System.currentTimeMillis()

    val disks = buildList {
        val array = json.optJSONArray("disks") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val device = item.optString("device", "-")
            val fileSystem = item.optString("fstype", "-")
            if (!isMeaningfulHardwareDisk(device, fileSystem)) continue
            add(
                HardwareDisk(
                    device = device,
                    mountpoint = item.optString("mountpoint", "-"),
                    fileSystem = fileSystem,
                    totalBytes = item.optLong("total_bytes", 0L),
                    usedBytes = item.optLong("used_bytes", 0L),
                    freeBytes = item.optLong("free_bytes", 0L),
                    percent = item.optFiniteDouble("percent") ?: 0.0
                )
            )
        }
    }

    val temperatures = buildList {
        val array = json.optJSONArray("temperatures") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val current = item.optFiniteDouble("current_c") ?: continue
            if (current < 0.0 || current > 150.0) continue
            add(
                HardwareTemperature(
                    name = item.optString("name", "-"),
                    label = item.optString("label", item.optString("name", "-")),
                    currentC = current,
                    highC = item.optFiniteDouble("high_c"),
                    criticalC = item.optFiniteDouble("critical_c")
                )
            )
        }
    }

    val gpus = buildList {
        val array = json.optJSONArray("gpus") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val memoryTotalMb = item.optFiniteDouble("memory_total_mb") ?: 0.0
            val memoryUsedMb = item.optFiniteDouble("memory_used_mb") ?: 0.0
            add(
                HardwareGpu(
                    index = item.optInt("index", i),
                    name = item.optString("name", "GPU"),
                    utilizationPercent = item.optFiniteDouble("utilization_gpu_percent") ?: 0.0,
                    memoryUtilizationPercent = item.optFiniteDouble("utilization_memory_percent") ?: 0.0,
                    memoryUsedBytes = if (memoryUsedMb > 0.0) (memoryUsedMb * 1024.0 * 1024.0).toLong() else item.optLong("memory_used_bytes", 0L),
                    memoryTotalBytes = if (memoryTotalMb > 0.0) (memoryTotalMb * 1024.0 * 1024.0).toLong() else item.optLong("memory_total_bytes", 0L),
                    temperatureC = item.optFiniteDouble("temperature_c")?.takeIf { it in 0.0..150.0 },
                    powerDrawWatts = item.optFiniteDouble("power_draw_watts")?.takeIf { it in 0.0..1000.0 },
                    powerLimitWatts = item.optFiniteDouble("power_limit_watts")?.takeIf { it in 0.0..1000.0 },
                    driverVersion = item.optString("driver_version", "-")
                )
            )
        }
    }

    return HardwareSnapshot(
        status = json.optString("status", "ok"),
        timestampMs = timestampMs,
        hostname = host.optString("hostname", "-"),
        operatingSystem = host.optString("os", "-"),
        platform = host.optString("platform", "-"),
        architecture = host.optString("architecture", "-"),
        processor = host.optString("processor", "-"),
        uptimeSeconds = host.optLong("uptime_seconds", 0L),
        cpuPercent = cpu.optFiniteDouble("percent") ?: 0.0,
        physicalCores = cpu.optInt("physical_cores", 0),
        logicalCores = cpu.optInt("logical_cores", 0),
        currentMhz = cpu.optFiniteDouble("current_mhz"),
        maxMhz = cpu.optFiniteDouble("max_mhz"),
        memoryPercent = memory.optFiniteDouble("percent") ?: 0.0,
        memoryTotalBytes = memory.optLong("total_bytes", 0L),
        memoryUsedBytes = memory.optLong("used_bytes", 0L),
        memoryAvailableBytes = memory.optLong("available_bytes", 0L),
        swapPercent = swap.optFiniteDouble("percent") ?: 0.0,
        swapTotalBytes = swap.optLong("total_bytes", 0L),
        swapUsedBytes = swap.optLong("used_bytes", 0L),
        networkBytesSent = network.optLong("bytes_sent", 0L),
        networkBytesReceived = network.optLong("bytes_recv", 0L),
        processCount = json.optInt("process_count", 0),
        temperatureSupport = json.optString("temperature_support", "unavailable"),
        disks = disks,
        temperatures = temperatures,
        gpus = gpus,
        message = "Statistiche aggiornate dal gateway Hermes."
    )
}

internal suspend fun testGateway(healthUrl: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val response = httpGetResponse(healthUrl, apiKey)
        if (response.first in 200..299) {
            "Hermes raggiungibile."
        } else {
            "Hermes risponde: HTTP ${response.first}"
        }
    } catch (ex: Exception) {
        "Hermes non raggiungibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}


internal suspend fun sendWorkspaceRunRequest(
    settings: AppSettings,
    kind: String,
    prompt: String,
    apiKey: String?
): WorkspaceRunResult = withContext(Dispatchers.IO) {
    val runPrompt = workspaceInstructions(settings, kind, prompt)
    val title = makeTitle(prompt)

    val jobPayload = JSONObject()
        .put("name", title)
        .put("prompt", runPrompt)
        .put("deliver", "local")

    try {
        val job = postJson("${hermesRoot(settings)}/api/jobs", jobPayload, apiKey)
        if (job.first in 200..299) {
            val remoteId = extractTaskId(job.second)
            if (!remoteId.isNullOrBlank()) {
                runCatching { postJson("${hermesRoot(settings)}/api/jobs/${URLEncoder.encode(remoteId, "UTF-8")}/run", JSONObject(), apiKey) }
            }
            val artifact = parseWorkspaceArtifact(kind, job.second)
            return@withContext WorkspaceRunResult(
                result = artifact.result.ifBlank { job.second.ifBlank { "Job Hermes creato per la sezione $kind." } },
                source = "Hermes Jobs",
                status = artifact.status.ifBlank { "Job creato su Hermes." },
                remoteId = remoteId,
                title = artifact.title.ifBlank { title },
                streamUrl = artifact.streamUrl,
                downloadUrl = artifact.downloadUrl
            )
        }
    } catch (_: Exception) {
        // Fall through to runs/chat fallback.
    }

    val payload = JSONObject()
        .put("model", settings.model)
        .put("input", runPrompt)
        .put(
            "metadata",
            JSONObject()
                .put("client", "hermes-hub")
                .put("client_surface", "android-app")
                .put("workspace", kind.lowercase())
                .put("source", "workspace-section")
                .put("memory_scope", "shared-hermes-agent-memory")
                .put("share_with_cli", true)
        )
    // Per-request model/provider: Hermes resta responsabile del routing.
    if (settings.provider.isNotBlank() && !settings.provider.equals("hermes-agent", ignoreCase = true)) {
        payload.put("provider", settings.provider)
    }
    buildHermesModelOptions(settings.reasoningEffort, settings.serviceTier, capabilities = null)?.let {
        payload.put("model_options", it)
    }

    try {
        val run = postJson(resolveHermesUrl(settings, "/v1/runs"), payload, apiKey)
        if (run.first in 200..299) {
            val artifact = parseWorkspaceArtifact(kind, run.second)
            return@withContext WorkspaceRunResult(
                result = artifact.result.ifBlank { run.second.ifBlank { "Run creata su Hermes." } },
                source = "Hermes Runs",
                status = artifact.status.ifBlank { "Run Hermes completata." },
                remoteId = extractTaskId(run.second),
                title = artifact.title.ifBlank { title },
                streamUrl = artifact.streamUrl,
                downloadUrl = artifact.downloadUrl
            )
        }
    } catch (_: Exception) {
        // Fall through to chat fallback.
    }

    val chat = sendChatRequest(
        settings = settings,
        mode = "Agente",
        prompt = runPrompt,
        history = listOf(ChatMessage("Tu", runPrompt, fromUser = true)),
        conversationId = null,
        previousResponseId = null,
        apiKey = apiKey
    )
    val artifact = parseWorkspaceArtifact(kind, chat.text)
    WorkspaceRunResult(
        result = artifact.result.ifBlank { chat.text },
        source = chat.source,
        status = chat.statusMessage,
        title = artifact.title.ifBlank { title },
        streamUrl = artifact.streamUrl,
        downloadUrl = artifact.downloadUrl
    )
}


internal fun formatHardwareUptime(seconds: Long): String {
    if (seconds <= 0L) return "n/d"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return if (days > 0) "${days}g ${hours}h" else "${hours}h ${minutes}m"
}

internal fun formatMhz(value: Double?): String {
    return if (value != null && value > 0.0) "${value.roundToInt()} MHz" else "n/d"
}

internal fun formatTemperature(value: Double?): String {
    return if (value != null) "${String.format(java.util.Locale.US, "%.1f", value)} C" else "n/d"
}

internal fun formatWatts(value: Double?): String {
    return if (value != null) "${String.format(java.util.Locale.US, "%.0f", value)} W" else "n/d"
}

