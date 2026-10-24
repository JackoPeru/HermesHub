package com.nemoclaw.chat

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Riga prefill "Prefill · 12k/262k tok · 654 tk/s · 45%".
 * Stimato (senza totale affidabile) = prefisso ~ e niente %. Puro e testabile.
 */
internal fun formatCompactTokens(n: Long): String {
    if (n < 0) return "…"
    return when {
        n >= 1_000_000L -> {
            val v = n / 1_000_000.0
            (if (v >= 100) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", v))
                .replace('.', ',').trimEnd('0').trimEnd(',') + "M"
        }
        n >= 1_000L -> {
            val v = n / 1_000.0
            (if (v >= 100) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", v))
                .replace('.', ',').trimEnd('0').trimEnd(',') + "k"
        }
        else -> n.toString()
    }
}

internal fun formatTps(tps: Double): String {
    if (!tps.isFinite() || tps <= 0) return ""
    return if (tps >= 100) tps.roundToInt().toString()
    else String.format(java.util.Locale.US, "%.1f", tps).replace('.', ',').trimEnd('0').trimEnd(',')
}

internal fun formatPrefillLine(
    processed: Int?,
    total: Int?,
    maxTokens: Int?,
    tps: Double?,
    percent: Int?,
    estimated: Boolean
): String {
    val head = if (estimated) "~Prefill" else "Prefill"
    val denom = maxTokens?.takeIf { it > 0 } ?: total?.takeIf { it > 0 }
    val tokPart = when {
        processed != null && denom != null -> "${formatCompactTokens(processed.toLong())}/${formatCompactTokens(denom.toLong())} tok"
        processed != null -> "${formatCompactTokens(processed.toLong())} tok"
        else -> null
    }
    val tpsPart = tps?.takeIf { it.isFinite() && it > 0 }?.let { "${formatTps(it)} tk/s" }
    val pctPart = percent?.takeIf { it in 1..100 }?.let { "$it%" }
    val parts = listOfNotNull(tokPart, tpsPart, pctPart)
    return if (parts.isEmpty()) head else "$head · ${parts.joinToString(" · ")}"
}

/** Primo argomento significativo (comando/path/query) dagli args tool. Puro. */
private val WS_COLLAPSE_RE = Regex("\\s+")
private val CALL_ID_ARG_RE = Regex("^call_[0-9a-fA-F]+$")

internal fun extractFirstToolArg(args: String): String {
    val trimmed = args.trim()
    if (trimmed.isEmpty()) return ""
    val fromJson = runCatching {
        val obj = org.json.JSONObject(trimmed)
        listOf("command", "path", "file", "query", "url", "prompt", "text", "q", "pattern", "input")
            .firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
    }.getOrNull()
    val raw = fromJson ?: trimmed.lines().firstOrNull { it.isNotBlank() } ?: ""
    val single = raw.replace(WS_COLLAPSE_RE, " ").trim()
    return if (single.length <= 80) single else single.take(80).trimEnd() + "…"
}

/**
 * Titolo riga tool stile feed ("Eseguito: gh repo view…", "Letto: file.txt").
 * Solo verbi+argomenti reali, mai conteggi inventati. Puro e testabile.
 */
internal fun workToolTitle(name: String, args: String): String {
    val clean = humanToolName(name, "", args).trim()
    val detail = extractFirstToolArg(args)
    // Pattern verbi precompilato: workToolTitle gira per riga a ogni ricomposizione.
    fun has(re: Regex): Boolean = re.containsMatchIn(clean)
    val verb = when {
        argsHasCommand(args) || has(TOOL_EXEC_RE) -> "Eseguito"
        has(TOOL_READ_RE) -> "Letto"
        has(TOOL_EXPLORE_RE) -> "Esplorato"
        has(TOOL_WRITE_RE) -> "Scritto"
        has(TOOL_EDIT_RE) -> "Modificato"
        has(TOOL_FETCH_RE) -> "Scaricato"
        has(TOOL_SEARCH_RE) -> "Cercato"
        has(TOOL_ANALYZE_RE) -> "Analizzato"
        has(TOOL_MEDIA_RE) -> "Elaborato"
        else -> ""
    }
    // Nomi generici del parser senza dettaglio: mai mostrare "tool".
    if (detail.isBlank() && (clean.isBlank() || clean.equals("tool", ignoreCase = true) || clean.matches(CALL_ID_ARG_RE))) {
        return "Attività"
    }
    if (verb.isEmpty()) return clean.ifBlank { "Attività" }
    return if (detail.isNotBlank()) "$verb: $detail" else verb
}

private fun argsHasCommand(args: String): Boolean = runCatching {
    org.json.JSONObject(args).has("command")
}.getOrDefault(false)

private fun toolVerbRe(vararg words: String): Regex =
    Regex("\\b(${words.joinToString("|") { Regex.escape(it) }})", RegexOption.IGNORE_CASE)

private val TOOL_EXEC_RE = toolVerbRe("terminal", "shell", "bash", "exec", "command", "run", "script", "codice")
private val TOOL_READ_RE = toolVerbRe("read", "cat", "show", "view", "lettura", "leggi")
private val TOOL_EXPLORE_RE = toolVerbRe("ls", "dir", "find", "glob", "list", "explor", "tree")
private val TOOL_WRITE_RE = toolVerbRe("write", "create", "save", "scriv", "crea")
private val TOOL_EDIT_RE = toolVerbRe("edit", "patch", "diff", "modif", "apply")
private val TOOL_FETCH_RE = toolVerbRe("fetch", "curl", "download", "wget", "scaric")
private val TOOL_SEARCH_RE = toolVerbRe("search", "query", "web", "cerc", "grep", "lookup")
private val TOOL_ANALYZE_RE = toolVerbRe("image", "vision", "analy", "screenshot", "photo")
private val TOOL_MEDIA_RE = toolVerbRe("video", "audio", "elabor")

