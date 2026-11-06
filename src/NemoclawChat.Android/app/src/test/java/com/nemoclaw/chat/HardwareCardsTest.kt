package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCardsTest {

    private fun sampleSnapshot() = HardwareSnapshot(
        status = "ok",
        processor = "AMD Ryzen 9",
        cpuPercent = 42.0,
        physicalCores = 12,
        logicalCores = 24,
        currentMhz = 4200.0,
        maxMhz = 5700.0,
        memoryPercent = 50.0,
        memoryTotalBytes = 32L * 1024 * 1024 * 1024,
        memoryUsedBytes = 16L * 1024 * 1024 * 1024,
        memoryAvailableBytes = 16L * 1024 * 1024 * 1024,
        temperatures = listOf(
            HardwareTemperature("k10temp", "Tctl", 58.5, 95.0, 100.0)
        ),
        gpus = listOf(
            HardwareGpu(
                index = 0,
                name = "NVIDIA GeForce RTX 4090",
                utilizationPercent = 73.0,
                memoryUtilizationPercent = 50.0,
                memoryUsedBytes = 12L * 1024 * 1024 * 1024,
                memoryTotalBytes = 24L * 1024 * 1024 * 1024,
                temperatureC = 61.0,
                powerDrawWatts = 320.0,
                powerLimitWatts = 450.0,
                driverVersion = "550.0"
            )
        )
    )

    @Test
    fun `cards carry their facts for fast reading`() {
        val snapshot = sampleSnapshot()
        val components = buildHardwareComponents(
            snapshot,
            snapshot.temperatures.toHardwareTemperatureViews(),
            downRate = 1_000_000L,
            upRate = 500_000L
        )
        val byId = components.associateBy { it.id }
        val cpu = byId.getValue("cpu")
        assertTrue(cpu.facts.any { it.contains("4200") })
        assertTrue(cpu.facts.any { it.contains("58.5") })
        val gpu = byId.getValue("gpu-0")
        assertTrue(gpu.facts.any { it.contains("12") && it.contains("24") })
        assertTrue(gpu.facts.any { it.contains("61.0") })
        val memory = byId.getValue("memory")
        assertTrue(memory.facts.any { it.contains("/") })
        val network = byId.getValue("network")
        assertTrue(network.facts.any { it.startsWith("↓") })
        assertTrue(network.facts.any { it.startsWith("↑") })
        components.forEach {
            assertTrue(it.facts.isNotEmpty())
        }
    }

    @Test
    fun `facts default to empty and never break old callers`() {
        val view = HardwareComponentView("x", "T", "s", "1%", 1.0, null, emptyList())
        assertEquals(emptyList<String>(), view.facts)
    }
}
