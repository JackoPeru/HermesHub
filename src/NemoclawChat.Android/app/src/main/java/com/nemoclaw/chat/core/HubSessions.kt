package com.nemoclaw.chat

import android.content.Context
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Esito scrittura sessione server: mai presentare falso successo da sola cache locale. */
internal enum class HermesSessionWrite {
    APPLIED,
    GONE,
    AUTH_DENIED,
    TRANSIENT
}

internal fun sessionWriteOutcome(code: Int): HermesSessionWrite = when {
    code in 200..299 -> HermesSessionWrite.APPLIED
    code == 404 -> HermesSessionWrite.GONE
    code == 401 || code == 403 -> HermesSessionWrite.AUTH_DENIED
    else -> HermesSessionWrite.TRANSIENT
}

internal data class HermesSessionWriteResult(
    val ok: Boolean,
    /** false = conversazione non collegata a sessione Hermes (percorso legacy locale). */
    val serverBound: Boolean,
    val error: String? = null
)

/**
 * Rename con Hermes autorevole: prima PATCH server, poi cache locale.
 * 401/403/transient -> nessun falso successo e nessuna divergenza silenziosa.
 */
internal suspend fun renameHermesSessionForConversation(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    localId: String,
    newTitle: String,
    profile: String? = null,
    multiplexEnabled: Boolean = false
): HermesSessionWriteResult = withContext(Dispatchers.IO) {
    val serverId = loadSessionBinding(context, localId, profile)
        ?: loadConversation(context, localId)?.hermesSessionId
    if (serverId.isNullOrBlank()) {
        val localOk = renameConversation(context, localId, newTitle)
        return@withContext HermesSessionWriteResult(localOk, serverBound = false)
    }
    return@withContext try {
        val caps = loadHermesCapabilitiesCached(settings, apiKey)
        if (caps != null && !caps.sessionPatch) {
            val localOk = renameConversation(context, localId, newTitle)
            return@withContext HermesSessionWriteResult(localOk, serverBound = false)
        }
        val client = HermesSessionClient(settings, apiKey, profile, multiplexEnabled, caps)
        when (sessionWriteOutcome(client.patch(serverId, title = newTitle).first)) {
            HermesSessionWrite.APPLIED -> {
                val localOk = renameConversation(context, localId, newTitle)
                HermesSessionWriteResult(localOk, serverBound = true)
            }
            HermesSessionWrite.GONE -> {
                // Sessione inesistente: binding morto rimosso, locale resta e diventa
                // unbound (il prossimo turno crea una sessione fresca). Mai presentarlo
                // come modifica applicata sul server.
                clearSessionBinding(context, localId, profile)
                val localOk = renameConversation(context, localId, newTitle)
                HermesSessionWriteResult(localOk, serverBound = false)
            }
            HermesSessionWrite.AUTH_DENIED ->
                HermesSessionWriteResult(false, serverBound = true, error = "Chiave rifiutata dal server (fail-closed).")
            HermesSessionWrite.TRANSIENT ->
                HermesSessionWriteResult(false, serverBound = true, error = "Modifica server non applicata (errore temporaneo): riprova.")
        }
    } catch (ex: Exception) {
        HermesSessionWriteResult(false, serverBound = true, error = "Modifica server non applicata: ${ex.message ?: ex.javaClass.simpleName}.")
    }
}

/**
 * Delete con Hermes autorevole: prima DELETE server (binding conservato per retry),
 * poi tombstone locale. 401/403/transient -> nulla eliminato, errore esplicito.
 */
internal suspend fun deleteHermesSessionForConversation(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    localId: String,
    profile: String? = null,
    multiplexEnabled: Boolean = false
): HermesSessionWriteResult = withContext(Dispatchers.IO) {
    val serverId = loadSessionBinding(context, localId, profile)
        ?: loadConversation(context, localId)?.hermesSessionId
    if (serverId.isNullOrBlank()) {
        val localOk = deleteConversation(context, localId)
        return@withContext HermesSessionWriteResult(localOk, serverBound = false)
    }
    return@withContext try {
        val caps = loadHermesCapabilitiesCached(settings, apiKey)
        if (caps != null && !caps.sessionDelete) {
            val localOk = deleteConversation(context, localId)
            clearSessionBinding(context, localId, profile)
            return@withContext HermesSessionWriteResult(localOk, serverBound = false)
        }
        when (sessionWriteOutcome(HermesSessionClient(settings, apiKey, profile, multiplexEnabled, caps).delete(serverId))) {
            HermesSessionWrite.APPLIED, HermesSessionWrite.GONE -> {
                val localOk = deleteConversation(context, localId)
                clearSessionBinding(context, localId, profile)
                HermesSessionWriteResult(localOk, serverBound = true)
            }
            HermesSessionWrite.AUTH_DENIED ->
                HermesSessionWriteResult(false, serverBound = true, error = "Chiave rifiutata dal server (fail-closed).")
            HermesSessionWrite.TRANSIENT ->
                HermesSessionWriteResult(false, serverBound = true, error = "Eliminazione server non applicata (errore temporaneo): riprova.")
        }
    } catch (ex: Exception) {
        HermesSessionWriteResult(false, serverBound = true, error = "Eliminazione server non applicata: ${ex.message ?: ex.javaClass.simpleName}.")
    }
}

/** Fork reale Hermes: branch server-side + nuova chat locale collegata al fork. */
internal suspend fun forkHermesSessionForConversation(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    localId: String,
    profile: String? = null,
    multiplexEnabled: Boolean = false,
    title: String? = null
): LocalConversation? = withContext(Dispatchers.IO) {
    val source = loadConversation(context, localId) ?: return@withContext null
    val serverId = loadSessionBinding(context, localId, profile) ?: source.hermesSessionId
    if (serverId.isNullOrBlank()) return@withContext null
    return@withContext try {
        val caps = loadHermesCapabilitiesCached(settings, apiKey)
        if (caps != null && !caps.sessionFork) return@withContext null
        val client = HermesSessionClient(settings, apiKey, profile, multiplexEnabled, caps)
        val (code, forked) = client.fork(serverId, title = title ?: "${source.title} (branch)")
        if (code !in 200..299 || forked == null) return@withContext null
        val newId = "conv_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
        val branched = source.copy(
            id = newId,
            title = forked.title.ifBlank { "${source.title} (branch)" },
            updatedAt = System.currentTimeMillis(),
            hermesSessionId = forked.id,
            serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, newId),
            parentConversationId = source.id
        )
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        conversations.add(0, branched)
        saveConversations(context, conversations)
        saveSessionBinding(context, newId, profile, forked.id)
        branched
    } catch (_: Exception) {
        null
    }
}

