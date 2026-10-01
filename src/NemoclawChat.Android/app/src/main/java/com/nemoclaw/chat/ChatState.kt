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

internal fun loadPendingAttachments(context: Context, conversationId: String?): List<ChatInputAttachment> {
    return runCatching {
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

internal class ChatStateHolder {
    val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()
    val pendingAttachments: SnapshotStateList<ChatInputAttachment> = mutableStateListOf()
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

    val activeStreams: androidx.compose.runtime.snapshots.SnapshotStateMap<String, ActiveStreamState> = androidx.compose.runtime.mutableStateMapOf()
    var streamUiTickNs: Long by mutableLongStateOf(System.nanoTime())

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
        activeStreamJob?.cancel()
        activeConversationId?.let { activeStreams.remove(it) }
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
