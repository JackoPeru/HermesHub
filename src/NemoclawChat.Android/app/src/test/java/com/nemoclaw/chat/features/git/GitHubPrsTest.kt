package com.nemoclaw.chat.features.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubPrsTest {
    @Test
    fun `parse https and ssh urls`() {
        assertEquals(
            Pair("JackoPeru", "HermesHub"),
            parseGitHubRepo("https://github.com/JackoPeru/HermesHub")
        )
        assertEquals(
            Pair("JackoPeru", "HermesHub"),
            parseGitHubRepo("https://github.com/JackoPeru/HermesHub.git")
        )
        assertEquals(
            Pair("o", "r"),
            parseGitHubRepo("git@github.com:o/r.git")
        )
    }

    @Test
    fun `reject non github urls`() {
        assertNull(parseGitHubRepo(""))
        assertNull(parseGitHubRepo("https://example.com/o/r"))
        assertNull(parseGitHubRepo("github.com/onlyowner"))
    }

    @Test
    fun `diff line kinds`() {
        assertEquals('+', diffLineKind("+aggiunta"))
        assertEquals('-', diffLineKind("-rimossa"))
        assertEquals('@', diffLineKind("@@ -1 +1 @@"))
        assertEquals(' ', diffLineKind(" contesto"))
        assertEquals(' ', diffLineKind("+++ b/file"))
    }

    @Test
    fun `endpoint urls`() {
        assertEquals(
            "https://api.github.com/repos/o/r/pulls?state=open&per_page=30",
            prListUrl("o", "r")
        )
        assertEquals(
            "https://api.github.com/repos/o/r/pulls/7/merge",
            prMergeUrl("o", "r", 7)
        )
    }
}
