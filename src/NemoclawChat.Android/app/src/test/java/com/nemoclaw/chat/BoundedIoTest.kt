package com.nemoclaw.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedIoTest {
    @Test
    fun boundedCopyRejectsTheFirstByteBeyondTheLimit() {
        val output = ByteArrayOutputStream()
        assertThrows(PayloadTooLargeException::class.java) {
            ByteArrayInputStream(ByteArray(5)).copyToBounded(output, 4L)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun boundedLineRejectsAnOversizedLineWithoutReadingItAll() {
        val source = Buffer().writeUtf8("abcdefghij\n")
        assertThrows(PayloadTooLargeException::class.java) {
            source.readUtf8LineBounded(5L)
        }
    }

    @Test
    fun boundedLinePreservesTheFinalLineWithoutANewline() {
        val source = Buffer().writeUtf8("finale")
        assertEquals("finale", source.readUtf8LineBounded(16L))
        assertEquals(null, source.readUtf8LineBounded(16L))
    }

    @Test
    fun wavStreamingAcceptsAValidMinimalHeader() {
        val directory = Files.createTempDirectory("hermes-wav-test").toFile()
        val wav = ByteArray(44).apply {
            "RIFF".encodeToByteArray().copyInto(this, 0)
            "WAVE".encodeToByteArray().copyInto(this, 8)
        }
        try {
            val file = streamWavToTempFile(
                directory = directory,
                prefix = "test-",
                input = ByteArrayInputStream(wav),
                contentLength = wav.size.toLong()
            )
            assertTrue(file.isFile)
            assertEquals(44L, file.length())
        } finally {
            directory.deleteRecursively()
        }
    }
}
