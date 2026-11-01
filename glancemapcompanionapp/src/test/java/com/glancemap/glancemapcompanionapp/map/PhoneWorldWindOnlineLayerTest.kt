package com.glancemap.glancemapcompanionapp.map

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorldWindOnlineLayerTest {
    private val provider =
        OnlineMapProvider(
            id = "test",
            displayName = "Test",
            attribution = "Test",
            rasterTileUrlTemplate = "https://example.test/{z}/{y}/{x}.png",
            bounds = listOf(10.0, 45.0, 12.0, 47.0),
            minimumZoom = 2,
            maximumZoom = 10,
        )

    @Test
    fun onlyRecoverableHttpFailuresAreRetried() {
        listOf(408, 425, 429, 500, 503).forEach { assertTrue(phoneWorldWindTileFailureIsTransient(it)) }
        listOf(400, 401, 403, 404).forEach { assertFalse(phoneWorldWindTileFailureIsTransient(it)) }
    }

    @Test
    fun selectedProviderCoordinatesZoomAndCoverageAreRespected() {
        val tile = phoneWorldWindTile(46.0, 11.0, 8)
        assertEquals("https://example.test/8/${tile.y}/${tile.x}.png", phoneWorldWindOnlineTileUrl(provider, tile))
        assertNull(phoneWorldWindOnlineTileUrl(provider, phoneWorldWindTile(0.0, 0.0, 8)))
        assertNull(phoneWorldWindOnlineTileUrl(provider, PhoneWorldWindTile(1, 0, 0)))
        assertNull(phoneWorldWindOnlineTileUrl(provider, PhoneWorldWindTile(8, -1, 0)))
    }

    @Test
    fun osmCacheFallbackHonorsServerCacheHeaders() {
        val request = Request.Builder().url("https://tile.openstreetmap.org/1/1/1.png").build()
        val response =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        assertEquals("public, max-age=604800", phoneWorldWindCacheFallback(response).header("Cache-Control"))
        val configured = response.newBuilder().header("Cache-Control", "max-age=60").build()
        assertEquals("max-age=60", phoneWorldWindCacheFallback(configured).header("Cache-Control"))
        val expires = response.newBuilder().header("Expires", "Wed, 21 Oct 2030 07:28:00 GMT").build()
        assertNull(phoneWorldWindCacheFallback(expires).header("Cache-Control"))
    }

    @Test
    fun vectorStylesAreNotTreatedAsRasterTiles() {
        val vector = provider.copy(rasterTileUrlTemplate = "", styleUrl = "https://example.test/style.json")
        assertNull(phoneWorldWindOnlineTileUrl(vector, PhoneWorldWindTile(8, 1, 1)))
    }
}
