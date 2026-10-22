package com.nemoclaw.chat.features.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlTest {
    private fun node(
        text: String = "",
        depth: Int = 0,
        clickable: Boolean = false,
        cls: String = "android.widget.TextView"
    ) = UiNode(
        depth = depth,
        className = cls,
        text = text,
        description = "",
        viewId = "",
        bounds = "0,0,10,10",
        clickable = clickable,
        editable = false
    )

    @Test
    fun `formatter indents and flags clickable`() {
        val out = formatUiTree(
            listOf(
                node("Titolo", depth = 0),
                node("Ok", depth = 1, clickable = true, cls = "android.widget.Button")
            )
        )
        val lines = out.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("[android.widget.TextView]"))
        assertTrue(lines[1].startsWith("  [android.widget.Button]"))
        assertTrue(lines[1].contains("<C>"))
    }

    @Test
    fun `formatter caps nodes`() {
        val many = (0 until 500).map { node("r$it") }
        assertEquals(PHONE_TREE_NODE_CAP, formatUiTree(many).lines().size)
    }

    @Test
    fun `redacted password never leaks`() {
        val leaked = formatUiTree(
            listOf(
                UiNode(0, "EditText", "•••", "", "", "0,0,1,1", false, true)
            )
        )
        assertTrue(!leaked.contains("segret"))
    }

    @Test
    fun `log line keeps short result`() {
        val line = phoneLogLine("tap", "abc123", "ok: tocco eseguito")
        assertTrue(line.contains("tap"))
        assertTrue(line.contains("abc123"))
    }
}
