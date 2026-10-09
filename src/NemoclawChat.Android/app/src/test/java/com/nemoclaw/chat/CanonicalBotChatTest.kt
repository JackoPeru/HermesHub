package com.nemoclaw.chat

import com.nemoclaw.chat.features.bots.canonicalPreviewOf
import com.nemoclaw.chat.features.bots.CanonicalBotOpenLocks
import com.nemoclaw.chat.features.bots.isCanonicalBotRow
import com.nemoclaw.chat.features.bots.pickCanonicalRow
import com.nemoclaw.chat.features.bots.relativeTimeLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

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
    fun transcriptFoldsMediaBlocks() {
        val block = JSONObject()
            .put("id", "m1")
            .put("type", "media_file")
            .put("mediaKind", "image")
            .put("media_kind", "image")
            .put("mediaUrl", "/v1/media/abc")
            .put("media_url", "/v1/media/abc")
            .put("alt", "foto")
        val blocks = org.json.JSONArray().put(block)
        val rows = listOf(
            rowMsg("assistant", "ecco la foto", mapOf("visual_blocks" to blocks))
        )
        val folded = foldTranscriptToChat(rows)
        assertEquals(1, folded.size)
        assertEquals("ecco la foto", folded[0].text)
        assertTrue(folded[0].visualBlocks.isNotEmpty())
        assertEquals("/v1/media/abc", folded[0].visualBlocks[0].mediaUrl)
    }

    @Test
    fun transcriptKeepsUserMediaWithoutCaption() {
        val block = JSONObject()
            .put("id", "u1")
            .put("type", "media_file")
            .put("media_kind", "image")
            .put("media_url", "/v1/media/xyz")
            .put("alt", "foto")
        val blocks = org.json.JSONArray().put(block)
        val folded = foldTranscriptToChat(
            listOf(rowMsg("user", "", mapOf("visual_blocks" to blocks)))
        )
        assertEquals(1, folded.size)
        assertTrue(folded[0].fromUser)
        assertTrue(folded[0].visualBlocks.isNotEmpty())
        assertEquals("/v1/media/xyz", folded[0].visualBlocks[0].mediaUrl)
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

    private fun sessionFull(
        id: String,
        title: String,
        createdAt: String = "",
        lastActive: Double = 0.0,
        rootTitle: String? = null
    ): HermesSession {
        val raw = JSONObject()
        if (lastActive != 0.0) raw.put("last_active", lastActive)
        if (rootTitle != null) raw.put("root_title", rootTitle)
        return HermesSession(id = id, title = title, createdAt = createdAt, raw = raw)
    }

    @Test
    fun pickCaseInsensitiveTrimmed() {
        val rows = listOf(
            session("s1", "Lavoro"),
            session("s2", "  BOT CHAT  "),
            session("s3", "bot chat")
        )
        val picked = pickCanonicalRow(rows)!!
        assertTrue(isCanonicalBotRow(picked))
        // Deterministico: a parita di created_at/last_active vince id minore.
        assertEquals("s2", picked.id)
    }

    @Test
    fun pickDeterministicoDuplicati() {
        val a = sessionFull("b", "Bot Chat", createdAt = "2026-02-02T00:00:00Z", lastActive = 200.0)
        val b = sessionFull("a", "bot chat", createdAt = "2026-01-01T00:00:00Z", lastActive = 100.0)
        val c = sessionFull("c", "  BOT CHAT ", createdAt = "2026-01-01T00:00:00Z", lastActive = 100.0)
        // Ordine d'ingresso diverso, risultato identico (ordinato per created_at/last_active/id).
        assertEquals("a", pickCanonicalRow(listOf(a, b, c))!!.id)
        assertEquals("a", pickCanonicalRow(listOf(c, a, b))!!.id)
        assertEquals("a", pickCanonicalRow(listOf(b, c, a))!!.id)
    }

    @Test
    fun sortMistoTimestampCreatedAtId() {
        val r1 = rowMsg("user", "t3", mapOf("timestamp" to 3.0, "created_at" to "2026-01-03", "id" to 30))
        val r2 = rowMsg("user", "t1", mapOf("timestamp" to 1.0, "created_at" to "2026-01-01", "id" to 10))
        val r3 = rowMsg("user", "t2a", mapOf("timestamp" to 2.0, "created_at" to "2026-01-02A", "id" to 21))
        val r4 = rowMsg("user", "t2b", mapOf("timestamp" to 2.0, "created_at" to "2026-01-02A", "id" to 20))
        val r5 = rowMsg("user", "t2c", mapOf("timestamp" to 2.0, "created_at" to "2026-01-02B", "id" to 1))
        val sorted = sortTranscriptRows(listOf(r1, r2, r3, r4, r5))
        // timestamp asc, poi created_at asc, poi id asc.
        assertEquals(listOf("t1", "t2b", "t2a", "t2c", "t3"), sorted.map { it.content })
    }

    @Test
    fun sortFallbackSoloSeTuttiTimestampZero() {
        val r1 = rowMsg("user", "primo", mapOf("created_at" to "2026-01-01"))
        val r2 = rowMsg("user", "secondo", mapOf("created_at" to "2026-01-02"))
        // Tutti timestamp == 0 -> asReversed (ordine API invertito).
        assertEquals(listOf("secondo", "primo"), sortTranscriptRows(listOf(r1, r2)).map { it.content })
        // Un solo timestamp > 0 -> sort stabile, non fallback.
        val r3 = rowMsg("user", "conTs", mapOf("timestamp" to 5.0, "created_at" to "2026-01-01"))
        val mixed = sortTranscriptRows(listOf(r2, r3, r1))
        assertEquals("conTs", mixed.last().content)
    }

    @Test
    fun idAssenteUsaCreatedAtHash() {
        val rawNoId = JSONObject().put("created_at", "2026-01-01T00:00:00Z").put("role", "user").put("content", "ciao")
        val idA = stableMessageId(rawNoId, 0)
        val idB = stableMessageId(rawNoId, 7)
        // Stesso created_at+contenuto -> stesso id stabile anche con fallbackIndex diverso.
        assertEquals(idA, idB)
        assertTrue(idA != 0)
        val rawOther = JSONObject().put("created_at", "2026-01-02T00:00:00Z").put("role", "user").put("content", "ciao")
        assertNotEquals(idA, stableMessageId(rawOther, 0))
        // id numerico diretto vince.
        assertEquals(42, stableMessageId(JSONObject().put("id", 42), 0))
        assertEquals(7, stableMessageId(JSONObject().put("message_id", "7"), 0))
        assertEquals(9, stableMessageId(JSONObject().put("seq", 9), 3))
        // Senza id e senza created_at -> fallback deterministico da indice.
        assertEquals(1_000_000, stableMessageId(JSONObject(), 0))
        assertEquals(1_000_005, stableMessageId(JSONObject(), 5))
        assertEquals(1_000_005, stableMessageId(null, 5))
    }

    @Test
    fun previewOfInvariato() {
        val row = session("s9", "Bot Chat", mapOf("live_message_count" to 12, "preview" to "ciao", "last_active" to 1791226226.0))
        val preview = canonicalPreviewOf(row)
        assertEquals("s9", preview.sessionId)
        assertEquals(12, preview.messageCount)
        assertEquals("ciao", preview.preview)
        assertEquals(1791226226000L, preview.lastActiveMs)
        // Vuoto: zero messaggi, preview vuota, lastActive 0.
        val empty = canonicalPreviewOf(session("e1", "Bot Chat"))
        assertEquals("e1", empty.sessionId)
        assertEquals(0, empty.messageCount)
        assertEquals("", empty.preview)
        assertEquals(0L, empty.lastActiveMs)
    }

    @Test
    fun botLockSerializzaStessaChiave() = runBlocking {
        val counter = AtomicInteger(0)
        val dentro = AtomicInteger(0)
        val maxDentro = AtomicInteger(0)
        (1..20).map {
            async {
                CanonicalBotOpenLocks.withBotLock("test-serial") {
                    val n = dentro.incrementAndGet()
                    maxDentro.getAndUpdate { prev -> maxOf(prev, n) }
                    kotlinx.coroutines.delay(1)
                    dentro.decrementAndGet()
                    counter.incrementAndGet()
                }
            }
        }.awaitAll()
        // Tutti passano una volta sola e mai in due dentro insieme.
        assertEquals(20, counter.get())
        assertEquals(1, maxDentro.get())
    }

    @Test
    fun botLockPotaturaLimitaMappa() = runBlocking {
        for (i in 1..120) {
            CanonicalBotOpenLocks.withBotLock("test-potatura-$i") { }
        }
        // La mappa non cresce all'infinito: resta sotto il tetto.
        assertTrue(CanonicalBotOpenLocks.lockCountForTest() <= CanonicalBotOpenLocks.MAX_LOCKS)
    }

    @Test
    fun ecoTurnoLocaleNonELavoroEsterno() {
        val now = 1_800_000_000_000L
        val turnEnd = now - 10_000L
        // Riga nostra appena arrivata: fresca ma non esterna.
        assertFalse(isExternalBotWork(now - 5_000L, now, turnEnd))
        // Riga esterna arrivata dopo con margine: lavoro vero.
        assertTrue(isExternalBotWork(turnEnd + 60_000L, turnEnd + 70_000L, turnEnd))
        // Stantia: mai lavoro.
        assertFalse(isExternalBotWork(now - 300_000L, now, turnEnd))
        assertFalse(isExternalBotWork(0L, now, turnEnd))
        // Senza turno locale: comportamento di prima (solo freschezza).
        assertTrue(isExternalBotWork(now - 5_000L, now, 0L))
        assertFalse(isExternalBotWork(now - 300_000L, now, 0L))
    }

    @Test
    fun turnElapsedFormats() {
        assertEquals("0:00", formatTurnElapsed(0L))
        assertEquals("0:07", formatTurnElapsed(7_500L))
        assertEquals("1:05", formatTurnElapsed(65_000L))
        assertEquals("0:00", formatTurnElapsed(-1_000L))
    }

    @Test
    fun staleLabelHonest() {
        assertNull(staleTurnLabel(10_000L))
        assertNull(staleTurnLabel(44_999L))
        assertEquals("Ancora al lavoro · nessun segnale da 45s", staleTurnLabel(45_000L))
        assertEquals("Ancora al lavoro · nessun segnale da 89s", staleTurnLabel(89_000L))
        assertEquals("Ancora al lavoro · nessun segnale da 2 min", staleTurnLabel(150_000L))
    }

    @Test
    fun transportFailureClassified() {
        assertTrue(isTransportFailure(java.io.IOException("Connection reset by peer")))
        assertTrue(isTransportFailure(Exception("Read timed out")))
        assertTrue(isTransportFailure(Exception("software caused connection abort")))
        assertFalse(isTransportFailure(Exception("HTTP 401: chiave non valida")))
        assertFalse(isTransportFailure(Exception("Run failed: model error")))
    }

    @Test
    fun silentEndNeverSilent() {
        assertTrue(silentTurnEndMessage(true).contains("Connessione persa"))
        assertTrue(silentTurnEndMessage(false).contains("Riprova"))
    }
}
