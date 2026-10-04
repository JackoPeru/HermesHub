package com.nemoclaw.chat.core

/**
 * Limiti e magic numbers centralizzati per il background work Hermes.
 *
 * Solo duplicazione di valori gia esistenti nei call-site: nessuna logica,
 * nessun cambio di comportamento. I file vietati dallo scope
 * (HermesRuns, ActiveWorkStore, HermesWorkService, ChatStream, ChatMediaViewer,
 * ChatMediaBlocks, AppRoot, MainActivity, ChatViewModel, LocalBackupExporter)
 * mantengono le loro costanti locali: qui sono solo documentate/mirrorate.
 */
object WorkLimits {
    /** ID notifica foreground di HermesWorkService (HermesWorkService.NOTIFICATION_ID, file vietato: non migrato). */
    const val HERMES_WORK_NOTIFICATION_ID = 8644

    /** Poll background work: base (mirror di HermesWorkService.POLL_BASE_MS). */
    const val WORK_POLL_BASE_MS = 10_000L

    /** Poll background work: tetto backoff (mirror di HermesWorkService.POLL_MAX_MS). */
    const val WORK_POLL_MAX_MS = 60_000L

    /** Timeout run poll (mirror di ChatStream.RUN_POLL_TIMEOUT_MS, file vietato: non migrato). */
    const val RUN_POLL_TIMEOUT_MS = 60 * 60 * 1000L

    /** Max failure consecutivi run poll (mirror di ChatStream, file vietato: non migrato). */
    const val RUN_POLL_MAX_CONSECUTIVE_FAILURES = 5

    /** Watchdog inattivita SSE 90s (mirror di ChatStream.SSE_INACTIVITY_TIMEOUT_MS, gia costante: non migrato). */
    const val SSE_INACTIVITY_TIMEOUT_MS = 90_000L

    /** Check inattivita SSE (mirror di ChatStream.SSE_INACTIVITY_CHECK_MS, file vietato: non migrato). */
    const val SSE_INACTIVITY_CHECK_MS = 10_000L

    /** Timeout richiesta TTS 90s (canonico: HubOperations lo riusa, era gia costante con identico valore). */
    const val TTS_REQUEST_TIMEOUT_MS = 90_000L

    // Troncamenti stringa .take(N) per UI/notifiche/log. Solo i 6 valori dello scope.
    const val TRUNC_60 = 60
    const val TRUNC_80 = 80
    const val TRUNC_120 = 120
    const val TRUNC_140 = 140
    const val TRUNC_220 = 220
    const val TRUNC_300 = 300

    // Auto-approvazione run: "off" (chiedi sempre), "session", "always". Mai "deny" automatico.
    const val AUTO_APPROVE_OFF = "off"
    const val AUTO_APPROVE_SESSION = "session"
    const val AUTO_APPROVE_ALWAYS = "always"

    /** Modi validi per l'auto-approvazione (canonico: AppSettings lo riusa). */
    val AUTO_APPROVE_MODES: Set<String> = setOf(AUTO_APPROVE_OFF, AUTO_APPROVE_SESSION, AUTO_APPROVE_ALWAYS)
}
