package com.nemoclaw.chat.jarvis

import com.nemoclaw.chat.LocalHttpTestServer
import com.nemoclaw.chat.PayloadTooLargeException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisGatewayBoundsTest {
    @Test
    fun `risposta JSON Jarvis oltre il limite viene rifiutata`() {
        val oversized = ("{" + "x".repeat(600 * 1024) + "}").toByteArray()
        LocalHttpTestServer(oversized).use { server ->
            val api = JarvisGatewayApi(server.origin, null)
            val error = runCatching { runBlocking { api.capabilities() } }.exceptionOrNull()
            assertTrue("Expected bounded-body error, got $error", error is PayloadTooLargeException)
        }
    }

    @Test
    fun `riga SSE Jarvis oltre il limite chiude il flusso con errore`() {
        val body = ("data: " + "x".repeat(300 * 1024) + "\n\n").toByteArray()
        LocalHttpTestServer(body, contentType = "text/event-stream").use { server ->
            val api = JarvisGatewayApi(server.origin, null)
            val error = runCatching {
                runBlocking { api.events("session-1").toList() }
            }.exceptionOrNull()
            assertTrue("Expected bounded SSE error, got $error", error is PayloadTooLargeException)
        }
    }

    @Test
    fun `evento SSE Jarvis aggregato oltre il limite chiude il flusso`() {
        val body = buildString {
            repeat(9) { append("data: ").append("x".repeat(240 * 1024)).append('\n') }
            append('\n')
        }.toByteArray()
        LocalHttpTestServer(body, contentType = "text/event-stream").use { server ->
            val api = JarvisGatewayApi(server.origin, null)
            val error = runCatching {
                runBlocking { api.events("session-1").toList() }
            }.exceptionOrNull()
            assertTrue("Expected bounded SSE aggregate error, got $error", error is PayloadTooLargeException)
        }
    }
}
