package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneMapSettingsTest {
    @Test
    fun defaultsMirrorWatchMapDisplayAndZoomDefaults() {
        val settings = PhoneMapSettings()

        assertEquals(PhoneMapMarkerAnchor.CENTER, settings.markerAnchor)
        assertFalse(settings.autoRecenterEnabled)
        assertEquals(5, settings.autoRecenterDelaySeconds)
        assertEquals(PhoneMapNorthIndicatorMode.ALWAYS, settings.northIndicatorMode)
        assertEquals(PhoneMapMarkerStyle.DOT, settings.markerStyle)
        assertEquals(PhoneMapZoomButtonsMode.BOTH, settings.zoomButtonsMode)
        assertFalse(settings.gpsAccuracyCircleEnabled)
        assertEquals(200, settings.zoomDefaultScaleMeters)
        assertEquals(200_000, settings.zoomMinScaleMeters)
        assertEquals(20, settings.zoomMaxScaleMeters)
        assertFalse(settings.liveElevationEnabled)
        assertFalse(settings.liveDistanceEnabled)
        assertFalse(settings.hillShadingEnabled)
        assertFalse(settings.reliefOverlayEnabled)
        assertFalse(settings.nightModeEnabled)
        assertEquals(PhoneMapNorthReferenceMode.TRUE, settings.northReferenceMode)
        assertEquals(PhoneOfflineDemSource.STANDARD, settings.demSource)
    }

    @Test
    fun normalizationClampsDelayAndKeepsZoomScalesOrdered() {
        val normalized =
            PhoneMapSettings(
                autoRecenterDelaySeconds = 100,
                zoomDefaultScaleMeters = 1,
                zoomMinScaleMeters = 20,
                zoomMaxScaleMeters = 200_000,
            ).normalized()

        assertEquals(MAX_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS, normalized.autoRecenterDelaySeconds)
        assertEquals(200_000, normalized.zoomMinScaleMeters)
        assertEquals(20, normalized.zoomMaxScaleMeters)
        assertEquals(20, normalized.zoomDefaultScaleMeters)
    }

    @Test
    fun northIndicatorModesOnlyShowInTheirConfiguredOrientation() {
        val northUp = PhoneMapMode(orientation = PhoneMapOrientation.NORTH_UP)
        val headingUp = PhoneMapMode(orientation = PhoneMapOrientation.HEADING_UP)

        assertEquals(true, PhoneMapNorthIndicatorMode.NORTH_UP_ONLY.isVisibleFor(northUp, true))
        assertEquals(false, PhoneMapNorthIndicatorMode.NORTH_UP_ONLY.isVisibleFor(headingUp, true))
        assertEquals(true, PhoneMapNorthIndicatorMode.COMPASS_ONLY.isVisibleFor(headingUp, true))
        assertEquals(false, PhoneMapNorthIndicatorMode.COMPASS_ONLY.isVisibleFor(headingUp, false))
    }
}
