package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualBlocksContractTest {
    @Test
    fun `v1 parser accepts declarative blocks and keeps unknown blocks forward compatible`() {
        val blocks = extractVisualBlocks(
            """
            {
              "output_text":"Risposta finale sempre visibile.",
              "visual_blocks_version":1,
              "visual_blocks":[
                {"id":"metric-1","type":"metric","value":100,"unit":"%"},
                {"id":"progress-1","type":"progress","progress":0.5,"status":"active"},
                {"id":"approval-1","type":"approval","status":"pending","action":"Confermare"},
                {"id":"device-1","type":"device","device_name":"Ray-Ban Meta","device_status":"connected"},
                {"id":"future-1","type":"future_widget","arbitrary":{"not":"rendered"}}
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("metric", "progress", "approval", "device", "unknown_block"),
            blocks.map { it.type }
        )
        assertEquals("100", blocks[0].value)
        assertEquals(0.5, blocks[1].progressValue)
        assertEquals("pending", blocks[2].status)
        assertEquals("Ray-Ban Meta", blocks[3].deviceName)
        assertTrue(blocks.last().rawJson.contains("future_widget"))
    }

    @Test
    fun `agent document kind upgrades to image on png evidence`() {
        val blocks = extractVisualBlocks(
            """
            {
              "visual_blocks":[
                {"id":"m1","type":"media_file","media_kind":"document","media_url":"/v1/media/tramonto.png","filename":"tramonto","mime_type":""}
              ]
            }
            """.trimIndent()
        )
        assertEquals(1, blocks.size)
        assertEquals("image", blocks[0].mediaKind)
        assertEquals("image/png", blocks[0].mimeType)
    }

    @Test
    fun `duplicate agent and inline blocks collapse to one card`() {
        val agent = VisualBlock(id = "a1", type = "media_file", filename = "tramonto", mediaUrl = "/v1/media/tramonto.png", mediaKind = "document")
        val inline = VisualBlock(id = "a2", type = "media_file", filename = "tramonto.png", mediaUrl = "/v1/media/tramonto.png", mediaKind = "image")
        val merged = dedupeVisualBlocks(listOf(agent), listOf(inline))
        assertEquals(1, merged.size)
        assertEquals("image", merged[0].mediaKind)
        val absolute = VisualBlock(id = "a3", type = "media_file", filename = "x.png", mediaUrl = "http://gw:8642/v1/media/tramonto.png", mediaKind = "image")
        assertEquals(1, dedupeVisualBlocks(listOf(agent), listOf(absolute)).size)
        val other = VisualBlock(id = "a4", type = "media_file", filename = "altro.png", mediaUrl = "/v1/media/altro.png", mediaKind = "image")
        assertEquals(2, dedupeVisualBlocks(listOf(agent), listOf(other)).size)
    }
}
