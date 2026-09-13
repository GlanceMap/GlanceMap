package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.map.maplibre.phoneMapGpxFitIsStillEligible
import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapComparisonTest {
    @Test
    fun cameraSynchronizationUsesTolerancesIncludingAcrossNorth() {
        val camera =
            PhoneMapCameraSnapshot(
                latitude = 46.0,
                longitude = 7.0,
                zoom = 12.0,
                bearingDegrees = 359.8f,
            )

        assertFalse(
            phoneMapComparisonCameraNeedsSync(
                current = camera,
                target = camera.copy(longitude = 7.0000005, bearingDegrees = 0.1f),
            ),
        )
        assertTrue(
            phoneMapComparisonCameraNeedsSync(
                current = camera,
                target = camera.copy(zoom = 12.2),
            ),
        )
    }

    @Test
    fun transparencyControlsOnlyTheUpperLayerOpacity() {
        val comparison =
            PhoneMapComparisonState(
                layer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
            )

        assertEquals(1f, comparison.withTransparency(0f).overlayAlpha(), 0f)
        assertEquals(0.75f, comparison.withTransparency(25f).overlayAlpha(), 0f)
        assertEquals(0.5f, comparison.overlayAlpha(), 0f)
        assertEquals(0.25f, comparison.withTransparency(75f).overlayAlpha(), 0f)
        assertEquals(0f, comparison.withTransparency(100f).overlayAlpha(), 0f)
        assertEquals(1f, comparison.withTransparency(-1f).overlayAlpha(), 0f)
        assertEquals(0f, comparison.withTransparency(101f).overlayAlpha(), 0f)
        assertEquals(comparison.layer, comparison.withTransparency(51f).layer)
        assertEquals(comparison.layer, comparison.withTransparency(100f).layer)
    }

    @Test
    fun onlyComparisonMapsUseTextureSurfaces() {
        assertFalse(phoneMapLibreSurfaceUsesTexture(PhoneMapLibreSurfaceMode.PRIMARY))
        assertTrue(phoneMapLibreSurfaceUsesTexture(PhoneMapLibreSurfaceMode.COMPARISON))
    }

    @Test
    fun visibleComparisonOwnsSemanticOverlays() {
        assertFalse(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = false,
                onlineComparisonActive = false,
            ),
        )
        assertTrue(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = true,
                onlineComparisonActive = false,
            ),
        )
        assertTrue(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = false,
                onlineComparisonActive = true,
            ),
        )
    }

    @Test
    fun comparisonOwnsUserCameraOnlyWhileVisible() {
        assertFalse(phoneMapComparisonOwnsUserCamera(false, false))
        assertTrue(phoneMapComparisonOwnsUserCamera(true, false))
        assertTrue(phoneMapComparisonOwnsUserCamera(false, true))
    }

    @Test
    fun onlineComparisonOwnsFollowAndCommandsOnlyAboveOfflineBase() {
        assertFalse(phoneMapComparisonOwnsFollowAndCommands(PhoneMapSource.Online, true))
        assertFalse(phoneMapComparisonOwnsFollowAndCommands(PhoneMapSource.Offline(offlineMap()), false))
        assertTrue(phoneMapComparisonOwnsFollowAndCommands(PhoneMapSource.Offline(offlineMap()), true))
    }

    @Test
    fun initialGpxFitHasExactlyOneOwnerAcrossSourceTransitions() {
        assertFalse(phoneMapComparisonOwnsInitialGpxFit(PhoneMapSource.Online, true))
        assertFalse(phoneMapComparisonOwnsInitialGpxFit(PhoneMapSource.Offline(offlineMap()), false))
        assertTrue(phoneMapComparisonOwnsInitialGpxFit(PhoneMapSource.Offline(offlineMap()), true))
    }

    @Test
    fun delayedGpxFitRequiresCurrentRendererOwnerAndUnfittedState() {
        assertTrue(phoneMapGpxFitIsStillEligible(true, true, false))
        assertFalse(phoneMapGpxFitIsStillEligible(false, true, false))
        assertFalse(phoneMapGpxFitIsStillEligible(true, false, false))
        assertFalse(phoneMapGpxFitIsStillEligible(true, true, true))
    }

    @Test
    fun fractionalCameraSyncStillRequiresNoFeedbackWhenValuesMatch() {
        val camera = PhoneMapCameraSnapshot(46.0, 7.0, 12.75, 45f)

        assertFalse(phoneMapComparisonCameraNeedsSync(camera, camera.copy()))
    }

    @Test
    fun cameraZoomConversionPreservesGroundScaleAcrossDensitiesAndFractionalZooms() {
        val latitude = 47.5
        val mapLibreZoom = 12.75

        listOf(1.0, 2.0, 3.0).forEach { density ->
            val mapLibreTileScale = PHONE_MAPLIBRE_CAMERA_TILE_SIZE_PX * density
            val mapsforgeTileSize = 256.0 * density
            val expectedResolution =
                phoneGroundResolutionMetersPerPixel(latitude, mapLibreZoom, mapLibreTileScale)
            val mapsforgeZoom =
                phoneMapsforgeZoomForGroundResolution(latitude, expectedResolution, mapsforgeTileSize)
            val mapLibreZoomAgain =
                phoneMapLibreZoomForGroundResolution(latitude, expectedResolution, density)

            assertEquals(
                expectedResolution,
                phoneGroundResolutionMetersPerPixel(latitude, mapsforgeZoom, mapsforgeTileSize),
                1e-9,
            )
            assertEquals(mapLibreZoom, mapLibreZoomAgain, 1e-9)
            assertEquals(
                mapLibreZoom + 1.0,
                phoneMapsforgeZoomForMapLibreZoom(mapLibreZoom, mapsforgeTileSize, density),
                1e-9,
            )
        }
    }

    @Test
    fun comparisonOwnershipRemovesGpxSegmentsFromTheBaseRenderer() {
        val segments =
            listOf(
                PhoneMapRouteSegment(
                    points = listOf(GeoPoint(46.0, 7.0), GeoPoint(46.1, 7.1)),
                ),
            )

        assertEquals(
            emptyList<PhoneMapRouteSegment>(),
            phoneMapComparisonBaseGpxSegments(
                segments = segments,
                comparisonOwnsSemanticOverlays = true,
            ),
        )
        assertEquals(
            segments,
            phoneMapComparisonBaseGpxSegments(
                segments = segments,
                comparisonOwnsSemanticOverlays = false,
            ),
        )
    }

    private fun offlineMap(): PhoneOfflineMap = PhoneOfflineMap(java.io.File("alps.map"))
}
