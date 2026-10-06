package com.nemoclaw.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URI

/**
 * Scelta automatica del percorso gateway più veloce per letture stato/manager.
 * Solo letture (TopBar, Comfy, probe dot): mai hot path chat/invii.
 */

private const val FASTEST_GATEWAY_CACHE_MS = 60_000L
private const val FASTEST_TIE_TOLERANCE_NS = 150_000_000L

@Volatile
private var fastestCacheRoot: String = ""

@Volatile
private var fastestCacheAtMs: Long = 0L

@Volatile
private var fastestCacheKey: String = ""

internal fun fastestCacheKeyFor(settings: AppSettings): String =
    "${settings.gatewayUrl.trim()}|${settings.localGatewayUrl.trim()}"

internal fun isHttpUrlWithHost(value: String): Boolean {
    return try {
        val uri = URI(value.trim())
        val scheme = uri.scheme.orEmpty().lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

/**
 * Candidati root puri: [localGatewayUrl valida (http/https+host), gatewayUrl] distinte.
 * Pura e testabile, niente rete.
 */
internal fun localRootCandidates(settings: AppSettings): List<String> {
    val out = mutableListOf<String>()
    val local = settings.localGatewayUrl.trim().trimEnd('/')
    if (local.isNotBlank() && isHttpUrlWithHost(local)) {
        out += local
    }
    val configured = settings.gatewayUrl.trim().trimEnd('/')
    if (configured.isNotBlank()) {
        out += configured
    }
    return out.distinctBy { it.lowercase() }
}

private fun probeUrlForRoot(root: String): String {
    // Ri riusa la normalizzazione /v1 esistente: evita doppi /v1/v1.
    return try {
        resolveHermesUrl(AppSettings(gatewayUrl = root), "/v1/capabilities")
    } catch (_: Exception) {
        val base = root.trim().trimEnd('/')
        if (base.endsWith("/v1", ignoreCase = true)) "$base/capabilities" else "$base/v1/capabilities"
    }
}

private fun probeRootOk(root: String): Boolean {
    return try {
        val url = probeUrlForRoot(root)
        if (!isHttpUrlWithHost(url)) return false
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "HermesHub-Android-Fastest")
            .get()
            .build()
        gatewayProbeHttpClient.newCall(request).execute().use { it.code in 200..299 }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }
}

/**
 * Ritorna la root più veloce (prima 2xx su GET {root}/v1/capabilities,
 * pareggio -> configurato). Cache in-memory 60s. Mai eccezioni:
 * fallback alla root configurata.
 */
internal suspend fun fastestGatewayRoot(settings: AppSettings): String {
    try {
        val candidates = localRootCandidates(settings)
        if (candidates.isEmpty()) return settings.gatewayUrl
        if (candidates.size == 1) return candidates[0]

        val key = fastestCacheKeyFor(settings)
        val now = System.currentTimeMillis()
        val cached = fastestCacheRoot
        if (fastestCacheKey == key && cached in candidates && now - fastestCacheAtMs < FASTEST_GATEWAY_CACHE_MS) {
            return cached
        }

        val configured = settings.gatewayUrl.trim().trimEnd('/')
        val timed = coroutineScope {
            candidates.map { root ->
                async(Dispatchers.IO) {
                    val start = System.nanoTime()
                    val ok = withTimeoutOrNull(4_000L) { probeRootOk(root) } ?: false
                    val elapsed = System.nanoTime() - start
                    Triple(root, ok, elapsed)
                }
            }.map { it.await() }
        }
        val successful = timed.filter { it.second }.sortedBy { it.third }
        val winner = when {
            successful.isEmpty() -> configured.ifBlank { candidates[0] }
            successful.size == 1 -> successful[0].first
            else -> {
                val fastest = successful[0]
                val configuredResult = successful.firstOrNull { it.first.equals(configured, ignoreCase = true) }
                if (configuredResult != null && (configuredResult.third - fastest.third) <= FASTEST_TIE_TOLERANCE_NS) {
                    configuredResult.first
                } else {
                    fastest.first
                }
            }
        }
        fastestCacheKey = key
        fastestCacheRoot = winner
        fastestCacheAtMs = now
        return winner
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        return settings.gatewayUrl
    }
}
