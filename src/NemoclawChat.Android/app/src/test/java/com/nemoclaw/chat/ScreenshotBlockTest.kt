package com.nemoclaw.chat

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotBlockTest {

    @Test
    fun `abilitato aggiunge FLAG_SECURE`() {
        val updated = applyScreenshotBlock(0, true)
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, updated)
        assertTrue((updated and WindowManager.LayoutParams.FLAG_SECURE) != 0)
    }

    @Test
    fun `disabilitato rimuove FLAG_SECURE preservando altri bit`() {
        val otherBit = 0x00000400
        val flags = otherBit or WindowManager.LayoutParams.FLAG_SECURE
        val cleared = applyScreenshotBlock(flags, false)
        assertEquals(otherBit, cleared)
        assertEquals(0, cleared and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun `idempotente e preserva flag esistenti`() {
        val base = 0x00000100 or 0x00000200
        val enabled = applyScreenshotBlock(base, true)
        assertEquals(base or WindowManager.LayoutParams.FLAG_SECURE, enabled)
        // Ri-abilitare non duplica/cambia.
        assertEquals(enabled, applyScreenshotBlock(enabled, true))
        // Disabilitare due volte resta stabile.
        val cleared = applyScreenshotBlock(enabled, false)
        assertEquals(base, cleared)
        assertEquals(base, applyScreenshotBlock(cleared, false))
    }

    @Test
    fun `default impostazioni non blocca screenshot`() {
        assertFalse(AppSettings().blockScreenshots)
        assertFalse(AppDefaults.blockScreenshots)
    }
}
