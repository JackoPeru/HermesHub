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
internal fun extractFirstToolArg(args: String): String {
    val trimmed = args.trim()
    if (trimmed.isEmpty()) return ""
    val fromJson = runCatching {
        val obj = org.json.JSONObject(trimmed)
        listOf("command", "path", "file", "query", "url", "prompt", "text", "q", "pattern", "input")
            .firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
    }.getOrNull()
    val raw = fromJson ?: trimmed.lines().firstOrNull { it.isNotBlank() } ?: ""
    val single = raw.replace(Regex("\\s+"), " ").trim()
    return if (single.length <= 80) single else single.take(80).trimEnd() + "…"
}

/**
 * Titolo riga tool stile feed ("Eseguito: gh repo view…", "Letto: file.txt").
 * Solo verbi+argomenti reali, mai conteggi inventati. Puro e testabile.
 */
internal fun workToolTitle(name: String, args: String): String {
    val clean = humanToolName(name, "", args).trim()
    val detail = extractFirstToolArg(args)
    fun has(vararg words: String): Boolean {
        val pattern = Regex("\\b(${words.joinToString("|") { Regex.escape(it) }})", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(clean)
    }
    val argsHasCommand = runCatching {
        org.json.JSONObject(args).has("command")
    }.getOrDefault(false)
    val verb = when {
        argsHasCommand || has("terminal", "shell", "bash", "exec", "command", "run", "script", "codice") -> "Eseguito"
        has("read", "cat", "show", "view", "lettura", "leggi") -> "Letto"
        has("ls", "dir", "find", "glob", "list", "explor", "tree") -> "Esplorato"
        has("write", "create", "save", "scriv", "crea") -> "Scritto"
        has("edit", "patch", "diff", "modif", "apply") -> "Modificato"
        has("fetch", "curl", "download", "wget", "scaric") -> "Scaricato"
        has("search", "query", "web", "cerc", "grep", "lookup") -> "Cercato"
        has("image", "vision", "analy", "screenshot", "photo") -> "Analizzato"
        has("video", "audio", "elabor") -> "Elaborato"
        else -> ""
    }
    // Nomi generici del parser senza dettaglio: mai mostrare "tool".
    if (detail.isBlank() && (clean.isBlank() || clean.equals("tool", ignoreCase = true) || clean.matches(Regex("^call_[0-9a-fA-F]+$")))) {
        return "Attività"
    }
    if (verb.isEmpty()) return clean.ifBlank { "Attività" }
    return if (detail.isNotBlank()) "$verb: $detail" else verb
}

