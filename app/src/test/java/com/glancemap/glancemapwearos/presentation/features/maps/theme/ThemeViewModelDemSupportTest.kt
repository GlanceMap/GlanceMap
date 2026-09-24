package com.glancemap.glancemapwearos.presentation.features.maps.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemeViewModelDemSupportTest {
    @Test
    fun demProgressCombinesCompletedTilesWithCurrentTileBytes() {
        val progress =
            demDownloadProgressPercent(
                totalTiles = 4,
                completedTiles = 1,
                currentTileBytes = 50L,
                currentTileTotalBytes = 100L,
            )

        assertEquals(37, progress)
    }

    @Test
    fun demProgressUsesCompletedTilesWhenContentLengthIsUnknown() {
        val progress =
            demDownloadProgressPercent(
                totalTiles = 5,
                completedTiles = 2,
                currentTileBytes = 80L,
                currentTileTotalBytes = null,
            )

        assertEquals(40, progress)
    }

    @Test
    fun demProgressReachesOneHundredAfterEveryTileCompletes() {
        val progress =
            demDownloadProgressPercent(
                totalTiles = 3,
                completedTiles = 3,
                currentTileBytes = 0L,
                currentTileTotalBytes = null,
            )

        assertEquals(100, progress)
    }

    @Test
    fun demProgressIsUnavailableBeforeTileCountIsKnown() {
        val progress =
            demDownloadProgressPercent(
                totalTiles = 0,
                completedTiles = 0,
                currentTileBytes = 0L,
                currentTileTotalBytes = null,
            )

        assertEquals(null, progress)
    }

    @Test
    fun demSummaryReportsUnavailableTilesAsIncomplete() {
        assertEquals(
            "DEM download incomplete: 4 unavailable.",
            buildDemSummaryMessage(
                downloaded = 48,
                skipped = 2,
                missing = 4,
                failed = 0,
                remaining = 0,
            ),
        )
    }

    @Test
    fun demSummaryReportsCompleteSelectedSourceDownloadAsSuccessful() {
        assertEquals(
            "DEM download successful.",
            buildDemSummaryMessage(
                downloaded = 48,
                skipped = 2,
                missing = 0,
                failed = 0,
                remaining = 0,
            ),
        )
    }

    @Test
    fun demSummaryReportsFailedTilesAsIncomplete() {
        assertEquals(
            "DEM download incomplete: 1 failed.",
            buildDemSummaryMessage(
                downloaded = 1,
                skipped = 0,
                missing = 0,
                failed = 1,
                remaining = 0,
            ),
        )
    }

    @Test
    fun socketTimeoutIsNotMarkedOfflineWhenInternetIsAvailable() {
        val networkUnavailable =
            classifyDemFailureAsNetworkUnavailable(
                throwable = SocketTimeoutException("Read timed out"),
                internetAvailableNow = true,
            )

        assertFalse(networkUnavailable)
    }

    @Test
    fun socketTimeoutIsMarkedOfflineWhenInternetIsUnavailable() {
        val networkUnavailable =
            classifyDemFailureAsNetworkUnavailable(
                throwable = SocketTimeoutException("Read timed out"),
                internetAvailableNow = false,
            )

        assertTrue(networkUnavailable)
    }

    @Test
    fun unknownHostStillCountsAsOffline() {
        val networkUnavailable =
            classifyDemFailureAsNetworkUnavailable(
                throwable = UnknownHostException("Unable to resolve host"),
                internetAvailableNow = true,
            )

        assertTrue(networkUnavailable)
    }

    @Test
    fun timeoutFailureGetsFriendlyRetryMessage() {
        val message =
            buildDemFailureMessage(
                throwable = SocketTimeoutException("Read timed out"),
                networkUnavailable = false,
            )

        assertEquals(
            "DEM download timed out. Retry when the watch internet connection is stable.",
            message,
        )
    }

    @Test
    fun timeoutFailuresRemainRetryable() {
        assertTrue(isRetryableDemDownloadFailure(SocketTimeoutException("Read timed out")))
    }

    @Test
    fun socketAbortFailuresAreRetryable() {
        assertTrue(isRetryableDemDownloadFailure(SocketException("Software caused connection abort")))
    }

    @Test
    fun resumeRejectedFailureIsRetryable() {
        assertTrue(isRetryableDemDownloadFailure(DemResumeRejectedException("HTTP 416")))
    }

    @Test
    fun invalidDemTileFailureIsNotRetryable() {
        assertFalse(isRetryableDemDownloadFailure(DemInvalidTileException("Bad DEM ZIP")))
    }

    @Test
    fun validDemZipPassesValidation() {
        val file = createTempDemZip(hgtSize = 1201 * 1201 * 2)

        validateDemTileFile(file)
    }

    @Test
    fun validDemGzipPassesValidation() {
        val file = createTempDemGzip(hgtSize = 3601 * 3601 * 2)

        validateDemTileFile(file)
    }

    @Test(expected = DemInvalidTileException::class)
    fun zipWithoutHgtEntryFailsValidation() {
        val file = createTempZipWithEntry(entryName = "readme.txt", payloadSize = 32)

        validateDemTileFile(file)
    }

    @Test(expected = DemInvalidTileException::class)
    fun implausibleHgtSizeFailsValidation() {
        val file = createTempDemZip(hgtSize = 64)

        validateDemTileFile(file)
    }

    private fun createTempDemZip(hgtSize: Int): File =
        createTempZipWithEntry(
            entryName = "N46E006.hgt",
            payloadSize = hgtSize,
        )

    private fun createTempDemGzip(hgtSize: Int): File {
        val file = Files.createTempFile("dem-validation-test", ".hgt.gz").toFile()
        GZIPOutputStream(file.outputStream().buffered()).use { gzip ->
            val buffer = ByteArray(8192)
            var remaining = hgtSize
            while (remaining > 0) {
                val write = minOf(buffer.size, remaining)
                gzip.write(buffer, 0, write)
                remaining -= write
            }
        }
        file.deleteOnExit()
        return file
    }

    private fun createTempZipWithEntry(
        entryName: String,
        payloadSize: Int,
    ): File {
        val file = Files.createTempFile("dem-validation-test", ".hgt.zip").toFile()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            val buffer = ByteArray(8192)
            var remaining = payloadSize
            while (remaining > 0) {
                val write = minOf(buffer.size, remaining)
                zip.write(buffer, 0, write)
                remaining -= write
            }
            zip.closeEntry()
        }
        file.deleteOnExit()
        return file
    }
}
