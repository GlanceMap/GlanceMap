package com.glancemap.glancemapcompanionapp.transfer.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiUploadProtocolTest {
    @Test
    fun buildsUploadUrlWithName() {
        val url =
            WifiUploadProtocol.uploadUrl(
                WifiWatchEndpoint("192.0.2.10", 8765, "123456"),
                "route 12.gpx",
            )
        assertTrue(url.contains("/upload?"))
        assertTrue(url.contains("t=123456"))
        assertTrue(url.contains("name=route%2012.gpx"))
    }

    @Test
    fun parsesSuccessAndError() {
        assertEquals(
            true,
            WifiUploadProtocol.parseResponse("""{"ok":true,"sha256":"abc"}""")?.ok,
        )
        assertEquals(
            "invalid_token",
            WifiUploadProtocol.parseResponse("""{"ok":false,"error":"invalid_token"}""")?.error,
        )
    }
}
