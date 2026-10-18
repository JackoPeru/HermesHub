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
    fun buildConfigShipsNoApiKey() {
        // Audit 0.6.277: nessun segreto nel APK. La chiave arriva dal
        // pairing (SecretStore) e non puo' esistere nella BuildConfig.
        org.junit.Assert.assertFalse(
            "HERMES_DEFAULT_API_KEY non deve esistere nella BuildConfig",
            BuildConfig::class.java.fields.any { it.name == "HERMES_DEFAULT_API_KEY" }
        )
    }
}
