package com.nemoclaw.chat

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class LocalHttpTestServer(
    private val body: ByteArray,
    private val contentType: String = "application/json",
    private val status: String = "200 OK"
) : AutoCloseable {
    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply {
        soTimeout = 5_000
    }
    private val received = CountDownLatch(1)
    private val requestRef = AtomicReference<RecordedRequest?>()
    private val thread = Thread(::serve).apply {
        name = "hermes-http-test-server"
        isDaemon = true
        start()
    }

    val origin: String = "http://127.0.0.1:${socket.localPort}"
    fun url(path: String = "/"): String = origin + if (path.startsWith('/')) path else "/$path"

    fun awaitRequest(): RecordedRequest {
        check(received.await(3, TimeUnit.SECONDS)) { "Client did not reach local HTTP server" }
        return checkNotNull(requestRef.get())
    }

    private fun serve() {
        try {
            socket.accept().use { client ->
                client.soTimeout = 3_000
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
                requestRef.set(
                    RecordedRequest(
                        method = requestLine.substringBefore(' '),
                        path = requestLine.split(' ').getOrNull(1).orEmpty(),
                        authorization = authorization
                    )
                )
                received.countDown()
                val headers = buildString {
                    append("HTTP/1.1 $status\r\n")
                    append("Content-Type: $contentType\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply {
                    write(headers)
                    write(body)
                    flush()
                }
            }
        } catch (_: SocketTimeoutException) {
            received.countDown()
        } catch (_: SocketException) {
            // Expected when close() releases accept() after a completed request.
        }
    }

    override fun close() {
        socket.close()
        thread.join(1_000)
    }

    internal data class RecordedRequest(val method: String, val path: String, val authorization: String?)
}
