package com.nemoclaw.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFollowUpTest {
    private fun msg(id: String, author: String, text: String, fromUser: Boolean = false) =
        ChatMessage(author, text, fromUser = fromUser, id = id)

    @Test
    fun `missing follow messages appends only new assistant rows`() {
        val local = listOf(
            msg("u1", "Tu", "vai", fromUser = true),
            msg("a1", "Hermes", "Inizio.")
        )
        val remote = listOf(
            msg("u1", "Tu", "vai", fromUser = true),
            msg("a1", "Hermes", "Inizio."),
            msg("srv_9", "Hermes", "Controllo i file."),
            msg("srv_10", "Hermes", "")
        )
        val missing = missingFollowMessages(local, remote)
        assertEquals(listOf("srv_9"), missing.map { it.id })
    }

    @Test
    fun `missing follow messages skips duplicates and user rows`() {
        val local = listOf(msg("srv_9", "Hermes", "Controllo i file."))
        val remote = listOf(
            msg("srv_9", "Hermes", "Controllo i file."),
            msg("srv_10", "Tu", "eco?", fromUser = true)
        )
        assertTrue(missingFollowMessages(local, remote).isEmpty())
        assertTrue(missingFollowMessages(local, emptyList()).isEmpty())
    }

    @Test
    fun `followState parses from hub payload`() {
        val convo = JSONObject()
            .put("id", "conv_1")
            .put("title", "Chat")
            .put("messages", JSONArray())
            .put("followState", JSONObject().put("active", true).put("lastMsgId", 42).put("checkedAt", 123456789))
        val parsed = readConversationsFromJsonArray(JSONArray().put(convo))
        assertEquals(1, parsed.size)
        val follow = parsed[0].followState
        assertTrue(follow?.active == true)
        assertEquals(42L, follow?.lastMsgId)
        assertEquals(123456789L, follow?.checkedAt)
    }

    @Test
    fun `followState absent stays null`() {
        val convo = JSONObject().put("id", "conv_2").put("messages", JSONArray())
        val parsed = readConversationsFromJsonArray(JSONArray().put(convo))
        assertEquals(1, parsed.size)
        assertNull(parsed[0].followState)
        assertFalse(parsed[0].hermesSessionId?.isNotBlank() == true)
    }
}
