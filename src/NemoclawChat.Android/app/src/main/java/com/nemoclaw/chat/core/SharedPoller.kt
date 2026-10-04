package com.nemoclaw.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nemoclaw.chat.core.WorkLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Policy di backoff esponenziale per i poller foreground. Pura e testabile.
 *
 * - failures <= 0 (successo / reset): ritorna base (capped a max).
 * - failures >= 1: base * 2^failures, capped a max, overflow-safe.
 *   Es: base 5s -> 0:5s, 1:10s, 2:20s, 3:40s, 4+:60s (con max 60s).
 */
internal fun nextPollDelay(failures: Int, base: Long, max: Long): Long {
    val safeBase = base.coerceAtLeast(0L)
    val safeMax = max.coerceAtLeast(0L)
    if (safeMax == 0L) return 0L
    if (failures <= 0) return safeBase.coerceAtMost(safeMax)
    var current = safeBase.coerceAtMost(safeMax)
    if (current == 0L) return 0L
    repeat(failures) {
        if (current >= safeMax) return safeMax
        if (current > safeMax / 2) return safeMax
        current *= 2
    }
    return current.coerceAtMost(safeMax)
}

/**
 * Confronto puro delle chiavi di restart del poller. Testabile.
 * Ritorna true se le chiavi differiscono (il poller deve riavviarsi e resettare i failure).
 */
internal fun pollKeysChanged(oldKeys: Array<out Any?>, newKeys: Array<out Any?>): Boolean {
    if (oldKeys.size != newKeys.size) return true
    for (index in oldKeys.indices) {
        if (oldKeys[index] != newKeys[index]) return true
    }
    return false
}

/**
 * Polling foreground unificato: gira SOLO a lifecycle STARTED, zero lavoro in background.
 *
 * Stesso pattern LifecycleEventObserver di ChatTopBar (niente repeatOnLifecycle,
 * non disponibile nel classpath): osserva il lifecycle, tiene [started] aggiornato
 * e il LaunchedEffect gira solo quando STARTED. Quando il lifecycle scende sotto
 * STARTED l'effect viene cancellato (nessun delay/job orfano in background).
 *
 * - [baseIntervalMs]: intervallo base a successo (equivale a nextPollDelay(0)).
 * - [maxIntervalMs]: tetto del backoff esponenziale su failure consecutive.
 * - [immediate]: se true esegue [block] subito al restart, altrimenti attende prima il base.
 * - [block]: ritorna true = successo (reset failure, prossimo delay = base),
 *   false = errore (failure++, prossimo delay = backoff). Le CancellationException
 *   vengono rilanciate (cancellazione lifecycle), le altre Exception valgono false.
 * - [keys]: chiavi di restart (come LaunchedEffect); al cambio, failure resettati.
 */
@Composable
internal fun PollWhileStarted(
    vararg keys: Any?,
    baseIntervalMs: Long,
    maxIntervalMs: Long = WorkLimits.WORK_POLL_MAX_MS,
    immediate: Boolean = true,
    block: suspend () -> Boolean
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            started = event.targetState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(started, baseIntervalMs, maxIntervalMs, immediate, *keys) {
        if (!started) return@LaunchedEffect
        var failures = 0
        if (!immediate) {
            delay(nextPollDelay(0, baseIntervalMs, maxIntervalMs))
        }
        while (isActive) {
            val ok = try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            failures = if (ok) 0 else failures + 1
            delay(nextPollDelay(failures, baseIntervalMs, maxIntervalMs))
        }
    }
}
