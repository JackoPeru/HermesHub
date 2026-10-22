package com.nemoclaw.chat.features.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTurnActivityTest {
    private fun member(handle: String, reply: String, silent: Boolean = false) =
        HermesBotGroupMemberResult(
            HermesBotGroupMember(
                connectionId = "c1",
                profile = "p",
                displayName = "D $handle",
                handle = handle,
                identityKey = "c1::p"
            ),
            reply,
            silent
        )

    private fun result() = HermesBotGroupTurnResult(
        groupName = "g",
        outcome = "reply",
        reply = "ciao",
        rounds = 2,
        botMessages = 3,
        memberResults = listOf(
            member("alpha", "ciao"),
            member("beta", "pass", silent = true),
            member("alpha", "ancora")
        ),
        failures = listOf("x: ko")
    )

    @Test
    fun `summary counts speaking members and failures`() {
        val summary = summarizeGroupTurn(result(), atMs = 123L)
        assertEquals("g", summary.group)
        assertEquals(2, summary.replies)
        assertEquals(1, summary.failures)
        assertEquals(123L, summary.atMs)
    }

    @Test
    fun `fleet summary line counts`() {
        assertEquals("3 bot · 2 gruppi · 5 turni recenti", fleetSummaryLine(3, 2, 5))
        assertEquals("0 bot · 0 gruppi · 0 turni recenti", fleetSummaryLine(0, 0, 0))
    }

    @Test
    fun `history prunes to newest twenty`() {
        val items = (0 until 30).map {
            GroupTurnSummary("g", "reply", 1, 1, 0, it.toLong())
        }
        val pruned = pruneGroupTurnHistory(items)
        assertEquals(20, pruned.size)
        assertEquals(29L, pruned.first().atMs)
        assertTrue(pruned.all { it.atMs >= 10L })
    }
}
