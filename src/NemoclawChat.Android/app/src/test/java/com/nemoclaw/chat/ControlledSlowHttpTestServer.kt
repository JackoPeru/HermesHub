package com.nemoclaw.chat

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class ControlledSlowHttpTestServer(
    private val body: ByteArray,
    private val pauseBeforeHeaders: Boolean
) : AutoCloseable {
    private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply {
        soTimeout = 5_000
    }
    private val requestReceived = CountDownLatch(1)
    private val firstBodyChunkSent = CountDownLatch(1)
    private val releaseResponse = CountDownLatch(1)
    private val clientClosed = CountDownLatch(1)
    private val writeFailed = CountDownLatch(1)
    private val writeFailure = AtomicReference<IOException?>()
    private val thread = Thread(::serve).apply {
        name = "hermes-slow-http-test-server"
        isDaemon = true
        start()
    }

    val url: String = "http://127.0.0.1:${server.localPort}/media"

    fun awaitRequest(): Boolean = requestReceived.await(3, TimeUnit.SECONDS)
    fun awaitFirstBodyChunk(): Boolean = firstBodyChunkSent.await(3, TimeUnit.SECONDS)
    fun release() = releaseResponse.countDown()
    fun awaitClientClosed(): Boolean = clientClosed.await(3, TimeUnit.SECONDS)
    fun awaitWriteFailure(): Boolean = writeFailed.await(5, TimeUnit.SECONDS)
    fun writeFailure(): IOException? = writeFailure.get()

    private fun serve() {
        try {
            server.accept().use { client ->
                client.soTimeout = 3_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                reader.readLine() // request line
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                requestReceived.countDown()
                if (pauseBeforeHeaders) releaseResponse.await(5, TimeUnit.SECONDS)
                val output = client.getOutputStream()
                output.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: keep-alive\r\n\r\n").toByteArray(Charsets.UTF_8)
                )
                output.flush()
                if (!pauseBeforeHeaders) {
                    val firstBytes = minOf(body.size, 8 * 1024)
                    output.write(body, 0, firstBytes)
                    output.flush()
                    firstBodyChunkSent.countDown()
                    releaseResponse.await(5, TimeUnit.SECONDS)
                }
                val inputClosed = try {
                    client.getInputStream().read() < 0
                } catch (_: SocketTimeoutException) {
                    false
                } catch (_: IOException) {
                    true
                }
                if (inputClosed) clientClosed.countDown()
                if (!pauseBeforeHeaders) {
                    try {
                        output.write(body, minOf(body.size, 8 * 1024), body.size - minOf(body.size, 8 * 1024))
                        output.flush()
                    } catch (ex: IOException) {
                        writeFailure.set(ex)
                        writeFailed.countDown()
                        clientClosed.countDown()
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            requestReceived.countDown()
        } catch (_: SocketException) {
            clientClosed.countDown()
        } catch (_: IOException) {
            clientClosed.countDown()
        } finally {
            releaseResponse.countDown()
        }
    }

    override fun close() {
        release()
        server.close()
        thread.join(1_000)
    }
}
