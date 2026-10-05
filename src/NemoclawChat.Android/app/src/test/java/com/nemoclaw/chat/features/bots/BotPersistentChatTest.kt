package com.nemoclaw.chat.features.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotPersistentChatTest {
    private fun item(
        profile: String,
        connectionId: String = "primary",
        chatId: String? = null
    ) = HermesBotItem(
        profile = profile,
        displayName = profile,
        description = "",
        hidden = false,
        chatId = chatId,
        isDefault = false,
        connectionId = connectionId,
        connectionLabel = connectionId,
        identityKey = "$connectionId::$profile"
    )

    @Test
    fun serverChatIdWinsOverProfileFallback() {
        val id = stableBotConversationId(item("helper", chatId = "abc-123"))
        assertEquals("botchat-primary-abc-123", id)
    }

    @Test
    fun sameChatIdOnDifferentConnectionsStaysSeparate() {
        val a = stableBotConversationId(item("helper", "primary", chatId = "srv-1"))
        val b = stableBotConversationId(item("helper", "remote1", chatId = "srv-1"))
        assertNotEquals(a, b)
    }

    @Test
    fun fallbackIsConnectionPlusNormalizedProfile() {
        val id = stableBotConversationId(item("helper-bot", "remote1"))
        assertEquals("bot-remote1-helper-bot", id)
    }

    @Test
    fun invalidProfileNeverThrowsAndStaysStable() {
        val bot = item("Helper Bot!?", "primary")
        val first = stableBotConversationId(bot)
        assertEquals(first, stableBotConversationId(bot))
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun sameBotAlwaysMapsToSameId() {
        val bot = item("helper", chatId = "srv-1")
        assertEquals(stableBotConversationId(bot), stableBotConversationId(bot))
    }

    @Test
    fun differentBotsMapToDifferentIds() {
        val a = stableBotConversationId(item("alpha", "primary"))
        val b = stableBotConversationId(item("beta", "primary"))
        val c = stableBotConversationId(item("alpha", "remote1"))
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun unsafeCharsAreStripped() {
        val id = stableBotConversationId(item("a/b c.d", "../evil", chatId = "x/y z!"))
        assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
