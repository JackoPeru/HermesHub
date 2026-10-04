package com.nemoclaw.chat

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val CHAT_DRAFT_SAVED_KEY = "chat_viewmodel.draft"
internal const val CHAT_ACTIVE_CONVERSATION_SAVED_KEY = "chat_viewmodel.active_conversation_id"
internal const val CHAT_SAVED_STATE_DEBOUNCE_MS = 500L

/**
 * Ripristina draft + activeConversationId dal SavedStateHandle SOLO dove
 * l'holder e' vuoto (draft vuoto, conversation null): mai sovrascrivere stato vivo.
 * Funzione pura/testabile, chiamata dal ViewModel al boot.
 */
internal fun restoreChatStateIfEmpty(holder: ChatStateHolder, savedState: SavedStateHandle) {
    if (holder.draft.isBlank()) {
        val saved = savedState.get<String>(CHAT_DRAFT_SAVED_KEY).orEmpty()
        if (saved.isNotBlank()) holder.draft = saved
    }
    if (holder.activeConversationId == null) {
        val saved = savedState.get<String>(CHAT_ACTIVE_CONVERSATION_SAVED_KEY).orEmpty()
        if (saved.isNotBlank()) holder.activeConversationId = saved
    }
}

/** Scrittura sincrona holder -> SavedStateHandle (il flusso osservato la chiama dopo debounce). */
internal fun saveChatSnapshotToHandle(holder: ChatStateHolder, savedState: SavedStateHandle) {
    savedState[CHAT_DRAFT_SAVED_KEY] = holder.draft
    savedState[CHAT_ACTIVE_CONVERSATION_SAVED_KEY] = holder.activeConversationId.orEmpty()
}

/**
 * Lo stato chat sopravvive alla rotazione (il ViewModel e retained,
 * il remember no). Niente logica qui dentro: solo ownership del holder.
 * Process death resta coperto dagli snapshot su disco come prima.
 *
 * Il SavedStateHandle e' iniettato dalla SavedStateViewModelFactory di default
 * (AppRoot usa viewModel() senza factory: ComponentActivity lo fornisce da solo,
 * il costruttore (SavedStateHandle) e' supportato senza modifiche al call-site).
 * Draft + activeConversationId vengono persistiti in tempo reale con debounce
 * 500ms e ripristinati al boot solo se l'holder e' vuoto.
 */
internal class ChatViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    val chatState = ChatStateHolder()

    // Scope dedicato su Default (non viewModelScope/Main): resta testabile negli unit-test JVM.
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        restoreChatStateIfEmpty(chatState, savedState)
        persistScope.launch {
            snapshotFlow { chatState.draft to chatState.activeConversationId }
                .debounce(CHAT_SAVED_STATE_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { saveChatSnapshotToHandle(chatState, savedState) }
        }
    }

    override fun onCleared() {
        persistScope.cancel()
        super.onCleared()
    }
}
