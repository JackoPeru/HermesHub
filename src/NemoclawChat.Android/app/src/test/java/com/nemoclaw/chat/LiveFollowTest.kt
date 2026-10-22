package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Accumulo output del follow live: snapshot sostituiscono, delta fondono.
 * Puro, nessuna rete.
 */
class LiveFollowTest {
    @Test
    fun `snapshot wins over previous text`() {
        assertEquals(
            "finale",
            foldLiveFollowText("vecchio", listOf(ChatStreamEvent.TextSnapshot("finale")))
        )
    }

    @Test
    fun `last snapshot wins`() {
        assertEquals(
            "b",
            foldLiveFollowText("", listOf(ChatStreamEvent.TextSnapshot("a"), ChatStreamEvent.TextSnapshot("b")))
        )
    }

    @Test
    fun `deltas merge onto previous without snapshot`() {
        assertEquals(
            "ciao mondo",
            foldLiveFollowText("ciao ", listOf(ChatStreamEvent.TextDelta("mon"), ChatStreamEvent.TextDelta("do")))
        )
    }

    @Test
    fun `empty events keep previous`() {
        assertEquals("fermo", foldLiveFollowText("fermo", emptyList()))
        assertEquals("fermo", foldLiveFollowThinking("fermo", emptyList()))
    }

    @Test
    fun `thinking folds like text`() {
        assertEquals(
            "penso",
            foldLiveFollowThinking("", listOf(ChatStreamEvent.ThinkingSnapshot("penso")))
        )
        assertEquals(
            "ab",
            foldLiveFollowThinking("", listOf(ChatStreamEvent.ThinkingDelta("a"), ChatStreamEvent.ThinkingDelta("b")))
        )
    }
}
