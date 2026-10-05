package com.nemoclaw.chat.features.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
