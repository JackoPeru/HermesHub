package com.nemoclaw.chat

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Client per il Bot Screen (desktop Xvnc del bot via gpu-manager).
 * Viewer live = noVNC in WebView su /display/ws?display_ticket=…;
 * qui solo stato/ticket/takeover/preview. Bearer = chiave gateway.
 */
data class ScreenStatusInfo(
    val running: Boolean = false,
    val holder: String = "bot",
    val viewerId: String = "",
    val width: Int = 0,
    val height: Int = 0
) {
    val humanInControl: Boolean get() = holder == "human"
}

internal fun screenManagerBase(settings: AppSettings): String = gpuManagerBase(settings.gatewayUrl)

internal suspend fun getScreenStatus(settings: AppSettings, apiKey: String?): ScreenStatusInfo? =
    withContext(Dispatchers.IO) {
        val res = httpGetResponse("${screenManagerBase(settings)}/display/status", apiKey)
        if (res.first !in 200..299) return@withContext null
        val root = runCatching { JSONObject(res.second) }.getOrNull() ?: return@withContext null
        val geo = root.optJSONObject("geometry")
        ScreenStatusInfo(
            running = root.optBoolean("running", false),
            holder = root.optString("holder", "bot").takeIf { it.isNotBlank() } ?: "bot",
            viewerId = root.optString("viewer_id", ""),
            width = geo?.optInt("width", 0) ?: 0,
            height = geo?.optInt("height", 0) ?: 0
        )
    }

internal suspend fun postScreenTicket(settings: AppSettings, apiKey: String?): Pair<String, String>? =
    withContext(Dispatchers.IO) {
        val (code, body) = postJson("${screenManagerBase(settings)}/display/ticket", JSONObject(), apiKey)
        if (code !in 200..299) return@withContext null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val ticket = root.optString("ticket")
        if (ticket.isBlank()) return@withContext null
        ticket to root.optString("viewer_id", "")
    }

internal suspend fun postScreenTakeover(settings: AppSettings, apiKey: String?, viewerId: String?): Boolean =
    withContext(Dispatchers.IO) {
        val payload = JSONObject()
        if (!viewerId.isNullOrBlank()) payload.put("viewer_id", viewerId)
        val (code, _) = postJson("${screenManagerBase(settings)}/display/takeover", payload, apiKey)
        code in 200..299
    }

internal suspend fun postScreenRelease(settings: AppSettings, apiKey: String?, viewerId: String?): Boolean =
    withContext(Dispatchers.IO) {
        val payload = JSONObject()
        if (!viewerId.isNullOrBlank()) payload.put("viewer_id", viewerId)
        val (code, _) = postJson("${screenManagerBase(settings)}/display/release", payload, apiKey)
        code in 200..299
    }

internal suspend fun fetchScreenFrameBytes(settings: AppSettings, apiKey: String?, width: Int = 480): ByteArray? =
    withContext(Dispatchers.IO) {
        val url = "${screenManagerBase(settings)}/display/frame.png?width=${width.coerceIn(160, 1920)}"
        var last: ByteArray? = null
        for (candidateUrl in plugAndPlayUrlCandidates(url)) {
            for (token in hermesAuthCandidates(apiKey)) {
                last = runCatching {
                    val builder = okhttp3.Request.Builder()
                        .url(candidateUrl)
                        .header("Accept", "image/png")
                        .header("User-Agent", "HermesHub-Android")
                    token?.let { builder.header("Authorization", "Bearer $it") }
                    apiHttpClient.newCall(builder.get().build()).execute().use { resp ->
                        if (!resp.isSuccessful) null
                        else resp.body.bytes().takeIf { it.isNotEmpty() }
                    }
                }.getOrNull()
                if (last != null) return@withContext last
            }
        }
        last
    }

internal fun decodeScreenFrame(bytes: ByteArray?, reqWidth: Int = BitmapImageLoader.DEFAULT_SCREEN_REQ_WIDTH): Bitmap? {
    // Migrazione a loader centralizzato: sampling + cache, default = larghezza view.
    return BitmapImageLoader.decodeScreenFrame(bytes, reqWidth)
}

/** Costruisce l'URL ws(s) del bridge a partire dal base manager. Puro, testabile. */
internal fun buildScreenWsUrl(managerBase: String, ticket: String): String? {
    val root = managerBase.trim().trimEnd('/')
    if (root.isEmpty() || ticket.isBlank()) return null
    val wsRoot = when {
        root.startsWith("http://", ignoreCase = true) -> "ws://" + root.drop(7)
        root.startsWith("https://", ignoreCase = true) -> "wss://" + root.drop(8)
        else -> return null
    }
    return "$wsRoot/display/ws?display_ticket=$ticket"
}

/** URL pagina viewer locale (WebViewAssetLoader) con parametri noVNC. Puro, testabile. */
internal fun buildScreenViewerUrl(wsUrl: String, viewOnly: Boolean): String {
    val base = "https://appassets.androidplatform.net/assets/novnc/viewer.html"
    // URLEncoder (java.net, JVM-puro) invece di android.net.Uri: testabile senza framework.
    val encoded = java.net.URLEncoder.encode(wsUrl, "UTF-8")
    return base + "?url=" + encoded +
        "&viewonly=" + if (viewOnly) "1" else "0"
}
