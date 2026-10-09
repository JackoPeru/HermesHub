package com.nemoclaw.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewsAuthBoundaryTest {
    @Test
    fun `news esterna non riceve il bearer del gateway`() = runBlocking {
        LocalHttpTestServer("<html>news</html>".toByteArray()) .use { server ->
            val settings = AppSettings(gatewayUrl = "http://gateway.invalid/v1")
            val item = newsItem(server.url("/news/page.html"))

            val (html, _) = loadNewsHtml(settings, item, "test-gateway-token")

            assertEquals("<html>news</html>", html)
            assertNull(server.awaitRequest().authorization)
        }
    }

    @Test
    fun `news same origin mantiene autenticazione gateway`() = runBlocking {
        LocalHttpTestServer("<html>news</html>".toByteArray()) .use { server ->
            val settings = AppSettings(gatewayUrl = "${server.origin}/v1")
            val item = newsItem(server.url("/v1/news/page.html"))

            loadNewsHtml(settings, item, "test-gateway-token")

            assertEquals("Bearer test-gateway-token", server.awaitRequest().authorization)
        }
    }

    private fun newsItem(url: String) = NewsHtmlItem(
        id = "news-1",
        title = "News",
        filename = "news.html",
        url = url,
        path = "",
        mimeType = "text/html",
        sizeBytes = 0,
        modifiedAt = 0
    )
}
