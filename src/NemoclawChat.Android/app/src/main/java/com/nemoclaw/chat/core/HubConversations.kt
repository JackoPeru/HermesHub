package com.nemoclaw.chat

import android.content.Context
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


internal fun saveConversationSnapshot(
    context: Context,
    conversationId: String?,
    mode: String,
    prompt: String,
    messages: List<ChatMessage>,
    source: String,
    responseId: String? = null,
    projectId: String? = null,
    syncAfterSave: Boolean = true,
    hermesSessionId: String? = null,
    modelOverride: String? = null,
    providerOverride: String? = null,
    reasoningEffort: String? = null,
    // Chat collegata al desktop (stesso id Hub): non sovrascrivere i
    // puntatori di continuazione altrui (serverConversationId e
    // previousResponseId restano quelli esistenti). Vale anche per
    // override e hermesSessionId: la chat condivisa non si ri-brandizza.
    preserveRemoteContinuity: Boolean = false,
    // Identita bot proprietaria ("connessione::profilo", "" = default):
    // stampata sempre (anche con preserveRemoteContinuity) per filtrare
    // la sidebar per bot.
    botIdentityKey: String = ""
): LocalConversation {
    synchronized(localArchiveLock) {
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        val index = conversations.indexOfFirst { it.id == conversationId && it.deletedAt == null }
        val now = System.currentTimeMillis()
        val newConversationId = conversationId?.takeIf { it.isNotBlank() } ?: "conv_$now"
        val conversation = if (index >= 0) {
            val current = conversations[index]
            current.copy(
                kind = if (mode == "Agente") "Task" else current.kind,
                description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
                prompt = prompt,
                updatedAt = now,
                messages = messages,
                previousResponseId = if (preserveRemoteContinuity) current.previousResponseId else responseId ?: current.previousResponseId,
                serverConversationId = if (preserveRemoteContinuity) {
                    current.serverConversationId
                } else {
                    hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, current.id)
                },
                hermesSessionId = if (preserveRemoteContinuity) current.hermesSessionId else hermesSessionId ?: current.hermesSessionId,
                modelOverride = if (preserveRemoteContinuity) current.modelOverride else modelOverride ?: current.modelOverride,
                providerOverride = if (preserveRemoteContinuity) current.providerOverride else providerOverride ?: current.providerOverride,
                reasoningEffort = if (preserveRemoteContinuity) current.reasoningEffort else reasoningEffort ?: current.reasoningEffort,
                projectId = current.projectId.ifBlank { projectId.orEmpty() },
                botIdentityKey = botIdentityKey.trim().takeIf { it.isNotBlank() } ?: current.botIdentityKey
            )
        } else {
            LocalConversation(
                id = newConversationId,
                title = UNTITLED_CHAT_TITLE,
                kind = if (mode == "Agente") "Task" else "Chat",
                description = if (mode == "Agente") "Conversazione agente via $source." else "Conversazione chat via $source.",
                prompt = prompt,
                updatedAt = now,
                messages = messages,
                previousResponseId = responseId,
                serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, newConversationId),
                hermesSessionId = hermesSessionId,
                modelOverride = modelOverride.orEmpty(),
                providerOverride = providerOverride.orEmpty(),
                reasoningEffort = reasoningEffort.orEmpty(),
                projectId = projectId.orEmpty(),
                botIdentityKey = botIdentityKey.trim()
            )
        }

        if (index >= 0) {
            conversations[index] = conversation
        } else {
            conversations.add(0, conversation)
        }
        materializeArtifacts(conversations, conversation)
        saveConversations(context, conversations, syncAfterSave = syncAfterSave)
        return conversation
    }
}

internal fun materializeArtifacts(items: MutableList<LocalConversation>, conversation: LocalConversation) {
    if (conversation.kind == "Artifact") return
    val types = setOf("media_file", "image_gallery", "code", "diagram", "markdown", "table", "chart")
    conversation.messages.forEach { message ->
        message.visualBlocks.filter { it.type in types }.forEach { block ->
            val blockKey = block.id.ifBlank { block.rawJson.hashCode().toUInt().toString(16) }
            val id = "artifact_${conversation.id}_${message.id}_$blockKey"
            if (items.any { it.id == id }) return@forEach
            val title = block.title.ifBlank { block.filename.ifBlank { "${block.type} · ${conversation.title}" } }
            val grouping = block.filename.ifBlank { title }
            val version = items.count { it.kind == "Artifact" && it.projectId == conversation.projectId && (it.artifactFileName.equals(grouping, true) || it.title.equals(grouping, true)) } + 1
            items.add(0, LocalConversation(
                id = id,
                title = title,
                kind = "Artifact",
                description = block.caption.ifBlank { block.summary },
                prompt = "",
                updatedAt = conversation.updatedAt,
                messages = emptyList(),
                projectId = conversation.projectId,
                artifactType = block.type,
                artifactUrl = block.mediaUrl.ifBlank { block.renderedMediaUrl },
                artifactFileName = block.filename,
                artifactMimeType = block.mimeType,
                sourceConversationId = conversation.id,
                sourceRunId = "",
                version = version
            ))
        }
    }
}

