package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Il gateway e configurato dall'utente e puo essere hostname MagicDNS
 * (FQDN, short-name) o IP privato letterale: forme non allowlistabili in XML
 * (niente CIDR). Il default resta quindi permissivo; il confine di sicurezza
 * e il Bearer per-richiesta + Keystore + rete privata. Questo test impedisce
 * regressioni silenziose del file.
 */
class NetworkSecurityConfigTest {

    private fun loadConfig(): org.w3c.dom.Document {
        val candidates = listOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException(
                "network_security_config.xml non trovato da workingDir=" + File(".").absolutePath
            )
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    }

    @Test
    fun `base config permette cleartext verso host privati configurati`() {
        val doc = loadConfig()
        val base = doc.getElementsByTagName("base-config")
        assertEquals(1, base.length)
        assertEquals("true", base.item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
    }

    @Test
    fun `cleartext exception for tailscale magicdns is present`() {
        val doc = loadConfig()
        val configs = doc.getElementsByTagName("domain-config")
        var foundTsNetException = false
        for (i in 0 until configs.length) {
            val node = configs.item(i)
            val permitted = node.attributes.getNamedItem("cleartextTrafficPermitted")?.nodeValue
            if (permitted != "true") continue
            val domains = (node as org.w3c.dom.Element).getElementsByTagName("domain")
            for (j in 0 until domains.length) {
                val domain = domains.item(j)
                if (domain.textContent.trim() == "ts.net" &&
                    domain.attributes.getNamedItem("includeSubdomains")?.nodeValue == "true"
                ) {
                    foundTsNetException = true
                }
            }
        }
        assertTrue("manca l'eccezione cleartext per *.ts.net (HTTP gateway Tailscale)", foundTsNetException)
    }
}
