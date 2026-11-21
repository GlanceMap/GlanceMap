package com.glancemap.glancemapwearos.core.service.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class GpxWifiProtocolTest {
    @Test
    fun tokenIsSixDigits() {
        val token = GpxWifiProtocol.generateToken(SecureRandom())
        assertEquals(6, token.length)
        assertTrue(token.all(Char::isDigit))
    }

    @Test
    fun sanitizeRejectsTraversalAndNonGpx() {
        assertEquals("route.gpx", GpxWifiProtocol.sanitizeFileName("route.gpx"))
        assertEquals("a.gpx", GpxWifiProtocol.sanitizeFileName("../../a.gpx"))
        assertNull(GpxWifiProtocol.sanitizeFileName("../a.txt"))
        assertNull(GpxWifiProtocol.sanitizeFileName(""))
    }

    @Test
    fun acceptsGpxAndRejectsOtherXml() {
        assertTrue(
            GpxWifiProtocol.looksLikeGpx(
                "<?xml version=\"1.0\"?><gpx version=\"1.1\">".toByteArray(),
            ),
        )
        assertTrue(GpxWifiProtocol.looksLikeGpx("<trk><name>x</name></trk>".toByteArray()))
        assertFalse(GpxWifiProtocol.looksLikeGpx("<kml><Document/></kml>".toByteArray()))
    }

    @Test
    fun buildsUploadUrlWithToken() {
        assertEquals(
            "http://192.0.2.10:8765/?t=123456",
            GpxWifiProtocol.uploadPageUrl("192.0.2.10", 8765, "123456"),
        )
    }
}
