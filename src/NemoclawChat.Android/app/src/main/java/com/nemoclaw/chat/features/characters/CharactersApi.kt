package com.nemoclaw.chat.features.characters

import android.content.Context
import android.net.Uri
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.apiHttpClient
import com.nemoclaw.chat.gpuManagerBase
import com.nemoclaw.chat.httpGetResponse
import com.nemoclaw.chat.loadGatewaySecret
import com.nemoclaw.chat.postJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Stato personaggio (backend) -> etichetta italiana. Puro e testabile.
 */
internal fun characterStatusLabel(status: String): String = when (status) {
    "draft" -> "Bozza"
    "analyzing" -> "Analisi foto…"
    "ready_to_train" -> "Pronto a creare"
    "training" -> "Creazione…"
    "validating" -> "Verifica…"
    "ready" -> "Pronto"
    "failed" -> "Errore"
    "needs_retrain" -> "Da migliorare"
    "interrupted" -> "Interrotto"
    else -> status.ifBlank { "Sconosciuto" }
}

/** Stato job character -> etichetta italiana. Puro e testabile. */
internal fun characterJobLabel(status: String): String = when (status) {
    "queued" -> "In coda"
    "preparing" -> "Preparazione"
    "caching" -> "Cache identità"
    "smoke_test" -> "Prova fumo"
    "training" -> "Apprendimento"
    "validating" -> "Verifica"
    "ready" -> "Finito"
    "failed" -> "Fallito"
    "cancelled" -> "Annullato"
    else -> status.ifBlank { "—" }
}

internal data class CharacterSummary(
    val id: String,
    val name: String,
    val status: String,
    val imageCount: Int,
    val identityScore: Double?,
    val recommendedEngine: String,
    val defaultMode: String
)

internal data class CharacterJob(
    val id: String,
    val kind: String,
    val status: String,
    val progress: Double,
    val detail: String,
    val error: String
)

internal fun parseCharacterSummary(item: JSONObject): CharacterSummary? {
    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
    return CharacterSummary(
        id = id,
        name = item.optString("name", "Senza nome"),
        status = item.optString("status", "draft"),
        imageCount = item.optInt("image_count", 0),
        identityScore = item.optDouble("identity_score", Double.NaN).takeIf { !it.isNaN() },
        recommendedEngine = item.optString("recommended_engine", "lora"),
        defaultMode = item.optString("default_mode", "auto")
    )
}

internal fun parseCharacterJob(item: JSONObject?): CharacterJob? {
    if (item == null) return null
    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
    return CharacterJob(
        id = id,
        kind = item.optString("kind"),
        status = item.optString("status"),
        progress = item.optDouble("progress", 0.0).coerceIn(0.0, 1.0),
        detail = item.optString("detail"),
        error = item.optString("error")
    )
}

/** Fasi training mostrate in UI (nomi tecnici nascosti). Puro e testabile. */
internal fun trainingPhaseLabel(job: CharacterJob?): String {
    if (job == null) return "Pronto"
    return when (job.kind) {
        "analyze" -> "Analisi foto ${percent(job.progress)}"
        "train" -> if (job.status == "caching" || job.progress < 0.26) "Cache identità ${percent(job.progress)}"
        else "Apprendimento identità ${percent(job.progress)}"
        "evaluate" -> "Verifica coerenza ${percent(job.progress)}"
        "benchmark" -> "Confronto motori ${percent(job.progress)}"
        else -> "${characterJobLabel(job.status)} ${percent(job.progress)}"
    }
}

private fun percent(progress: Double): String = "${(progress * 100).toInt()}%"

internal fun charactersBase(settings: AppSettings): String = gpuManagerBase(settings.gatewayUrl)

internal suspend fun loadCharacters(settings: AppSettings, managerKey: String?): Pair<List<CharacterSummary>, String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val base = charactersBase(settings)
            if (base.isBlank()) return@runCatching emptyList<CharacterSummary>() to "Gateway non configurato"
            val (code, body) = httpGetResponse("$base/characters", managerKey)
            if (code !in 200..299) return@runCatching emptyList<CharacterSummary>() to "Personaggi HTTP $code"
            val array = JSONObject(body).optJSONArray("characters") ?: return@runCatching emptyList<CharacterSummary>() to "Nessun personaggio"
            val result = buildList {
                for (i in 0 until array.length()) {
                    parseCharacterSummary(array.optJSONObject(i) ?: continue)?.let { add(it) }
                }
            }
            result to if (result.isEmpty()) "Nessun personaggio ancora." else "${result.size} personaggi."
        }.getOrElse { emptyList<CharacterSummary>() to "Personaggi non disponibili" }
    }

internal suspend fun createCharacter(settings: AppSettings, managerKey: String?, name: String): Pair<String?, String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = postJson("${charactersBase(settings)}/characters", JSONObject().put("name", name), managerKey)
            if (code !in 200..299) return@runCatching null to "Creazione HTTP $code: ${body.take(160)}"
            val created = JSONObject(body).optString("id").takeIf { it.isNotBlank() }
            if (created != null) created to created else null to "Risposta illeggibile"
        }.getOrElse { null to "Creazione fallita" }
    }

internal suspend fun loadCharacterManifest(settings: AppSettings, managerKey: String?, id: String): JSONObject? =
    withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = httpGetResponse("${charactersBase(settings)}/characters/$id", managerKey)
            if (code !in 200..299) null else JSONObject(body)
        }.getOrNull()
    }

