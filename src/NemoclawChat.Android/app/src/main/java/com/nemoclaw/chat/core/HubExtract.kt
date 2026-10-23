package com.nemoclaw.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal const val EXTRACT_ASSISTANT_TEXT_MAX_DEPTH = 10

internal fun extractAssistantText(body: String, depth: Int = 0): String {
    if (depth >= EXTRACT_ASSISTANT_TEXT_MAX_DEPTH) return ""
    val trimmed = body.trim()
    if (trimmed.isBlank()) {
        return ""
    }

    if (trimmed.contains("data:", ignoreCase = true)) {
        val builder = StringBuilder()
        trimmed.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("data:", ignoreCase = true)) return@forEach
            val payload = line.removePrefix("data:").trim()
            if (payload.isBlank() || payload == "[DONE]") return@forEach
            builder.append(extractAssistantText(payload, depth + 1))
        }
        return builder.toString().trim()
    }

    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
        return trimmed
    }

    return try {
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            buildString {
                for (i in 0 until array.length()) {
                    append(extractJsonText(array.opt(i)))
                }
            }.trim()
        } else {
            extractJsonText(JSONObject(trimmed)).trim()
        }
    } catch (_: Exception) {
        trimmed
    }
}

internal const val EXTRACT_JSON_TEXT_MAX_DEPTH = 10

internal fun extractJsonText(value: Any?, depth: Int = 0): String {
    if (depth >= EXTRACT_JSON_TEXT_MAX_DEPTH) return ""
    return when (value) {
        is String -> value
        is JSONObject -> {
            listOf("output_text", "text", "content", "message", "reply").forEach { key ->
                val text = extractJsonText(value.opt(key), depth + 1)
                if (text.isNotBlank()) {
                    return text
                }
            }

            val choices = value.optJSONArray("choices")
            if (choices != null) {
                for (i in 0 until choices.length()) {
                    val text = extractJsonText(choices.opt(i), depth + 1)
                    if (text.isNotBlank()) {
                        return text
                    }
                }
            }

            listOf("delta", "choice", "data").forEach { key ->
                val text = extractJsonText(value.opt(key), depth + 1)
                if (text.isNotBlank()) {
                    return text
                }
            }
            ""
        }
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                append(extractJsonText(value.opt(i), depth + 1))
            }
        }
        else -> ""
    }
}


internal fun extractTaskId(body: String): String? {
    return try {
        val json = JSONObject(body)
        json.extractString("id")
            ?: json.extractString("taskId")
            ?: json.extractString("job_id")
            ?: json.extractString("jobId")
            ?: json.extractNestedString("task", "id")
            ?: json.extractNestedString("job", "id")
    } catch (_: Exception) {
        null
    }
}

internal fun extractTaskStatus(body: String): String? {
    return try {
        val json = JSONObject(body)
        json.extractString("status")
            ?: json.extractNestedString("task", "status")
            ?: json.extractNestedString("job", "status")
    } catch (_: Exception) {
        null
    }
}

internal fun extractResponseId(body: String): String? {
    return try {
        val json = JSONObject(body)
        json.extractString("id")
            ?: json.extractString("response_id")
            ?: json.extractString("responseId")
            ?: json.extractNestedString("response", "id")
    } catch (_: Exception) {
        null
    }
}

internal fun extractHumanError(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isBlank()) {
        return "errore sconosciuto"
    }

    return try {
        val json = JSONObject(trimmed)
        json.extractString("error")
            ?: json.extractString("message")
            ?: json.extractString("detail")
            ?: trimmed
    } catch (_: Exception) {
        trimmed
    }
}

internal fun JSONObject.extractString(key: String): String? {
    val value = opt(key)
    return when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject, is JSONArray -> extractJsonText(value).takeIf { it.isNotBlank() }
        else -> null
    }
}

internal fun JSONObject.extractNestedString(parentKey: String, childKey: String): String? {
    return optJSONObject(parentKey)?.extractString(childKey)
}

internal fun JSONObject.optFiniteDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.takeIf { it.isFinite() }
}

internal fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return try { getLong(key) } catch (_: Exception) { null }
}

internal fun JSONArray.toStringList(limit: Int = 100): List<String> = buildList {
    for (index in 0 until minOf(length(), limit)) {
        val value = optString(index).trim()
        if (value.isNotBlank() && none { it.equals(value, ignoreCase = true) }) add(value)
    }
}

internal fun buildFallbackReply(settings: AppSettings, mode: String, reason: String): String {
    val prefix = if (mode == "Agente") {
        "Hermes assente: preparo un job locale e tengo il contesto pronto."
    } else {
        "Hermes assente: uso la risposta locale di emergenza senza perdere la conversazione."
    }

    return "$prefix Preset: API ${settings.gatewayUrl}, modello ${settings.model}, protocollo ${settings.preferredApi}. Motivo: $reason."
}

internal fun fallbackTaskResult(settings: AppSettings, task: AgentTask, message: String): GatewayTaskResult {
    return if (settings.demoMode) {
        GatewayTaskResult(
            task.copy(
                mode = "Locale",
                status = if (task.requiresApproval) "In attesa approvazione" else "Pronto",
                source = "Fallback locale",
                updatedAt = System.currentTimeMillis()
            ),
            "$message Salvo il task in locale."
        )
    } else {
        GatewayTaskResult(
            task.copy(
                status = "Errore Hermes",
                source = "Errore Hermes",
                updatedAt = System.currentTimeMillis()
            ),
            message
        )
    }
}

internal suspend fun checkGithubUpdate(localVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(AppDefaults.latestReleaseApi)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HermesHub-Android")
            .get()
            .build()
        updateHttpClient.newCall(request).execute().use { response ->
            val code = response.code
            if (code !in 200..299) {
                return@withContext UpdateCheckResult(
                    hasUpdate = false,
                    latestVersion = null,
                    message = "Nessuna release GitHub trovata. Crea una release con tag vX.Y.Z e asset APK.",
                    releaseUrl = AppDefaults.releasesPage,
                    assetUrl = null,
                    releaseSummary = ""
                )
            }

            val body = response.body.byteStream().readUtf8Bounded()
            val json = JSONObject(body)
            val latest = normalizeVersion(json.optString("tag_name"))
            val releaseUrl = json.optString("html_url", AppDefaults.releasesPage)
            val releaseSummary = summarizeReleaseNotes(json.optString("body"))
            val assetUrl = findReleaseAsset(json.optJSONArray("assets") ?: JSONArray(), ".apk")
            val hasUpdate = compareVersions(latest, localVersion) > 0
            var message = if (hasUpdate) {
                "Aggiornamento disponibile: $localVersion -> $latest."
            } else {
                "App aggiornata. Versione locale: $localVersion, GitHub: $latest."
            }
            if (hasUpdate && assetUrl == null) {
                message += " Release trovata, ma manca asset Android .apk."
            }

            UpdateCheckResult(
                hasUpdate = hasUpdate,
                latestVersion = latest,
                message = message,
                releaseUrl = releaseUrl,
                assetUrl = assetUrl,
                releaseSummary = releaseSummary
            )
        }
    } catch (ex: Exception) {
        UpdateCheckResult(
            hasUpdate = false,
            latestVersion = null,
            message = "Controllo update non riuscito: ${ex.message ?: ex.javaClass.simpleName}",
            releaseUrl = AppDefaults.releasesPage,
            assetUrl = null,
            releaseSummary = ""
        )
    }
}

