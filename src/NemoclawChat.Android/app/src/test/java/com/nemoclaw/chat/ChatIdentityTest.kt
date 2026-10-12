package com.nemoclaw.chat

import com.nemoclaw.chat.features.bots.HermesBotItem
import com.nemoclaw.chat.features.bots.HermesBotRoster
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatIdentityTest {

    private fun bot(
        profile: String,
        displayName: String = profile,
        connectionId: String = "primary",
        hidden: Boolean = false,
        chatId: String? = null
    ) = HermesBotItem(
        profile = profile,
        displayName = displayName,
        description = "",
        hidden = hidden,
        chatId = chatId,
        isDefault = false,
        connectionId = connectionId,
        connectionLabel = "Gateway principale",
        identityKey = "$connectionId::$profile"
    )

    private fun roster(vararg bots: HermesBotItem) =
        HermesBotRoster(bots.toList(), false, false, true, "ok")

    private fun conv(id: String, key: String = "") = LocalConversation(
        id = id,
        title = "t",
        kind = "Chat",
        description = "",
        prompt = "",
        updatedAt = 0L,
        messages = emptyList(),
        botIdentityKey = key
    )

    @Test
    fun resolveChatIdentityMatchesByKey() {
        val r = roster(bot("jarvis"), bot("anna", connectionId = "remote"))
        assertEquals("jarvis", resolveChatIdentity(r, "primary::jarvis")?.profile)
        assertEquals("remote", resolveChatIdentity(r, "remote::anna")?.connectionId)
        assertNull(resolveChatIdentity(r, ""))
        assertNull(resolveChatIdentity(r, "  "))
        assertNull(resolveChatIdentity(r, "primary::ghost"))
        assertNull(resolveChatIdentity(null, "primary::jarvis"))
    }

    @Test
    fun chatIdentityFallbackLabelUsesProfile() {
        assertEquals("jarvis", chatIdentityFallbackLabel("primary::jarvis"))
        assertEquals("Hermes", chatIdentityFallbackLabel(""))
        assertEquals("Hermes", chatIdentityFallbackLabel("   "))
    }

    @Test
    fun conversationIdentityKeyPrefersStamp() {
        val r = roster(bot("jarvis"))
        assertEquals("", conversationIdentityKey(conv("conv_1"), r))
        assertEquals("primary::jarvis", conversationIdentityKey(conv("conv_1", "primary::jarvis"), r))
        // Legacy senza stamp e senza match: mai nascosta (default).
        assertEquals("", conversationIdentityKey(conv("bot-primary-xyz"), r))
    }

    @Test
    fun conversationIdentityKeyAttributesLegacyBotChatsViaRoster() {
        val b = bot("jarvis")
        val r = roster(b)
        val legacyId = com.nemoclaw.chat.features.bots.stableBotConversationId(b)
        assertEquals("primary::jarvis", conversationIdentityKey(conv(legacyId), r))
        // Senza roster resta visibile col default.
        assertEquals("", conversationIdentityKey(conv(legacyId), null))
    }

    @Test
    fun conversationsForIdentityFiltersAndKeepsOrder() {
        val r = roster(bot("jarvis"))
        val all = listOf(conv("c1"), conv("c2", "primary::jarvis"), conv("c3"), conv("c4", "remote::anna"))
        assertEquals(listOf("c1", "c3"), conversationsForIdentity(all, "", r).map { it.id })
        assertEquals(listOf("c2"), conversationsForIdentity(all, "primary::jarvis", r).map { it.id })
        assertEquals(listOf("c4"), conversationsForIdentity(all, "remote::anna", r).map { it.id })
        assertEquals(emptyList<String>(), conversationsForIdentity(all, "primary::ghost", r).map { it.id })
    }

    @Test
    fun chatIdentityOptionsSkipsHiddenAndFallsBackToProfile() {
        val r = roster(bot("jarvis", displayName = "Jarvis"), bot("ghost", displayName = "", hidden = true), bot("anna", displayName = "  "))
        val options = chatIdentityOptions(r)
        assertEquals(listOf("primary::jarvis", "primary::anna"), options.map { it.key })
        assertEquals(listOf("Jarvis", "anna"), options.map { it.label })
        assertEquals(emptyList<ChatIdentityOption>(), chatIdentityOptions(null))
    }

    @Test
    fun botIdentityKeySurvivesJsonRoundTrip() {
        // Regressione: JSONObject.NULL riletto come "null" nascondeva le
        // chat dal filtro sidebar. Blank resta blank dopo save/load.
        val all = listOf(conv("c1"), conv("c2", "primary::jarvis"))
        val back = readConversationsFromJsonArray(conversationsToJsonArray(all))
        assertEquals("", back.first { it.id == "c1" }.botIdentityKey)
        assertEquals("primary::jarvis", back.first { it.id == "c2" }.botIdentityKey)
        val legacyNull = JSONArray().put(JSONObject().put("id", "c3").put("botIdentityKey", JSONObject.NULL))
        assertEquals("", readConversationsFromJsonArray(legacyNull).single().botIdentityKey)
        val legacyMissing = JSONArray().put(JSONObject().put("id", "c4"))
        assertEquals("", readConversationsFromJsonArray(legacyMissing).single().botIdentityKey)
    }
}
