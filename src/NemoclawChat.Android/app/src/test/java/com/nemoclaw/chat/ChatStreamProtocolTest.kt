package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamProtocolTest {
    @Test
    fun repeatedDeltasAreNeverDropped() {
        assertEquals("haha", mergeTextDelta("ha", "ha"))
        assertEquals("!!", mergeTextDelta("!", "!"))
    }

    @Test
    fun finalSnapshotIsAuthoritativeWithoutConcatenatingDivergentFormatting() {
        assertEquals("hello world", mergeTextSnapshot("hello", "hello world"))
        assertEquals("hello world", mergeTextSnapshot("hello world", "hello"))
        assertEquals("xyz", mergeTextSnapshot("abc", "xyz"))
        assertEquals("\n\nFinale Markdown", mergeTextSnapshot("Finale Markdown", "\n\nFinale Markdown"))
    }

    @Test
    fun terminalDetectionRequiresExplicitProtocolSignal() {
        assertTrue(isTerminalSseEvent(null, "[DONE]"))
        assertTrue(isTerminalSseEvent("response.completed", "{}"))
        assertTrue(isTerminalSseEvent(null, "{\"choices\":[{\"finish_reason\":\"stop\"}]}"))
        assertFalse(isTerminalSseEvent("response.output_text.delta", "{\"delta\":\"ciao\"}"))
    }

    @Test
    fun finalOutputTextIsParsedAsSnapshot() {
        val events = parseSseData(
            "response.output_text.done",
            "{\"type\":\"response.output_text.done\",\"text\":\"Risposta finale\"}"
        )
        assertTrue(events.any { it is ChatStreamEvent.TextSnapshot && it.text == "Risposta finale" })
    }

    @Test
    fun hermesReasoningIsParsedAsPersistentSnapshot() {
        val events = parseSseData(
            "hermes.reasoning.available",
            "{\"type\":\"hermes.reasoning.available\",\"reasoning\":\"Verifico i dati prima di rispondere.\"}"
        )
        assertTrue(events.any {
            it is ChatStreamEvent.ThinkingSnapshot && it.text == "Verifico i dati prima di rispondere."
        })
    }

    @Test
    fun analysisItemsGoToReasoningInsteadOfFinalAnswer() {
        val events = parseSseData(
            "response.output_item.done",
            """{"type":"response.output_item.done","item":{"type":"analysis","content":[{"type":"output_text","text":"Controllo i dati."}]}}"""
        )
        assertTrue(events.any { it is ChatStreamEvent.ThinkingSnapshot && it.text == "Controllo i dati." })
        assertFalse(events.any { it is ChatStreamEvent.TextSnapshot || it is ChatStreamEvent.TextDelta })
    }

    @Test
    fun thinkTagsInFinalSnapshotStayOutOfFinalAnswer() {
        val events = ThinkExtractor().processSnapshot("<think>Controllo i dati.</think>Risposta finale")
        assertTrue(events.any { it is ChatStreamEvent.ThinkingSnapshot && it.text == "Controllo i dati." })
        assertTrue(events.any { it is ChatStreamEvent.TextSnapshot && it.text == "Risposta finale" })
    }

    @Test
    fun realPromptProgressKeepsServerCountersWithoutDuplicateEvent() {
        val events = parseSseData(
            "hermes.processing.progress",
            """{"type":"hermes.processing.progress","estimated":false,"percent":25,"prompt_progress":{"processed":25,"total":100,"cache":5,"time_ms":1200}}"""
        )
        val progress = events.filterIsInstance<ChatStreamEvent.PromptProgress>()
        assertEquals(1, progress.size)
        assertEquals(25, progress.single().percent)
        assertEquals(25, progress.single().processedTokens)
        assertEquals(100, progress.single().totalTokens)
        assertEquals(5, progress.single().cachedTokens)
        assertEquals(1200.0, progress.single().timeMs ?: -1.0, 0.0)
        assertFalse(progress.single().estimated)
    }

    @Test
    fun estimatedPromptProgressIsNeverShown() {
        val events = parseSseData(
            "hermes.processing.progress",
            """{"type":"hermes.processing.progress","estimated":true,"percent":50}"""
        )
        assertTrue(events.none { it is ChatStreamEvent.PromptProgress })
    }

    @Test
    fun backendNameIsRemovedFromVisibleStatus() {
        assertEquals("Elaborazione prompt", friendlyActivityStatus("llama.cpp: prefill prompt"))
        assertEquals("Attesa primo token della risposta", friendlyActivityStatus("llama.cpp: attesa primo token"))
    }

    @Test
    fun retryIsAllowedOnlyBeforeAcceptanceForAuthFailures() {
        assertTrue(shouldRetrySseAuth(false, 401, "unauthorized", true))
        assertFalse(shouldRetrySseAuth(true, 401, "unauthorized", true))
        assertFalse(shouldRetrySseAuth(false, 500, "error", true))
        assertFalse(shouldRetrySseAuth(false, 401, "unauthorized", false))
    }

    @Test
    fun strictAuthUsesOnlyConfiguredCredential() {
        assertEquals(listOf<String?>("secret"), hermesAuthCandidates(" secret ", allowCompatAuth = false))
        assertEquals(listOf<String?>(null), hermesAuthCandidates(null, allowCompatAuth = false))
        assertEquals(listOf<String?>("secret", null), hermesAuthCandidates("secret", allowCompatAuth = true))
    }

    @Test
    fun gatewayOriginUsesTheSuccessfulCandidate() {
        assertEquals(
            "http://hermes.tailnet-example.ts.net:8642",
            gatewayOrigin("http://hermes.tailnet-example.ts.net:8642/v1/media/upload")
        )
    }

    @Test
    fun updateApkSizeCapHandlesKnownAndStreamingLengths() {
        assertFalse(isAdvertisedUpdateApkSizeRejected(-1L))
        assertFalse(isAdvertisedUpdateApkSizeRejected(MAX_UPDATE_APK_BYTES))
        assertTrue(isAdvertisedUpdateApkSizeRejected(MAX_UPDATE_APK_BYTES + 1L))
        assertFalse(wouldExceedUpdateApkSizeLimit(MAX_UPDATE_APK_BYTES - 1L, 1))
        assertTrue(wouldExceedUpdateApkSizeLimit(MAX_UPDATE_APK_BYTES, 1))
    }

    @Test
    fun ttsUsesOnlyTheConfiguredGateway() {
        assertEquals(
            listOf("http://custom-gateway:8642/v1/audio/speech"),
            ttsUrlCandidates("http://custom-gateway:8642/v1/audio/speech")
        )
    }

    @Test
    fun shortDeltaIsReconciledBeforeFinalSnapshotsWithoutDuplication() {
        val extractor = ThinkExtractor()
        val events = buildList {
            addAll(extractor.process("OK"))
            addAll(extractor.processSnapshot("OK")) // response.output_text.done
            addAll(extractor.processSnapshot("OK")) // response.output_item.done
            addAll(extractor.processSnapshot("OK")) // response.completed
            addAll(extractor.flush())
        }

        val rendered = events.fold("") { current, event ->
            when (event) {
                is ChatStreamEvent.TextDelta -> mergeTextDelta(current, event.delta)
                is ChatStreamEvent.TextSnapshot -> mergeTextSnapshot(current, event.text)
                else -> current
            }
        }

        assertEquals("OK", rendered)
        assertEquals(1, events.count { it is ChatStreamEvent.TextDelta })
        assertEquals(3, events.count { it is ChatStreamEvent.TextSnapshot })
    }

    @Test
    fun threeTerminalSnapshotsDoNotRepeatFinalAnswerAfterUiTrimming() {
        val final = "\n\nRisposta finale con tabella Markdown."
        var rendered = "Risposta finale con tabella Markdown."
        repeat(3) {
            rendered = mergeTextSnapshot(rendered, final).trim()
        }
        assertEquals("Risposta finale con tabella Markdown.", rendered)
    }

    @Test
    fun responsesTerminalEventSequenceRendersOneFinalAnswer() {
        val final = "\n\nRisposta finale con tabella Markdown."
        val payloads = listOf(
            "response.output_text.done" to
                "{\"type\":\"response.output_text.done\",\"text\":\"\\n\\nRisposta finale con tabella Markdown.\"}",
            "response.output_item.done" to
                "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"\\n\\nRisposta finale con tabella Markdown.\"}]}}",
            "response.completed" to
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_test\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"\\n\\nRisposta finale con tabella Markdown.\"}]}]}}"
        )
        var state = StreamingState(text = final.trim())
        payloads.forEach { (event, data) ->
            parseSseData(event, data).forEach { state = state.applyEvent(it) }
        }
        assertEquals(final.trim(), state.text)
    }
}
