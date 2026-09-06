package com.glancemap.glancemapcompanionapp.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException

class BRouterTileDownloaderTest {
    @Test
    fun `computes overall routing progress within download range`() {
        assertEquals(0, overallRoutingDownloadProgress(stepIndex = 0, totalSteps = 1, stepFraction = 0.0))
        assertEquals(43, overallRoutingDownloadProgress(stepIndex = 0, totalSteps = 1, stepFraction = 0.5))
        assertEquals(85, overallRoutingDownloadProgress(stepIndex = 0, totalSteps = 1, stepFraction = 1.0))
    }

    @Test
    fun `marks transient routing statuses as retriable`() {
        assertTrue(isRetriableRoutingStatus(504))
        assertTrue(isRetriableRoutingStatus(429))
        assertFalse(isRetriableRoutingStatus(404))
    }

    @Test
    fun `retries only transient routing IO failures`() {
        assertTrue(isRetriableRoutingIoFailure(SocketTimeoutException("timeout")))
        assertFalse(isRetriableRoutingIoFailure(FileNotFoundException("missing")))
        assertFalse(isRetriableRoutingIoFailure(IOException("invalid routing pack")))
    }

    @Test
    fun `reads rd5 lookup version from routing pack header`() {
        val file = temporaryRoutingPack(lookupVersion = 11)

        try {
            assertEquals(11, readRoutingPackLookupVersion(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `rejects cached routing pack with incompatible lookup version`() {
        val oldFile = temporaryRoutingPack(lookupVersion = 10)
        val currentFile = temporaryRoutingPack(lookupVersion = SUPPORTED_ROUTING_PACK_LOOKUP_VERSION)

        try {
            assertFalse(isUsableRoutingPackCache(oldFile))
            assertTrue(isUsableRoutingPackCache(currentFile))
        } finally {
            oldFile.delete()
            currentFile.delete()
        }
    }

    @Test
    fun `requires final routing file to exist with expected size and valid index`() {
        val file = temporaryRoutingPack(lookupVersion = SUPPORTED_ROUTING_PACK_LOOKUP_VERSION)
        val missingFile = File(file.parentFile, "missing-routing-pack.rd5")

        try {
            assertFalse(isFinalPhoneRoutingFileReady(file, file.length() + 1L))
            assertFalse(isFinalPhoneRoutingFileReady(file, file.length()))
            assertFalse(isFinalPhoneRoutingFileReady(missingFile, file.length()))
        } finally {
            file.delete()
            missingFile.delete()
        }
    }

    @Test
    fun `supported routing pack lookup version matches watch bundled lookup table`() {
        val lookupFile = projectFile("app/src/main/assets/brouter/profiles2/lookups.dat")
        val lookupVersion =
            lookupFile
                .readLines()
                .first { it.startsWith("---lookupversion:") }
                .substringAfter(":")
                .trim()
                .toInt()

        assertEquals(lookupVersion, SUPPORTED_ROUTING_PACK_LOOKUP_VERSION)
    }

    @Test
    fun bboxCoordinatesStayWestSouthEastNorthAndUseTheExpectedTileOrigin() {
        val bbox = BRouterTileMath.parseBbox("-30,10,-20,20")

        assertEquals("-30.00000,10.00000,-20.00000,20.00000", bbox.asQueryString())
        assertEquals(
            listOf(
                "W30_N10.rd5",
                "W25_N10.rd5",
                "W30_N15.rd5",
                "W25_N15.rd5",
            ),
            BRouterTileMath.tileFileNamesForBbox(bbox),
        )
    }

    @Test
    fun remote404KeepsItsHttpStatusDistinctFromLocalFileErrors() {
        val remote404 =
            BRouterTileDownloader.RoutingDownloadHttpException(
                message = "HTTP 404",
                statusCode = 404,
            )

        assertEquals(404, remote404.statusCode)
        assertFalse(isRetriableRoutingStatus(remote404.statusCode))
        assertFalse(isRetriableRoutingIoFailure(FileNotFoundException("local file missing")))
    }

    private fun temporaryRoutingPack(lookupVersion: Int): File =
        File.createTempFile("routing-pack", ".rd5").apply {
            writeBytes(
                byteArrayOf(
                    ((lookupVersion ushr 8) and 0xff).toByte(),
                    (lookupVersion and 0xff).toByte(),
                    0,
                    0,
                ),
            )
        }

    private fun projectFile(path: String): File {
        val candidates =
            listOf(
                File(path),
                File("../$path"),
                File("../../$path"),
            )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not find project file: $path")
    }
}
