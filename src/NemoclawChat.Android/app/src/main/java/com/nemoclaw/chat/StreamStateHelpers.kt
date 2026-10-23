package com.nemoclaw.chat


internal fun StreamingState.withActivity(message: String?): StreamingState {
    if (message.isNullOrBlank()) return this
    val elapsed = (System.nanoTime() - startedAtNs) / 1_000_000_000.0
    val row = "${String.format(java.util.Locale.US, "%.1fs", elapsed)}  $message"
    return copy(activityLog = (activityLog + row).takeLast(80))
}

internal fun upsertToolResult(tools: List<ToolCallState>, event: ChatStreamEvent.ToolResult): List<ToolCallState> {
    val id = event.id ?: event.name ?: "tool-result"
    if (tools.none { it.id == id }) {
        return tools + ToolCallState(
            id = id,
            name = event.name ?: id,
            status = "risultato pronto",
            result = safeToolPayloadSummary(event.output, result = true),
            resultPreview = scrubToolPayloadPreview(event.output, result = true)
        )
    }
    return tools.map {
        if ((event.id != null && it.id == event.id) || (event.id == null && event.name != null && it.name == event.name)) {
            it.copy(
                result = safeToolPayloadSummary(event.output, result = true),
                resultPreview = scrubToolPayloadPreview(event.output, result = true),
                endedAtMs = it.endedAtMs.takeIf { e -> e > 0 } ?: System.currentTimeMillis(),
                status = "risultato pronto"
            )
        } else {
            it
        }
    }
}

/**
 * Deduplica i blocchi per URL (case-insensitive, proxy e assoluti equivalenti):
 * blocco agente + estrazione inline della stessa URL diventano una sola card,
 * preferendo il kind più specifico (image/video/audio su document).
 */
internal fun dedupeVisualBlocks(current: List<VisualBlock>, incoming: List<VisualBlock>): List<VisualBlock> {
    if (incoming.isEmpty()) return current
    fun keyOf(block: VisualBlock): String {
        val url = block.mediaUrl.trim().lowercase()
        if (url.isBlank()) return "id:${block.id}"
        val proxy = url.substringAfterLast("/v1/media/", missingDelimiterValue = "")
        if (proxy.isNotEmpty() && proxy != url) return "proxy:$proxy"
        return "url:$url"
    }
    fun specificity(block: VisualBlock): Int = when (block.mediaKind.lowercase()) {
        "image", "video", "audio" -> 1
        else -> 0
    }
    val out = current.toMutableList()
    val indexByKey = out.mapIndexed { i, block -> keyOf(block) to i }.toMap().toMutableMap()
    for (block in incoming) {
        val key = keyOf(block)
        val existing = indexByKey[key]
        if (existing == null) {
            indexByKey[key] = out.size
            out += block
        } else if (block.type.equals("genui", ignoreCase = true) &&
            out[existing].type.equals("genui", ignoreCase = true) &&
            block.id == out[existing].id
        ) {
            // Stessa UI rigenerata dall'agente: l'ultima versione vince.
            out[existing] = block
        } else if (specificity(block) > specificity(out[existing])) {
            out[existing] = block
        }
    }
    return out
}

internal fun mergeVisualBlocks(current: List<VisualBlock>, incoming: List<VisualBlock>): List<VisualBlock> {
    return dedupeVisualBlocks(current, incoming)
}

internal fun StreamingState.appendReasoningActivity(delta: String): List<AssistantActivity> {
    if (delta.isEmpty()) return activityTimeline
    val last = activityTimeline.lastOrNull()
    return if (last?.kind == AssistantActivity.Kind.Reasoning) {
        boundedTimeline(activityTimeline.dropLast(1) + last.copy(text = mergeTextDelta(last.text, delta)))
    } else {
        boundedTimeline(activityTimeline + AssistantActivity(AssistantActivity.Kind.Reasoning, text = delta))
    }
}

internal fun StreamingState.appendReasoningSnapshotActivity(snapshot: String): List<AssistantActivity> {
    if (snapshot.isEmpty()) return activityTimeline
    val last = activityTimeline.lastOrNull()
    return if (last?.kind == AssistantActivity.Kind.Reasoning) {
        boundedTimeline(activityTimeline.dropLast(1) + last.copy(text = mergeTextSnapshot(last.text, snapshot)))
    } else {
        boundedTimeline(activityTimeline + AssistantActivity(AssistantActivity.Kind.Reasoning, text = snapshot))
    }
}

