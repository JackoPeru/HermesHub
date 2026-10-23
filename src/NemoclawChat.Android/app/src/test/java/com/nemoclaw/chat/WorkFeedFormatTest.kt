package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkFeedFormatTest {

    @Test
    fun compactTokensFormatsItalianSuffixes() {
        assertEquals("0", formatCompactTokens(0))
        assertEquals("999", formatCompactTokens(999))
        assertEquals("1k", formatCompactTokens(1000))
        assertEquals("12,3k", formatCompactTokens(12345))
        assertEquals("262k", formatCompactTokens(262144))
        assertEquals("1,5M", formatCompactTokens(1_500_000))
    }

    @Test
    fun prefillLineFullFormat() {
        assertEquals(
            "Prefill · 12,3k/262k tok · 654 tk/s · 45%",
            formatPrefillLine(12345, null, 262144, 654.3, 45, estimated = false)
        )
    }

    @Test
    fun prefillLineFallsBackToTotalWithoutMax() {
        assertEquals(
            "Prefill · 25/100 tok · 12%",
            formatPrefillLine(25, 100, null, null, 12, estimated = false)
        )
    }

    @Test
    fun prefillLineEstimatedMarksAndOmitsPercent() {
        assertEquals(
            "~Prefill · 12,3k tok",
            formatPrefillLine(12345, null, null, null, 0, estimated = true)
        )
    }

    @Test
    fun prefillLineBare() {
        assertEquals("Prefill", formatPrefillLine(null, null, null, null, null, estimated = false))
        assertEquals("~Prefill", formatPrefillLine(null, null, null, null, null, estimated = true))
    }

    @Test
    fun workToolTitleUsesItalianVerbsWithDetail() {
        assertEquals(
            "Eseguito: gh repo view Marmeria-Peru",
            workToolTitle("terminal", """{"command":"gh repo view Marmeria-Peru"}""")
        )
        assertEquals("Letto: CookieBanner.tsx", workToolTitle("read", """{"path":"CookieBanner.tsx"}"""))
        assertEquals("Esplorato: src/components", workToolTitle("glob", """{"path":"src/components"}"""))
        assertEquals("Modificato: audit4.mjs", workToolTitle("edit", """{"file":"audit4.mjs"}"""))
        assertEquals("Cercato: prefill regression", workToolTitle("search", """{"query":"prefill regression"}"""))
    }
    @Test
    fun workToolTitleNeverInventsCountsAndFallsBackToName() {
        assertEquals("Eseguito", workToolTitle("terminal", ""))
        assertEquals("mio-tool-strano", workToolTitle("mio-tool-strano", ""))
        assertTrue(workToolTitle("bash", "find . -name vercel.json").startsWith("Eseguito:"))
    }

    @Test
    fun workToolTitleNeverShowsGenericTool() {
        assertEquals("Attività", workToolTitle("tool", ""))
        assertEquals("Attività", workToolTitle("call_abc123", ""))
        assertEquals("Attività", workToolTitle("", ""))
        // Con dettaglio reale il generico si qualifica comunque.
        assertTrue(workToolTitle("tool", """{"command":"ls /tmp"}""").startsWith("Eseguito:"))
    }

    @Test
    fun toolDurationFormatsLikeDesktop() {
        assertEquals("", formatToolDuration(0))
        assertEquals("", formatToolDuration(-5))
        assertEquals("608ms", formatToolDuration(608))
        assertEquals("145ms", formatToolDuration(145))
        assertEquals("6,2s", formatToolDuration(6200))
        assertEquals("13s", formatToolDuration(13000))
        assertEquals("25s", formatToolDuration(25000))
    }

    @Test
    fun toolDurationNeedsBothEnds() {
        assertEquals(0L, toolDurationMs(ToolCallState("a", "bash")))
        assertEquals("", toolDurationLabel(ToolCallState("a", "bash")))
        val done = ToolCallState("a", "bash", startedAtMs = 1000L, endedAtMs = 26000L)
        assertEquals(25000L, toolDurationMs(done))
        assertEquals("25s", toolDurationLabel(done))
    }

    @Test
    fun toolStartAndEndStampDurations() {
        var state = StreamingState()
        state = state.applyEvent(ChatStreamEvent.ToolCallStart("t1", "bash"))
        val running = state.toolCalls.single()
        assertTrue(running.startedAtMs > 0)
        assertEquals(0L, running.endedAtMs)
        state = state.applyEvent(ChatStreamEvent.ToolCallEnd("t1"))
        val finished = state.toolCalls.single()
        assertTrue(finished.endedAtMs >= finished.startedAtMs)
        assertTrue(finished.startedAtMs > 0)
    }

    @Test
    fun toolsGroupTitleCounts() {
        assertEquals("1 attività", toolsGroupTitle(1))
        assertEquals("4 attività", toolsGroupTitle(4))
    }
}
