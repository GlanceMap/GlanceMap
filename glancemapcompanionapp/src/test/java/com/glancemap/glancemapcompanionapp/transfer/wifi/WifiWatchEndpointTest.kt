package com.glancemap.glancemapcompanionapp.transfer.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiWatchEndpointTest {
    @Test
    fun roundTripsIpv4() {
        val endpoint = WifiWatchEndpoint("192.0.2.10", 8765, "123456")
        assertEquals(endpoint, WifiWatchEndpoint.parse(endpoint.encode()))
    }

    @Test
    fun roundTripsIpv6() {
        val endpoint = WifiWatchEndpoint("fe80::1", 8765, "123456")
        assertEquals(endpoint, WifiWatchEndpoint.parse(endpoint.encode()))
    }

    @Test
    fun rejectsNonWifiIds() {
        assertNull(WifiWatchEndpoint.parse("abc123"))
        assertNull(WifiWatchEndpoint.parse("wifi://host:0?t=123456"))
    }

    @Test
    fun buildsUploadUrl() {
        val endpoint = WifiWatchEndpoint("192.0.2.10", 8765, "123456")
        assertTrue(endpoint.uploadUrl().startsWith("http://192.0.2.10:8765/upload"))
    }
}
