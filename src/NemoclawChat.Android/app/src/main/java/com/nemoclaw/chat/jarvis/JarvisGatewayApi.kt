package com.nemoclaw.chat.jarvis

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class JarvisGatewayApi(
    gatewayUrl: String,
    private val apiKey: String?,
    private val client: OkHttpClient = defaultClient
) {
    private val root = gatewayUrl.trim().trimEnd('/').also {
        require(it.startsWith("http://") || it.startsWith("https://")) { "Configura Hermes API URL." }
    }

    suspend fun capabilities(): JarvisCapabilities {
        val response = execute(request("$root/capabilities").get().build())
        val jarvis = JSONObject(response).optJSONObject("jarvis")
            ?: error("Il gateway non espone Jarvis Mode.")
        return JarvisCapabilities(
            enabled = jarvis.optBoolean("enabled"),
            vision = jarvis.optBoolean("vision"),
            proactiveEvents = jarvis.optBoolean("proactive_events"),
            singleModel = jarvis.optBoolean("single_model", true),
            fastModelConfigured = jarvis.optBoolean("fast_model_configured"),
            reasoningModelConfigured = jarvis.optBoolean("reasoning_model_configured"),
            maxFrameBytes = jarvis.optInt("max_frame_bytes", 1_000_000),
            maxSessionSeconds = jarvis.optInt("max_session_seconds", 3_600)
        )
    }

    suspend fun createSession(mode: JarvisInitiativeMode, goal: String): JarvisRemoteSession {
        val body = JSONObject().put("mode", mode.wireValue).put("goal", goal.take(2_000))
        return parseSession(execute(jsonRequest("$root/jarvis/sessions", body).post()))
    }

    suspend fun patchSession(
        sessionId: String,
        mode: JarvisInitiativeMode? = null,
        viewPaused: Boolean? = null,
        status: String? = null,
        speaking: Boolean? = null
    ): JarvisRemoteSession {
        val body = JSONObject()
        mode?.let { body.put("mode", it.wireValue) }
        viewPaused?.let { body.put("view_paused", it) }
        status?.let { body.put("status", it) }
        speaking?.let { body.put("speaking", it) }
        return parseSession(execute(jsonRequest(sessionUrl(sessionId), body).patch()))
    }

    suspend fun deleteSession(sessionId: String) {
        execute(request(sessionUrl(sessionId)).delete().build())
    }

    suspend fun uploadFrame(sessionId: String, jpeg: ByteArray, capturedAtMillis: Long): String {
        val url = "${sessionUrl(sessionId)}/frames?captured_at=${capturedAtMillis / 1000.0}"
        val request = request(url)
            .header("Content-Type", "image/jpeg")
            .post(jpeg.toRequestBody(JPEG))
            .build()
        return JSONObject(execute(request)).optString("frame_id")
    }

    suspend fun sendTurn(sessionId: String, transcript: String): JarvisTurnResult {
        val body = JSONObject().put("transcript", transcript.take(4_000))
        val parsed = JSONObject(execute(jsonRequest("${sessionUrl(sessionId)}/turns", body).post()))
        val metrics = parsed.optJSONObject("metrics")
        return JarvisTurnResult(
            text = parsed.optString("text").trim(),
            route = parsed.optString("route").ifBlank { "unknown" },
            totalLatencyMs = metrics?.optDouble("total_ms")
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.toLong()
        )
    }

    suspend fun sendFeedback(sessionId: String, eventId: String, helpful: Boolean) {
        require(eventId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Event id non valido." }
        val body = JSONObject().put("event_id", eventId).put("helpful", helpful)
        execute(jsonRequest("${sessionUrl(sessionId)}/feedback", body).post())
    }

    fun events(sessionId: String, lastEventId: String? = null): Flow<JarvisEvent> = callbackFlow {
        val builder = request("${sessionUrl(sessionId)}/events")
            .header("Accept", "text/event-stream")
            .get()
        if (!lastEventId.isNullOrBlank()) builder.header("Last-Event-ID", lastEventId)
        val call = client.newCall(builder.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    close(IOException("SSE Jarvis HTTP $code"))
                    return
                }
                launch {
                    response.use {
                        val source = it.body.source()
                        var id: String? = null
                        var eventName: String? = null
                        val data = StringBuilder()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("id:") -> id = line.substringAfter(':').trim()
                                line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                                line.startsWith("data:") -> {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.substringAfter(':').trimStart())
                                }
                                line.isEmpty() -> {
                                    if (data.isNotEmpty()) {
                                        runCatching { JarvisEvent.parse(data.toString(), id, eventName) }
                                            .onSuccess { send(it) }
                                    }
                                    id = null
                                    eventName = null
                                    data.setLength(0)
                                }
                            }
                        }
                    }
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }

    private fun sessionUrl(sessionId: String): String {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Session id non valido." }
        return "$root/jarvis/sessions/$sessionId"
    }

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", "HermesHub-Android-Jarvis")
        .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }

    private fun jsonRequest(url: String, body: JSONObject): JsonRequest = JsonRequest(
        request(url),
        body.toString().toRequestBody(JSON)
    )

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    if (call.isCanceled()) CancellationException("Richiesta Jarvis annullata.") else e
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body.string().take(512 * 1024)
                    if (!it.isSuccessful) {
                        val detail = runCatching {
                            JSONObject(raw).optJSONObject("error")?.optString("message")
                        }.getOrNull().orEmpty().ifBlank { raw.take(240) }
                        if (continuation.isActive) continuation.resumeWithException(IOException("HTTP ${it.code}: $detail"))
                    } else if (continuation.isActive) {
                        continuation.resume(raw)
                    }
                }
            }
        })
    }

    private fun parseSession(raw: String): JarvisRemoteSession {
        val body = JSONObject(raw)
        return JarvisRemoteSession(
            id = body.getString("id"),
            status = body.optString("status", "active"),
            mode = JarvisInitiativeMode.fromWire(body.optString("mode")),
            viewPaused = body.optBoolean("view_paused")
        )
    }

    private data class JsonRequest(val builder: Request.Builder, val body: okhttp3.RequestBody) {
        fun post(): Request = builder.post(body).build()
        fun patch(): Request = builder.patch(body).build()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val JPEG = "image/jpeg".toMediaType()
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}
