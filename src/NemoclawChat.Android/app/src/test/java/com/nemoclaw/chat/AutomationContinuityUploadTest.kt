package com.nemoclaw.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutomationContinuityUploadTest {
    @Test
    fun `bounded continuity stream reaches callback and removes temp file`() = runBlocking {
        val directory = Files.createTempDirectory("continuity-upload-test").toFile()
        val tempFile = File(directory, "continuity.tmp")
        val payload = "bounded file".encodeToByteArray()
        var uploadCalls = 0

        try {
            val result = withBoundedContinuityTempFile(
                tempFile = tempFile,
                openInput = { ByteArrayInputStream(payload) },
                maxBytes = payload.size.toLong()
            ) { stagedFile ->
                uploadCalls++
                assertTrue(stagedFile.isFile)
                assertTrue(stagedFile.readBytes().contentEquals(payload))
                "uploaded"
            }

            assertEquals("uploaded", result)
            assertEquals(1, uploadCalls)
            assertFalse(tempFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `over-limit continuity stream skips callback and removes temp file`() {
        val directory = Files.createTempDirectory("continuity-upload-test").toFile()
        val tempFile = File(directory, "continuity.tmp")
        var uploadCalls = 0

        try {
            assertThrows(PayloadTooLargeException::class.java) {
                runBlocking {
                    withBoundedContinuityTempFile(
                        tempFile = tempFile,
                        openInput = { ByteArrayInputStream(ByteArray(5)) },
                        maxBytes = 4L
                    ) {
                        uploadCalls++
                        "uploaded"
                    }
                }
            }
            assertEquals(0, uploadCalls)
            assertFalse(tempFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancellation after read propagates before write and upload and cleans temp file`() = runBlocking {
        val output = ByteArrayOutputStream()
        val directCopyJob = Job()
        val directCopyInput = object : InputStream() {
            override fun read(): Int = -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer[offset] = 1
                directCopyJob.cancel()
                return 1
            }
        }
        assertThrows(CancellationException::class.java) {
            directCopyInput.copyToBounded(output, 4L) { directCopyJob.ensureActive() }
        }
        assertEquals(0, output.size())

        val directory = Files.createTempDirectory("continuity-upload-test").toFile()
        val tempFile = File(directory, "continuity.tmp")
        val parentJob = coroutineContext[Job]
        val copyJob = Job(parentJob)
        var uploadCalls = 0
        val cancelledInput = object : InputStream() {
            override fun read(): Int = -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer[offset] = 1
                copyJob.cancel()
                return 1
            }
        }

        try {
            try {
                withContext(copyJob) {
                    withBoundedContinuityTempFile(
                        tempFile = tempFile,
                        openInput = { cancelledInput },
                        maxBytes = 4L
                    ) {
                        uploadCalls++
                        "uploaded"
                    }
                }
                throw AssertionError("cancellation must propagate")
            } catch (_: CancellationException) {
                // Expected: cancellation must not become an upload result.
            }
            assertEquals(0, uploadCalls)
            assertFalse(tempFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancelling slow HTTP upload closes call and skips publish`() = runBlocking {
        val server = ControlledSlowHttpTestServer(ByteArray(16 * 1024 * 1024), pauseBeforeHeaders = false)
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(server.url)
            .post(ByteArray(0).toRequestBody())
            .build()
        val publishCalls = AtomicInteger()

        try {
            val upload = launch(Dispatchers.IO) {
                continuityUploadStatus {
                    uploadContinuityRequest(client, request) {
                        publishCalls.incrementAndGet()
                        "published"
                    }
                }
            }

            assertTrue(server.awaitRequest())
            assertTrue(server.awaitFirstBodyChunk())
            upload.cancelAndJoin()
            server.release()

            assertTrue(server.awaitClientClosed())
            assertTrue(server.awaitWriteFailure())
            assertTrue(server.writeFailure() is IOException)
            assertEquals(0, publishCalls.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `oversized HTTP response returns visible error and skips publish`() = runBlocking {
        val server = LocalHttpTestServer(ByteArray(MAX_JSON_RESPONSE_BYTES.toInt() + 1))
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(server.url("/v1/media/upload"))
            .post(ByteArray(0).toRequestBody())
            .build()
        val publishCalls = AtomicInteger()

        try {
            val result = continuityUploadStatus {
                uploadContinuityRequest(client, request) {
                    publishCalls.incrementAndGet()
                    "published"
                }
            }

            assertEquals("Errore upload file: risposta troppo grande.", result)
            assertEquals("POST", server.awaitRequest().method)
            assertEquals(0, publishCalls.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `display name query error returns visible status without crashing`() = runBlocking {
        val result = continuityUploadStatus {
            continuityDisplayName { throw IOException("query failed") }
        }

        assertEquals("Errore upload file.", result)
    }
}
