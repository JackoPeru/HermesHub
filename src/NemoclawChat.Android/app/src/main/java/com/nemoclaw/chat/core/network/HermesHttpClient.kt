package com.nemoclaw.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

internal val plugAndPlayGatewayRoots = emptyList<String>()

internal fun plugAndPlayUrlCandidates(url: String): List<String> {
    return try {
        val uri = URI(url)
        if (uri.port != 8642) return listOf(url)
        val suffix = buildString {
            append(uri.rawPath.orEmpty())
            if (!uri.rawQuery.isNullOrBlank()) append("?").append(uri.rawQuery)
        }
        val currentRoot = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}".trimEnd('/')
        (listOf(currentRoot) + plugAndPlayGatewayRoots)
            .distinctBy { it.lowercase() }
            .map { it.trimEnd('/') + suffix }
    } catch (_: Exception) {
        listOf(url)
    }
}

internal suspend fun httpGet(
    url: String,
    apiKey: String? = null,
    requestContext: HermesRequestContext = HermesHubProtocol.newCorrelationContext()
): String = withContext(Dispatchers.IO) {
    httpGetResponse(url, apiKey, requestContext).second
}

internal suspend fun httpGetResponse(
    url: String,
    apiKey: String? = null,
    requestContext: HermesRequestContext = HermesHubProtocol.newCorrelationContext()
): Pair<Int, String> = withContext(Dispatchers.IO) {
    var last: Pair<Int, String>? = null
    for (candidateUrl in plugAndPlayUrlCandidates(url)) {
        for (token in hermesAuthCandidates(apiKey)) {
            val response = try {
                executeHttpGet(candidateUrl, token, requestContext)
            } catch (ex: Exception) {
                last = 0 to (ex.message ?: ex.javaClass.simpleName)
                continue
            }
            last = response
            if (!shouldRetryHermesWithBearerAuth(response.first, response.second)) {
                if (response.first != 0) return@withContext response
            }
        }
    }
    last ?: (0 to "")
}

internal val apiHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
}

internal suspend fun postJson(
    url: String,
    payload: JSONObject,
    apiKey: String? = null,
    method: String = "POST",
    allowCompatAuth: Boolean = true,
    sessionId: String? = null,
    requestContext: HermesRequestContext = HermesHubProtocol.newCorrelationContext()
): Pair<Int, String> = withContext(Dispatchers.IO) {
    var last: Pair<Int, String>? = null
    for (candidateUrl in plugAndPlayUrlCandidates(url)) {
        for (token in hermesAuthCandidates(apiKey, allowCompatAuth)) {
            val response = try {
                executeJsonRequest(candidateUrl, payload, method, token, sessionId, requestContext)
            } catch (ex: Exception) {
                last = 0 to (ex.message ?: ex.javaClass.simpleName)
                continue
            }
            last = response
            if (!shouldRetryHermesWithBearerAuth(response.first, response.second)) {
                if (response.first != 0) return@withContext response
            }
        }
    }
    last ?: (0 to "")
}

internal fun executeHttpGet(
    url: String,
    bearerToken: String?,
    requestContext: HermesRequestContext = HermesHubProtocol.newCorrelationContext()
): Pair<Int, String> {
    val builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", "HermesHub-Android")
    HermesHubProtocol.addCorrelationHeaders(builder, requestContext)
    bearerToken?.let { builder.header("Authorization", "Bearer $it") }
    val request = builder.get().build()

    return apiHttpClient.newCall(request).execute().use { response ->
        val limit = if (url.contains("/v1/hub/conversations", ignoreCase = true)) {
            MAX_ARCHIVE_JSON_RESPONSE_BYTES
        } else {
            MAX_JSON_RESPONSE_BYTES
        }
        response.code to response.body.byteStream().readUtf8Bounded(limit)
    }
}

internal fun executeJsonRequest(
    url: String,
    payload: JSONObject,
    method: String,
    bearerToken: String?,
    sessionId: String? = null,
    requestContext: HermesRequestContext = HermesHubProtocol.newCorrelationContext()
): Pair<Int, String> {
    val builder = Request.Builder()
        .url(url)
        .header("Accept", "text/event-stream, application/json, text/plain")
        .header("User-Agent", "HermesHub-Android")
    HermesHubProtocol.addCorrelationHeaders(builder, requestContext)
    bearerToken?.let { builder.header("Authorization", "Bearer $it") }
    sessionId?.takeIf { it.isNotBlank() }?.let { builder.header("X-Hermes-Session-Id", it) }
    val normalizedMethod = method.uppercase()
    val request = when (normalizedMethod) {
        "DELETE" -> builder.method(
            "DELETE",
            payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        ).build()
        "PATCH" -> builder.patch(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        else -> builder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }

    return apiHttpClient.newCall(request).execute().use { response ->
        response.code to response.body.byteStream().readUtf8Bounded()
    }
}
