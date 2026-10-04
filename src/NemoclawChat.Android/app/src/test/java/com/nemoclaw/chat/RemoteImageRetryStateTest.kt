package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Immagini remote: euristica ristretta + macchina a stati con errore e retry
 * (niente "caricamento..." infinito).
 */
class RemoteImageRetryStateTest {

    @Test
    fun strictHeuristicAcceptsImageExtensions() {
        assertTrue(isStrictRemoteImageUrl("https://example.com/foto.JPG?x=1#y"))
        assertTrue(isStrictRemoteImageUrl("https://example.com/a/b.webp"))
        assertTrue(isStrictRemoteImageUrl("https://example.com/img.jpeg"))
        assertTrue(isStrictRemoteImageUrl("https://example.com/i.png"))
        assertTrue(isStrictRemoteImageUrl("https://example.com/a.gif"))
        assertTrue(isStrictRemoteImageUrl("https://example.com/a.bmp"))
    }

    @Test
    fun strictHeuristicAcceptsMediaOrProxyPaths() {
        assertTrue(isStrictRemoteImageUrl("https://gw:8642/v1/media/abc123"))
        assertTrue(isStrictRemoteImageUrl("https://cdn.example.com/proxy/xyz"))
    }

    @Test
    fun strictHeuristicRejectsOverlyPermissiveMatches() {
        // La vecchia euristica accettava queste per sottostringhe generiche
        // ("image", "photo") o host noti: ora non sono piu' immagini.
        assertFalse(isStrictRemoteImageUrl("https://example.com/my-photo-gallery"))
        assertFalse(isStrictRemoteImageUrl("https://example.com/image?id=42"))
        assertFalse(isStrictRemoteImageUrl("https://picsum.photos/200"))
        assertFalse(isStrictRemoteImageUrl("https://images.unsplash.com/profile"))
        assertFalse(isStrictRemoteImageUrl("https://example.com/article"))
    }

    @Test
    fun strictHeuristicRejectsNonHttps() {
        assertFalse(isStrictRemoteImageUrl("http://example.com/foto.png"))
        assertFalse(isStrictRemoteImageUrl("/v1/media/abc123"))
        assertFalse(isStrictRemoteImageUrl(""))
        assertFalse(isStrictRemoteImageUrl("not a url"))
    }

    @Test
    fun loadStateStartsLoadingWithoutRetry() {
        val initial = remoteImageLoadInitial()
        assertEquals(RemoteImageLoadPhase.Loading, initial.phase)
        assertEquals(0, initial.attempts)
        assertFalse(initial.canRetry)
    }

    @Test
    fun failureSurfacesErrorWithRetry() {
        val failed = remoteImageLoadFailed(remoteImageLoadInitial(), "timeout dopo 30s")
        assertEquals(RemoteImageLoadPhase.Failed, failed.phase)
        assertEquals(1, failed.attempts)
        assertEquals("timeout dopo 30s", failed.errorMessage)
        assertTrue(failed.canRetry)
    }

    @Test
    fun failureWithoutDetailStillExplains() {
        assertEquals("download fallito", remoteImageLoadFailed(remoteImageLoadInitial(), null).errorMessage)
        assertEquals("download fallito", remoteImageLoadFailed(remoteImageLoadInitial(), "  ").errorMessage)
    }

    @Test
    fun retryReturnsToLoadingKeepingAttempts() {
        val failed = remoteImageLoadFailed(remoteImageLoadInitial(), "ko")
        val retrying = remoteImageLoadRetrying(failed)
        assertEquals(RemoteImageLoadPhase.Loading, retrying.phase)
        assertEquals(1, retrying.attempts)
    }

    @Test
    fun retriesAreExhaustedAfterMaxAttempts() {
        var state = remoteImageLoadInitial()
        repeat(REMOTE_IMAGE_MAX_RETRIES) {
            state = remoteImageLoadFailed(state, "ko")
            assertTrue(state.canRetry)
            state = remoteImageLoadRetrying(state)
            assertEquals(RemoteImageLoadPhase.Loading, state.phase)
        }
        // Un ulteriore fallimento esaurisce i retry: niente loop infinito.
        state = remoteImageLoadFailed(state, "ko")
        assertEquals(RemoteImageLoadPhase.Failed, state.phase)
        assertEquals(REMOTE_IMAGE_MAX_RETRIES + 1, state.attempts)
        assertFalse(state.canRetry)
        assertEquals(state, remoteImageLoadRetrying(state))
    }

    @Test
    fun successClearsError() {
        val failed = remoteImageLoadFailed(remoteImageLoadInitial(), "ko")
        val done = remoteImageLoadSucceeded(failed)
        assertEquals(RemoteImageLoadPhase.Loaded, done.phase)
        assertNull(done.errorMessage)
        assertFalse(done.canRetry)
    }
}
