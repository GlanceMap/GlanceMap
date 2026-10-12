package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mapsforge.core.util.MercatorProjection
import kotlin.math.pow

class PhoneMapScaleTest {
    @Test
    fun scaleUsesPublishedPhysicalGroundResolution() {
        val indicator = calculatePhoneMapScaleIndicator(groundResolutionMetersPerPixel = 4.0, viewportWidthPx = 1_000.0)

        assertEquals("1.0 km", requireNotNull(indicator).label)
        assertEquals(0.25f, indicator.widthRatio, 1e-6f)
        assertEquals(1_000.0, indicator.widthRatio * 1_000.0 * 4.0, 0.001)
    }

    @Test
    fun scaleDistanceTracksFractionalGroundScaleAndUsesNiceMetricSteps() {
        // Equatorial MapLibre camera resolution at zoom 13, then one zoom level closer.
        val zoom13 = calculatePhoneMapScaleIndicator(9.554628535647032, viewportWidthPx = 360.0)
        val zoom14 = calculatePhoneMapScaleIndicator(4.777314267823516, viewportWidthPx = 360.0)

        assertEquals("500 m", requireNotNull(zoom13).label)
        assertEquals("250 m", requireNotNull(zoom14).label)
        assertEquals(0.14536238f, zoom13.widthRatio, 1e-6f)
        assertEquals(zoom13.widthRatio, zoom14.widthRatio, 1e-6f)
    }

    @Test
    fun scaleBarHasTheSameLabelAndDpWidthAcrossDensities() {
        listOf(1.0, 1.5, 2.0, 3.0).forEach { density ->
            val indicator =
                requireNotNull(calculatePhoneMapScaleIndicator(2.0 / density, viewportWidthPx = 360.0 * density))

            assertEquals("200 m", indicator.label)
            assertEquals(100.0, indicator.widthRatio * 360.0, 0.001)
        }
    }

    @Test
    fun imperialLabelPreservesThePhysicalScaleWidth() {
        val indicator =
            requireNotNull(calculatePhoneMapScaleIndicator(2.0, viewportWidthPx = 360.0, isMetric = false))

        assertEquals("656 ft", indicator.label)
        assertEquals(100.0, indicator.widthRatio * 360.0, 0.001)
    }

    @Test
    fun invalidResolutionOrViewportDoesNotProduceScaleBar() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertNull(calculatePhoneMapScaleIndicator(invalid, viewportWidthPx = 360.0))
            assertNull(calculatePhoneMapScaleIndicator(2.0, viewportWidthPx = invalid))
        }
        assertNull(calculatePhoneMapScaleIndicator(Double.MAX_VALUE, viewportWidthPx = 360.0))
    }

    @Test
    fun scaleBarIsHiddenWhenTheSmallestDistanceWouldExceedTheViewport() {
        assertNull(calculatePhoneMapScaleIndicator(0.001, viewportWidthPx = 360.0))
        val indicator = requireNotNull(calculatePhoneMapScaleIndicator(0.01, viewportWidthPx = 360.0))

        assertEquals("1 m", indicator.label)
        assertEquals(100.0, indicator.widthRatio * 360.0, 0.001)
    }

    @Test
    fun scaleSettingsUseTheNativeCameraWorldAndDevicePixelRatio() {
        listOf(1.0, 1.5, 2.0, 3.0).forEach { density ->
            val onlineZoom =
                requireNotNull(phoneMapZoomForScale(0.0, 250, 360.0 * density, tileSizePx = 512.0 * density))
            val offlineZoom =
                requireNotNull(phoneMapZoomForScale(0.0, 250, 360.0 * density, tileSizePx = 256.0 * density))

            assertEquals(14.945767329220462, onlineZoom, 1e-8)
            assertEquals(15.945767329220462, offlineZoom, 1e-8)
        }
    }

    @Test
    fun configuredScaleAgreesWithMapsforgeProjectionAcrossLatitudesAndTileSizes() {
        listOf(0.0, 45.0, 75.0).forEach { latitude ->
            listOf(256, 384, 512, 768).forEach { tileSize ->
                listOf(20, 250, 200_000).forEach { scaleMeters ->
                    val zoom = requireNotNull(phoneMapZoomForScale(latitude, scaleMeters, 1_080.0, tileSize.toDouble()))
                    val sdkResolution =
                        MercatorProjection.calculateGroundResolutionWithScaleFactor(latitude, 2.0.pow(zoom), tileSize)
                    val distanceAcrossScaleBar = sdkResolution * 1_080.0 * PHONE_MAP_SCALE_TARGET_RATIO

                    // The SDK truncates world size to whole pixels.
                    assertEquals(scaleMeters.toDouble(), distanceAcrossScaleBar, scaleMeters * 1e-4)
                }
            }
        }
    }

    @Test
    fun invalidScaleSettingInputsAreRejectedAndZoomKeepsExistingLimits() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertNull(phoneMapZoomForScale(45.0, 250, 1_080.0, tileSizePx = invalid))
            assertNull(phoneMapZoomForScale(45.0, 250, invalid, tileSizePx = 512.0))
        }
        assertNull(phoneMapZoomForScale(Double.NaN, 250, 1_080.0, tileSizePx = 512.0))
        assertNull(phoneMapZoomForScale(45.0, 0, 1_080.0, tileSizePx = 512.0))
        assertEquals(0.0, requireNotNull(phoneMapZoomForScale(0.0, 5_000_000, 1.0, 512.0)), 0.0)
        assertEquals(22.0, requireNotNull(phoneMapZoomForScale(0.0, 1, 10_000.0, 512.0)), 0.0)
    }
}
