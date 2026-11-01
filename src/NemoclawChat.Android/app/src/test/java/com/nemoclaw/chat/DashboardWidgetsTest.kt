package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardWidgetsTest {

    @Test
    fun `dashboard covers every main section except hidden chat`() {
        val tabs = dashboardEntries().map { it.tab }
        assertTrue(tabs.containsAll(listOf(
            Tab.Bots, Tab.Voice, Tab.Jarvis, Tab.Video, Tab.Images,
            Tab.Hardware, Tab.Health, Tab.Cron, Tab.Archive, Tab.Settings
        )))
        assertTrue(tabs.none { it == Tab.Chat })
    }

    @Test
    fun `dashboard has no duplicate tabs and no blank subtitles`() {
        val entries = dashboardEntries()
        assertEquals(entries.size, entries.map { it.tab }.toSet().size)
        entries.forEach {
            assertTrue(it.subtitle.isNotBlank())
        }
    }

    @Test
    fun `dashboard widget tap navigates to a different tab than server`() {
        // Il tap e' un semplice onOpenTab(entry.tab): la navigazione riusa
        // navigateToTab, che e' no-op solo sullo stesso tab.
        for (entry in dashboardEntries()) {
            assertEquals(entry.tab.navRoute, entry.tab.name)
        }
        assertTrue(dashboardEntries().none { isSameTabNavigation(Tab.Server.navRoute, it.tab) })
    }
}
