package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardWidgetsTest {

    @Test
    fun `dashboard covers every main section except hidden chat`() {
        val tabs = dashboardEntries().map { it.tab }
        assertTrue(tabs.containsAll(listOf(
            Tab.Bots, Tab.Voice, Tab.Jarvis, Tab.Video, Tab.Images,
            Tab.Hardware, Tab.Health, Tab.Cron, Tab.Archive, Tab.Settings
        )))
        assertTrue(tabs.none { it == Tab.Chat })
    }

    @Test
    fun `dashboard has no duplicate tabs and no blank subtitles`() {
        val entries = dashboardEntries()
        assertEquals(entries.size, entries.map { it.tab }.toSet().size)
        entries.forEach {
            assertTrue(it.subtitle.isNotBlank())
        }
    }

    @Test
    fun `dashboard widget tap navigates to a different tab than server`() {
        // Il tap e' un semplice onOpenTab(entry.tab): la navigazione riusa
        // navigateToTab, che e' no-op solo sullo stesso tab.
        for (entry in dashboardEntries()) {
            assertEquals(entry.tab.navRoute, entry.tab.name)
        }
        assertTrue(dashboardEntries().none { isSameTabNavigation(Tab.Server.navRoute, it.tab) })
    }

    @Test
    fun `board overall status mapping`() {
        assertEquals("offline", boardOverallStatus(false, 0))
        assertEquals("offline", boardOverallStatus(false, 3))
        assertEquals("degraded", boardOverallStatus(true, 2))
        assertEquals("ok", boardOverallStatus(true, 0))
    }

    @Test
    fun `resolve uses default order when blank`() {
        val items = resolveDashboardItems("", "", emptyList())
        assertEquals(
            listOf("status", "bots", "cron", "chats", "gpu", "quick"),
            items.map {
                when (it) {
                    is DashboardItem.Builtin -> it.id
                    is DashboardItem.Custom -> it.widget.id
                }
            }
        )
    }

    @Test
    fun `resolve drops unknown and hidden, appends missing`() {
        val items = resolveDashboardItems("gpu,boh,quick", "quick", emptyList())
        val ids = items.map {
            when (it) {
                is DashboardItem.Builtin -> it.id
                is DashboardItem.Custom -> it.widget.id
            }
        }
        assertTrue(ids.none { it == "boh" })
        assertTrue(ids.none { it == "quick" })
        assertEquals("gpu", ids.first())
        assertTrue(ids.containsAll(listOf("status", "bots", "cron", "chats")))
    }

    @Test
    fun `custom widgets roundtrip and interleave`() {
        val custom = listOf(CustomWidget("c:1", "note", "Cose", "", "ricordami"))
        assertEquals(custom, parseCustomWidgets(encodeCustomWidgets(custom)))
        assertTrue(parseCustomWidgets("nope").isEmpty())
        assertTrue(parseCustomWidgets(encodeCustomWidgets(custom).replace("note", "bogus")).isEmpty())
        val items = resolveDashboardItems("c:1,status", "", custom)
        assertEquals(2 + 5, items.size)
        assertTrue(items.first() is DashboardItem.Custom)
        val hidden = resolveDashboardItems("", "c:1", custom)
        assertTrue(hidden.none { it is DashboardItem.Custom })
    }

    @Test
    fun `job video file detection`() {
        assertTrue(isJobVideoFile("clip.mp4"))
        assertTrue(isJobVideoFile("CLIP.WEBM"))
        assertTrue(isJobVideoFile("a.mov"))
        assertTrue(isJobVideoFile("a.mkv"))
        assertTrue(!isJobVideoFile("frame.png"))
        assertTrue(!isJobVideoFile("thumb.jpg"))
        assertTrue(!isJobVideoFile("noext"))
    }

    @Test
    fun `board time formats HHmm`() {
        val millis = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 21)
            set(java.util.Calendar.MINUTE, 14)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("21:14", formatBoardTime(millis))
    }

    @Test
    fun `short host and path collapse technical strings`() {
        assertEquals("100.94.223.14:8642", shortHost("http://100.94.223.14:8642"))
        assertEquals("hermes.local", shortHost("https://hermes.local/v1/x"))
        assertEquals("media", shortPath("/data/hermes/media"))
        assertEquals("", shortPath(""))
    }

    @Test
    fun `home suggestions are actionable starters`() {
        val suggestions = homeSuggestions()
        assertEquals(3, suggestions.size)
        suggestions.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.prompt.isNotBlank())
        }
        assertTrue(suggestions.map { it.label }.toSet().size == 3)
    }

    @Test
    fun `job video resolves without image browse`() {
        val parent = "/opt/hermes/media-output/videos/f020c037c4d0"
        val mp4 = jobVideoItem(
            "f020c037c4d0",
            "$parent/7_f020c037c4d0_00001_.h264.mp4",
            parent
        )!!
        assertEquals("job:f020c037c4d0:7_f020c037c4d0_00001_.h264.mp4", mp4.id)
        assertTrue(mp4.mediaUrl.startsWith("/v1/media/7_f020c037c4d0_00001_.h264.mp4?root="))
        assertTrue(mp4.mediaUrl.contains("root=%2Fopt%2Fhermes%2Fmedia-output%2Fvideos%2Ff020c037c4d0"))
        assertEquals(mp4.mediaUrl, mp4.playbackUrl)
        assertEquals("video/mp4", mp4.mimeType)
        assertEquals(null, jobVideoItem("j", "$parent/poster.jpg", parent))
        assertEquals(null, jobVideoItem("j", "", parent))
        val webm = jobVideoItem("j", "$parent/clip.webm", parent)!!
        assertEquals("video/webm", webm.mimeType)
        val preferred = preferJobVideo(listOf(webm, mp4))
        assertEquals(mp4.id, preferred!!.id)
        assertEquals(null, preferJobVideo(emptyList()))
    }

    @Test
    fun `probe returns code or null`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val result = kotlinx.coroutines.runBlocking {
                probeUrl("http://127.0.0.1:$port/", 5000)
            }
            assertEquals(200, result)
        } finally {
            server.stop(0)
        }
        val down = kotlinx.coroutines.runBlocking {
            probeUrl("http://127.0.0.1:9/", 1000)
        }
        assertEquals(null, down)
    }
}
