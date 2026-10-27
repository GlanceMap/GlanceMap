package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OamRemoteMetadataChecksTest {
    @Test
    fun `checks overlap while keeping results in request order`() =
        runTest {
            var active = 0
            var maximumActive = 0
            val requests = (1..7).map { request(it) }
            val previous = requests.associate { it.url to metadata(it, 10) }

            val results =
                checkRemoteMetadata(requests, previous) { request ->
                    active++
                    maximumActive = maxOf(maximumActive, active)
                    delay(100)
                    active--
                    metadata(request, if (request == requests[1]) 20 else 10)
                }

            assertTrue(maximumActive in 2..4)
            assertEquals(requests.map { it.fileName }, results.map { it.fileName })
            assertEquals(RemoteMetadataComparison.CHANGED, results[1].comparison)
            assertEquals(7, results.count { it.checked })
        }

    @Test
    fun `transient failure is retried but absent baseline remains unknown`() =
        runTest {
            val first = request(1)
            val second = request(2)
            var attempts = 0
            val results =
                checkRemoteMetadata(listOf(first, second), mapOf(first.url to metadata(first, 10))) { request ->
                    attempts++
                    if (attempts == 1) throw IOException("connection reset")
                    metadata(request, 10)
                }

            assertEquals(2, attempts)
            assertEquals(RemoteMetadataComparison.SAME, results[0].comparison)
            assertEquals(RemoteMetadataComparison.UNKNOWN, results[1].comparison)
            assertEquals(false, results[1].checked)
        }

    @Test
    fun `server errors stay incomplete without retrying`() =
        runTest {
            val request = request(1)
            var attempts = 0
            val results =
                checkRemoteMetadata(listOf(request), mapOf(request.url to metadata(request, 10))) {
                    attempts++
                    throw OamMetadataHttpException("HTTP 404")
                }

            assertEquals(1, attempts)
            assertEquals(RemoteMetadataComparison.UNKNOWN, results.single().comparison)
            assertEquals(false, results.single().checked)
        }

    @Test
    fun `cancellation is propagated without retry`() =
        runTest {
            val request = request(1)
            var attempts = 0
            try {
                checkRemoteMetadata(listOf(request), mapOf(request.url to metadata(request, 10))) {
                    attempts++
                    throw CancellationException("cancelled")
                }
                throw AssertionError("Cancellation should propagate")
            } catch (_: CancellationException) {
                assertEquals(1, attempts)
            }
        }

    private fun request(index: Int) = RemoteFileRequest("https://example.test/$index", "$index.zip")

    private fun metadata(
        request: RemoteFileRequest,
        size: Long,
    ) = OamRemoteFileMetadata(request.url, request.fileName, null, null, size)
}
