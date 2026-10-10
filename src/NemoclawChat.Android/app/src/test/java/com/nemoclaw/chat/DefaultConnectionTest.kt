package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultConnectionTest {
    @Test
    fun freshInstallHasPreconfiguredRoots() {
        val settings = AppSettings()
        assertEquals("http://100.94.223.14:8642", settings.gatewayUrl)
        assertEquals("http://192.168.1.6:8642", settings.localGatewayUrl)
        val roots = localRootCandidates(settings)
        assertTrue(roots.contains("http://192.168.1.6:8642"))
        assertTrue(roots.contains("http://100.94.223.14:8642"))
    }

    @Test
    fun freshInstallHasPreconfiguredLibraries() {
        val settings = AppSettings()
        assertEquals("/home/matteo/video", settings.videoLibraryPath)
        assertEquals("/home/matteo/news", settings.newsLibraryPath)
    }

    @Test
    fun defaultApiKeyMirrorsBuildConfig() {
        assertEquals(
            BuildConfig.HERMES_DEFAULT_API_KEY.trim().takeIf { it.isNotBlank() },
            defaultGatewayApiKey()
        )
    }
}
