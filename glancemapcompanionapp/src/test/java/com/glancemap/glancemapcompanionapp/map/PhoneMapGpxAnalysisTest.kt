package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapGpxAnalysisTest {
    @Test
    fun statisticsUseCurrentSpeedAndElevationSettings() {
        val item =
            PhoneMapGpxItem(
                id = "route",
                displayName = "Route",
                track =
                    PhoneMapGpxTrack(
                        id = "route",
                        points =
                            listOf(
                                TrailPoint(GeoPoint(46.0, 6.0), elevationMeters = 100.0),
                                TrailPoint(GeoPoint(46.0, 6.01), elevationMeters = 200.0),
                                TrailPoint(GeoPoint(46.0, 6.02), elevationMeters = 150.0),
                            ),
                    ),
                enabled = true,
            )
        val slow =
            buildPhoneMapGpxAnalysis(
                item = item,
                settings = PhoneMapGpxSettings(flatSpeedMetersPerSecond = 1f),
                generalSettings = PhoneGeneralSettings(),
            )
        val fast =
            buildPhoneMapGpxAnalysis(
                item = item,
                settings = PhoneMapGpxSettings(flatSpeedMetersPerSecond = 2f),
                generalSettings = PhoneGeneralSettings(),
            )

        assertEquals(3, slow.pointCount)
        assertEquals(100.0, slow.totalAscentMeters, 0.001)
        assertEquals(50.0, slow.totalDescentMeters, 0.001)
        assertEquals(3, slow.samples.size)
        assertEquals(100.0, slow.minElevationMeters!!, 0.001)
        assertEquals(200.0, slow.maxElevationMeters!!, 0.001)
        assertTrue(fast.estimatedDurationSeconds < slow.estimatedDurationSeconds)
    }

    @Test
    fun elevationSamplesAreBoundedAndKeepEndpoints() {
        val points =
            (0..9).map { index ->
                TrailPoint(
                    location = GeoPoint(46.0, 6.0 + index * 0.001),
                    elevationMeters = index.toDouble(),
                )
            }
        val item =
            PhoneMapGpxItem(
                id = "route",
                displayName = "Route",
                track = PhoneMapGpxTrack(id = "route", points = points),
                enabled = true,
            )

        val analysis =
            buildPhoneMapGpxAnalysis(
                item = item,
                settings = PhoneMapGpxSettings(),
                generalSettings = PhoneGeneralSettings(),
                maxSamples = 4,
            )

        assertEquals(4, analysis.samples.size)
        assertEquals(0.0, analysis.samples.first().elevationMeters, 0.001)
        assertEquals(9.0, analysis.samples.last().elevationMeters, 0.001)
    }
}
