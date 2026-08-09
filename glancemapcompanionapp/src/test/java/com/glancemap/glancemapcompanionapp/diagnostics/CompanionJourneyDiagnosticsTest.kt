package com.glancemap.glancemapcompanionapp.diagnostics

import com.glancemap.glancemapcompanionapp.weather.WeatherForecastSource
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionJourneyDiagnosticsTest {
    @After
    fun stopCapture() {
        PhoneDebugCapture.stop()
    }

    @Test
    fun recordsOnlyBucketsForImportedRouteContents() {
        PhoneDebugCapture.start()

        CompanionJourneyDiagnostics.routeImportSucceeded(
            pointCount = 825,
            waypointCount = 51,
            elevationCount = 825,
        )

        val line = PhoneDebugCapture.snapshot().single()
        val event = line.substringAfter("[CompanionJourney] ")
        assertTrue(line.contains("[CompanionJourney]"))
        assertTrue(event.contains("event=route_import outcome=success"))
        assertTrue(event.contains("route_points=251_1000"))
        assertTrue(event.contains("waypoints=26_plus"))
        assertTrue(event.contains("elevation=complete"))
        assertFalse(event.contains("825"))
        assertFalse(event.contains("waypoints=51"))
    }

    @Test
    fun redactsLiveHikeRouteAndMetricValuesAndDeduplicatesUnchangedSnapshot() {
        PhoneDebugCapture.start()
        val snapshot =
            ActiveHikeSnapshot(
                phase = ActiveHikePhase.FOLLOWING_ROUTE,
                routeId = "private-route-id",
                routeTitle = "Private Sunday hike",
                distanceFromStartMeters = 12_345.0,
                distanceRemainingMeters = 6_789.0,
                progressFraction = 0.64,
                estimatedRemainingSeconds = 2_345L,
                remainingAscentMeters = 456.0,
                remainingDescentMeters = 789.0,
                activeDurationSeconds = 3_600L,
                currentSpeedMetersPerSecond = 1.9,
                currentAltitudeMeters = 2_100.0,
                offRoute = false,
                recordedAtEpochMillis = 1L,
            )

        CompanionJourneyDiagnostics.activeHikeSnapshotAccepted(snapshot)
        CompanionJourneyDiagnostics.activeHikeSnapshotAccepted(snapshot)

        val event = PhoneDebugCapture.snapshot().single().substringAfter("[CompanionJourney] ")
        assertTrue(event.contains("event=active_hike_snapshot outcome=accepted"))
        assertTrue(event.contains("mode=routed"))
        assertTrue(event.contains("progress=true"))
        assertTrue(event.contains("eta=true"))
        assertTrue(event.contains("recording_metrics=true"))
        assertFalse(event.contains("private-route-id"))
        assertFalse(event.contains("Private Sunday hike"))
        assertFalse(event.contains("12345"))
        assertFalse(event.contains("6789"))
        assertFalse(event.contains("2100"))
    }

    @Test
    fun recordsWeatherSourceWithoutForecastContents() {
        PhoneDebugCapture.start()

        CompanionJourneyDiagnostics.routeWeatherRequested(forceRefresh = true)
        CompanionJourneyDiagnostics.routeWeatherSucceeded(WeatherForecastSource.STALE_CACHE)

        val lines = PhoneDebugCapture.snapshot()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("event=route_weather outcome=requested refresh=true"))
        assertTrue(lines[1].contains("event=route_weather outcome=success source=stale_cache"))
    }
}
