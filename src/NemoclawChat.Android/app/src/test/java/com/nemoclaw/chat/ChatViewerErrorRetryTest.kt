package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Viewer chat (ChatInlineImage/RemoteGalleryImage) sulla stessa UX di RemoteStreamImage:
 * loading, errore esplicito con Riprova, tentativi esauriti. JVM puro sulla macchina a stati.
 */
class ChatViewerErrorRetryTest {

    @Test
    fun viewerStartsLoadingWithoutRetry() {
        val initial = remoteImageLoadInitial()
        assertEquals(RemoteImageLoadPhase.Loading, initial.phase)
        assertEquals(0, initial.attempts)
        assertFalse(initial.canRetry)
    }

    @Test
    fun viewerFailureShowsErrorWithRetry() {
        val failed = remoteImageLoadFailed(remoteImageLoadInitial(), "download fallito o formato non valido")
        assertEquals(RemoteImageLoadPhase.Failed, failed.phase)
        assertEquals(1, failed.attempts)
        assertEquals("download fallito o formato non valido", failed.errorMessage)
        assertTrue(failed.canRetry)
    }

    @Test
    fun viewerTimeoutMessageIsExplicit() {
        val timeoutText = "timeout dopo ${REMOTE_IMAGE_LOAD_TIMEOUT_MS / 1000}s"
        val failed = remoteImageLoadFailed(remoteImageLoadInitial(), timeoutText)
        assertTrue(failed.errorMessage?.contains("timeout", ignoreCase = true) == true)
        assertTrue(failed.canRetry)
        assertEquals(30_000L, REMOTE_IMAGE_LOAD_TIMEOUT_MS)
    }

    @Test
    fun viewerRetryReturnsToLoading() {
        val failed = remoteImageLoadFailed(remoteImageLoadInitial(), "ko")
        val retrying = remoteImageLoadRetrying(failed)
        assertEquals(RemoteImageLoadPhase.Loading, retrying.phase)
        assertEquals(1, retrying.attempts)
    }

    @Test
    fun viewerRetriesExhausted() {
        var state = remoteImageLoadInitial()
        repeat(REMOTE_IMAGE_MAX_RETRIES) {
            state = remoteImageLoadFailed(state, "ko")
            assertTrue(state.canRetry)
            state = remoteImageLoadRetrying(state)
        }
        state = remoteImageLoadFailed(state, "ko")
        assertEquals(REMOTE_IMAGE_MAX_RETRIES + 1, state.attempts)
        assertFalse(state.canRetry)
        assertEquals(state, remoteImageLoadRetrying(state))
    }

    @Test
    fun viewerSuccessClearsError() {
        val failed = remoteImageLoadFailed(remoteImageLoadInitial(), "ko")
        val done = remoteImageLoadSucceeded(failed)
        assertEquals(RemoteImageLoadPhase.Loaded, done.phase)
        assertNull(done.errorMessage)
        assertFalse(done.canRetry)
    }
}
