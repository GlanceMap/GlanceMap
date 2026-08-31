package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapGpxSettingsTest {
    @Test
    fun defaultsMirrorWatchGpxAppearanceDefaults() {
        val settings = PhoneMapGpxSettings()

        assertEquals(0xFFFF00FF.toInt(), settings.trackColorArgb)
        assertEquals(PhoneMapGpxColorMode.SOLID, settings.colorMode)
        assertEquals(8f, settings.trackWidth, 0f)
        assertEquals(70, settings.trackOpacityPercent)
        assertFalse(settings.directionArrowsEnabled)
        assertTrue(settings.inspectionEnabled)
        assertEquals(DEFAULT_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND, settings.flatSpeedMetersPerSecond, 0f)
        assertFalse(settings.advancedEtaEnabled)
        assertFalse(settings.staminaAdjustmentEnabled)
    }

    @Test
    fun normalizationKeepsAppearanceValuesInsideWatchRanges() {
        val normalized =
            PhoneMapGpxSettings(
                trackWidth = -10f,
                trackOpacityPercent = 200,
            ).normalized()

        assertEquals(MIN_PHONE_GPX_TRACK_WIDTH, normalized.trackWidth, 0f)
        assertEquals(MAX_PHONE_GPX_TRACK_OPACITY_PERCENT, normalized.trackOpacityPercent)
    }

    @Test
    fun elevationModeKeepsTrackColorsAndAddsDirectionArrows() {
        val segments =
            listOf(
                PhoneMapRouteSegment(
                    points =
                        listOf(
                            GeoPoint(46.0, 6.0),
                            GeoPoint(46.0, 6.01),
                            GeoPoint(46.0, 6.02),
                        ),
                    elevationMeters = listOf(0.0, 10.0, 210.0),
                ),
            )
        val rendered =
            segments.toPhoneMapGpxRenderSegments(
                PhoneMapGpxSettings(
                    colorMode = PhoneMapGpxColorMode.ELEVATION,
                    directionArrowsEnabled = true,
                ),
            )

        assertTrue(rendered.any { segment -> segment.colorArgb == 0xFFD9E3EA.toInt() })
        assertTrue(rendered.any { segment -> segment.colorArgb == 0xFFFF8A3C.toInt() })
        assertTrue(rendered.any { segment -> segment.colorArgb == 0xFF000000.toInt() })
    }

    @Test
    fun routeSegmentRetainsElevationAlongsideCoordinates() {
        val segment =
            PhoneMapGpxTrack(
                id = "track",
                points =
                    listOf(
                        TrailPoint(GeoPoint(46.0, 6.0), elevationMeters = 100.0),
                        TrailPoint(GeoPoint(46.0, 6.01), elevationMeters = 120.0),
                    ),
            ).toRouteSegments().single()

        assertEquals(listOf(100.0, 120.0), segment.elevationMeters)
    }

    @Test
    fun advancedEtaSettingsFeedSharedRouteProfile() {
        val points =
            listOf(
                TrailPoint(GeoPoint(46.0, 6.0), elevationMeters = 100.0),
                TrailPoint(GeoPoint(46.0, 6.01), elevationMeters = 0.0),
            )
        val settings =
            PhoneMapGpxSettings(
                flatSpeedMetersPerSecond = 10f,
                advancedEtaEnabled = true,
                uphillVerticalMetersPerHour = 3_600f,
                downhillVerticalMetersPerHour = 1_800f,
            )

        val profile = buildTrailRouteProfile(points, settings.toTrailPacingConfig(points))

        assertEquals(1_800.0, settings.toTrailPacingConfig(points).downhillVerticalMetersPerHour, 0.0)
        assertTrue(profile.estimatedDurationSeconds > profile.totalDistanceMeters / 10.0)
    }

    @Test
    fun bikeActivityUsesTheBikeDefaultSpeedForEta() {
        val settings = PhoneMapGpxSettings()

        assertEquals(
            DEFAULT_PHONE_BIKE_GPX_FLAT_SPEED_METERS_PER_SECOND.toDouble(),
            settings
                .toTrailPacingConfig(
                    points = emptyList(),
                    generalSettings = PhoneGeneralSettings(activityProfile = PhoneActivityProfile.BIKE),
                ).flatSpeedMetersPerSecond,
            0.0001,
        )
    }
}
