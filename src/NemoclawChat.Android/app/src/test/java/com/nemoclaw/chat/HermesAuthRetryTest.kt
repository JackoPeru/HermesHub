package com.nemoclaw.chat

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

class HermesAuthRetryTest {
    @Test
    fun keyedPrivateGetPreserves401AndNeverRetriesAnonymously() = runBlocking {
        FakeAuthServer().use { server ->
            val result = httpGetResponse(server.url, apiKey = "configured-secret")

            assertEquals(401, result.first)
            assertEquals(REJECTED_BODY, result.second)
            assertEquals(1, server.requests.size)
            assertEquals("GET", server.requests.single().method)
            assertEquals("Bearer configured-secret", server.requests.single().authorization)
        }
    }

    @Test
    fun keyedMutationPreserves401AndNeverRetriesAnonymously() = runBlocking {
        FakeAuthServer().use { server ->
            val result = postJson(
                url = server.url,
                payload = JSONObject().put("title", "private session"),
                apiKey = "configured-secret"
            )

            assertEquals(401, result.first)
            assertEquals(REJECTED_BODY, result.second)
            assertEquals(1, server.requests.size)
            assertEquals("POST", server.requests.single().method)
            assertEquals("Bearer configured-secret", server.requests.single().authorization)
        }
    }

    @Test
    fun keyedTransportFailureDoesNotRetryAnonymously() = runBlocking {
        FakeAuthServer(respond = false).use { server ->
            val result = httpGetResponse(server.url, apiKey = "configured-secret")

            assertEquals(0, result.first)
            assertEquals(1, server.requests.size)
            assertEquals("Bearer configured-secret", server.requests.single().authorization)
        }
    }

    @Test
    fun keyedMutationTransportFailureDoesNotRetryAnonymously() = runBlocking {
        FakeAuthServer(respond = false).use { server ->
            val result = postJson(
                url = server.url,
                payload = JSONObject().put("title", "private session"),
                apiKey = "configured-secret"
            )

            assertEquals(0, result.first)
            assertEquals(1, server.requests.size)
            assertEquals("POST", server.requests.single().method)
            assertEquals("Bearer configured-secret", server.requests.single().authorization)
        }
    }

    @Test
    fun missingCredentialKeepsOneAnonymousCompatibilityRequest() = runBlocking {
        FakeAuthServer().use { server ->
            val result = httpGetResponse(server.url)

            assertEquals(401, result.first)
            assertEquals(REJECTED_BODY, result.second)
            assertEquals(1, server.requests.size)
            assertNull(server.requests.single().authorization)
        }
    }

    private data class RecordedRequest(val method: String, val authorization: String?)

    private class FakeAuthServer(
        private val respond: Boolean = true
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 2_500
        }
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val url: String = "http://127.0.0.1:${socket.localPort}/api/sessions"
        private val thread = Thread(::serve).apply {
            name = "hermes-auth-test-server"
            isDaemon = true
            start()
        }

        private fun serve() {
            try {
                while (requests.size < 2) {
                    socket.accept().use(::recordAndRespond)
                }
            } catch (_: SocketTimeoutException) {
                // The test closes the server after the client finishes its attempt plan.
            } catch (_: SocketException) {
                // Expected when close() releases accept() after a single request.
            }
        }

        private fun recordAndRespond(client: java.net.Socket) {
            client.soTimeout = 2_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            var authorization: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.substringBefore(':').equals("Authorization", ignoreCase = true)) {
                    authorization = line.substringAfter(':').trim()
                }
            }
            requests += RecordedRequest(requestLine.substringBefore(' '), authorization)
            if (respond) {
                val body = REJECTED_BODY.toByteArray(Charsets.UTF_8)
                val headers = buildString {
                    append("HTTP/1.1 401 Authentication Required\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply {
                    write(headers)
                    write(body)
                    flush()
                }
            }
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }

    private companion object {
        const val REJECTED_BODY = "{\"error\":\"token not accepted\"}"
    }
}
