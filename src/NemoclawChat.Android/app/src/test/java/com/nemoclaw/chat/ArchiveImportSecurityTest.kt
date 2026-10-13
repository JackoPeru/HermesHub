package com.nemoclaw.chat

import com.nemoclaw.chat.features.screen.isBlockedAssetPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Sicurezza import archivio + intercettore asset: funzioni pure, commenti italiani.
class ArchiveImportSecurityTest {

    // Formato export invariato: il blocco canonico si importa come prima.
    @Test
    fun `blocco export canonico resta valido`() {
        val parsed = parseArchiveExportText("## Ciao\nTipo: Chat\nPrompt: dimmi\nTu: ciao\nHermes: ok")
        assertEquals(1, parsed.size)
        assertEquals("Ciao", parsed[0].title)
        assertEquals("Chat", parsed[0].kind)
        assertEquals("dimmi", parsed[0].prompt)
        assertEquals(2, parsed[0].messages.size)
    }

    // botIdentityKey conservata se presente, "" di default (no breaking).
    @Test
    fun `parse conserva Bot se presente e default vuoto`() {
        val withBot = parseArchiveExportText("## T\nTipo: Chat\nBot: conn::profilo\nPrompt: p\nTu: x")
        assertEquals(1, withBot.size)
        assertEquals("conn::profilo", withBot[0].botIdentityKey)
        val withoutBot = parseArchiveExportText("## T\nTipo: Chat\nPrompt: p\nTu: x")
        assertEquals(1, withoutBot.size)
        assertEquals("", withoutBot[0].botIdentityKey)
    }

    // Kind validato: solo valori noti, ignoto -> Chat (fail-closed, niente record corrotti).
    @Test
    fun `kind ignoto normalizzato a Chat e noti conservati`() {
        assertEquals("Task", parseArchiveExportText("## T\nTipo: Task\nPrompt: p\nTu: x")[0].kind)
        assertEquals("Progetto", parseArchiveExportText("## T\nTipo: Progetto\nPrompt: p\nTu: x")[0].kind)
        assertEquals("Artifact", parseArchiveExportText("## T\nTipo: Artifact\nPrompt: p\nTu: x")[0].kind)
        assertEquals("Chat", parseArchiveExportText("## T\nTipo: Meow\nPrompt: p\nTu: x")[0].kind)
        assertEquals("Chat", parseArchiveExportText("## T\nTipo: Deleted\nPrompt: p\nTu: x")[0].kind)
    }

    // Blocchi malformati ignorati invece di creare conversazioni corrotte.
    @Test
    fun `blocchi malformati scartati`() {
        // Titolo vuoto.
        assertTrue(parseArchiveExportText("##   \nTipo: Chat\nPrompt: p\nTu: x").isEmpty())
        // Senza prompt e senza messaggi.
        assertTrue(parseArchiveExportText("## Solo titolo\nTipo: Chat\nPrompt: \n").isEmpty())
        // Testo senza marcatori: nessuna chat riconosciuta.
        assertTrue(parseArchiveExportText("testo libero senza blocchi").isEmpty())
        // Il blocco valido sopravvive accanto a quello corrotto.
        val mixed = parseArchiveExportText("##   \nTipo: Chat\nPrompt: \n\n## Buono\nTipo: Chat\nPrompt: p\nTu: x")
        assertEquals(1, mixed.size)
        assertEquals("Buono", mixed[0].title)
    }

    // Intercettore asset: leciti passano, traversal anche codificati bloccati.
    @Test
    fun `isBlockedAssetPath blocca traversal anche codificati`() {
        assertFalse(isBlockedAssetPath("vnc.html"))
        assertFalse(isBlockedAssetPath("js/app.js"))
        assertFalse(isBlockedAssetPath("a...b/c.png"))
        assertTrue(isBlockedAssetPath(""))
        assertTrue(isBlockedAssetPath("   "))
        assertTrue(isBlockedAssetPath("../secret"))
        assertTrue(isBlockedAssetPath("a/../b"))
        assertTrue(isBlockedAssetPath("%2e%2e/secret"))
        assertTrue(isBlockedAssetPath("..%2fsecret"))
        assertTrue(isBlockedAssetPath("%252e%252e%2fsecret"))
        assertTrue(isBlockedAssetPath("..\\secret"))
        assertTrue(isBlockedAssetPath("a%5csecret"))
        assertTrue(isBlockedAssetPath("/etc/passwd"))
    }
}
