package com.glancemap.glancemapwearos.presentation.features.maps.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class DemDownloadConnectionTest {
    @Test
    fun retainedNetworkConnectionPreservesPartialTileResume() {
        val directory = Files.createTempDirectory("dem-network-resume").toFile()
        try {
            val payload = ByteArray(1201 * 1201 * 2) { (it % 127).toByte() }
            val target = File(directory, "N46E006.hgt")
            val part = File(directory, ".N46E006.hgt.part")
            part.writeBytes(payload.copyOfRange(0, 4096))
            val connection = RetainedConnection(payload.copyOfRange(4096, payload.size))
            val requestedUrls = mutableListOf<String>()

            downloadDemFile(
                request =
                    DemDownloadRequest(
                        sourceId = "test_dem",
                        url = "https://example.test/N46E006.hgt",
                        target = target,
                        demRoot = directory,
                        userAgent = "GlanceMap-test",
                    ),
                openConnection = { url ->
                    requestedUrls += url.toString()
                    connection
                },
            )

            assertEquals(listOf("https://example.test/N46E006.hgt"), requestedUrls)
            assertEquals("bytes=4096-", connection.getRequestProperty("Range"))
            assertEquals("GlanceMap-test", connection.getRequestProperty("User-Agent"))
            assertArrayEquals(payload, target.readBytes())
            assertEquals(false, part.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RetainedConnection(
        private val payload: ByteArray,
    ) : HttpURLConnection(URL("https://example.test/N46E006.hgt")) {
        override fun getResponseCode(): Int = HTTP_PARTIAL

        override fun getContentLengthLong(): Long = payload.size.toLong()

        override fun getInputStream() = ByteArrayInputStream(payload)

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
