package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabNavigationTest {

    @Test
    fun `every tab exposes its enum name as nav route`() {
        for (tab in Tab.entries) {
            assertEquals(tab.name, tab.navRoute)
        }
    }

    @Test
    fun `nav route round-trips through tabForNavRoute`() {
        for (tab in Tab.entries) {
            assertEquals(tab, tabForNavRoute(tab.navRoute))
        }
    }

    @Test
    fun `unknown or null nav route falls back to Chat`() {
        assertEquals(Tab.Chat, tabForNavRoute(null))
        assertEquals(Tab.Chat, tabForNavRoute(""))
        assertEquals(Tab.Chat, tabForNavRoute("NoSuchTab"))
    }

    @Test
    fun `incoming deep-link routes still map to same tabs`() {
        assertEquals(Tab.Voice, tabForIncomingRoute("voice"))
        assertEquals(Tab.Jarvis, tabForIncomingRoute("jarvis"))
        assertEquals(Tab.Chat, tabForIncomingRoute("chat"))
        assertEquals(Tab.Chat, tabForIncomingRoute("unknown"))
        assertEquals(Tab.Chat, tabForIncomingRoute(""))
    }

    @Test
    fun `same-tab navigation is detected including null default`() {
        assertTrue(isSameTabNavigation("Chat", Tab.Chat))
        assertTrue(isSameTabNavigation(null, Tab.Chat))
        assertFalse(isSameTabNavigation("Chat", Tab.Voice))
        assertFalse(isSameTabNavigation("Voice", Tab.Chat))
    }

    @Test
    fun `start destination stays Chat`() {
        assertEquals(Tab.Chat.name, tabNavStartDestination)
    }
}
