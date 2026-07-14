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
    fun finalSnapshotReplacesOnlyItsKnownPrefix() {
        assertEquals("hello world", mergeTextSnapshot("hello", "hello world"))
        assertEquals("hello world", mergeTextSnapshot("hello world", "hello"))
        assertEquals("abcxyz", mergeTextSnapshot("abc", "xyz"))
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
        assertEquals(listOf<String?>("secret", HERMES_FALLBACK_API_KEY, null), hermesAuthCandidates("secret", allowCompatAuth = true))
    }

    @Test
    fun gatewayOriginUsesTheSuccessfulCandidate() {
        assertEquals("http://100.94.223.14:8642", gatewayOrigin("http://100.94.223.14:8642/v1/media/upload"))
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
    fun ttsFallbacksPreserveTheDocumentedGatewayOrder() {
        assertEquals(
            listOf(
                "http://custom-gateway:8642/v1/audio/speech",
                "http://hermes:8642/v1/audio/speech",
                "http://100.94.223.14:8642/v1/audio/speech",
                "http://hermes.local:8642/v1/audio/speech"
            ),
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
}
