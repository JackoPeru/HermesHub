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
    fun transcriptFoldsLikeNormalChat() {
        val toolRow = JSONObject().put("name", "exec").put("tool_call_id", "c1")
        val calls = org.json.JSONArray().put(toolRow)
        val rows = listOf(
            rowMsg("user", "ciao"),
            rowMsg("assistant", "ecco", mapOf("reasoning_content" to "penso")),
            rowMsg("assistant", "", mapOf("tool_calls" to calls)),
            rowMsg("tool", "<untrusted>muro di testo che non deve mai essere retained</untrusted>", mapOf("tool_call_id" to "c1")),
            rowMsg("assistant", "fatto")
        )
        val folded = foldTranscriptToChat(rows)
        // Tu + "ecco" (col tool piegato dentro) + "fatto"; il muro tool
        // non e mai retained come testo.
        assertEquals(3, folded.size)
        assertEquals("Tu", folded[0].author)
        assertEquals("Hermes", folded[1].author)
        assertEquals("ecco", folded[1].text)
        val tools = folded[1].activityTimeline.filter { it.kind == AssistantActivity.Kind.Tool }
        assertEquals(1, tools.size)
        assertEquals("exec", tools[0].tool?.name)
        assertTrue(folded.none { it.text.contains("untrusted") })
        assertEquals("fatto", folded[2].text)
    }

    @Test
    fun transcriptMarksFailedTools() {
        val toolRow = JSONObject().put("name", "exec").put("tool_call_id", "c9")
        val rows = listOf(
            rowMsg("assistant", "", mapOf("tool_calls" to org.json.JSONArray().put(toolRow))),
            rowMsg("tool", "err", mapOf("tool_call_id" to "c9", "display_kind" to "failed_turn"))
        )
        val folded = foldTranscriptToChat(rows)
        assertEquals(1, folded.size)
        assertEquals("", folded[0].text)
        val tools = folded[0].activityTimeline.filter { it.kind == AssistantActivity.Kind.Tool }
        assertEquals(1, tools.size)
        assertEquals("fallito", tools[0].tool?.status)
    }

    @Test
    fun transcriptSkipsNoise() {        val rows = listOf(
            rowMsg("system", "x"),
            rowMsg("assistant", "   "),
            rowMsg("unknown", ""),
            rowMsg("summary", "riepilogo", emptyMap()),
            rowMsg("user", "")
        )
        assertTrue(foldTranscriptToChat(rows).isEmpty())
    }

    @Test
    fun botLiveStatusLabels() {
        val toolRow = JSONObject().put("name", "exec")
        assertEquals(
            "Sta usando exec…",
            botLiveStatusFor(rowMsg("assistant", "", mapOf("tool_calls" to org.json.JSONArray().put(toolRow))))
        )
        assertEquals(
            "Sta ragionando…",
            botLiveStatusFor(rowMsg("assistant", "", mapOf("reasoning_content" to "penso")))
        )
        assertEquals("Sta scrivendo…", botLiveStatusFor(rowMsg("assistant", "ciao")))
        assertEquals("Nuovo messaggio…", botLiveStatusFor(rowMsg("user", "ciao")))
        assertEquals("Sta lavorando…", botLiveStatusFor(rowMsg("system", "x")))
    }
}