internal suspend fun loadImageVerdicts(settings: AppSettings, managerKey: String?, id: String): Triple<Int, Int, Int> =
    withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = httpGetResponse("${charactersBase(settings)}/characters/$id/images", managerKey)
            if (code !in 200..299) return@runCatching Triple(0, 0, 0)
            val array = JSONObject(body).optJSONArray("images") ?: return@runCatching Triple(0, 0, 0)
            var good = 0
            var warn = 0
            var bad = 0
            for (i in 0 until array.length()) {
                when (array.optJSONObject(i)?.optString("accepted")) {
                    "accepted" -> good++
                    "warning" -> warn++
                    "rejected" -> bad++
                }
            }
            Triple(good, warn, bad)
        }.getOrElse { Triple(0, 0, 0) }
    }

internal suspend fun loadCharacterStatus(settings: AppSettings, managerKey: String?, id: String): Triple<String, CharacterJob?, JSONObject?> =
    withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = httpGetResponse("${charactersBase(settings)}/characters/$id/status", managerKey)
            if (code !in 200..299) return@runCatching Triple("draft", null, null)
            val root = JSONObject(body)
            Triple(root.optString("status", "draft"), parseCharacterJob(root.optJSONObject("active_job")), root)
        }.getOrElse { Triple("draft", null, null) }
    }

internal suspend fun postCharacterAction(
    settings: AppSettings,
    managerKey: String?,
    id: String,
    action: String,
    payload: JSONObject = JSONObject()
): Pair<Int, String> = withContext(Dispatchers.IO) {
    runCatching {
        postJson("${charactersBase(settings)}/characters/$id/$action", payload, managerKey)
    }.getOrElse { 0 to "Rete non disponibile" }
}

internal suspend fun deleteCharacter(settings: AppSettings, managerKey: String?, id: String): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        runCatching {
            postJson("${charactersBase(settings)}/characters/$id", JSONObject(), managerKey, method = "DELETE")
        }.getOrElse { 0 to "Rete non disponibile" }
    }

internal suspend fun renameCharacter(settings: AppSettings, managerKey: String?, id: String, name: String): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        runCatching {
            postJson(
                "${charactersBase(settings)}/characters/$id",
                JSONObject().put("name", name),
                managerKey,
                method = "PATCH"
            )
        }.getOrElse { 0 to "Rete non disponibile" }
    }

/**
 * Upload foto (multipart, campo "files" ripetuto). Copia ogni URI in cache con
 * tetto 15MB prima dell'invio: niente OOM, niente file giganti al server.
 * Ritorna (inviati, messaggio, motivi scarto).
 */
internal suspend fun uploadCharacterPhotos(
    context: Context,
    settings: AppSettings,
    managerKey: String?,
    id: String,
    uris: List<Uri>
): Triple<Int, String, List<String>> = withContext(Dispatchers.IO) {
    runCatching {
        val base = charactersBase(settings)
        if (base.isBlank()) return@runCatching Triple(0, "Gateway non configurato", emptyList())
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        var staged = 0
        val skipped = mutableListOf<String>()
        val tempFiles = mutableListOf<File>()
        try {
            for ((index, uri) in uris.withIndex()) {
                val temp = File(context.cacheDir, "hcid-${System.currentTimeMillis()}-$index.jpg")
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > 15L * 1024 * 1024 + 1) break
                            output.write(buffer, 0, read)
                        }
                        total
                    }
                    out
                }
                if (bytes == null) {
                    temp.delete()
                    skipped.add("file $index illeggibile")
                    continue
                }
                if (bytes <= 0 || bytes > 15L * 1024 * 1024) {
                    temp.delete()
                    skipped.add("file $index oltre 15MB")
                    continue
                }
                tempFiles.add(temp)
                builder.addFormDataPart("files", temp.name, temp.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                staged++
            }
            if (staged == 0) return@runCatching Triple(0, "Nessuna foto leggibile (max 15MB cad.)", skipped)
            val request = Request.Builder()
                .url("$base/characters/$id/images")
                .post(builder.build())
                .apply { if (!managerKey.isNullOrBlank()) header("Authorization", "Bearer $managerKey") }
                .build()
            val response = apiHttpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()
            response.close()
            if (code !in 200..299) return@runCatching Triple(0, "Upload HTTP $code: ${body.take(160)}", skipped)
            val root = JSONObject(body)
            val uploaded = root.optInt("uploaded", staged)
            val serverFailed = mutableListOf<String>()
            val failedArray = root.optJSONArray("failed")
            if (failedArray != null) {
                for (i in 0 until failedArray.length()) {
                    serverFailed.add(failedArray.optString(i))
                }
            }
            Triple(uploaded, "Caricate $uploaded foto.", skipped + serverFailed)
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }.getOrElse { Triple(0, "Upload fallito", emptyList()) }
}

internal suspend fun generateCharacterVideo(
    settings: AppSettings,
    managerKey: String?,
    id: String,
    prompt: String,
    identityMode: String,
    duration: Int,
    aspect: String,
    seed: Long
): Pair<String?, String> = withContext(Dispatchers.IO) {
    runCatching {
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("identity_mode", identityMode)
            .put("duration", duration)
            .put("aspect_ratio", aspect)
            .put("seed", seed)
        val (code, body) = postJson("${charactersBase(settings)}/characters/$id/generate", payload, managerKey)
        if (code !in 200..299) return@runCatching null to "Generazione HTTP $code: ${body.take(160)}"
        val jobId = JSONObject(body).optString("job_id").takeIf { it.isNotBlank() }
        if (jobId != null) jobId to "Coda: $jobId" else null to "Risposta illeggibile"
    }.getOrElse { null to "Generazione fallita" }
}

internal fun managerKeyOf(context: Context): String? = loadGatewaySecret(context)
