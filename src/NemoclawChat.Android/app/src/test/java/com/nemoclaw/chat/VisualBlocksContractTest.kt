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
}