internal fun StreamingState.appendPromptProgressActivity(event: ChatStreamEvent.PromptProgress): List<AssistantActivity> {
    val text = formatPrefillLine(
        processed = event.processedTokens,
        total = event.totalTokens,
        maxTokens = stats?.contextLength,
        tps = stats?.tokensPerSecond,
        percent = event.percent,
        estimated = event.estimated
    )
    val last = activityTimeline.lastOrNull()
    return if (last?.kind == AssistantActivity.Kind.PromptProgress) {
        boundedTimeline(activityTimeline.dropLast(1) + last.copy(text = text))
    } else {
        boundedTimeline(activityTimeline + AssistantActivity(AssistantActivity.Kind.PromptProgress, text = text))
    }
}

internal fun StreamingState.withTimelineTool(id: String, name: String?, update: (ToolCallState) -> ToolCallState): StreamingState {
    val index = activityTimeline.indexOfLast {
        it.kind == AssistantActivity.Kind.Tool &&
            (it.tool?.id == id || (name != null && it.tool?.name == name))
    }
    val current = index.takeIf { it >= 0 }?.let { activityTimeline[it].tool }
    val tool = update(current ?: ToolCallState(id, name ?: id))
    val next = if (index >= 0) {
        activityTimeline.toMutableList().also { it[index] = it[index].copy(tool = tool) }
    } else {
        activityTimeline + AssistantActivity(AssistantActivity.Kind.Tool, tool = tool)
    }
    return copy(activityTimeline = boundedTimeline(next))
}

/**
 * Congela il testo ricevuto finora come segmento AgentText del transcript.
 * Lo snapshot finale puo riscrivere il testo: in quel caso i segmenti
 * precedenti vengono sostituiti da un unico segmento coerente.
 */
internal fun StreamingState.flushPendingText(): StreamingState {
    if (text == flushedText) return this
    val blocks = transcript.toMutableList()
    if (text.startsWith(flushedText) && flushedText.isNotEmpty()) {
        val segment = text.substring(flushedText.length)
        if (segment.isNotBlank()) {
            blocks += TranscriptBlock.AgentText(takeNewestActivityText(segment))
        }
    } else {
        while (blocks.isNotEmpty() && blocks.last() is TranscriptBlock.AgentText) {
            blocks.removeAt(blocks.size - 1)
        }
        if (text.isNotBlank()) {
            blocks += TranscriptBlock.AgentText(takeNewestActivityText(text))
        }
    }
    return copy(transcript = boundedTranscript(blocks), flushedText = text, thoughtStartedAtNs = null)
}

internal fun StreamingState.appendThought(text: String, isSnapshot: Boolean): StreamingState {
    if (text.isEmpty()) return this
    val nowNs = System.nanoTime()
    val startNs = thoughtStartedAtNs ?: nowNs
    val elapsed = (nowNs - startNs) / 1_000_000_000.0
    val blocks = transcript.toMutableList()
    val last = blocks.lastOrNull()
    if (last is TranscriptBlock.Thought) {
        val merged = if (isSnapshot) mergeTextSnapshot(last.text, text) else mergeTextDelta(last.text, text)
        blocks[blocks.size - 1] = last.copy(text = takeNewestActivityText(merged), elapsedSec = elapsed)
    } else {
        blocks += TranscriptBlock.Thought(takeNewestActivityText(text), elapsed)
    }
    return copy(transcript = boundedTranscript(blocks), thoughtStartedAtNs = startNs)
}

internal fun StreamingState.appendTranscriptTool(id: String, name: String?, update: (ToolCallState) -> ToolCallState): StreamingState {
    val blocks = transcript.toMutableList()
    val last = blocks.lastOrNull()
    if (last is TranscriptBlock.Tools) {
        val tools = last.tools.toMutableList()
        val index = tools.indexOfLast { it.id == id || (name != null && it.name == name) }
        if (index >= 0) {
            tools[index] = update(tools[index])
        } else {
            tools += update(ToolCallState(id, name ?: id))
        }
        blocks[blocks.size - 1] = last.copy(tools = tools.toList())
    } else {
        blocks += TranscriptBlock.Tools(listOf(update(ToolCallState(id, name ?: id))))
    }
    return copy(transcript = boundedTranscript(blocks))
}

