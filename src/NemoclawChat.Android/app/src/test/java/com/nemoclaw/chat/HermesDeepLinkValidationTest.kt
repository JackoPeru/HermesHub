package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesDeepLinkValidationTest {

    @Test
    fun `valid chat deeplink is routed`() {
        val parsed = parseHermesDeepLink("hermes-hub://chat")
        assertNotNull(parsed)
        assertEquals("chat", parsed!!.tab)
        assertEquals("", parsed.conversationId)
        assertEquals("", parsed.prompt)
        assertFalse(parsed.promptRequiresConfirmation)
    }

    @Test
    fun `valid chat deeplink with conversation is routed`() {
        val parsed = parseHermesDeepLink("hermes-hub://chat?conversation=abc-123_XY")
        assertNotNull(parsed)
        assertEquals("chat", parsed!!.tab)
        assertEquals("abc-123_XY", parsed.conversationId)
    }

    @Test
    fun `internal voice and jarvis routes still work`() {
        assertEquals("voice", parseHermesDeepLink("hermes-hub://voice")!!.tab)
        assertEquals("jarvis", parseHermesDeepLink("hermes-hub://jarvis")!!.tab)
        // Widget camera: prompt va in bozza, mai auto-inviato (conferma utente).
        val widget = parseHermesDeepLink("hermes-hub://chat?prompt=Scansiona%20e%20analizza%20un%20documento.")
        assertNotNull(widget)
        assertEquals("Scansiona e analizza un documento.", widget!!.prompt)
        assertTrue(widget.promptRequiresConfirmation)
    }

    @Test
    fun `malicious deeplink is rejected`() {
        // Host fuori allowlist.
        assertNull(parseHermesDeepLink("hermes-hub://evil.example?prompt=pwned"))
        assertNull(parseHermesDeepLink("hermes-hub://chat.evil.example"))
        // Path non previsti.
        assertNull(parseHermesDeepLink("hermes-hub://chat/evil/path?prompt=x"))
        // Query key sconosciute.
        assertNull(parseHermesDeepLink("hermes-hub://chat?evil=1"))
        assertNull(parseHermesDeepLink("hermes-hub://chat?conversation=abc&evil=1"))
        // Frammenti, userinfo, porte.
        assertNull(parseHermesDeepLink("hermes-hub://chat#frag"))
        assertNull(parseHermesDeepLink("hermes-hub://user@chat"))
        assertNull(parseHermesDeepLink("hermes-hub://chat:8642"))
        // Scheme diversi o assenti.
        assertNull(parseHermesDeepLink("https://chat?prompt=x"))
        assertNull(parseHermesDeepLink("javascript:alert(1)"))
        assertNull(parseHermesDeepLink(null))
        assertNull(parseHermesDeepLink("   "))
        // Conversation id con traversal/caratteri anomali.
        assertNull(parseHermesDeepLink("hermes-hub://chat?conversation=../etc/passwd"))
        assertNull(parseHermesDeepLink("hermes-hub://chat?conversation=a/b"))
        assertNull(parseHermesDeepLink("hermes-hub://chat?conversation=" + "a".repeat(200)))
        // Percent-encoding malformato.
        assertNull(parseHermesDeepLink("hermes-hub://chat?prompt=%ZZ"))
        // Chiavi duplicate.
        assertNull(parseHermesDeepLink("hermes-hub://chat?prompt=a&prompt=b"))
    }

    @Test
    fun `overlong prompt is truncated never auto-sent`() {
        val parsed = parseHermesDeepLink("hermes-hub://chat?prompt=" + "p".repeat(9000))
        assertNotNull(parsed)
        assertEquals(MAX_HERMES_DEEPLINK_PROMPT_CHARS, parsed!!.prompt.length)
        assertTrue(parsed.promptRequiresConfirmation)
    }
}
