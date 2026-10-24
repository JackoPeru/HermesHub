package com.nemoclaw.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nemoclaw.chat.core.WorkLimits
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal val gatewayProbeHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}

internal fun isSuccessfulGatewayProbe(statusCode: Int): Boolean = statusCode in 200..299

internal data class GatewayRuntimeStatus(
    val agentVersion: String,
    val status: String,
    val failureReason: String
)

internal fun gatewayRuntimeLabel(connected: Boolean, runtime: GatewayRuntimeStatus?): String {
    if (!connected) return "Rete non disponibile"
    val version = runtime?.agentVersion?.trim().orEmpty()
    val detail = when (runtime?.status?.trim()?.lowercase()) {
        "rolled_back" -> "rollback"
        "rollback_failed" -> "ripristino fallito"
        "blocked" -> "aggiornamento bloccato"
        "unhealthy" -> "stato da verificare"
        "updating" -> "aggiornamento in corso"
        else -> ""
    }
    val agent = if (version.isNotBlank()) "Agent $version" else "versione non letta"
    return if (detail.isBlank()) {
        "Gateway disponibile · $agent"
    } else {
        "Gateway disponibile · $agent · $detail"
    }
}

internal fun isValidGatewayProbeUrl(url: String): Boolean {
    return try {
        val uri = URI(url)
        val scheme = uri.scheme.orEmpty().lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

/**
 * Come probeHermesGateway ma con motivo del fallimento (mai eccezioni):
 * null = raggiunto, altrimenti "URL non valido", "timeout di rete" o
 * "HTTP {code}". Serve a distinguere "sto verificando" da "giù davvero".
 */
internal fun probeHermesGatewayDetailed(settings: AppSettings, apiKey: String?): Pair<Boolean, String?> {
    val url = resolveHermesUrl(settings, "/v1/capabilities")
    if (!isValidGatewayProbeUrl(url)) return false to "URL non valido"
    val requestContext = HermesHubProtocol.newCorrelationContext()
    for (token in hermesAuthCandidates(apiKey)) {
        val request = try {
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android-Reachability")
                .apply {
                    HermesHubProtocol.addCorrelationHeaders(this, requestContext)
                    token?.let { header("Authorization", "Bearer $it") }
                }
                .get()
                .build()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return false to "URL non valido"
        }
        val statusCode = try {
            gatewayProbeHttpClient.newCall(request).execute().use { it.code }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return false to "timeout di rete"
        }
        if (isSuccessfulGatewayProbe(statusCode)) return true to null
        if (statusCode != 401) return false to "HTTP $statusCode"
    }
    return false to "HTTP 401"
}

internal fun loadGatewayRuntimeStatus(settings: AppSettings, apiKey: String?): GatewayRuntimeStatus? {
    val url = resolveHermesUrl(settings, "/v1/hub/runtime")
    if (!isValidGatewayProbeUrl(url)) return null
    val requestContext = HermesHubProtocol.newCorrelationContext()
    for (token in hermesAuthCandidates(apiKey)) {
        val request = try {
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android-Runtime")
                .apply {
                    HermesHubProtocol.addCorrelationHeaders(this, requestContext)
                    token?.let { header("Authorization", "Bearer $it") }
                }
                .get()
                .build()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        val response = try {
            gatewayProbeHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        response.use {
            if (it.code == 401) return@use
            if (it.code !in 200..299) return null
            val body = it.body.string()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val failure = json.optJSONObject("failure")
            return GatewayRuntimeStatus(
                agentVersion = json.optString("agent_version").take(WorkLimits.TRUNC_80),
                status = json.optString("status").take(40),
                failureReason = failure?.optString("reason").orEmpty().take(240)
            )
        }
    }
    return null
}

