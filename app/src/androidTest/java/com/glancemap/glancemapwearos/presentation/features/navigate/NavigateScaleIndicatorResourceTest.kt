package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.core.maps.MAP_ZOOM_REPRESENTATIVE_LATITUDE_DEGREES
import com.glancemap.glancemapwearos.core.maps.MAP_ZOOM_REPRESENTATIVE_VIEWPORT_WIDTH_PX
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class NavigateScaleIndicatorResourceTest {
    private lateinit var previousLocale: Locale

    @Before
    fun useEnglishFormattingLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreFormattingLocale() {
        Locale.setDefault(previousLocale)
    }

    private val resources =
        InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            context.createConfigurationContext(configuration).resources
        }

    @Test
    fun farthestBoundShowsConfiguredTwoHundredKilometerScaleWithTruthfulBarWidth() {
        val indicator =
            requireNotNull(
                calculateScaleIndicatorForZoom(
                    zoomLevel = 6,
                    viewportWidthPx = MAP_ZOOM_REPRESENTATIVE_VIEWPORT_WIDTH_PX,
                    latitudeDegrees = MAP_ZOOM_REPRESENTATIVE_LATITUDE_DEGREES,
                    isMetric = true,
                    preferredScaleMeters = 200_000,
                    resources = resources,
                ),
            )

        assertEquals("200 km", indicator.label)
        assertTrue(indicator.widthRatio < 1f)
    }

    @Test
    fun closestBoundShowsConfiguredTwentyMeterScaleWithTruthfulBarWidth() {
        val indicator =
            requireNotNull(
                calculateScaleIndicatorForZoom(
                    zoomLevel = 20,
                    viewportWidthPx = MAP_ZOOM_REPRESENTATIVE_VIEWPORT_WIDTH_PX,
                    latitudeDegrees = MAP_ZOOM_REPRESENTATIVE_LATITUDE_DEGREES,
                    isMetric = true,
                    preferredScaleMeters = 20,
                    resources = resources,
                ),
            )

        assertEquals("20 m", indicator.label)
        assertTrue(indicator.widthRatio > 1f)
    }

    @Test
    fun closestZoomUsesConfiguredFiveMeterScale() {
        val indicator =
            requireNotNull(
                calculateScaleIndicatorForZoom(
                    zoomLevel = 22,
                    viewportWidthPx = MAP_ZOOM_REPRESENTATIVE_VIEWPORT_WIDTH_PX,
                    latitudeDegrees = MAP_ZOOM_REPRESENTATIVE_LATITUDE_DEGREES,
                    isMetric = true,
                    preferredScaleMeters = 5,
                    resources = resources,
                ),
            )

        assertEquals("5 m", indicator.label)
        assertTrue(indicator.widthRatio > 1f)
    }

    @Test
    fun fiveMeterLimitProducesStableCloseZoomLabels() {
        val labels =
            (18..22).map { zoomLevel ->
                val preferredScaleMeters =
                    preferredScaleMetersForZoomLevel(
                        currentZoomLevel = zoomLevel,
                        zoomMin = 6,
                        zoomMax = 22,
                        zoomMinScaleMeters = 200_000,
                        zoomMaxScaleMeters = 5,
                    )
                requireNotNull(
                    calculateScaleIndicatorForZoom(
                        zoomLevel = zoomLevel,
                        viewportWidthPx = MAP_ZOOM_REPRESENTATIVE_VIEWPORT_WIDTH_PX,
                        latitudeDegrees = MAP_ZOOM_REPRESENTATIVE_LATITUDE_DEGREES,
                        isMetric = true,
                        preferredScaleMeters = preferredScaleMeters,
                        resources = resources,
                    ),
                ).label
            }

        assertEquals(
            listOf("50 m", "25 m", "20 m", "10 m", "5 m"),
            labels,
        )
    }

    @Test
    fun twentyMeterLimitRemainsTheFinalCloseZoomLabel() {
        val labels =
            (18..20).map { zoomLevel ->
                val preferredScaleMeters =
                    preferredScaleMetersForZoomLevel(
                        currentZoomLevel = zoomLevel,
                        zoomMin = 6,
                        zoomMax = 20,
                        zoomMinScaleMeters = 200_000,
                        zoomMaxScaleMeters = 20,
                    )
                requireNotNull(
                    calculateScaleIndicatorForZoom(
                        zoomLevel = zoomLevel,
                        viewportWidthPx = MAP_ZOOM_REPRESENTATIVE_VIEWPORT_WIDTH_PX,
                        latitudeDegrees = MAP_ZOOM_REPRESENTATIVE_LATITUDE_DEGREES,
                        isMetric = true,
                        preferredScaleMeters = preferredScaleMeters,
                        resources = resources,
                    ),
                ).label
            }

        assertEquals(listOf("50 m", "25 m", "20 m"), labels)
    }

    @Test
    fun tenMeterLimitRemainsTheFinalCloseZoomLabel() {
        val labels =
            (18..21).map { zoomLevel ->
                val preferredScaleMeters =
                    preferredScaleMetersForZoomLevel(
                        currentZoomLevel = zoomLevel,
                        zoomMin = 6,
                        zoomMax = 21,
                        zoomMinScaleMeters = 200_000,
                        zoomMaxScaleMeters = 10,
                    )
                requireNotNull(
                    calculateScaleIndicatorForZoom(
                        zoomLevel = zoomLevel,
                        viewportWidthPx = MAP_ZOOM_REPRESENTATIVE_VIEWPORT_WIDTH_PX,
                        latitudeDegrees = MAP_ZOOM_REPRESENTATIVE_LATITUDE_DEGREES,
                        isMetric = true,
                        preferredScaleMeters = preferredScaleMeters,
                        resources = resources,
                    ),
                ).label
            }

        assertEquals(listOf("50 m", "25 m", "20 m", "10 m"), labels)
    }
}
