package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * No-downgrade: mai ripiegare su livello inferiore a quello configurato.
 */
class HermesNoDowngradeTest {

    @Test
    fun exactLevelIsApproved() {
        val full = listOf("once", "session", "always", "deny")
        assertEquals("session", pickAutoApprovalChoice(full, "session"))
        assertEquals("always", pickAutoApprovalChoice(full, "always"))
    }

    @Test
    fun downgradeToOnceIsRefused() {
        // Server offre solo [once, deny]: mode always/session NON approvano.
        assertNull(pickAutoApprovalChoice(listOf("once", "deny"), "always"))
        assertNull(pickAutoApprovalChoice(listOf("once", "deny"), "session"))
    }

    @Test
    fun sessionWithoutAlwaysIsRefusedForAlways() {
        assertNull(pickAutoApprovalChoice(listOf("once", "session", "deny"), "always"))
    }

    @Test
    fun upgradeToAlwaysIsRefusedForSession() {
        // Mode session con sola always offerta: niente upgrade automatico.
        assertNull(pickAutoApprovalChoice(listOf("always", "deny"), "session"))
    }

    @Test
    fun offNeverApproves() {
        val full = listOf("once", "session", "always", "deny")
        assertNull(pickAutoApprovalChoice(full, "off"))
        assertNull(pickAutoApprovalChoice(full, ""))
        assertNull(pickAutoApprovalChoice(full, "banana"))
    }

    @Test
    fun denyOnlyOrUnknownNeverApproves() {
        assertNull(pickAutoApprovalChoice(listOf("deny"), "always"))
        assertNull(pickAutoApprovalChoice(listOf("deny"), "session"))
        assertNull(pickAutoApprovalChoice(emptyList(), "session"))
        assertNull(pickAutoApprovalChoice(listOf("mystery"), "always"))
    }
}
