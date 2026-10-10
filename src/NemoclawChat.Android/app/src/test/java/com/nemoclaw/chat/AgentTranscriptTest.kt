package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptTest {

    private fun empty() = StreamingState()

    @Test
    fun textToolTextThoughtBuildsInterleavedTranscript() {
        var state = empty()
            .applyEvent(ChatStreamEvent.TextDelta("Analizzo"))
            .applyEvent(ChatStreamEvent.TextDelta(" il file."))
            .applyEvent(ChatStreamEvent.ToolCallStart("c1", "read"))
            .applyEvent(ChatStreamEvent.ToolCallEnd("c1"))
            .applyEvent(ChatStreamEvent.TextDelta("Trovato."))
            .applyEvent(ChatStreamEvent.ThinkingDelta("penso"))
            .applyEvent(ChatStreamEvent.ThinkingDelta(" al dopo."))
            .applyEvent(ChatStreamEvent.TextDelta("Fine."))
        val blocks = state.transcript
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is TranscriptBlock.AgentText)
        assertEquals("Analizzo il file.", (blocks[0] as TranscriptBlock.AgentText).text)
        assertTrue(blocks[1] is TranscriptBlock.Tools)
        assertEquals("c1", (blocks[1] as TranscriptBlock.Tools).tools.single().id)
        assertEquals("Trovato.", (blocks[2] as TranscriptBlock.AgentText).text)
        assertTrue(blocks[3] is TranscriptBlock.Thought)
        assertEquals("penso al dopo.", (blocks[3] as TranscriptBlock.Thought).text)
        // Il testo finale resta integro, la coda non flusciata non duplica.
        assertEquals("Analizzo il file.Trovato.Fine.", state.text)
        assertEquals("Analizzo il file.Trovato.", state.flushedText)
    }

    @Test
    fun consecutiveToolsShareOneBurstBlock() {
        val state = empty()
            .applyEvent(ChatStreamEvent.ToolCallStart("c1", "read"))
            .applyEvent(ChatStreamEvent.ToolCallStart("c2", "bash"))
            .applyEvent(ChatStreamEvent.ToolCallEnd("c1"))
            .applyEvent(ChatStreamEvent.ToolCallEnd("c2"))
        val bursts = state.transcript.filterIsInstance<TranscriptBlock.Tools>()
        assertEquals(1, bursts.size)
        assertEquals(listOf("c1", "c2"), bursts.single().tools.map { it.id })
        // La timeline classica resta coerente.
        assertEquals(2, state.activityTimeline.count { it.kind == AssistantActivity.Kind.Tool })
    }

    @Test
    fun textBetweenToolsSplitsBursts() {
        val state = empty()
            .applyEvent(ChatStreamEvent.ToolCallStart("c1", "read"))
            .applyEvent(ChatStreamEvent.ToolCallEnd("c1"))
            .applyEvent(ChatStreamEvent.TextDelta("ok"))
            .applyEvent(ChatStreamEvent.ToolCallStart("c2", "bash"))
            .applyEvent(ChatStreamEvent.ToolCallEnd("c2"))
        val bursts = state.transcript.filterIsInstance<TranscriptBlock.Tools>()
        assertEquals(2, bursts.size)
        assertEquals("c1", bursts[0].tools.single().id)
        assertEquals("c2", bursts[1].tools.single().id)
    }

    @Test
    fun thoughtMergesDeltasAndTracksElapsed() {
        val state = empty()
            .applyEvent(ChatStreamEvent.ThinkingDelta("a"))
            .applyEvent(ChatStreamEvent.ThinkingDelta("b"))
        val thoughts = state.transcript.filterIsInstance<TranscriptBlock.Thought>()
        assertEquals(1, thoughts.size)
        assertEquals("ab", thoughts.single().text)
        assertTrue(thoughts.single().elapsedSec >= 0.0)
    }

    @Test
    fun divergentSnapshotAppendsCoherentSegment() {
        // Lo snapshot non cumulativo accoda (semantica mergeTextSnapshot):
        // il segmento registrato resta coerente col testo finale.
        val state = empty()
            .applyEvent(ChatStreamEvent.TextDelta("bozza..."))
            .applyEvent(ChatStreamEvent.ToolCallStart("c1", "read"))
            .applyEvent(ChatStreamEvent.ToolCallEnd("c1"))
            .applyEvent(ChatStreamEvent.TextSnapshot("RISPOSTA FINALE"))
        val texts = state.transcript.filterIsInstance<TranscriptBlock.AgentText>()
        assertEquals(2, texts.size)
        assertEquals("bozza...", texts[0].text)
        assertEquals("RISPOSTA FINALE", texts[1].text)
        assertEquals("bozza...RISPOSTA FINALE", state.text)
        assertEquals(state.text, state.flushedText)
    }

    @Test
    fun shorterSnapshotDoesNotCorruptSegments() {
        val state = empty()
            .applyEvent(ChatStreamEvent.TextDelta("RISPOSTA FINALE"))
            .applyEvent(ChatStreamEvent.ToolCallStart("c1", "read"))
            .applyEvent(ChatStreamEvent.ToolCallEnd("c1"))
            .applyEvent(ChatStreamEvent.TextSnapshot("RISPOSTA"))
        val texts = state.transcript.filterIsInstance<TranscriptBlock.AgentText>()
        assertEquals(1, texts.size)
        assertEquals("RISPOSTA FINALE", texts.single().text)
        assertEquals("RISPOSTA FINALE", state.flushedText)
    }

    @Test
    fun plainChatKeepsEmptyTranscript() {
        val state = empty()
            .applyEvent(ChatStreamEvent.TextDelta("ciao"))
            .applyEvent(ChatStreamEvent.Done(ChatStreamStats()))
        assertTrue(state.transcript.isEmpty())
        assertTrue(state.isDone)
    }

    @Test
    fun prefillKeepsOnlyLatest() {
        val progress = { p: Int ->
            ChatStreamEvent.PromptProgress(percent = p, label = "Elaborazione", estimated = false,
                processedTokens = null, totalTokens = null, cachedTokens = null, timeMs = null)
        }
        val state = empty().applyEvent(progress(20)).applyEvent(progress(90))
        val prefills = state.transcript.filterIsInstance<TranscriptBlock.Prefill>()
        assertEquals(1, prefills.size)
        assertTrue(prefills.single().text.contains("90%"))
    }

    @Test
    fun deriveFromTimelineKeepsOrderAndGroupsTools() {
        val timeline = listOf(
            AssistantActivity(AssistantActivity.Kind.Reasoning, text = "penso"),
            AssistantActivity(AssistantActivity.Kind.Tool, tool = ToolCallState("c1", "read")),
            AssistantActivity(AssistantActivity.Kind.Tool, tool = ToolCallState("c2", "bash")),
            AssistantActivity(AssistantActivity.Kind.PromptProgress, text = "Elaborazione 50%"),
            AssistantActivity(AssistantActivity.Kind.PromptProgress, text = "Elaborazione 100%"),
            AssistantActivity(AssistantActivity.Kind.Reasoning, text = "ripenso")
        )
        val blocks = transcriptBlocksOf(timeline, "", showToolCalls = true)
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is TranscriptBlock.Thought)
        assertTrue(blocks[1] is TranscriptBlock.Tools)
        assertEquals(2, (blocks[1] as TranscriptBlock.Tools).tools.size)
        assertTrue(blocks[2] is TranscriptBlock.Prefill)
        assertTrue((blocks[2] as TranscriptBlock.Prefill).text.contains("100%"))
        assertTrue(blocks[3] is TranscriptBlock.Thought)
    }

    @Test
    fun deriveRespectsShowToolCallsAndLegacyThinking() {
        val timeline = listOf(
            AssistantActivity(AssistantActivity.Kind.Tool, tool = ToolCallState("c1", "read"))
        )
        assertTrue(transcriptBlocksOf(timeline, "", showToolCalls = false).isEmpty())
        assertEquals(1, transcriptBlocksOf(timeline, "", showToolCalls = true).size)
        val legacy = transcriptBlocksOf(emptyList(), "vecchio pensiero", showToolCalls = true)
        assertEquals(1, legacy.size)
        assertEquals("vecchio pensiero", (legacy.single() as TranscriptBlock.Thought).text)
    }

    @Test
    fun flagHidesAgentTextAndHonorsToolFilter() {
        val blocks = listOf(
            TranscriptBlock.AgentText("risposta"),
            TranscriptBlock.Thought("penso"),
            TranscriptBlock.Tools(listOf(ToolCallState("c1", "read")))
        )
        val shown = flagTranscriptBlocks(blocks, showToolCalls = true)
        assertEquals(2, shown.size)
        assertFalse(shown.any { it is TranscriptBlock.AgentText })
        val noTools = flagTranscriptBlocks(blocks, showToolCalls = false)
        assertEquals(1, noTools.size)
        assertTrue(noTools.single() is TranscriptBlock.Thought)
        assertTrue(flagTranscriptBlocks(listOf(TranscriptBlock.AgentText("x")), true).isEmpty())
    }
}
