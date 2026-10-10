package com.nemoclaw.chat.features.bots

import com.nemoclaw.chat.HermesSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class BotMenuHelpersTest {
    private fun item(profile: String, connectionId: String = "primary") = HermesBotItem(
        profile = profile,
        displayName = profile,
        description = "",
        hidden = false,
        chatId = null,
        isDefault = false,
        connectionId = connectionId,
        connectionLabel = connectionId,
        identityKey = "$connectionId::$profile"
    )

    @Test
    fun pinsFloatToTopPreservingServerOrder() {
        val items = listOf(item("a"), item("b"), item("c"))
        val sorted = sortBotsForRoster(items, setOf("primary::c", "primary::a"))
        // Stabile: tra i fissati resta l'ordine server (a prima di c).
        assertEquals(listOf("a", "c", "b"), sorted.map { it.profile })
    }

    @Test
    fun noPinsKeepsOrder() {
        val items = listOf(item("a"), item("b"))
        assertEquals(items, sortBotsForRoster(items, emptySet()))
    }

    @Test
    fun groupsFollowSectionOrderThenUnassigned() {
        val items = listOf(item("a"), item("b"), item("c"))
        val sections = BotSections(
            order = listOf("Lavoro", "Casa"),
            assign = mapOf("primary::b" to "Casa", "primary::a" to "Lavoro")
        )
        val grouped = groupBotsBySection(items, sections)
        assertEquals(
            listOf("Lavoro" to listOf("a"), "Casa" to listOf("b"), null to listOf("c")),
            grouped.map { it.first to it.second.map { b -> b.profile } }
        )
    }

    @Test
    fun unknownSectionFallsBackToUnassigned() {
        val items = listOf(item("a"))
        val sections = BotSections(order = listOf("Lavoro"), assign = mapOf("primary::a" to "Cancellata"))
        val grouped = groupBotsBySection(items, sections)
        assertEquals(1, grouped.size)
        assertNull(grouped[0].first)
    }

    @Test
    fun sectionNamesAreSanitized() {
        assertEquals("La mia sezione", sanitizeSectionName("  La   mia  sezione  "))
        assertEquals("", sanitizeSectionName("   "))
        assertEquals(40, sanitizeSectionName("x".repeat(100)).length)
    }

    @Test
    fun savedBotRefRebuildsOpenableItem() {
        val item = SavedBotRef("remote1", "helper", "Helper").toItem()
        assertEquals("remote1::helper", item.identityKey)
        assertEquals("helper", item.profile)
        assertEquals("remote1", item.connectionId)
        assertEquals("Helper", item.displayName)
    }

    @Test
    fun editorValidationMirrorsServerLimits() {
        assertNull(validateBotEditor("helper", "Helper", "desc", "", true))
        assertEquals("Nome profilo obbligatorio.", validateBotEditor("", "H", "", "", true))
        assertEquals(
            "Nome profilo non valido (minuscole, numeri, _ -).",
            validateBotEditor("Helper Bot!", "H", "", "", true)
        )
        assertEquals(
            "Descrizione troppo lunga (max 2000).",
            validateBotEditor("helper", "H", "x".repeat(2001), "", true)
        )
        assertEquals(
            "SOUL troppo grande (max 100000).",
            validateBotEditor("helper", "H", "", "x".repeat(100001), true)
        )
        assertEquals("Nome visualizzato obbligatorio.", validateBotEditor("helper", "", "", "", false))
        // Update senza soul: ok (non sovrascrive).
        assertNull(validateBotEditor("helper", "Helper", "desc", "", false))
    }

    @Test
    fun sessionConversationIdNeverCollidesWithCanonical() {
        val bot = item("helper")
        val canonical = stableBotConversationId(bot)
        val a = stableBotSessionConversationId(bot, "sess-aaa")
        val b = stableBotSessionConversationId(bot, "sess-bbb")
        assertTrue(a.startsWith(canonical))
        assertTrue(a != canonical && b != canonical && a != b)
        // Id strani: sanitizzati, mai vuoti.
        assertTrue(stableBotSessionConversationId(bot, "!!!").startsWith("$canonical-s-"))
    }

    @Test
    fun newChatTitleNeverMatchesCanonicalRegistry() {
        val bot = item("helper")
        assertEquals("Chat con helper", newBotChatTitle(bot))
        assertTrue(!newBotChatTitle(bot).trim().equals("Bot Chat", ignoreCase = true))
    }

    private fun session(id: String, title: String, lastActive: Double = 0.0) = HermesSession(
        id = id,
        title = title,
        raw = JSONObject().put("last_active", lastActive)
    )

    @Test
    fun recentSessionsExcludeCanonicalAndSortByActivity() {
        val rows = listOf(
            session("c1", "Bot Chat", lastActive = 9999.0),
            session("old", "Vecchia", lastActive = 10.0),
            session("new", "Recente", lastActive = 50.0),
            session("notitle", "", lastActive = 60.0)
        )
        val recent = selectRecentBotSessions(rows)
        assertEquals(listOf("notitle", "new", "old"), recent.map { it.id })
    }

    @Test
    fun recentSessionsRespectsLimit() {
        val rows = (1..30).map { session("s$it", "Chat $it", lastActive = it.toDouble()) }
        assertEquals(20, selectRecentBotSessions(rows).size)
        assertEquals(5, selectRecentBotSessions(rows, limit = 5).size)
        assertTrue(selectRecentBotSessions(rows, limit = 0).isEmpty())
    }

    @Test
    fun recentSessionLabels() {
        assertEquals("La mia chat", recentSessionLabel(session("a", "La mia chat")))
        assertEquals("Senza titolo", recentSessionLabel(session("b", "")))
        assertEquals(0L, recentSessionActiveMs(session("c", "x")))
        assertEquals(5000L, recentSessionActiveMs(session("d", "x", lastActive = 5.0)))
    }
}
