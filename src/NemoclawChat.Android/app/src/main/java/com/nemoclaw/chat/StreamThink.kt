package com.nemoclaw.chat


internal fun stripReasoningArtifacts(text: String): String {
    if (text.isEmpty()) return ""
    return text
        .replace(Regex("<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("<\\s*think\\s*>.*\\z", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("<\\s*/?\\s*think\\s*>", RegexOption.IGNORE_CASE), "")
        .trimStart('\r', '\n')
}

internal class ThinkExtractor {
    private companion object {
        const val MAX_THINK_TAG_FRAGMENT_CHARS = 24
        val OPEN_THINK_TAG = Regex("<\\s*think\\s*>", RegexOption.IGNORE_CASE)
        val CLOSE_THINK_TAG = Regex("<\\s*/\\s*think\\s*>", RegexOption.IGNORE_CASE)
    }

    private val buffer = StringBuilder()
    private var inThink = false

    fun process(delta: String): List<ChatStreamEvent> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        var text = buffer.toString()
        val events = mutableListOf<ChatStreamEvent>()

        while (true) {
            if (!inThink) {
                val start = OPEN_THINK_TAG.find(text)
                if (start != null) {
                    if (start.range.first > 0) {
                        events.add(ChatStreamEvent.TextDelta(text.substring(0, start.range.first)))
                    }
                    text = text.substring(start.range.last + 1)
                    buffer.clear()
                    buffer.append(text)
                    inThink = true
                } else {
                    val safeLen = maxOf(0, text.length - MAX_THINK_TAG_FRAGMENT_CHARS)
                    if (safeLen > 0) {
                        events.add(ChatStreamEvent.TextDelta(text.substring(0, safeLen)))
                        text = text.substring(safeLen)
                        buffer.clear()
                        buffer.append(text)
                    }
                    break
                }
            } else {
                val end = CLOSE_THINK_TAG.find(text)
                if (end != null) {
                    if (end.range.first > 0) {
                        events.add(ChatStreamEvent.ThinkingDelta(text.substring(0, end.range.first)))
                    }
                    text = text.substring(end.range.last + 1)
                    buffer.clear()
                    buffer.append(text)
                    inThink = false
                } else {
                    val safeLen = maxOf(0, text.length - MAX_THINK_TAG_FRAGMENT_CHARS)
                    if (safeLen > 0) {
                        events.add(ChatStreamEvent.ThinkingDelta(text.substring(0, safeLen)))
                        text = text.substring(safeLen)
                        buffer.clear()
                        buffer.append(text)
                    }
                    break
                }
            }
        }
        return events
    }

    fun flush(): List<ChatStreamEvent> {
        val text = buffer.toString()
        val events = mutableListOf<ChatStreamEvent>()
        if (text.isNotEmpty()) {
            if (inThink) events.add(ChatStreamEvent.ThinkingDelta(text))
            else events.add(ChatStreamEvent.TextDelta(text))
        }
        buffer.clear()
        return events
    }

    fun processSnapshot(snapshot: String): List<ChatStreamEvent> {
        val pending = flush()
        val snapshotExtractor = ThinkExtractor()
        val parts = snapshotExtractor.process(snapshot) + snapshotExtractor.flush()
        val answer = buildString {
            parts.filterIsInstance<ChatStreamEvent.TextDelta>().forEach { append(it.delta) }
        }
        val reasoning = buildString {
            parts.filterIsInstance<ChatStreamEvent.ThinkingDelta>().forEach { append(it.delta) }
        }
        return buildList {
            addAll(pending)
            if (reasoning.isNotEmpty()) add(ChatStreamEvent.ThinkingSnapshot(reasoning))
            if (answer.isNotEmpty() || reasoning.isEmpty()) add(ChatStreamEvent.TextSnapshot(answer))
        }
    }
}

