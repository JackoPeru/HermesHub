package com.nemoclaw.chat

import com.nemoclaw.chat.features.bots.canonicalPreviewOf
import com.nemoclaw.chat.features.bots.pickCanonicalRow
import com.nemoclaw.chat.features.bots.relativeTimeLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalBotChatTest {
    private fun session(id: String, title: String, extra: Map<String, Any?> = emptyMap()): HermesSession {
        val raw = JSONObject()
        extra.forEach { (k, v) -> raw.put(k, v) }
        return HermesSession(id = id, title = title, raw = raw)
    }

    @Test
    fun exactTitleMatchWins() {
        val rows = listOf(
            session("s1", "Lavoro"),
            session("s2", "Bot Chat"),
            session("s3", "bot chat")
        )
        assertEquals("s2", pickCanonicalRow(rows)!!.id)
    }

    @Test
    fun rootTitleWinsOverTitle() {
        val rows = listOf(
            session("s1", "Altro", mapOf("root_title" to "Bot Chat")),
            session("s2", "Bot Chat")
        )
        assertEquals("s1", pickCanonicalRow(rows)!!.id)
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(pickCanonicalRow(listOf(session("s1", "Lavoro"))))
        assertNull(pickCanonicalRow(emptyList()))
    }

    @Test
    fun previewExtractsCounts() {
        val row = session("s9", "Bot Chat", mapOf("live_message_count" to 12, "preview" to "ciao", "last_active" to 1791226226.0))
        val preview = canonicalPreviewOf(row)
        assertEquals("s9", preview.sessionId)
        assertEquals(12, preview.messageCount)
        assertEquals("ciao", preview.preview)
        assertEquals(1791226226000L, preview.lastActiveMs)
    }

    @Test
    fun previewFallsBackToMessageCount() {
        val row = session("s9", "Bot Chat", mapOf("message_count" to 7))
        assertEquals(7, canonicalPreviewOf(row).messageCount)
    }

    @Test
    fun relativeTimeLabels() {
        val now = 1_791_226_226_000L
        assertEquals("ora", relativeTimeLabel(now, now - 30_000))
        assertEquals("5m", relativeTimeLabel(now, now - 5 * 60_000))
        assertEquals("3h", relativeTimeLabel(now, now - 3 * 3_600_000))
        assertEquals("2g", relativeTimeLabel(now, now - 2 * 86_400_000))
        assertEquals("", relativeTimeLabel(now, 0))
    }

    private fun rowMsg(role: String, content: String, extra: Map<String, Any?> = emptyMap()): HermesSessionMessage {
        val raw = JSONObject()
        extra.forEach { (k, v) -> raw.put(k, v) }
        return HermesSessionMessage(role = role, content = content, raw = raw)
    }

    @Test
    fun transcriptMapping() {
        val user = rowMsg("user", "ciao").toBotChatMessages()
        assertEquals(1, user.size)
        assertEquals("Tu", user[0].author)
        assertTrue(user[0].fromUser)

        val assistant = rowMsg("assistant", "ecco", mapOf("reasoning_content" to "penso")).toBotChatMessages()
        assertEquals(1, assistant.size)
        assertEquals("Hermes", assistant[0].author)
        assertEquals("penso", assistant[0].thinking)

        // Assistant con testo + tool: due righe (testo + azione).
        val toolRow = JSONObject().put("name", "exec")
        val both = rowMsg("assistant", "fatto", mapOf("tool_calls" to org.json.JSONArray().put(toolRow))).toBotChatMessages()
        assertEquals(2, both.size)
        assertTrue(both[1].isAction)
        assertTrue(both[1].text.contains("exec"))

        val toolOnly = rowMsg("assistant", "", mapOf("tool_calls" to org.json.JSONArray().put(toolRow))).toBotChatMessages()
        assertEquals(1, toolOnly.size)
        assertTrue(toolOnly[0].isAction)

        val tool = rowMsg("tool", "output", mapOf("tool_name" to "exec")).toBotChatMessages()
        assertEquals(1, tool.size)
        assertTrue(tool[0].isAction)
        assertTrue(tool[0].text.contains("output"))

        // Troncamento lungo marcato.
        val long = rowMsg("tool", "x".repeat(400), mapOf("tool_name" to "exec")).toBotChatMessages()
        assertEquals(1, long.size)
        assertTrue(long[0].text.contains("…"))

        // Turno fallito marcato.
        val failed = rowMsg("assistant", "boom", mapOf("display_kind" to "failed_turn")).toBotChatMessages()
        assertEquals(1, failed.size)
        assertTrue(failed[0].text.startsWith("(fallito) "))

        assertTrue(rowMsg("system", "x").toBotChatMessages().isEmpty())
        assertTrue(rowMsg("assistant", "   ").toBotChatMessages().isEmpty())
        assertTrue(rowMsg("unknown", "").toBotChatMessages().isEmpty())
        // Compattazione con testo: azione visibile, non buco.
        assertEquals(1, rowMsg("summary", "riepilogo", emptyMap()).toBotChatMessages().size)
    }
}