internal fun StreamingState.appendTranscriptPrefill(event: ChatStreamEvent.PromptProgress): StreamingState {
    val text = formatPrefillLine(
        processed = event.processedTokens,
        total = event.totalTokens,
        maxTokens = stats?.contextLength,
        tps = stats?.tokensPerSecond,
        percent = event.percent,
        estimated = event.estimated
    )
    val blocks = transcript.filterNot { it is TranscriptBlock.Prefill }.toMutableList()
    blocks += TranscriptBlock.Prefill(text)
    return copy(transcript = boundedTranscript(blocks))
}

internal fun boundedTranscript(items: List<TranscriptBlock>): List<TranscriptBlock> {
    val bounded = items.map { item ->
        when (item) {
            is TranscriptBlock.AgentText -> item.copy(text = takeNewestActivityText(item.text))
            is TranscriptBlock.Thought -> item.copy(text = takeNewestActivityText(item.text))
            is TranscriptBlock.Tools -> item.copy(tools = item.tools.map { tool ->
                tool.copy(
                    args = takeNewestActivityText(tool.args),
                    result = tool.result?.let(::takeNewestActivityText)
                )
            })
            is TranscriptBlock.Prefill -> item.copy(text = takeNewestActivityText(item.text))
        }
    }.toMutableList()
    while ((bounded.size > MAX_TRANSCRIPT_BLOCKS || transcriptChars(bounded) > MAX_TRANSCRIPT_CHARS) && bounded.size > 1) {
        bounded.removeAt(0)
    }
    return bounded
}

internal fun transcriptChars(items: List<TranscriptBlock>): Int = items.sumOf { item ->
    when (item) {
        is TranscriptBlock.AgentText -> item.text.length
        is TranscriptBlock.Thought -> item.text.length
        is TranscriptBlock.Prefill -> item.text.length
        is TranscriptBlock.Tools -> item.tools.sumOf {
            it.args.length + (it.result?.length ?: 0) + it.argsPreview.length + it.resultPreview.length
        }
    }
}

/**
 * Ricostruisce il transcript dai dati persistiti (archivio, bot canonici,
 * messaggi vecchi senza transcript live): ordine cronologico, tool
 * consecutivi raggruppati, solo l'ultimo prefill.
 */
internal fun transcriptBlocksOf(
    timeline: List<AssistantActivity>,
    legacyThinking: String,
    showToolCalls: Boolean
): List<TranscriptBlock> {
    val compatible = if (timeline.isEmpty() && legacyThinking.isNotBlank()) {
        listOf(AssistantActivity(AssistantActivity.Kind.Reasoning, text = legacyThinking))
    } else timeline
    val lastPrefillIndex = compatible.indexOfLast { it.kind == AssistantActivity.Kind.PromptProgress }
    val blocks = mutableListOf<TranscriptBlock>()
    for ((index, item) in compatible.withIndex()) {
        when (item.kind) {
            AssistantActivity.Kind.Reasoning -> {
                if (item.text.isNotBlank()) blocks += TranscriptBlock.Thought(item.text)
            }
            AssistantActivity.Kind.Tool -> {
                if (!showToolCalls) continue
                val tool = item.tool ?: continue
                val last = blocks.lastOrNull()
                if (last is TranscriptBlock.Tools) {
                    blocks[blocks.size - 1] = last.copy(tools = last.tools + tool)
                } else {
                    blocks += TranscriptBlock.Tools(listOf(tool))
                }
            }
            AssistantActivity.Kind.PromptProgress -> {
                if (index == lastPrefillIndex && item.text.isNotBlank()) {
                    blocks += TranscriptBlock.Prefill(item.text)
                }
            }
        }
    }
    return blocks
}

/** Solo i blocchi che hanno senso dentro il flag finale (niente testo agente). */
internal fun flagTranscriptBlocks(blocks: List<TranscriptBlock>, showToolCalls: Boolean): List<TranscriptBlock> {
    return blocks.filter {
        when (it) {
            is TranscriptBlock.AgentText -> false
            is TranscriptBlock.Tools -> showToolCalls && it.tools.isNotEmpty()
            is TranscriptBlock.Thought -> it.text.isNotBlank()
            is TranscriptBlock.Prefill -> it.text.isNotBlank()
        }
    }
}

