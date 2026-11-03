package dev.amatv.ui

import org.junit.Assert.*
import org.junit.Test

class ArtworkMemoryPolicyTest {
    private val mib = 1024L * 1024L

    @Test fun largeJavaHeapDoesNotAuthorizeLargeGraphicsCacheOn32BitTv() {
        val policy = artworkMemoryPolicy(512L * mib, is64Bit = false, lowRam = false)
        assertTrue(policy.constrained)
        assertEquals(16L * mib, policy.decodedCacheBytes)
        assertFalse(policy.allowHardware)
        assertFalse(policy.allowEarlyGpuUpload)
    }

    @Test fun lowRamDeviceIsConstrainedEvenWith64BitAddressSpace() {
        val policy = artworkMemoryPolicy(512L * mib, is64Bit = true, lowRam = true)
        assertTrue(policy.constrained)
        assertEquals(16L * mib, policy.decodedCacheBytes)
        assertFalse(policy.allowHardware)
        assertFalse(policy.allowEarlyGpuUpload)
    }

    @Test fun constrainedSmallerHeapKeepsAUsefulEightMiBDecodedWindow() {
        val policy = artworkMemoryPolicy(128L * mib, is64Bit = false, lowRam = true)
        assertEquals(8L * mib, policy.decodedCacheBytes)
        assertFalse(policy.allowHardware)
    }

    @Test fun unconstrained64BitDevicePreservesExistingWarmImagePolicy() {
        val policy = artworkMemoryPolicy(512L * mib, is64Bit = true, lowRam = false)
        assertFalse(policy.constrained)
        assertEquals(48L * mib, policy.decodedCacheBytes)
        assertTrue(policy.allowHardware)
        assertTrue(policy.allowEarlyGpuUpload)
    }
}
