package com.nemoclaw.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.edit
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val PENDING_ATTACHMENTS_PREFS = "chatclaw_pending_attachments"
private const val QUEUED_PROMPTS_KEY = "queued:v1"

internal fun saveQueuedPrompts(context: Context, queue: List<QueuedPrompt>) {
    runCatching {
        val arr = JSONArray()
        queue.take(MAX_QUEUED_PROMPTS_PER_CHAT * 4).forEach { q ->
            val files = JSONArray()
            q.attachments.forEach {
                val path = it.localFilePath
                if (!path.isNullOrBlank() && File(path).isFile) {
                    files.put(
                        JSONObject()
                            .put("filename", it.filename)
                            .put("mimeType", it.mimeType)
                            .put("sizeBytes", it.sizeBytes)
                            .put("localFilePath", path)
                    )
                }
            }
            arr.put(
                JSONObject()
                    .put("conversationId", q.conversationId)
                    .put("text", q.text)
                    .put("attachments", files)
                    .put("atMs", q.atMs)
            )
        }
        context.getSharedPreferences(PENDING_ATTACHMENTS_PREFS, Context.MODE_PRIVATE).edit {
            putString(QUEUED_PROMPTS_KEY, arr.toString())
        }
    }
}

internal fun loadQueuedPrompts(context: Context): List<QueuedPrompt> = runCatching {
    val raw = context.getSharedPreferences(PENDING_ATTACHMENTS_PREFS, Context.MODE_PRIVATE)
        .getString(QUEUED_PROMPTS_KEY, null) ?: return emptyList()
    val arr = JSONArray(raw)
    List(arr.length()) { index ->
        val obj = arr.getJSONObject(index)
        val files = mutableListOf<ChatInputAttachment>()
        val filesArr = obj.optJSONArray("attachments")
        if (filesArr != null) {
            for (i in 0 until filesArr.length()) {
                val fileObj = filesArr.optJSONObject(i) ?: continue
                val path = fileObj.optString("localFilePath").takeIf { it.isNotBlank() }
                if (path != null && File(path).isFile) {
                    files.add(
                        ChatInputAttachment(
                            filename = fileObj.optString("filename"),
                            mimeType = fileObj.optString("mimeType"),
                            sizeBytes = fileObj.optLong("sizeBytes"),
                            localFilePath = path
                        )
                    )
                }
            }
        }
        QueuedPrompt(
            conversationId = obj.optString("conversationId"),
            text = obj.optString("text"),
            attachments = files,
            atMs = obj.optLong("atMs", System.currentTimeMillis())
        )
    }.filter { it.conversationId.isNotBlank() && (it.text.isNotBlank() || it.attachments.isNotEmpty()) }
}.getOrDefault(emptyList())

internal fun savePendingAttachments(context: Context, conversationId: String?, attachments: List<ChatInputAttachment>) {
    runCatching {
        val arr = JSONArray()
        attachments.forEach {
            val path = it.localFilePath
            if (!path.isNullOrBlank() && File(path).isFile) {
                arr.put(
                    JSONObject()
                        .put("filename", it.filename)
                        .put("mimeType", it.mimeType)
                        .put("sizeBytes", it.sizeBytes)
                        .put("localFilePath", path)
                )
            }
        }
        context.getSharedPreferences(PENDING_ATTACHMENTS_PREFS, Context.MODE_PRIVATE).edit {
            putString("pending:${conversationId.orEmpty()}", arr.toString())
        }
    }
}

internal fun loadPendingAttachments(context: Context, conversationId: String?): List<ChatInputAttachment> {    return runCatching {
        val raw = context.getSharedPreferences(PENDING_ATTACHMENTS_PREFS, Context.MODE_PRIVATE)
            .getString("pending:${conversationId.orEmpty()}", null) ?: return emptyList()
        val arr = JSONArray(raw)
        List(arr.length()) { index ->
            val obj = arr.getJSONObject(index)
            ChatInputAttachment(
                filename = obj.optString("filename"),
                mimeType = obj.optString("mimeType"),
                sizeBytes = obj.optLong("sizeBytes"),
                localFilePath = obj.optString("localFilePath").takeIf { it.isNotBlank() }
            )
        }.filter { item ->
            val path = item.localFilePath
            !path.isNullOrBlank() && File(path).isFile
        }
    }.getOrDefault(emptyList())
}

internal data class ActiveStreamState(
    val streamingState: StreamingState?,
    val job: Job?
)

