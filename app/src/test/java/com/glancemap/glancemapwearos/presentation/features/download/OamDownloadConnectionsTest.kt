package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class OamDownloadConnectionsTest {
    @Test
    fun metadataUsesRetainedNetwork() =
        runTest {
            val connection = FakeConnection()
            val urls = mutableListOf<URL>()
            val connections =
                OamDownloadConnections { url ->
                    urls += url
                    connection
                }

            val result = connections.metadata("https://example.test/map.zip")

            assertSame(connection, result)
            assertEquals(listOf("https://example.test/map.zip"), urls.map(URL::toString))
            assertEquals("HEAD", result.requestMethod)
        }

    @Test
    fun resumedDownloadUsesRetainedNetworkAndOriginalHttpSettings() =
        runTest {
            val connection = FakeConnection()
            val connections = OamDownloadConnections { connection }

            val result = connections.download("https://example.test/N46E006.hgt", 4096L)

            assertSame(connection, result)
            assertEquals("GET", result.requestMethod)
            assertEquals("bytes=4096-", result.getRequestProperty("Range"))
            assertEquals("identity", result.getRequestProperty("Accept-Encoding"))
            assertEquals(20_000, result.connectTimeout)
            assertEquals(60_000, result.readTimeout)
            assertEquals(false, result.useCaches)
        }

    @Test
    fun retryCanOpenConnectionOnReplacementNetwork() =
        runTest {
            var network = "wifi"
            val routes = mutableListOf<String>()
            val connections =
                OamDownloadConnections {
                    routes += network
                    FakeConnection()
                }
            connections.download("https://example.test/tile.hgt", 0L)
            network = "cellular"

            connections.download("https://example.test/tile.hgt", 1024L)

            assertEquals(listOf("wifi", "cellular"), routes)
        }

    private class FakeConnection : HttpURLConnection(URL("https://example.test/file")) {
        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
