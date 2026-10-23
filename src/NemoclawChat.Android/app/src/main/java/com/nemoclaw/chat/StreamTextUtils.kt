package com.nemoclaw.chat


internal fun debugHttpLoggingInterceptor(): okhttp3.Interceptor? {
    if (!com.nemoclaw.chat.BuildConfig.DEBUG) return null
    return try {
        val clazz = Class.forName("okhttp3.logging.HttpLoggingInterceptor")
        val instance = clazz.getDeclaredConstructor().newInstance()
        val levelClass = Class.forName("okhttp3.logging.HttpLoggingInterceptor\$Level")
        val headersLevel = levelClass.enumConstants?.firstOrNull { (it as Enum<*>).name == "HEADERS" }
        if (headersLevel != null) {
            clazz.getMethod("setLevel", levelClass).invoke(instance, headersLevel)
        }
        val redactMethod = clazz.getMethod("redactHeader", String::class.java)
        redactMethod.invoke(instance, "Authorization")
        redactMethod.invoke(instance, "Cookie")
        instance as okhttp3.Interceptor
    } catch (_: Throwable) {
        null
    }
}

internal fun StringBuilder.appendBounded(text: String): Boolean {
    if (length >= STREAM_ACCUM_MAX_CHARS) return false
    val remaining = STREAM_ACCUM_MAX_CHARS - length
    if (text.length <= remaining) {
        append(text)
    } else {
        append(text, 0, remaining)
        append("\n\n[…troncato: limite ${STREAM_ACCUM_MAX_CHARS} caratteri raggiunto.]")
    }
    return true
}

internal fun validateTokensPerSecond(value: Double?): Double? {
    val v = value ?: return null
    return if (v.isFinite() && v > 0.0 && v <= 70.0) v else null
}

/**
 * Acceptance rate dello speculative decoding (llama.cpp `timings.draft_n` /
 * `draft_n_accepted`, esposti con `timings_per_token`). Solo server, mai stimato.
 */
internal fun validateDraftAcceptance(accepted: Int?, total: Int?): Double? {
    val n = total ?: return null
    val a = accepted ?: return null
    if (n <= 0 || a < 0 || a > n) return null
    return (a.toDouble() / n.toDouble()).takeIf { it.isFinite() }
}

/** Tipo spec attivo dichiarato dal server (`draft-mtp` -> `mtp`). Mai inventato: null se assente. */
internal fun normalizeSpecTypeLabel(raw: String?): String? {
    val v = raw?.trim()?.lowercase() ?: return null
    if (v.isEmpty()) return null
    val stripped = v.removePrefix("draft-").removePrefix("draft_")
    if (stripped.isEmpty() || stripped.length > 24) return null
    if (!stripped[0].isLetterOrDigit()) return null
    if (!stripped.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
    return stripped
}

internal fun formatAcceptancePart(rate: Double, label: String?): String {
    val pct = String.format(java.util.Locale.US, "%.0f", rate * 100.0)
    return if (label.isNullOrBlank()) "Accept $pct%" else "Accept $pct% ($label)"
}

