package com.nemoclaw.chat

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class VideoSourceProbeTest {

    private fun serverFor(status: Int, onRequest: (com.sun.net.httpserver.HttpExchange) -> Unit = {}): com.sun.net.httpserver.HttpServer {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            onRequest(exchange)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        return server
    }

    @Test
    fun `probe returns server code without downloading`() {
        val server = serverFor(206)
        try {
            val code = runBlocking {
                probeVideoSource("http://127.0.0.1:${server.address.port}/v.mp4", emptyMap())
            }
            assertEquals(206, code)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `probe surfaces auth failure and forwards headers`() {
        var seenAuth: String? = null
        var seenRange: String? = null
        val server = serverFor(401) { exchange ->
            seenAuth = exchange.requestHeaders.getFirst("Authorization")
            seenRange = exchange.requestHeaders.getFirst("Range")
        }
        try {
            val code = runBlocking {
                probeVideoSource(
                    "http://127.0.0.1:${server.address.port}/v.mp4",
                    mapOf("Authorization" to "Bearer K")
                )
            }
            assertEquals(401, code)
            assertEquals("Bearer K", seenAuth)
            assertEquals("bytes=0-0", seenRange)
        } finally {
            server.stop(0)
        }
        assertTrue(isAuthPlaybackFailure(401))
        assertTrue(isAuthPlaybackFailure(403))
        assertTrue(!isAuthPlaybackFailure(206))
        assertTrue(!isAuthPlaybackFailure(null))
    }

    @Test
    fun `probe returns null when unreachable`() {
        val code = runBlocking {
            probeVideoSource("http://127.0.0.1:9/v.mp4", emptyMap(), timeoutMs = 1000)
        }
        assertNull(code)
    }
}

@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class PlaybackHttpStatusTest {

    @Test
    fun `walker extracts http code from player error chain`() {
        val httpError = HttpDataSource.InvalidResponseCodeException(
            401,
            "Unauthorized",
            null,
            emptyMap(),
            DataSpec(android.net.Uri.parse("http://127.0.0.1/v.mp4")),
            ByteArray(0)
        )
        val wrapped = PlaybackException("boom", httpError, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(401, playbackHttpStatus(wrapped))
        assertEquals(null, playbackHttpStatus(PlaybackException("x", null, PlaybackException.ERROR_CODE_TIMEOUT)))
    }
}
