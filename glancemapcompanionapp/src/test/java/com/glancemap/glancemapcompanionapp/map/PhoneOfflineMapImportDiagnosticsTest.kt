package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCaptureSlot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class PhoneOfflineMapImportDiagnosticsTest {
    @After
    fun clearDiagnostics() {
        PhoneOfflineMapImportDiagnostics.clear()
        PhoneDebugCapture.stop()
    }

    @Test
    fun successfulImportReportsSafeMapsforgeMetadataAndCopiedSizes() {
        PhoneDebugCapture.start()
        val captureSessionId = requireNotNull(PhoneOfflineMapImportDiagnostics.activeCaptureSessionId())
        val attempt =
            PhoneOfflineMapImportAttempt(
                outcome = PhoneOfflineMapImportOutcome.SUCCESS,
                failureStage = null,
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
                attemptId = "attempt-42",
                captureSessionId = captureSessionId,
                startedAtEpochMs = 100L,
                endedAtEpochMs = 150L,
                stageDurationsMillis = mapOf(PhoneOfflineMapImportStage.COPY to 20L),
                candidateSizeBytes = 1_024L,
                destinationSpaceBytesAtStart = 4_096L,
                destinationSpaceBytesAtEnd = 3_072L,
            )

        PhoneOfflineMapImportDiagnostics.importStarted(
            attempt.attemptId,
            attempt.startedAtEpochMs,
            captureSessionId,
            4_096L,
        )
        PhoneOfflineMapImportDiagnostics.record(attempt)

        val report = requireNotNull(PhoneOfflineMapImportDiagnostics.latestReportSection(captureSessionId))
        assertTrue(report.contains("Outcome: SUCCESS"))
        assertTrue(report.contains("Bytes copied: 1024"))
        assertTrue(report.contains("Candidate size: 1024"))
        assertTrue(report.contains("Duration (ms): 50"))
        assertTrue(report.contains("Stage durations (ms): COPY:20"))
        assertTrue(report.contains("Installed destination size (bytes): 1024"))
        assertTrue(report.contains("Destination space at start: 4096"))
        assertTrue(report.contains("Destination space at end: 3072"))
        assertTrue(report.contains("Result relation: CURRENT_CAPTURE"))
        assertFalse(report.contains("alps.map"))
        assertTrue(report.contains("Min zoom: 8"))
        assertTrue(report.contains("Max zoom: 18"))
        val events = PhoneDebugCapture.snapshot()
        assertEquals(2, events.size)
        assertTrue(events.first().contains("event=offline_map_import_started"))
        assertTrue(events.all { it.contains("attemptId=attempt-42") })
        assertTrue(events.last().contains("event=offline_map_import_completed"))
    }

    @Test
    fun failedImportReportsSafeStageAndCategoryWithoutRawExceptionDetails() {
        val attempt =
            PhoneOfflineMapImportAttempt(
                outcome = PhoneOfflineMapImportOutcome.FAILED,
                failureStage = PhoneOfflineMapImportStage.STREAM_OPEN,
                sourceSizeBytes = 2_048L,
                streamOpened = false,
                bytesCopied = 0L,
                destinationSizeBytes = null,
                candidateValid = null,
                mapFileOpened = null,
                metadata = null,
                finalError = PhoneOfflineMapError.FILE_NOT_READABLE,
                failureCategory = PhoneOfflineMapFailureCategory.SOURCE_UNAVAILABLE,
            )

        PhoneOfflineMapImportDiagnostics.record(attempt)

        val latest = requireNotNull(PhoneOfflineMapImportDiagnostics.latestAttempt())
        val report = requireNotNull(PhoneOfflineMapImportDiagnostics.latestReportSection())
        assertEquals(PhoneOfflineMapImportStage.STREAM_OPEN, latest.failureStage)
        assertEquals(PhoneOfflineMapError.FILE_NOT_READABLE, latest.finalError)
        assertTrue(report.contains("Final error: FILE_NOT_READABLE"))
        assertTrue(report.contains("Result relation: HISTORICAL_RETAINED_RESULT"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("alps.map"))
    }

    @Test
    fun retainedImportIsLabeledAgainstTheExportedCaptureSession() {
        val attempt =
            PhoneOfflineMapImportAttempt(
                outcome = PhoneOfflineMapImportOutcome.SUCCESS,
                failureStage = null,
                sourceSizeBytes = 5L,
                streamOpened = true,
                bytesCopied = 5L,
                destinationSizeBytes = 5L,
                candidateValid = true,
                mapFileOpened = true,
                metadata = null,
                finalError = null,
                captureSessionId = 7L,
                completionCaptured = true,
            )

        assertTrue(attempt.toReportSection(7L).contains("Result relation: CURRENT_CAPTURE"))
        assertTrue(attempt.toReportSection(8L).contains("Result relation: HISTORICAL_RETAINED_RESULT"))
    }

    @Test
    fun folderScanSummaryContainsCountsDurationAndOnlySafeCategories() {
        val summary =
            PhoneOfflineMapFolderScanSummary(
                scanId = "scan-opaque-1",
                captureSessionId = 3L,
                startedAtEpochMs = 100L,
                endedAtEpochMs = 350L,
                durationMillis = 250L,
                candidateCount = 5,
                importedCount = 2,
                skippedCount = 2,
                failedCount = 1,
                failureCategory = PhoneOfflineMapFailureCategory.PERMISSION,
                completionCaptured = true,
            )

        val line = summary.toCaptureLine()
        assertTrue(line.contains("candidates=5 imported=2 skipped=2 failed=1"))
        assertTrue(line.contains("durationMs=250"))
        assertTrue(line.contains("category=PERMISSION"))
        assertTrue(summary.toReportSection(3L).contains("Result relation: CURRENT_CAPTURE"))
        assertFalse(line.contains("content://"))
        assertFalse(line.contains("/private/"))
    }

    @Test
    fun folderScanStartAndCompletionRemainCorrelatedInTheCurrentCapture() {
        PhoneDebugCapture.start()
        val sessionId = requireNotNull(PhoneOfflineMapImportDiagnostics.activeCaptureSessionId())
        val scan = PhoneOfflineMapImportDiagnostics.beginFolderScan()
        scan.candidatesDiscovered(3)
        scan.candidateStarted()
        scan.candidateCompleted(PhoneOfflineMapFolderSyncResult(importedCount = 1))
        scan.candidateStarted()
        scan.candidateCompleted(PhoneOfflineMapFolderSyncResult(skippedCount = 1))
        scan.candidateStarted()
        scan.candidateCompleted(PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FILE_NOT_READABLE))

        val summary = scan.finish(PhoneOfflineMapError.FILE_NOT_READABLE)
        val events = PhoneDebugCapture.snapshot()

        assertEquals(3, summary.candidateCount)
        assertEquals(1, summary.importedCount)
        assertEquals(1, summary.skippedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(PhoneOfflineMapFailureCategory.SOURCE_UNAVAILABLE, summary.failureCategory)
        assertEquals(2, events.size)
        assertTrue(events.first().contains("event=offline_map_folder_scan_started"))
        assertTrue(events.last().contains("event=offline_map_folder_scan_completed"))
        assertTrue(events.all { it.contains("scanId=${summary.scanId}") })
        assertTrue(events.all { it.contains("captureSessionId=$sessionId") })
        assertTrue(
            requireNotNull(PhoneOfflineMapImportDiagnostics.latestFolderScanReportSection(sessionId))
                .contains("Result relation: CURRENT_CAPTURE"),
        )
    }

    @Test
    fun actualImportReportsCorrelatedEventsAndObservedFileSizes() {
        withDirectory { directory ->
            PhoneDebugCapture.start()
            val result =
                PhoneOfflineMapStore(directory) { PhoneOfflineMapValidation() }
                    .import("private-name.map", "map data".byteInputStream())

            assertTrue(result is PhoneOfflineMapImportResult.Success)
            val attempt = requireNotNull(PhoneOfflineMapImportDiagnostics.latestAttempt())
            assertEquals(8L, attempt.bytesCopied)
            assertEquals(8L, attempt.candidateSizeBytes)
            assertEquals(8L, attempt.destinationSizeBytes)
            assertTrue(
                attempt.stageDurationsMillis.keys.containsAll(
                    listOf(
                        PhoneOfflineMapImportStage.COPY,
                        PhoneOfflineMapImportStage.MAPFILE_OPEN,
                        PhoneOfflineMapImportStage.PROMOTION,
                    ),
                ),
            )
            assertTrue(attempt.completionCaptured)
            assertTrue(attempt.durationMillis >= 0L)
            assertTrue(requireNotNull(attempt.destinationSpaceBytesAtStart) >= 0L)
            val events = PhoneDebugCapture.snapshot()
            assertEquals(2, events.size)
            assertTrue(events.all { it.contains("attemptId=${attempt.attemptId}") })
            assertFalse(events.joinToString().contains("private-name.map"))
            assertFalse(events.joinToString().contains(directory.absolutePath))
        }
    }

    @Test
    fun failedCopyReportsPartialCandidateBytesWithoutExceptionDetails() {
        withDirectory { directory ->
            val input =
                object : InputStream() {
                    private var copied = false

                    override fun read(): Int = throw IOException("content://private/source?key=placeholder")

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (copied) return read()
                        copied = true
                        buffer[offset] = 1
                        return 1
                    }
                }
            PhoneDebugCapture.start()
            val result =
                PhoneOfflineMapStore(directory) { PhoneOfflineMapValidation() }
                    .import("private-name.map", input)

            assertEquals(PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.COPY_FAILED), result)
            val attempt = requireNotNull(PhoneOfflineMapImportDiagnostics.latestAttempt())
            assertEquals(1L, attempt.bytesCopied)
            assertEquals(1L, attempt.candidateSizeBytes)
            assertNull(attempt.destinationSizeBytes)
            assertEquals(PhoneOfflineMapFailureCategory.COPY_OR_STORAGE, attempt.failureCategory)
            assertFalse(attempt.toReportSection().contains("content://"))
            assertFalse(PhoneDebugCapture.snapshot().joinToString().contains("placeholder"))
        }
    }

    @Test
    fun completionFromAnEarlierCaptureRemainsHistoricalAndCannotEnterANewCapture() {
        withDirectory { directory ->
            PhoneDebugCapture.start()
            val originalSessionId = requireNotNull(PhoneDebugCapture.activeSessionId())
            val store =
                PhoneOfflineMapStore(directory) {
                    PhoneDebugCapture.stop()
                    PhoneDebugCapture.start()
                    PhoneOfflineMapValidation()
                }

            store.import("private-name.map", "map data".byteInputStream())

            val attempt = requireNotNull(PhoneOfflineMapImportDiagnostics.latestAttempt())
            assertEquals(originalSessionId, attempt.captureSessionId)
            assertFalse(attempt.completionCaptured)
            assertTrue(PhoneDebugCapture.snapshot().isEmpty())
            assertEquals(1, PhoneDebugCapture.snapshot(PhoneDebugCaptureSlot.PREVIOUS).size)
            assertTrue(attempt.toReportSection(originalSessionId).contains("HISTORICAL_RETAINED_RESULT"))
            assertTrue(
                requireNotNull(PhoneOfflineMapImportDiagnostics.latestReportSection(PhoneDebugCapture.sessionId()))
                    .contains("HISTORICAL_RETAINED_RESULT"),
            )
        }
    }

    @Test
    fun abortedScanPreservesDiscoveredCandidatesAndCountsTheFailedCandidate() {
        val scan = PhoneOfflineMapImportDiagnostics.beginFolderScan()
        scan.candidatesDiscovered(4)
        scan.candidateStarted()
        scan.candidateCompleted(PhoneOfflineMapFolderSyncResult(importedCount = 1))
        scan.candidateStarted()
        scan.failed(PhoneOfflineMapFailureCategory.PERMISSION)

        val summary = scan.finish(PhoneOfflineMapError.FOLDER_PERMISSION_LOST)

        assertEquals(4, summary.candidateCount)
        assertEquals(1, summary.importedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(PhoneOfflineMapFailureCategory.PERMISSION, summary.failureCategory)
    }

    @Test
    fun permissionFailureBeforeListingDoesNotInventCandidateCounts() {
        val scan = PhoneOfflineMapImportDiagnostics.beginFolderScan()
        scan.failed(PhoneOfflineMapFailureCategory.PERMISSION)

        val summary = scan.finish(PhoneOfflineMapError.FOLDER_PERMISSION_LOST)

        assertNull(summary.candidateCount)
        assertEquals(0, summary.failedCount)
        assertTrue(summary.toCaptureLine().contains("candidates=unknown"))
    }

    private fun withDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("phone-import-diagnostics-").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
