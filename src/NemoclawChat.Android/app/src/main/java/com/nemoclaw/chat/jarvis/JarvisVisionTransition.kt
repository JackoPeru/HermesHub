package com.nemoclaw.chat.jarvis

import kotlinx.coroutines.CancellationException

/** Local camera state changes before a remote pause request; resume stays remote-first. */
internal suspend fun applyJarvisVisionTransition(
    paused: Boolean,
    currentState: () -> JarvisUiState,
    updateState: (JarvisUiState) -> Unit,
    isCurrentSession: () -> Boolean,
    pauseSource: suspend () -> Unit,
    resumeSource: suspend () -> Unit,
    stopUploader: suspend () -> Unit,
    startUploader: () -> Unit,
    clearPendingFrames: suspend () -> Unit,
    patchGateway: suspend () -> Unit,
    onError: (Throwable) -> Unit
) {
    suspend fun cleanupStaleSession() {
        for (action in listOf(pauseSource, stopUploader, clearPendingFrames)) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Session is already stale; attempt every local cleanup independently.
            }
        }
    }

    if (!isCurrentSession()) return
    if (paused) {
        updateState(currentState().copy(phase = JarvisPhase.PAUSED, visionActive = false, error = null))
        var firstFailure: Exception? = null
        suspend fun attemptLocal(action: suspend () -> Unit) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
            }
        }
        attemptLocal(pauseSource)
        attemptLocal(stopUploader)
        attemptLocal(clearPendingFrames)
        if (isCurrentSession()) attemptLocal(patchGateway)
        if (isCurrentSession()) firstFailure?.let(onError)
        return
    }

    try {
        patchGateway()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (isCurrentSession()) onError(error)
        return
    }
    if (!isCurrentSession()) return
    try {
        resumeSource()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (isCurrentSession()) onError(error)
        return
    }
    if (!isCurrentSession()) {
        cleanupStaleSession()
        return
    }
    startUploader()
    if (!isCurrentSession()) {
        cleanupStaleSession()
        return
    }
    updateState(currentState().copy(phase = JarvisPhase.ACTIVE, visionActive = true, error = null))
}
