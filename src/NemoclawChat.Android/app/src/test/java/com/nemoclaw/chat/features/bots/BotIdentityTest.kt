package com.nemoclaw.chat.features.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BotIdentityTest {
    @Test
    fun sameDisplayNameFromDifferentConnectionsGetsStableDisambiguatedHandles() {
        val items = listOf(
            HermesBotItem(
                profile = "helper",
                displayName = "Helper",
                description = "",
                hidden = false,
                chatId = null,
                isDefault = false,
                connectionId = "desktop",
                connectionLabel = "Desktop",
                identityKey = "desktop::helper"
            ),
            HermesBotItem(
                profile = "helper",
                displayName = "Helper",
                description = "",
                hidden = false,
                chatId = null,
                isDefault = false,
                connectionId = "watch",
                connectionLabel = "Watch",
                identityKey = "watch::helper"
            )
        )

        val result = assignStableBotHandles(items)

        assertEquals(listOf("helper-desktop", "helper-watch"), result.map { it.handle })
        assertNotEquals(result[0].handle, result[1].handle)
        assertEquals(listOf("desktop::helper", "watch::helper"), result.map { it.identityKey })
    }
}