internal fun saveProjectConversation(
    context: Context,
    title: String,
    description: String,
    prompt: String
): LocalConversation {
    synchronized(localArchiveLock) {
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        val now = System.currentTimeMillis()
        val index = conversations.indexOfFirst { it.deletedAt == null && it.kind == "Progetto" && it.title.equals(title, ignoreCase = true) }
        val project = if (index >= 0) {
            conversations[index].copy(description = description, prompt = prompt, projectId = conversations[index].id, updatedAt = now)
        } else {
            LocalConversation(
                id = "project_$now",
                title = title,
                kind = "Progetto",
                description = description,
                prompt = prompt,
                updatedAt = now,
                messages = emptyList(),
                projectId = "project_$now"
            )
        }

        if (index >= 0) {
            conversations[index] = project
        } else {
            conversations.add(0, project)
        }
        saveConversations(context, conversations)
        return project
    }
}

internal fun saveProjectWorkspace(
    context: Context,
    projectId: String?,
    title: String,
    description: String,
    workspacePath: String,
    repositoryUrl: String,
    instructions: String,
    memory: String,
    authorizedTools: List<String>
): LocalConversation {
    synchronized(localArchiveLock) {
        val normalizedTitle = title.trim().take(180)
        require(normalizedTitle.isNotBlank()) { "Nome progetto obbligatorio." }
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        var index = projectId?.let { id -> conversations.indexOfFirst { it.deletedAt == null && it.kind == "Progetto" && it.id == id } } ?: -1
        if (index < 0) {
            index = conversations.indexOfFirst { it.deletedAt == null && it.kind == "Progetto" && it.title.equals(normalizedTitle, ignoreCase = true) }
        }
        val id = if (index >= 0) conversations[index].id else "project_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
        val existing = if (index >= 0) conversations[index] else null
        val project = LocalConversation(
            id = id,
            title = normalizedTitle,
            kind = "Progetto",
            description = description.trim().take(4_000),
            prompt = instructions.trim().take(20_000),
            updatedAt = System.currentTimeMillis(),
            messages = existing?.messages ?: emptyList(),
            projectId = id,
            workspacePath = workspacePath.trim().take(1_024),
            repositoryUrl = repositoryUrl.trim().take(2_048),
            projectInstructions = instructions.trim().take(20_000),
            projectMemory = memory.trim().take(20_000),
            authorizedTools = authorizedTools.map { it.trim().take(100) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(100)
        )
        if (index >= 0) conversations[index] = project else conversations.add(0, project)
        saveConversations(context, conversations)
        return project
    }
}

internal fun renameConversation(context: Context, id: String, newTitle: String): Boolean {
    if (newTitle.isBlank()) return false

    synchronized(localArchiveLock) {
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        val index = conversations.indexOfFirst { it.id == id && it.deletedAt == null }
        if (index < 0) return false

        conversations[index] = conversations[index].copy(title = newTitle, updatedAt = System.currentTimeMillis())
        saveConversations(context, conversations)
        return true
    }
}

internal fun deleteConversation(context: Context, id: String): Boolean {
    synchronized(localArchiveLock) {
        val conversations = loadConversations(context, includeDeleted = true).toMutableList()
        val index = conversations.indexOfFirst { it.id == id && it.deletedAt == null }
        if (index < 0) return false
        val now = System.currentTimeMillis()
        conversations[index] = conversations[index].copy(
            title = "Chat eliminata",
            kind = "Deleted",
            description = "",
            prompt = "",
            updatedAt = now,
            messages = emptyList(),
            previousResponseId = null,
            serverConversationId = null,
            deletedAt = now
        )
        saveConversations(context, conversations)
        return true
    }
}

