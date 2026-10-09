package com.nemoclaw.chat

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalMediaCancellationTest {
    @Test
    fun `cancel prima degli header chiude la chiamata senza file o apertura`() = runBlocking {
        val directory = Files.createTempDirectory("hermes-open-cancel-headers").toFile()
        try {
            ControlledSlowHttpTestServer(ByteArray(32), pauseBeforeHeaders = true).use { server ->
                val opened = AtomicInteger()
                val download = async(Dispatchers.IO) {
                    downloadAndOpenExternalMedia(Request.Builder().url(server.url).get().build(), directory, 1024L) {
                        opened.incrementAndGet()
                        true
                    }
                }

                val requestReceived = server.awaitRequest()
                if (!requestReceived && download.isCompleted) download.await()
                assertTrue("No request observed", requestReceived)
                download.cancelAndJoin()
                server.release()

                assertTrue(server.awaitClientClosed())
                assertEquals(0, opened.get())
                assertNoFilesRemain(directory)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancel durante il corpo chiude la chiamata pulisce il partial e non apre`() = runBlocking {
        val directory = Files.createTempDirectory("hermes-open-cancel-body").toFile()
        val body = ByteArray(8 * 1024 * 1024) { 7 }
        try {
            ControlledSlowHttpTestServer(body, pauseBeforeHeaders = false).use { server ->
                val opened = AtomicInteger()
                val download = async(Dispatchers.IO) {
                    downloadAndOpenExternalMedia(
                        Request.Builder().url(server.url).get().build(),
                        directory,
                        body.size.toLong()
                    ) {
                        opened.incrementAndGet()
                        true
                    }
                }

                val requestReceived = server.awaitRequest()
                if (!requestReceived && download.isCompleted) download.await()
                assertTrue("No request observed", requestReceived)
                assertTrue(server.awaitFirstBodyChunk())
                download.cancelAndJoin()
                server.release()

                assertTrue(server.awaitClientClosed())
                assertEquals(0, opened.get())
                assertNoFilesRemain(directory)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertNoFilesRemain(directory: java.io.File) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (directory.listFiles().orEmpty().isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(emptyList<java.io.File>(), directory.listFiles()?.toList().orEmpty())
    }
}
