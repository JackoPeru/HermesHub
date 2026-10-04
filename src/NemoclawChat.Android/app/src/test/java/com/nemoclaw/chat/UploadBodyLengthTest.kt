package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadBodyLengthTest {

    @Test
    fun base64LengthIsFourPerThreeBytes() {
        // 4 byte ogni 3 di input (padding incluso), senza wrap.
        val cases = mapOf(
            0L to 0L, 1L to 4L, 2L to 4L, 3L to 4L, 4L to 8L,
            5L to 8L, 6L to 8L, 7L to 12L, 1024L to 1368L,
            570500L to 760668L
        )
        for ((raw, expected) in cases) {
            assertEquals("raw=$raw", expected, base64EncodedLength(raw))
        }
    }
}
