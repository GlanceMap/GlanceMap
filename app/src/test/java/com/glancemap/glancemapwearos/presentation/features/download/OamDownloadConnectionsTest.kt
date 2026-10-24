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

    @Test
    fun resumedRequestUsesStrongEntityValidator() =
        runTest {
            val connection = FakeConnection()
            val connections = OamDownloadConnections { connection }
            connections.download("https://example.test/map.zip", 4096, "\"revision-1\"")
            assertEquals("\"revision-1\"", connection.getRequestProperty("If-Range"))
        }

    @Test
    fun weakEntityTagFallsBackToLastModified() {
        assertEquals(
            "Wed, 21 Oct 2026 10:00:00 GMT",
            oamResumeValidator(
                "W/\"weak\"",
                "Wed, 21 Oct 2026 10:00:00 GMT",
            ),
        )
        assertEquals(null, oamResumeValidator("W/\"weak\"", null))
    }

    @Test
    fun freshRequestNeverUsesIfRange() =
        runTest {
            val connection = FakeConnection()
            val connections = OamDownloadConnections { connection }
            connections.download("https://example.test/map.zip", 0, "\"revision-1\"")
            assertEquals(null, connection.getRequestProperty("If-Range"))
        }

    @Test
    fun omittedHeadersOnSecondResumeKeepKnownValidator() =
        runTest {
            val firstValidator = oamResumeValidator(null, "Wed, 21 Oct 2026 10:00:00 GMT")
            val afterPartialResponse = oamResumeValidator(null, null, firstValidator, isAppending = true)
            val connection = FakeConnection()
            OamDownloadConnections { connection }.download(
                "https://example.test/map.zip",
                8192,
                afterPartialResponse,
            )
            assertEquals("Wed, 21 Oct 2026 10:00:00 GMT", connection.getRequestProperty("If-Range"))
            assertEquals(OamResumeResponse.RESTART, oamResumeResponse(8192, HttpURLConnection.HTTP_OK))
            assertEquals(null, oamResumeValidator(null, null, afterPartialResponse, isAppending = false))
        }

    @Test
    fun responsePolicyAppendsOnlyPartialResponsesAndRestartsIgnoredRanges() {
        assertEquals(OamResumeResponse.APPEND, oamResumeResponse(4096, HttpURLConnection.HTTP_PARTIAL))
        assertEquals(OamResumeResponse.RESTART, oamResumeResponse(4096, HttpURLConnection.HTTP_OK))
        assertEquals(OamResumeResponse.RESTART, oamResumeResponse(4096, 416))
        assertEquals(OamResumeResponse.FRESH, oamResumeResponse(0, HttpURLConnection.HTTP_OK))
        assertEquals(OamResumeResponse.ERROR, oamResumeResponse(0, HttpURLConnection.HTTP_PARTIAL))
        assertEquals(OamResumeResponse.ERROR, oamResumeResponse(0, 416))
    }

    private class FakeConnection : HttpURLConnection(URL("https://example.test/file")) {
        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
