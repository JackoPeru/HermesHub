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

    @Test
    fun coerceImageZoomClamps() {
        assertEquals(1f, coerceImageZoom(1f))
        assertEquals(1f, coerceImageZoom(0.2f))
        assertEquals(1f, coerceImageZoom(Float.NaN))
        assertEquals(6f, coerceImageZoom(99f))
        assertEquals(2.5f, coerceImageZoom(2.5f))
    }

    @Test
    fun galleryCounterLabelIsOneBasedAndSafe() {
        assertEquals("3 / 12", galleryCounterLabel(2, 12))
        assertEquals("1 / 1", galleryCounterLabel(0, 1))
        assertEquals("1 / 5", galleryCounterLabel(-3, 5))
        assertEquals("5 / 5", galleryCounterLabel(99, 5))
        assertEquals("", galleryCounterLabel(0, 0))
    }

    @Test
    fun defaultImageStartPathFallsBackToDenudate() {
        assertEquals("/a/b", defaultImageStartPath("  /a/b  ", listOf("/x")))
        assertEquals("/x", defaultImageStartPath("   ", listOf("/x", "")))
        assertEquals("~/.hermes/hub_uploads/denudate", defaultImageStartPath("", emptyList()))
        assertEquals("~/.hermes/hub_uploads/denudate", defaultImageStartPath("   ", listOf("  ")))
    }

    @Test
    fun isBrowsableImageKeepsOnlyPhotos() {
        assertEquals(true, isBrowsableImage("a.jpg", ""))
        assertEquals(true, isBrowsableImage("A.PNG", ""))
        assertEquals(true, isBrowsableImage("19.webp", "image/webp"))
        assertEquals(true, isBrowsableImage("senza-estensione", "image/jpeg"))
        assertEquals(true, isBrowsableImage("foto.heic", ""))
        assertEquals(false, isBrowsableImage("video.mp4", ""))
        assertEquals(false, isBrowsableImage("doc.pdf", "application/pdf"))
        assertEquals(false, isBrowsableImage("note.txt", ""))
        assertEquals(false, isBrowsableImage("senza-estensione", ""))
        assertEquals(false, isBrowsableImage("senza-estensione", "video/mp4"))
    }
}
