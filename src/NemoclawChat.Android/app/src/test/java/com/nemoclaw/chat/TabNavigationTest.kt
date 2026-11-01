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
    fun `unknown or null nav route falls back to Server`() {
        assertEquals(Tab.Server, tabForNavRoute(null))
        assertEquals(Tab.Server, tabForNavRoute(""))
        assertEquals(Tab.Server, tabForNavRoute("NoSuchTab"))
    }

    @Test
    fun `incoming deep-link routes still map to same tabs`() {
        assertEquals(Tab.Voice, tabForIncomingRoute("voice"))
        assertEquals(Tab.Jarvis, tabForIncomingRoute("jarvis"))
        assertEquals(Tab.Server, tabForIncomingRoute("chat"))
        assertEquals(Tab.Bots, tabForIncomingRoute("bots"))
        assertEquals(Tab.Server, tabForIncomingRoute("unknown"))
        assertEquals(Tab.Server, tabForIncomingRoute(""))
    }

    @Test
    fun `same-tab navigation is detected including null default`() {
        assertTrue(isSameTabNavigation("Server", Tab.Server))
        assertTrue(isSameTabNavigation(null, Tab.Server))
        assertFalse(isSameTabNavigation("Server", Tab.Voice))
        assertFalse(isSameTabNavigation("Voice", Tab.Server))
    }

    @Test
    fun `start destination stays Server`() {
        assertEquals(Tab.Server.name, tabNavStartDestination)
    }
}