internal fun boundedTimeline(items: List<AssistantActivity>): List<AssistantActivity> {
    val bounded = items.map { item ->
        item.copy(
            text = takeNewestActivityText(item.text),
            tool = item.tool?.copy(
                args = takeNewestActivityText(item.tool.args),
                result = item.tool.result?.let(::takeNewestActivityText)
            )
        )
    }.toMutableList()
    while (bounded.size > MAX_ACTIVITY_TIMELINE_ENTRIES || activityTimelineChars(bounded) > MAX_ACTIVITY_TIMELINE_CHARS) {
        if (bounded.size == 1) break
        bounded.removeAt(0)
    }
    return bounded
}

internal fun activityTimelineChars(items: List<AssistantActivity>): Int = items.sumOf {
    it.text.length + (it.tool?.args?.length ?: 0) + (it.tool?.result?.length ?: 0)
}

internal fun takeNewestActivityText(value: String): String {
    if (value.length <= MAX_ACTIVITY_ENTRY_CHARS) return value
    var start = value.length - MAX_ACTIVITY_ENTRY_CHARS
    if (start < value.length && start > 0 && Character.isHighSurrogate(value[start - 1]) && Character.isLowSurrogate(value[start])) start++
    return value.substring(start)
}

internal fun mergeTextDelta(current: String, delta: String): String {
    if (delta.isEmpty()) return current
    if (current.isEmpty()) return delta
    return current + delta
}

internal fun mergeTextSnapshot(current: String, snapshot: String): String {
    if (snapshot.isEmpty()) return current
    if (current.isEmpty() || snapshot.startsWith(current)) return snapshot
    if (current.startsWith(snapshot) || current == snapshot) return current
    // Stesso contenuto con wrapping diverso (es. "\n\nFinale..."): vince lo snapshot.
    if (snapshot.contains(current)) return snapshot
    if (current.contains(snapshot)) return current
    // Stesso testo riemesso con micro-differenze (snapshot finale cumulativo
    // dopo i delta: ritokenizzazione, spaziatura). Blocchi quasi identici
    // => vince lo snapshot, altrimenti la risposta appare due volte.
    if (nearDuplicateBlocks(current, snapshot)) return snapshot
    // Snapshot non cumulativo (chunk sequenziali del server): accoda invece di
    // sostituire, altrimenti resta visibile solo l'ultimo token generato.
    // Se la coda di current coincide con la testa di snapshot (finestre
    // sovrapposte), unisci sulla sovrapposizione per non duplicare.
    val maxOverlap = minOf(current.length, snapshot.length, 512)
    var overlap = 0
    for (len in maxOverlap downTo 8) {
        if (current.endsWith(snapshot.take(len))) {
            overlap = len
            break
        }
    }
    return current + snapshot.drop(overlap)
}

internal fun nearDuplicateBlocks(first: String, second: String): Boolean {
    val minLen = minOf(first.length, second.length)
    if (minLen < 64) return first == second
    if (second.length < first.length / 2 || second.length > first.length * 2 + 256) return false
    var prefix = 0
    while (prefix < minLen && first[prefix] == second[prefix]) prefix++
    if (prefix >= minLen) return true
    var suffix = 0
    while (suffix < minLen - prefix && first[first.length - 1 - suffix] == second[second.length - 1 - suffix]) suffix++
    return prefix >= 48 && suffix >= 16 && prefix + suffix >= minLen * 3 / 5
}

/**
 * Collassa blocchi consecutivi quasi identici (risposta emessa 2x/4x dal
 * server o da merge concorrenti): mostra una sola copia. Solo display,
 * non altera i dati salvati/sincronizzati.
 */
internal fun collapseRepeatedBlocks(text: String): String {
    // Fast path: segmenti sotto i 64 char non possono mai collassare.
    if (text.length < 128) return text
    var result = text
    for (reps in listOf(4, 3, 2)) {
        val segLen = result.length / reps
        if (segLen < 64 || result.length % reps != 0) continue
        val first = result.take(segLen)
        var ok = true
        for (i in 1 until reps) {
            if (!nearDuplicateBlocks(first, result.drop(i * segLen).take(segLen))) {
                ok = false
                break
            }
        }
        if (ok) {
            result = first
            break
        }
    }
    return result
}

internal fun inferToolPendingStatus(tool: ToolCallState): Boolean {
    val s = tool.status.lowercase()
    return !s.contains("completat") && !s.contains("risultato") && !s.contains("success") && !s.contains("done") && !s.contains("fail") && !s.contains("error")
}

