package com.glancemap.glancemapwearos.core.service.transfer.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWifiHandoffTest {
    @Test
    fun bluetoothToWifiRequestsOneHandoffDespiteRepeatedCapabilities() {
        val handoff = DownloadWifiHandoff()
        assertFalse(handoff.onChanged(null))
        assertFalse(handoff.onChanged(false))
        assertTrue(handoff.onChanged(true))
        assertFalse(handoff.onChanged(true))
        assertFalse(handoff.onChanged(true))
    }

    @Test
    fun startingOnWifiDoesNotReconnect() {
        val handoff = DownloadWifiHandoff()
        assertFalse(handoff.onChanged(null))
        assertFalse(handoff.onChanged(true))
        assertFalse(handoff.onChanged(true))
    }

    @Test
    fun lostWifiRearmsExactlyOneRecovery() {
        val handoff = DownloadWifiHandoff()
        assertFalse(handoff.onChanged(true))
        assertFalse(handoff.onChanged(null))
        assertTrue(handoff.onChanged(true))
        assertFalse(handoff.onChanged(true))
        assertFalse(handoff.onChanged(false))
        assertTrue(handoff.onChanged(true))
    }

    @Test
    fun freshLeaseDoesNotInheritOldRecovery() {
        val handoff = DownloadWifiHandoff()
        handoff.onChanged(false)
        handoff.reset()
        assertFalse(handoff.onChanged(true))
    }
}
