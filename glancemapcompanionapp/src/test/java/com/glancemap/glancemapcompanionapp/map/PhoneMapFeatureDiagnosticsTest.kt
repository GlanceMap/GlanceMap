package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapFeatureDiagnosticsTest {
    @Test
    fun poiReadFailuresAreFailedOrPartialAndStaleResultsKeepTheirStatus() {
        val emptySuccess = PhoneMapPoiQueryResult(queriedSources = 2)
        val allFailed = PhoneMapPoiQueryResult(queriedSources = 2, failedSources = 2)
        val partial = PhoneMapPoiQueryResult(queriedSources = 2, failedSources = 1)

        assertEquals(PhoneMapPoiQueryDiagnosticOutcome.COMPLETED, phoneMapPoiQueryOutcome(emptySuccess, true))
        assertEquals(PhoneMapPoiQueryDiagnosticOutcome.FAILED, phoneMapPoiQueryOutcome(allFailed, true))
        assertEquals(PhoneMapPoiQueryDiagnosticOutcome.PARTIAL, phoneMapPoiQueryOutcome(partial, true))
        assertEquals(PhoneMapPoiQueryDiagnosticOutcome.STALE_RESULT_REJECTED, phoneMapPoiQueryOutcome(partial, false))
        val line =
            phoneMapPoiQueryDiagnosticLine(
                phoneMapPoiQueryOutcome(partial, true),
                requestId = 1L,
                resultCount = 0,
                durationMillis = 2L,
                sourceCounts = partial,
            )
        assertTrue(line.contains("event=poi_query_partial"))
        assertTrue(line.contains("queried_sources=2 failed_sources=1"))
    }

    @Test
    fun gpxLoadSummaryContainsOnlyCountsAndOutcome() {
        val line =
            phoneMapGpxLoadDiagnosticLine(
                event = PhoneMapGpxLoadDiagnosticEvent.COMPLETED,
                generation = 8L,
                durationMillis = 125L,
                counts = PhoneMapGpxLoadCounts(routeTracks = 2, folderTracks = 1, skippedSources = 1, points = 240),
            )

        assertTrue(line.contains("event=gpx_load_completed"))
        assertTrue(line.contains("outcome=partial"))
        assertTrue(line.contains("route_tracks=2 folder_tracks=1 skipped_sources=1 points=240"))
        assertFalse(line.contains("track.gpx"))
        assertFalse(line.contains("46.0"))
    }

    @Test
    fun gpxFailureIsClassifiedWithoutRetainingExceptionDetails() {
        val line =
            phoneMapGpxLoadDiagnosticLine(
                event = PhoneMapGpxLoadDiagnosticEvent.FAILED,
                generation = 9L,
                durationMillis = 500L,
                counts = PhoneMapGpxLoadCounts(),
            )

        assertTrue(line.contains("event=gpx_load_failed"))
        assertTrue(line.contains("outcome=failed"))
        assertFalse(line.contains("secret-track.gpx"))
        assertFalse(line.contains("private exception"))
    }

    @Test
    fun poiQuerySummaryContainsResultCountAndDurationWithoutPoiIdentity() {
        val line =
            phoneMapPoiQueryDiagnosticLine(
                outcome = PhoneMapPoiQueryDiagnosticOutcome.STALE_RESULT_REJECTED,
                requestId = 12L,
                resultCount = 4,
                durationMillis = 235L,
            )

        assertTrue(line.contains("event=poi_query_stale_result_rejected"))
        assertTrue(line.contains("results=4 duration_ms=235"))
        assertFalse(line.contains("restaurant"))
        assertFalse(line.contains("46.0"))

        val failureLine =
            phoneMapPoiQueryDiagnosticLine(
                outcome = PhoneMapPoiQueryDiagnosticOutcome.FAILED,
                requestId = 13L,
                resultCount = 0,
                durationMillis = 17L,
            )
        assertTrue(failureLine.contains("event=poi_query_failed"))
        assertFalse(failureLine.contains("raw exception"))
    }
}