internal data class BackgroundWorkUi(
    val runId: String,
    val goal: String,
    val statusText: String,
    val approvalPending: Boolean = false
)

/** Prompt accodato mentre un turno e attivo: parte da solo alla fine. */
internal data class QueuedPrompt(
    val conversationId: String,
    val text: String,
    val attachments: List<ChatInputAttachment> = emptyList(),
    val atMs: Long = System.currentTimeMillis()
)

internal const val MAX_QUEUED_PROMPTS_PER_CHAT = 10

/** Prompt in attesa di invio mentre il turno e occupato (scelta Accoda/Correggi). */
internal data class PendingBusySend(
    val text: String,
    val attachments: List<ChatInputAttachment> = emptyList()
)

/** Prossimo prompt in coda per cid (FIFO), o null. Puro/testabile. */
internal fun nextQueuedFor(queue: List<QueuedPrompt>, cid: String): QueuedPrompt? =
    queue.firstOrNull { it.conversationId == cid }

/** C'e posto in coda per cid? */
internal fun canEnqueuePrompt(queue: List<QueuedPrompt>, cid: String): Boolean =
    queue.count { it.conversationId == cid } < MAX_QUEUED_PROMPTS_PER_CHAT

internal class ChatStateHolder {
    val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()
    val pendingAttachments: SnapshotStateList<ChatInputAttachment> = mutableStateListOf()
    val queuedPrompts: SnapshotStateList<QueuedPrompt> = mutableStateListOf()
    var draft: String by mutableStateOf("")
    var mode: String by mutableStateOf("Chat")
    var activeConversationId: String? by mutableStateOf(null)
    var previousResponseId: String? by mutableStateOf(null)
    var hermesSessionId: String? by mutableStateOf(null)
    var chatCapabilities: HermesCapabilities? by mutableStateOf(null)
    var chatModelCatalog: HermesModelCatalog by mutableStateOf(HermesModelCatalog())
    var chatModelOverride: String by mutableStateOf("")
    var chatProviderOverride: String by mutableStateOf("")
    var chatReasoningEffort: String by mutableStateOf("")
    var sessionRoute: String by mutableStateOf("legacy")
    var isRecordingVoiceNote: Boolean by mutableStateOf(false)
    var tempVoiceNoteFile: java.io.File? = null
    var backgroundWork: BackgroundWorkUi? by mutableStateOf(null)

    val activeStreams: androidx.compose.runtime.snapshots.SnapshotStateMap<String, ActiveStreamState> = androidx.compose.runtime.mutableStateMapOf()

    val sending: Boolean
        get() = activeConversationId?.let { activeStreams[it] != null } ?: false

    var streamingState: StreamingState?
        get() = activeConversationId?.let { activeStreams[it]?.streamingState }
        set(value) {
            val cid = activeConversationId ?: return
            val current = activeStreams[cid] ?: ActiveStreamState(null, null)
            if (value == null && current.job == null) {
                activeStreams.remove(cid)
            } else {
                activeStreams[cid] = current.copy(streamingState = value)
            }
        }

    var activeStreamJob: Job?
        get() = activeConversationId?.let { activeStreams[it]?.job }
        set(value) {
            val cid = activeConversationId ?: return
            val current = activeStreams[cid] ?: ActiveStreamState(null, null)
            if (current.streamingState == null && value == null) {
                activeStreams.remove(cid)
            } else {
                activeStreams[cid] = current.copy(job = value)
            }
        }

    fun resetForNewChat() {
        activeStreams.values.forEach { it.job?.cancel() }
        activeStreams.clear()
        messages.clear()
        pendingAttachments.clear()
        activeConversationId = null
        previousResponseId = null
        hermesSessionId = null
        chatModelOverride = ""
        chatProviderOverride = ""
        chatReasoningEffort = ""
        sessionRoute = "legacy"
        draft = ""
    }
}

internal fun saveDraft(context: Context, conversationId: String?, draft: String) {
    runCatching {
        context.getSharedPreferences(PENDING_ATTACHMENTS_PREFS, Context.MODE_PRIVATE).edit {
            putString("draft:${conversationId.orEmpty()}", draft)
        }
    }
}

internal fun loadDraft(context: Context, conversationId: String?): String {
    return runCatching {
        context.getSharedPreferences(PENDING_ATTACHMENTS_PREFS, Context.MODE_PRIVATE)
            .getString("draft:${conversationId.orEmpty()}", null).orEmpty()
    }.getOrDefault("")
}
