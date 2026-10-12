package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageBrowseTest {

    @Test
    fun browseSegmentsBuildsCumulativePaths() {
        assertEquals(
            listOf("denudate" to "/home/matteo/.hermes/hub_uploads/denudate"),
            browseSegments("/home/matteo/.hermes/hub_uploads/denudate").takeLast(1)
        )
        val all = browseSegments("/a/b/c")
        assertEquals(listOf("/a", "/a/b", "/a/b/c"), all.map { it.second })
        assertEquals(listOf("a", "b", "c"), all.map { it.first })
        assertEquals(emptyList<Pair<String, String>>(), browseSegments("  "))
        assertEquals(listOf("x" to "x"), browseSegments("x"))
    }

    @Test
    fun encodeMediaPathSegmentUsesPercent20() {
        assertEquals("a%20b%2Fc.jpg", encodeMediaPathSegment("a b/c.jpg"))
        assertEquals("denudate", encodeMediaPathSegment("denudate"))
    }
}
