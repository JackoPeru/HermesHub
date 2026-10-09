package com.nemoclaw.chat

internal const val HERMES_HUB_ANDROID_SURFACE = "android-app"

internal fun hermesAuthRetryCandidates(apiKey: String?): List<String> {
    val candidates = linkedSetOf<String>()
    apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let(candidates::add)
    return candidates.toList()
}

@Suppress("UNUSED_PARAMETER")
internal fun hermesAuthCandidates(apiKey: String?, allowCompatAuth: Boolean = true): List<String?> {
    val configured = apiKey?.trim()?.takeIf { it.isNotEmpty() }
    // Una credenziale configurata resta vincolata alla richiesta: 401 o errore
    // di trasporto non autorizzano mai il retry anonimo. Senza chiave resta un
    // solo tentativo anonimo per conservare la compatibilita' preesistente.
    return hermesAuthRetryCandidates(configured).ifEmpty { listOf(null) }
}

/**
 * Fail-closed per profilo nominato (rif. breaking change luglio 2026):
 * la default key non deve mai essere riusata su /p/<profile>/.
 * Per richieste profilo: solo credenziale esplicita, nessun fallback null/compat.
 */
internal fun hermesProfileAuthCandidates(apiKey: String?, profile: String?): List<String?> {
    if (profile.isNullOrBlank()) return hermesAuthCandidates(apiKey, allowCompatAuth = true)
    val configured = apiKey?.trim()?.takeIf { it.isNotEmpty() }
    // Profilo nominato senza key propria -> fail-closed (non inviare, il server rispondera' 401).
    // Non includere mai null: evita riuso accidentale default key su prefisso /p/.
    return listOf(configured)
}

internal fun isValidHermesSessionKey(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    if (value.length > 256) return false
    return value.none { it == '\r' || it == '\n' || it == '\u0000' }
}

internal fun shouldUseResponsesFirst(settings: AppSettings, mode: String): Boolean {
    if (settings.preferredApi.equals("hermes-native", ignoreCase = true)) return true
    return settings.preferredApi.equals("openai-responses", ignoreCase = true) &&
        mode.equals("Agente", ignoreCase = true)
}

internal fun isHermesNative(settings: AppSettings): Boolean {
    return settings.preferredApi.equals("hermes-native", ignoreCase = true)
}

internal fun shouldRetryHermesWithBearerAuth(code: Int, body: String): Boolean {
    return code == 401
}

/**
 * Classifica se il piano può proseguire dopo un 401 prima dell'accettazione.
 * Token nullo o errore esplicito della chiave chiudono il retry. Un 401
 * generico può proseguire verso un'altra route configurata, ma non aggiunge
 * credenziali: hermesAuthCandidates mantiene la chiave configurata come unico
 * candidato, quindi il retry non degrada all'anonimo.
 */
internal fun shouldRetryHermesWithBearerAuth(code: Int, body: String, token: String?): Boolean {
    if (code != 401) return false
    if (token.isNullOrBlank()) return false
    val lower = body.lowercase()
    if (lower.contains("invalid_api_key") ||
        lower.contains("invalid api key") ||
        lower.contains("invalidapikey") ||
        lower.contains("unauthorized")
    ) return false
    return true
}

internal fun isHermesAuthError(message: String?): Boolean {
    val normalized = message?.lowercase().orEmpty()
    return normalized.contains("401") ||
        normalized.contains("api key rifiutata") ||
        normalized.contains("key rifiutata") ||
        normalized.contains("invalid api key") ||
        normalized.contains("invalid_api_key") ||
        normalized.contains("invalidapikey")
}

internal fun isRecoverablePreviousResponseError(message: String?): Boolean {
    val normalized = message?.lowercase().orEmpty()
    return isHermesAuthError(message) ||
        normalized.contains("previous_response_id") ||
        normalized.contains("previous response") ||
        normalized.contains("conversation") ||
        normalized.contains("response not found") ||
        normalized.contains("not found")
}

internal fun hermesHubServerConversationId(surface: String, conversationId: String?): String? {
    val localId = conversationId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (localId.startsWith("hermes-hub:", ignoreCase = true)) return localId
    val safeSurface = surface.trim().lowercase().replace(Regex("""[^a-z0-9_.-]+"""), "-").ifBlank { "unknown" }
    val safeLocal = localId.replace(Regex("""[^A-Za-z0-9_.:-]+"""), "-")
    return "hermes-hub:$safeSurface:$safeLocal"
}
