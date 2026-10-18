package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuBridgeBaseTest {

    @Test
    fun bridgeSwapsExistingPort() {
        assertEquals("http://100.94.223.14:8645", gpuBridgeBase("http://100.94.223.14:8642"))
    }

    @Test
    fun bridgeAppendsPortWhenMissing() {
        assertEquals("http://hermes:8645", gpuBridgeBase("http://hermes"))
    }

    @Test
    fun bridgeStripsPathAndTrailingSlash() {
        assertEquals("https://hermes.example:8645", gpuBridgeBase("https://hermes.example:8443/v1/"))
        assertEquals("https://hermes.example:8645", gpuBridgeBase("https://hermes.example/api"))
    }

    @Test
    fun managerAndBridgeUseDistinctPorts() {
        val gateway = "http://100.94.223.14:8642"
        assertEquals("http://100.94.223.14:8643", gpuManagerBase(gateway))
        assertEquals("http://100.94.223.14:8645", gpuBridgeBase(gateway))
    }
}
