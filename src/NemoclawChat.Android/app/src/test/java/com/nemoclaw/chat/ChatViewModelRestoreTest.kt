package com.nemoclaw.chat

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelRestoreTest {

    @Test
    fun `restore riempie holder vuoto dal viewmodel`() {
        val handle = SavedStateHandle()
        handle[CHAT_DRAFT_SAVED_KEY] = "bozza salvata"
        handle[CHAT_ACTIVE_CONVERSATION_SAVED_KEY] = "conv-123"
        val vm = ChatViewModel(handle)
        assertEquals("bozza salvata", vm.chatState.draft)
        assertEquals("conv-123", vm.chatState.activeConversationId)
    }

    @Test
    fun `restore con handle vuoto lascia default`() {
        val vm = ChatViewModel(SavedStateHandle())
        assertEquals("", vm.chatState.draft)
        assertNull(vm.chatState.activeConversationId)
    }

    @Test
    fun `restore non sovrascrive stato vivo`() {
        val handle = SavedStateHandle()
        handle[CHAT_DRAFT_SAVED_KEY] = "bozza vecchia"
        handle[CHAT_ACTIVE_CONVERSATION_SAVED_KEY] = "conv-vecchia"
        val holder = ChatStateHolder().apply {
            draft = "bozza viva"
            activeConversationId = "conv-viva"
        }
        restoreChatStateIfEmpty(holder, handle)
        assertEquals("bozza viva", holder.draft)
        assertEquals("conv-viva", holder.activeConversationId)
    }

    @Test
    fun `restore parziale riempie solo campi vuoti`() {
        val handle = SavedStateHandle()
        handle[CHAT_DRAFT_SAVED_KEY] = "bozza vecchia"
        handle[CHAT_ACTIVE_CONVERSATION_SAVED_KEY] = "conv-vecchia"
        val holder = ChatStateHolder().apply { draft = "bozza viva" }
        restoreChatStateIfEmpty(holder, handle)
        assertEquals("bozza viva", holder.draft)
        assertEquals("conv-vecchia", holder.activeConversationId)
    }

    @Test
    fun `save helper scrive entrambi i campi`() {
        val handle = SavedStateHandle()
        val holder = ChatStateHolder().apply {
            draft = "ciao"
            activeConversationId = "conv-9"
        }
        saveChatSnapshotToHandle(holder, handle)
        assertEquals("ciao", handle.get<String>(CHAT_DRAFT_SAVED_KEY))
        assertEquals("conv-9", handle.get<String>(CHAT_ACTIVE_CONVERSATION_SAVED_KEY))
    }

    @Test
    fun `modifiche holder propagate dopo debounce`() {
        val handle = SavedStateHandle()
        val vm = ChatViewModel(handle)
        vm.chatState.draft = "bozza-x"
        vm.chatState.activeConversationId = "conv-9"
        val deadline = System.currentTimeMillis() + 8000
        var ok = false
        while (System.currentTimeMillis() < deadline) {
            if (handle.get<String>(CHAT_DRAFT_SAVED_KEY) == "bozza-x" &&
                handle.get<String>(CHAT_ACTIVE_CONVERSATION_SAVED_KEY) == "conv-9"
            ) {
                ok = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue("draft e conversation devono propagarsi nello SavedStateHandle dopo debounce", ok)
    }
}
