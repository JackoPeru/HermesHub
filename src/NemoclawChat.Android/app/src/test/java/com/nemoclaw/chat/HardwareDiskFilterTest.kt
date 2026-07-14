package com.nemoclaw.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareDiskFilterTest {
    @Test
    fun filtersVirtualAndSnapMounts() {
        assertFalse(isMeaningfulHardwareDisk("/dev/loop7", "squashfs"))
        assertFalse(isMeaningfulHardwareDisk("overlay", "overlay"))
        assertFalse(isMeaningfulHardwareDisk("tmpfs", "tmpfs"))
    }

    @Test
    fun keepsPhysicalAndMappedFilesystems() {
        assertTrue(isMeaningfulHardwareDisk("/dev/nvme0n1p2", "ext4"))
        assertTrue(isMeaningfulHardwareDisk("/dev/mapper/vg-data", "xfs"))
        assertTrue(isMeaningfulHardwareDisk("tank/data", "zfs"))
    }
}
