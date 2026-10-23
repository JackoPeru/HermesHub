package com.nemoclaw.chat

import okhttp3.Response

@androidx.compose.runtime.Immutable
data class StreamingState(
    val text: String = "",
    val thinking: String = "",
    val hasThinking: Boolean = false,
    val thinkingFrozen: Boolean = false,
    val thinkingElapsedSec: Double = 0.0,
    val toolCalls: List<ToolCallState> = emptyList(),
    val activityTimeline: List<AssistantActivity> = emptyList(),
    /** Transcript cronologico (testo agente + pensieri + tool). Vuoto = chat semplice. */
    val transcript: List<TranscriptBlock> = emptyList(),
    /** Prefisso di [text] gia emesso come blocco AgentText. */
    val flushedText: String = "",
    /** Istante di inizio del pensiero corrente (ns), null se nessun pensiero aperto. */
    val thoughtStartedAtNs: Long? = null,
    val visualBlocks: List<VisualBlock> = emptyList(),
    val visualBlocksVersion: Int? = null,
    val responseId: String? = null,
    val activeRunId: String? = null,
    val runStatus: String = "",
    val pendingApprovals: List<HermesServerApproval> = emptyList(),
    val pendingSteer: String = "",
    val stats: ChatStreamStats? = null,
    val status: String = "Invio richiesta",
    val promptProgressPercent: Int? = null,
    val promptProgressEstimated: Boolean = false,
    val promptProgressProcessedTokens: Int? = null,
    val promptProgressTotalTokens: Int? = null,
    val promptProgressCachedTokens: Int? = null,
    val promptProgressTimeMs: Double? = null,
    val activityLog: List<String> = listOf("0.0s  Invio richiesta"),
    val error: String? = null,
    val source: String = "Hermes",
    val usedFallback: Boolean = false,
    val isDone: Boolean = false,
    val startedAtNs: Long = System.nanoTime()
) {
    fun applyEvent(event: ChatStreamEvent): StreamingState = when (event) {
        is ChatStreamEvent.TextDelta -> {
            val merged = mergeTextDelta(text, event.delta)
            val media = extractInlineMediaBlocks(merged)
            val cleanedText = stripInlineMediaMarkup(merged)
            val nextBlocks = mergeVisualBlocks(visualBlocks, media)
            copy(
                text = cleanedText,
                visualBlocks = nextBlocks,
                promptProgressPercent = null,
                promptProgressEstimated = false,
                promptProgressProcessedTokens = null,
                promptProgressTotalTokens = null,
                promptProgressCachedTokens = null,
                promptProgressTimeMs = null,
                status = if (toolCalls.any { inferToolPendingStatus(it) }) status else "Hermes sta scrivendo la risposta...",
                thinkingFrozen = thinkingFrozen || hasThinking,
                thinkingElapsedSec = if (!thinkingFrozen && hasThinking) {
                    (System.nanoTime() - startedAtNs) / 1_000_000_000.0
                } else thinkingElapsedSec
            ).withActivity(if (text.isEmpty()) "Primo testo ricevuto." else null)
        }
        is ChatStreamEvent.TextSnapshot -> {
            val merged = mergeTextSnapshot(text, event.text)
            val media = extractInlineMediaBlocks(merged)
            copy(
                text = stripInlineMediaMarkup(merged),
                visualBlocks = mergeVisualBlocks(visualBlocks, media),
                promptProgressPercent = null,
                promptProgressEstimated = false,
                promptProgressProcessedTokens = null,
                promptProgressTotalTokens = null,
                promptProgressCachedTokens = null,
                promptProgressTimeMs = null,
                status = "Risposta finale ricevuta.",
                thinkingFrozen = thinkingFrozen || hasThinking
            ).flushPendingText().withActivity("Snapshot finale ricevuto.")
        }
        is ChatStreamEvent.ThinkingDelta -> copy(
            thinking = mergeTextDelta(thinking, event.delta),
            activityTimeline = appendReasoningActivity(event.delta),
            hasThinking = true,
            thinkingFrozen = false,
            promptProgressPercent = null,
            promptProgressEstimated = false,
            promptProgressProcessedTokens = null,
            promptProgressTotalTokens = null,
            promptProgressCachedTokens = null,
            promptProgressTimeMs = null,
            status = "Hermes sta ragionando..."
        ).flushPendingText().appendThought(event.delta, isSnapshot = false)
            .withActivity(if (!hasThinking) "Reasoning ricevuto." else null)
        is ChatStreamEvent.ThinkingSnapshot -> copy(
            thinking = mergeTextSnapshot(thinking, event.text),
            activityTimeline = appendReasoningSnapshotActivity(event.text),
            hasThinking = true,
            thinkingFrozen = false,
            promptProgressPercent = null,
            promptProgressEstimated = false,
            promptProgressProcessedTokens = null,
            promptProgressTotalTokens = null,
            promptProgressCachedTokens = null,
            promptProgressTimeMs = null,
            status = "Hermes sta ragionando..."
        ).flushPendingText().appendThought(event.text, isSnapshot = true)
            .withActivity(if (!hasThinking) "Reasoning ricevuto." else null)
        is ChatStreamEvent.ToolCallStart -> copy(
            status = "Tool in esecuzione: ${event.name}",
            toolCalls = if (toolCalls.any { it.id == event.id }) toolCalls
                else toolCalls + ToolCallState(event.id, event.name, startedAtMs = System.currentTimeMillis())
        ).flushPendingText().withTimelineTool(event.id, event.name) {
            if (it.startedAtMs == 0L) it.copy(startedAtMs = System.currentTimeMillis()) else it
        }
            .appendTranscriptTool(event.id, event.name) {
                if (it.startedAtMs == 0L) it.copy(startedAtMs = System.currentTimeMillis()) else it
            }
        is ChatStreamEvent.ToolCallArgs -> {
            val update: (ToolCallState) -> ToolCallState = {
                it.copy(
                    name = humanToolName(it.name, it.id, event.delta),
                    args = safeToolPayloadSummary(event.delta, result = false).orEmpty(),
                    argsPreview = scrubToolPayloadPreview(event.delta, result = false)
                )
            }
            copy(
                status = "Preparazione tool...",
                toolCalls = toolCalls.map { if (it.id == event.id) update(it) else it }
            ).flushPendingText().withTimelineTool(event.id, null, update).appendTranscriptTool(event.id, null, update)
        }
        is ChatStreamEvent.ToolCallEnd -> copy(
            status = "Tool completato.",
            toolCalls = toolCalls.map {
                if (it.id == event.id) it.copy(status = "completato", endedAtMs = it.endedAtMs.takeIf { e -> e > 0 } ?: System.currentTimeMillis()) else it
            }
        ).flushPendingText()
            .withTimelineTool(event.id, null) { it.copy(status = "completato", endedAtMs = it.endedAtMs.takeIf { e -> e > 0 } ?: System.currentTimeMillis()) }
            .appendTranscriptTool(event.id, null) { it.copy(status = "completato", endedAtMs = it.endedAtMs.takeIf { e -> e > 0 } ?: System.currentTimeMillis()) }
        is ChatStreamEvent.ToolResult -> {
            val update: (ToolCallState) -> ToolCallState = {
                it.copy(
                    name = if (!event.name.isNullOrBlank()) humanToolName(event.name, it.id, "") else it.name,
                    result = safeToolPayloadSummary(event.output, result = true),
                    resultPreview = scrubToolPayloadPreview(event.output, result = true),
                    endedAtMs = it.endedAtMs.takeIf { e -> e > 0 } ?: System.currentTimeMillis(),
                    status = "risultato pronto"
                )
            }
            copy(
                status = "Risultato tool ricevuto.",
                toolCalls = upsertToolResult(toolCalls, event)
            ).flushPendingText()
                .withTimelineTool(event.id ?: event.name ?: "tool-result", event.name, update)
                .appendTranscriptTool(event.id ?: event.name ?: "tool-result", event.name, update)
        }
        is ChatStreamEvent.ResponseId -> copy(responseId = event.id).withActivity("Response id: ${event.id}")
        is ChatStreamEvent.RunId -> copy(activeRunId = event.id).withActivity("Run id: ${event.id}")
        is ChatStreamEvent.RunStatusChanged -> copy(
            activeRunId = event.runId.takeIf { it.isNotBlank() } ?: activeRunId,
            runStatus = event.status,
            status = "Run Hermes: ${event.status}",
            pendingSteer = event.pendingSteer ?: pendingSteer
        ).withActivity("Run ${event.status}${event.pendingSteer?.let { " (steer in attesa)" }.orEmpty()}")
        is ChatStreamEvent.ApprovalRequest -> {
            val approval = HermesServerApproval(
                approvalId = event.approvalId,
                requestId = event.requestId,
                runId = event.runId,
                tool = event.tool,
                command = event.command,
                description = event.description,
                choices = event.choices
            )
            val existing = pendingApprovals.filterNot {
                it.approvalId.isNotBlank() && it.approvalId == approval.approvalId
            }
            copy(
                pendingApprovals = existing + approval,
                activeRunId = event.runId.takeIf { it.isNotBlank() } ?: activeRunId,
                status = "Approvazione Hermes richiesta."
            ).withActivity("Approvazione richiesta: ${event.tool} ${event.command}".trim())
        }
        is ChatStreamEvent.ApprovalResolved -> copy(
            pendingApprovals = pendingApprovals.filterNot {
                event.approvalId.isNotBlank() && it.approvalId == event.approvalId
            },
            status = "Approvazione Hermes risolta: ${event.choice}."
        ).withActivity("Approvazione risolta: ${event.choice}")
        is ChatStreamEvent.EnvelopeMetadata -> {
            // Solo tracking runId: gli envelope non toccano mai lo status
            // visibile (ciclo di vita: Invio richiesta / Prefill / ...).
            if (!event.runId.isNullOrBlank() && event.runId != activeRunId) {
                copy(activeRunId = event.runId)
            } else this
        }
        is ChatStreamEvent.VisualBlocks -> copy(
            visualBlocks = mergeVisualBlocks(visualBlocks, event.blocks),
            visualBlocksVersion = event.version
        ).withActivity("Visual blocks ricevuti: ${event.blocks.size}.")
        is ChatStreamEvent.Status -> copy(status = friendlyActivityStatus(event.message)).withActivity(event.message)
        // Eventi raw: mai nomi tecnici in UI (solo vista debug espandibile);
        // lo stato visibile resta quello del ciclo di vita (Invio/Prefill/...).
        is ChatStreamEvent.KeepAlive -> withActivity(null)
        is ChatStreamEvent.RawHermesEvent ->
            withActivity(redactToolRawEvent(event.name, event.json))
        is ChatStreamEvent.PromptProgress -> copy(
            promptProgressPercent = event.percent.coerceIn(0, 100),
            promptProgressEstimated = event.estimated,
            promptProgressProcessedTokens = event.processedTokens,
            promptProgressTotalTokens = event.totalTokens,
            promptProgressCachedTokens = event.cachedTokens,
            promptProgressTimeMs = event.timeMs,
            status = friendlyActivityStatus(event.label.ifBlank { "Elaborazione prompt" }),
            activityTimeline = appendPromptProgressActivity(event)
        ).flushPendingText().appendTranscriptPrefill(event)
        is ChatStreamEvent.Done -> copy(
            text = stripReasoningArtifacts(text),
            stats = event.stats.let { base ->
                // Il totale prompt reale macinato dal modello andrebbe perso con
                // l'azzeramento qui sotto: conservalo nelle stats per il meter.
                val modelTotal = promptProgressTotalTokens?.takeIf { !promptProgressEstimated }
                if (modelTotal != null && modelTotal > 0) base.copy(modelPromptTokens = modelTotal)
                else base
            },
            isDone = true,
            status = "Risposta completata.",
            promptProgressPercent = null,
            promptProgressEstimated = false,
            promptProgressProcessedTokens = null,
            promptProgressTotalTokens = null,
            promptProgressCachedTokens = null,
            promptProgressTimeMs = null,
            thinkingFrozen = true,
            thinkingElapsedSec = if (thinkingElapsedSec > 0) thinkingElapsedSec
                else (System.nanoTime() - startedAtNs) / 1_000_000_000.0
        ).withActivity("Risposta completata.")
        is ChatStreamEvent.Error -> copy(error = event.message, status = "Errore Hermes.", isDone = true).withActivity("Errore: ${event.message}")
        is ChatStreamEvent.Usage -> copy(
            status = "Usage ricevuta: prompt ${event.promptTokens ?: "-"}, output ${event.completionTokens ?: "-"}."
        ).withActivity("Usage ricevuta.")
        // Acceptance aggregata dall'accumulatore stream nelle stats finali: nessuno stato live.
        is ChatStreamEvent.DraftAcceptance -> this
        is ChatStreamEvent.ContextUsage -> copy(
            stats = (stats ?: ChatStreamStats()).copy(
                contextTokens = event.tokens ?: stats?.contextTokens,
                contextLength = event.length ?: stats?.contextLength,
                contextPercent = event.percent ?: stats?.contextPercent
            ),
            status = "Contesto Hermes: ${event.tokens ?: "-"} / ${event.length ?: "-"} (${event.percent ?: "-"}%)."
        ).withActivity("Contesto Hermes aggiornato.")
    }
}

