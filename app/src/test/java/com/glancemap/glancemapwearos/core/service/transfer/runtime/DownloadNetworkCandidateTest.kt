package com.glancemap.glancemapwearos.core.service.transfer.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadNetworkCandidateTest {
    @Test
    fun captiveWifiDoesNotReplaceWorkingPhoneInternet() {
        assertEquals(
            "phone",
            selectDownloadNetwork(
                DownloadNetworkCandidate("captive-wifi", validated = false),
                DownloadNetworkCandidate("phone", validated = true),
            ),
        )
    }

    @Test
    fun validatedWifiIsPreferredForLargeTransfers() {
        assertEquals(
            "wifi",
            selectDownloadNetwork(
                DownloadNetworkCandidate("wifi", validated = true),
                DownloadNetworkCandidate("phone", validated = true),
            ),
        )
    }

    @Test
    fun unvalidatedNetworksAreNotReadyForDownload() {
        assertNull(
            selectDownloadNetwork(
                DownloadNetworkCandidate("captive-wifi", validated = false),
                DownloadNetworkCandidate("offline", validated = false),
            ),
        )
    }

    @Test
    fun activeVpnKeepsDefaultRouting() {
        assertEquals(
            "vpn",
            selectDownloadNetwork(
                DownloadNetworkCandidate("wifi", validated = true),
                DownloadNetworkCandidate("vpn", validated = true, vpn = true),
            ),
        )
    }

    @Test
    fun reconnectingVpnDoesNotLeakOntoUnderlyingWifi() {
        assertNull(
            selectDownloadNetwork(
                DownloadNetworkCandidate("wifi", validated = true),
                DownloadNetworkCandidate("vpn", validated = false, vpn = true),
            ),
        )
    }

    @Test
    fun lostWifiFallsBackToWorkingDefault() {
        assertEquals(
            "phone",
            selectDownloadNetwork(
                null,
                DownloadNetworkCandidate("phone", validated = true),
            ),
        )
    }
}
