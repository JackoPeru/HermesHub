package com.nemoclaw.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Job

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
        draft = ""
    }
}
