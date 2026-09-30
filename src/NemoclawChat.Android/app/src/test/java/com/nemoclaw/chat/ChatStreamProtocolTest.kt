package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

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
        assertEquals("\n\nFinale Markdown", mergeTextSnapshot("Finale Markdown", "\n\nFinale Markdown"))
    }

    @Test
    fun nonCumulativeSnapshotsAppendInsteadOfReplacing() {
        // Chunk sequenziali reasoning.available: nessun chunk è prefisso
        // dell'altro, la traccia deve accumularsi invece di mostrare solo
        // l'ultimo token generato.
        assertEquals("abcxyz", mergeTextSnapshot("abc", "xyz"))
        assertEquals(
            "The user said \"ciao\" in Italian.",
            mergeTextSnapshot(
                mergeTextSnapshot("The user said", " \"ciao\" in"),
                " Italian."
            )
        )
        // Finestre sovrapposte (>=8 char): unione sulla sovrapposizione.
        assertEquals("abcdefgh12345678efgh", mergeTextSnapshot("abcdefgh12345678", "12345678efgh"))
        assertEquals("", mergeTextSnapshot("", ""))
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

    @Test
    fun activityTimelinePreservesServerOrderAndCoalescesAdjacentReasoning() {
        var state = StreamingState()
        state = state.applyEvent(ChatStreamEvent.ThinkingDelta("Controllo "))
        state = state.applyEvent(ChatStreamEvent.ThinkingDelta("dati."))
        state = state.applyEvent(ChatStreamEvent.ToolCallStart("t1", "ricerca"))
        state = state.applyEvent(ChatStreamEvent.ToolCallArgs("t1", "{\"q\":\"x\"}"))
        state = state.applyEvent(ChatStreamEvent.ThinkingDelta("Leggo risultato."))
        state = state.applyEvent(ChatStreamEvent.PromptProgress(percent = 42, processedTokens = 42, totalTokens = 100))

        assertEquals(listOf(
            AssistantActivity.Kind.Reasoning,
            AssistantActivity.Kind.Tool,
            AssistantActivity.Kind.Reasoning,
            AssistantActivity.Kind.PromptProgress
        ), state.activityTimeline.map { it.kind })
        assertEquals("Controllo dati.", state.activityTimeline[0].text)
        assertEquals("Argomenti ricevuti; contenuto omesso.", state.activityTimeline[1].tool?.args)
    }

    @Test
    fun estimatedProgressNeverAppearsInActivityTimelineAndCompletionKeepsIt() {
        var state = StreamingState()
        state = state.applyEvent(ChatStreamEvent.PromptProgress(75, estimated = true))
        assertTrue(state.activityTimeline.isEmpty())
        state = state.applyEvent(ChatStreamEvent.ThinkingDelta("Evento esplicito."))
        state = state.applyEvent(ChatStreamEvent.Done(ChatStreamStats()))
        assertTrue(state.isDone)
        assertEquals("Evento esplicito.", state.activityTimeline.single().text)
    }

    @Test
    fun toolResultWithoutIdUpdatesOriginalTimelineEntryByName() {
        var state = StreamingState()
        state = state.applyEvent(ChatStreamEvent.ToolCallStart("call-1", "ricerca"))
        state = state.applyEvent(ChatStreamEvent.ThinkingDelta("Verifico il risultato."))
        state = state.applyEvent(ChatStreamEvent.ToolResult(id = null, name = "ricerca", output = "ok"))

        assertEquals(2, state.activityTimeline.size)
        assertEquals("call-1", state.activityTimeline.first().tool?.id)
        assertEquals("Risultato ricevuto; contenuto omesso.", state.activityTimeline.first().tool?.result)
    }

    @Test
    fun toolPayloadsNeverReachTimelineAsRawSecrets() {
        var state = StreamingState()
        state = state.applyEvent(ChatStreamEvent.ToolCallStart("t1", "richiesta"))
        state = state.applyEvent(ChatStreamEvent.ToolCallArgs("t1", "Authorization: Bearer top-secret"))
        state = state.applyEvent(ChatStreamEvent.ToolResult("t1", "richiesta", "{\"password\":\"top-secret\"}"))

        val tool = state.activityTimeline.single().tool!!
        assertEquals("Argomenti ricevuti; contenuto omesso.", tool.args)
        assertEquals("Risultato ricevuto; contenuto omesso.", tool.result)
        assertFalse(tool.args.contains("Bearer"))
        assertFalse(tool.result!!.contains("password"))
    }

    @Test
    fun rawEventsAlwaysUseConstantSafeMarker() {
        assertEquals(SAFE_RAW_EVENT_JSON, redactToolRawEvent("harmless.status", "ok"))
        assertEquals(SAFE_RAW_EVENT_JSON, redactToolRawEvent("tool.started", "Authorization: Bearer top-secret"))
        assertFalse(redactToolRawEvent("harmless.status", "unseen-secret-value").contains("unseen-secret-value"))
    }

    @Test
    fun canonicalEnvelopeKeepsMetadataAndParsesPayload() {
        val events = parseSseData(
            "hermes.processing.progress",
            """{"protocol_version":1,"event_id":"evt_fixture_001","sequence":7,"request_id":"req_fixture_001","correlation_id":"corr_fixture_001","run_id":"run_fixture_001","type":"hermes.processing.progress","payload":{"estimated":false,"percent":25,"prompt_progress":{"processed":25,"total":100,"cache":5,"time_ms":1200}},"source_type":"hermes-agent"}"""
        )

        val metadata = events.filterIsInstance<ChatStreamEvent.EnvelopeMetadata>().single()
        assertEquals(1, metadata.protocolVersion)
        assertEquals("evt_fixture_001", metadata.eventId)
        assertEquals(7L, metadata.sequence)
        assertEquals("req_fixture_001", metadata.requestId)
        assertEquals("corr_fixture_001", metadata.correlationId)
        assertEquals("run_fixture_001", metadata.runId)
        assertEquals("hermes.processing.progress", metadata.type)
        assertEquals("hermes-agent", metadata.sourceType)
        assertEquals(25, events.filterIsInstance<ChatStreamEvent.PromptProgress>().single().percent)
    }

    @Test
    fun unknownCanonicalEnvelopeRemainsForwardCompatibleAndCorrelated() {
        val raw = """{"protocol_version":1,"event_id":"evt_fixture_unknown","sequence":8,"request_id":"req_fixture_001","correlation_id":"corr_fixture_001","type":"hermes.future.event.v2","payload":{"opaque":true},"source_type":"unknown"}"""
        val events = parseSseData(null, raw)

        val metadata = events.filterIsInstance<ChatStreamEvent.EnvelopeMetadata>().single()
        val unknown = events.filterIsInstance<ChatStreamEvent.RawHermesEvent>().single()
        assertEquals("hermes.future.event.v2", metadata.type)
        assertEquals("req_fixture_001", unknown.requestId)
        assertEquals("corr_fixture_001", unknown.correlationId)
        assertEquals("hermes.future.event.v2", unknown.name)
        assertEquals(JSONObject(raw).toString(), unknown.json)
    }

    @Test
    fun canonicalWhitespaceOnlyDeltaIsNotTrimmed() {
        val events = parseSseData(
            null,
            """{"protocol_version":1,"event_id":"evt_fixture_delta","sequence":9,"request_id":"req_fixture_001","correlation_id":"corr_fixture_001","type":"response.output_text.delta","payload":{"delta":"  "},"source_type":"hermes-agent"}"""
        )

        assertEquals("  ", events.filterIsInstance<ChatStreamEvent.TextDelta>().single().delta)
    }

    @Test
    fun draftAcceptanceRateComesOnlyFromServerTimings() {
        val events = parseSseData(
            "response.completed",
            """{"type":"response.completed","timings":{"predicted_n":200,"draft_n":335,"draft_n_accepted":72}}"""
        )
        val draft = events.filterIsInstance<ChatStreamEvent.DraftAcceptance>().single()
        assertEquals(72.0 / 335.0, draft.rate, 1e-9)
        assertEquals(null, draft.label)
    }

    @Test
    fun draftAcceptanceLabelKeepsOnlyDeclaredSpecType() {
        val events = parseSseData(
            "response.completed",
            """{"type":"response.completed","timings":{"draft_n":100,"draft_n_accepted":50,"spec_type":"draft-dflash"}}"""
        )
        assertEquals("dflash", events.filterIsInstance<ChatStreamEvent.DraftAcceptance>().single().label)
    }

    @Test
    fun draftAcceptanceIsAbsentWithoutSpeculativeDecoding() {
        val withoutDraft = parseSseData(
            "response.completed",
            """{"type":"response.completed","timings":{"predicted_n":200,"predicted_per_second":17.7}}"""
        )
        assertTrue(withoutDraft.none { it is ChatStreamEvent.DraftAcceptance })
        val emptyDraft = parseSseData(
            "response.completed",
            """{"type":"response.completed","timings":{"draft_n":0,"draft_n_accepted":0}}"""
        )
        assertTrue(emptyDraft.none { it is ChatStreamEvent.DraftAcceptance })
        val inconsistentDraft = parseSseData(
            "response.completed",
            """{"type":"response.completed","timings":{"draft_n":10,"draft_n_accepted":11}}"""
        )
        assertTrue(inconsistentDraft.none { it is ChatStreamEvent.DraftAcceptance })
    }

    @Test
    fun specTypeLabelNormalizationRejectsJunk() {
        assertEquals("mtp", normalizeSpecTypeLabel("draft-mtp"))
        assertEquals("dspark", normalizeSpecTypeLabel("dspark"))
        assertEquals(null, normalizeSpecTypeLabel(null))
        assertEquals(null, normalizeSpecTypeLabel("  "))
        assertEquals(null, normalizeSpecTypeLabel("a".repeat(25)))
        assertEquals(null, normalizeSpecTypeLabel("../evil"))
    }

    @Test
    fun statsLineShowsAcceptanceOnlyWhenEnabledAndPresent() {
        val stats = ChatStreamStats(tokensPerSecond = 17.79, acceptanceRate = 72.0 / 335.0, acceptanceLabel = "mtp")
        val shown = formatChatStatsLine(stats, MetricDisplayFilter())
        assertTrue(shown.contains("Accept 21% (mtp)"))
        val hidden = formatChatStatsLine(stats, MetricDisplayFilter(acceptanceRate = false))
        assertFalse(hidden.contains("Accept"))
        val unlabeled = formatChatStatsLine(
            ChatStreamStats(acceptanceRate = 0.5, acceptanceLabel = null),
            MetricDisplayFilter()
        )
        assertTrue(unlabeled.contains("Accept 50%"))
        assertFalse(unlabeled.contains("("))
        assertEquals("", formatChatStatsLine(ChatStreamStats(), MetricDisplayFilter()))
    }

    @Test
    fun toolNamePrefersHumanNameOverCallId() {
        assertEquals("get_weather", humanToolName("get_weather", "call_abc123", ""))
        assertEquals("get_weather", humanToolName("call_abc123", "call_abc123", """{"name":"get_weather"}"""))
        assertEquals("call_abc…c123", humanToolName("", "call_abc123456789c123", ""))
        assertEquals("tool", humanToolName("", "", ""))
        assertEquals("call_1", humanToolName("call_1", "call_1", "Argomenti ricevuti; contenuto omesso."))
    }

    @Test
    fun prefillPinnedOnceAboveTools() {
        val timeline = listOf(
            AssistantActivity(AssistantActivity.Kind.Reasoning, text = "penso"),
            AssistantActivity(AssistantActivity.Kind.PromptProgress, text = "Elaborazione prompt 12%"),
            AssistantActivity(AssistantActivity.Kind.Tool, tool = ToolCallState("call_1", "tool_a")),
            AssistantActivity(AssistantActivity.Kind.PromptProgress, text = "Elaborazione prompt 100%"),
            AssistantActivity(AssistantActivity.Kind.Tool, tool = ToolCallState("call_2", "tool_b"))
        )
        val (pinned, rest) = splitTimelinePrefill(timeline)
        assertEquals("Elaborazione prompt 100%", pinned?.text)
        assertEquals(3, rest.size)
        assertTrue(rest.none { it.kind == AssistantActivity.Kind.PromptProgress })
        val (none, all) = splitTimelinePrefill(
            listOf(AssistantActivity(AssistantActivity.Kind.Tool, tool = ToolCallState("c", "t")))
        )
        assertEquals(null, none)
        assertEquals(1, all.size)
    }

    @Test
    fun reasoningMergesIntoSingleCanvasAfterTools() {
        val timeline = listOf(
            AssistantActivity(AssistantActivity.Kind.Reasoning, text = "penso A"),
            AssistantActivity(AssistantActivity.Kind.Tool, tool = ToolCallState("c1", "tool_a")),
            AssistantActivity(AssistantActivity.Kind.Reasoning, text = "penso B"),
            AssistantActivity(AssistantActivity.Kind.Reasoning, text = "  ")
        )
        assertEquals("penso A\n\npenso B", mergeReasoningCanvas(timeline))
        assertEquals("", mergeReasoningCanvas(emptyList()))
    }

    @Test
    fun toolPreviewScrubsSecretsAndTruncates() {
        assertEquals("", scrubToolPayloadPreview(""))
        assertEquals("", scrubToolPayloadPreview("Argomenti ricevuti; contenuto omesso."))
        val masked = scrubToolPayloadPreview("""{"query":"meteo Roma","api_key":"sk-secret-123"}""")
        assertTrue(masked.contains("meteo Roma"))
        assertFalse(masked.contains("sk-secret-123"))
        assertTrue(masked.contains("***"))
        val long = scrubToolPayloadPreview("""{"data":"${"x".repeat(2000)}"}""")
        assertTrue(long.contains("…[+"))
        assertFalse(long.contains("x".repeat(2000)))
        val plain = scrubToolPayloadPreview("ok")
        assertEquals("ok", plain)
    }
}
