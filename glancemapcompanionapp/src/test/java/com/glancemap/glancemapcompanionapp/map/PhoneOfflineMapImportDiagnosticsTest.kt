package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PhoneOfflineMapImportDiagnosticsTest {
    @After
    fun clearDiagnostics() {
        PhoneOfflineMapImportDiagnostics.clear()
        PhoneDebugCapture.stop()
    }

    @Test
    fun successfulImportReportsSafeMapsforgeMetadataAndCopiedSizes() {
        val attempt =
            PhoneOfflineMapImportAttempt(
                outcome = PhoneOfflineMapImportOutcome.SUCCESS,
                failureStage = null,
                displayName = "alps.map",
                mimeType = "application/octet-stream",
                sourceSizeBytes = 1_024L,
                streamOpened = true,
                bytesCopied = 1_024L,
                destinationSizeBytes = 1_024L,
                candidateValid = true,
                mapFileOpened = true,
                metadata =
                    PhoneOfflineMapsforgeMetadata(
                        boundingBoxAvailable = true,
                        minZoom = 8,
                        maxZoom = 18,
                        startPositionAvailable = true,
                    ),
                finalError = null,
                exceptionClass = null,
                exceptionMessage = null,
            )

        PhoneDebugCapture.start()
        PhoneOfflineMapImportDiagnostics.record(attempt)

        val report = requireNotNull(PhoneOfflineMapImportDiagnostics.latestReportSection())
        assertTrue(report.contains("Outcome: SUCCESS"))
        assertTrue(report.contains("Bytes copied: 1024"))
        assertTrue(report.contains("Min zoom: 8"))
        assertTrue(report.contains("Max zoom: 18"))
        assertTrue(PhoneDebugCapture.snapshot().single().contains("stage=COMPLETE"))
    }

    @Test
    fun failedImportReportsItsStageAndRedactsUriAndAbsolutePath() {
        val exception =
            IOException(
                "Could not open content://media/external/file/42 from /storage/emulated/0/Maps/alps.map",
            ).toPhoneOfflineMapImportException()
        val attempt =
            PhoneOfflineMapImportAttempt(
                outcome = PhoneOfflineMapImportOutcome.FAILED,
                failureStage = PhoneOfflineMapImportStage.STREAM_OPEN,
                displayName = "alps.map",
                mimeType = null,
                sourceSizeBytes = 2_048L,
                streamOpened = false,
                bytesCopied = 0L,
                destinationSizeBytes = null,
                candidateValid = null,
                mapFileOpened = null,
                metadata = null,
                finalError = PhoneOfflineMapError.FILE_NOT_READABLE,
                exceptionClass = exception.className,
                exceptionMessage = exception.message,
            )

        PhoneOfflineMapImportDiagnostics.record(attempt)

        val latest = requireNotNull(PhoneOfflineMapImportDiagnostics.latestAttempt())
        val report = requireNotNull(PhoneOfflineMapImportDiagnostics.latestReportSection())
        assertEquals(PhoneOfflineMapImportStage.STREAM_OPEN, latest.failureStage)
        assertEquals(PhoneOfflineMapError.FILE_NOT_READABLE, latest.finalError)
        assertTrue(report.contains("Exception: IOException"))
        assertTrue(report.contains("Final error: FILE_NOT_READABLE"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("/storage/"))
    }
}
