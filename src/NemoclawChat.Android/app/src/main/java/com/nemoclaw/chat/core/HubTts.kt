package com.nemoclaw.chat

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nemoclaw.chat.core.WorkLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal val voiceNoteHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
}

internal val updateHttpClient: OkHttpClient by lazy {
    apiHttpClient.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
}

internal val archiveEventsHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .build()
}

@Volatile
internal var activeTtsMediaPlayer: MediaPlayer? = null

/** Ferma subito la lettura TTS in corso, se presente. Sicuro da chiamare sempre. */
internal fun stopTtsPlayback() {
    runCatching { activeTtsMediaPlayer?.stop() }
    runCatching { activeTtsMediaPlayer?.release() }
    activeTtsMediaPlayer = null
}
@Volatile
internal var activeTtsFile: File? = null
private val ttsPlaybackMutex = Mutex()

internal data class TtsRequestResult(
    val statusCode: Int,
    val audioFile: File? = null,
    val errorBody: String = ""
)

/** Limite onesto TTS: una request sola per lettura, niente chunking silenzioso.
 *  Oltre si rifiuta ("testo troppo lungo, seleziona una parte"): il playback
 *  fire-and-forget su singolo MediaPlayer globale rende la concatenazione
 *  di chunk WAV troppo invasiva (header da ricucire + completamenti a catena). */
internal const val MAX_TTS_TEXT_CHARS = 4000

/** Valida il testo TTS: ritorna il testo pulito o lancia IllegalArgumentException. Pura e testabile. */
internal fun validateTtsText(text: String, maxChars: Int = MAX_TTS_TEXT_CHARS): String {
    val clean = text.trim()
    require(clean.isNotBlank()) { "Testo vuoto." }
    require(clean.length <= maxChars) {
        "Testo troppo lungo (${clean.length} caratteri, max $maxChars): seleziona una parte."
    }
    return clean
}

internal suspend fun speakChatMessage(context: Context, settings: AppSettings, text: String, apiKey: String?): Unit = withContext(Dispatchers.IO) {
    // Testo vuoto = uscita silenziosa (come prima); solo il troppo-lungo e un errore onesto.
    if (text.trim().isBlank()) return@withContext
    val cleanText = try {
        validateTtsText(text)
    } catch (ex: IllegalArgumentException) {
        throw java.io.IOException(ex.message)
    }
    val payload = JSONObject()
        .put("input", cleanText)
        .put("voice", "if_sara")
        .put("lang", "it")
        .put("speed", 1.08)
        .put("response_format", "wav")
    var lastError = "nessuna risposta"
    var lastHttpError: String? = null
    val dir = File(context.cacheDir, "tts").apply { mkdirs() }
    dir.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
        ?.forEach { runCatching { it.delete() } }
    for (candidateUrl in ttsUrlCandidates(resolveTtsSpeechUrl(settings))) {
    for (token in hermesAuthCandidates(apiKey, allowCompatAuth = false)) {
            val response = try {
                withTimeout(WorkLimits.TTS_REQUEST_TIMEOUT_MS) {
                    executeTtsRequest(dir, candidateUrl, payload, token)
                }
            } catch (ex: Exception) {
                if (lastHttpError == null) {
                    lastError = ex.message ?: ex.javaClass.simpleName
                }
                continue
            }
            val file = response.audioFile
            if (response.statusCode in 200..299 && file != null) {
                ttsPlaybackMutex.withLock {
                    withContext(Dispatchers.Main) {
                        runCatching { activeTtsMediaPlayer?.release() }
                        activeTtsFile?.let { runCatching { it.delete() } }
                    val player = MediaPlayer()
                    activeTtsMediaPlayer = player
                    activeTtsFile = file
                    fun cleanup() {
                        runCatching { player.release() }
                        if (activeTtsMediaPlayer === player) activeTtsMediaPlayer = null
                        if (activeTtsFile == file) activeTtsFile = null
                        runCatching { file.delete() }
                    }
                    player.setOnCompletionListener {
                        cleanup()
                    }
                    player.setOnErrorListener { mp, _, _ ->
                        cleanup()
                        true
                    }
                    player.setOnPreparedListener { it.start() }
                    try {
                        player.setDataSource(file.absolutePath)
                        player.prepareAsync()
                    } catch (ex: Exception) {
                        cleanup()
                        throw ex
                    }
                    }
                }
                return@withContext
            }
            file?.let { runCatching { it.delete() } }
            lastHttpError = "HTTP ${response.statusCode}${response.errorBody.take(160).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
            lastError = lastHttpError
            if (response.statusCode != 401) break
        }
    }
    throw java.io.IOException(lastError)
}

internal fun executeTtsRequest(
    targetDirectory: File,
    url: String,
    payload: JSONObject,
    bearerToken: String?
): TtsRequestResult {
    val builder = Request.Builder()
        .url(url)
        .header("Accept", "audio/wav")
        .header("User-Agent", "HermesHub-Android")
    bearerToken?.let { builder.header("Authorization", "Bearer $it") }
    val request = builder
        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    return apiHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            return@use TtsRequestResult(
                statusCode = response.code,
                errorBody = response.body.byteStream().readUtf8Bounded()
            )
        }
        val body = response.body
        TtsRequestResult(
            statusCode = response.code,
            audioFile = streamWavToTempFile(
                directory = targetDirectory,
                prefix = "hermes-tts-",
                input = body.byteStream(),
                contentLength = body.contentLength()
            )
        )
    }
}

internal fun resolveTtsSpeechUrl(settings: AppSettings): String {
    return try {
        val uri = URI(settings.gatewayUrl.trim())
        val scheme = uri.scheme ?: "http"
        val host = uri.host?.takeIf { it.isNotBlank() } ?: error("Configura Hermes API URL")
        val port = if (uri.port > 0) uri.port else 8642
        URI(scheme, null, host, port, "/v1/audio/speech", null, null).toString()
    } catch (_: Exception) {
        error("Configura Hermes API URL")
    }
}

internal fun ttsUrlCandidates(url: String): List<String> {
    return try {
        val uri = URI(url)
        val suffix = buildString {
            append(uri.rawPath.orEmpty())
            if (!uri.rawQuery.isNullOrBlank()) append("?").append(uri.rawQuery)
        }
        val port = if (uri.port > 0) uri.port else 8642
        val currentRoot = "${uri.scheme}://${uri.host}:$port"
        val roots = listOf(currentRoot)
        roots.distinctBy { it.lowercase() }.map { it.trimEnd('/') + suffix }
    } catch (_: Exception) {
        listOf(url)
    }
}

