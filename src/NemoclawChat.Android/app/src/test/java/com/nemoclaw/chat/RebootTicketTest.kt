package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RebootTicketTest {

    @Test
    fun extractRebootTicketAcceptsHex() {
        assertEquals(
            "abcdef0123456789abcdef0123456789",
            extractRebootTicket("""{"ticket":"abcdef0123456789abcdef0123456789","expires_in":120}""")
        )
    }

    @Test
    fun extractRebootTicketRejectsGarbage() {
        assertNull(extractRebootTicket("{}"))
        assertNull(extractRebootTicket(""))
        assertNull(extractRebootTicket("non json"))
        assertNull(extractRebootTicket("""{"ticket":"corto"}"""))
        assertNull(extractRebootTicket("""{"ticket":"zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"}"""))
        assertNull(extractRebootTicket("""{"noticket":1}"""))
    }
}
