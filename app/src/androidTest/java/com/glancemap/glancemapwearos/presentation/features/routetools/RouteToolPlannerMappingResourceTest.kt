package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxEtaModelConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RouteToolPlannerMappingResourceTest {
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
    fun loopRequestKeepsDistanceTargetWhenDistanceModeIsSelected() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopTargetMode = LoopTargetMode.DISTANCE,
                        loopDistanceKm = 12,
                    ),
            ).toRoundTripPlannerRequest(currentLocation = LatLong(42.5, 1.6), resources = resources)

        assertEquals(12_000, request.targetDistanceMeters)
        assertEquals("12 km", request.targetLabel)
    }

    @Test
    fun loopRequestConvertsTimeTargetIntoEstimatedDistance() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopTargetMode = LoopTargetMode.TIME,
                        loopDurationMinutes = 90,
                        routeStyle = RouteStylePreset.BALANCED_HIKE,
                        useElevation = true,
                    ),
            ).toRoundTripPlannerRequest(currentLocation = LatLong(42.5, 1.6), resources = resources)

        assertEquals(6_000, request.targetDistanceMeters)
        assertEquals("1 h 30", request.targetLabel)
    }

    @Test
    fun loopTimeRequestUsesConfiguredFlatSpeedWhenAvailable() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopTargetMode = LoopTargetMode.TIME,
                        loopDurationMinutes = 90,
                        routeStyle = RouteStylePreset.BALANCED_HIKE,
                        useElevation = true,
                    ),
            ).toRoundTripPlannerRequest(
                currentLocation = LatLong(42.5, 1.6),
                etaModelConfig = GpxEtaModelConfig(flatSpeedMps = 1.0),
                resources = resources,
            )

        assertEquals(5_400, request.targetDistanceMeters)
        assertEquals("1 h 30", request.targetLabel)
    }

    @Test
    fun loopRequestCanOverrideTargetDistanceAfterEtaCorrection() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopTargetMode = LoopTargetMode.TIME,
                        loopDurationMinutes = 90,
                    ),
            ).toRoundTripPlannerRequest(
                currentLocation = LatLong(42.5, 1.6),
                etaModelConfig = GpxEtaModelConfig(flatSpeedMps = 1.0),
                targetDistanceMetersOverride = 4_800,
                resources = resources,
            )

        assertEquals(4_800, request.targetDistanceMeters)
        assertEquals("1 h 30", request.targetLabel)
    }

    @Test
    fun loopRequestDefaultsToCircuitPreference() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                    ),
            ).toRoundTripPlannerRequest(currentLocation = LatLong(42.5, 1.6), resources = resources)

        assertFalse(request.allowOutAndBack)
        assertEquals(5, request.pointCount)
    }

    @Test
    fun loopRequestCanAllowOutAndBack() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopShapeMode = LoopShapeMode.ALLOW_OUT_AND_BACK,
                    ),
            ).toRoundTripPlannerRequest(currentLocation = LatLong(42.5, 1.6), resources = resources)

        assertTrue(request.allowOutAndBack)
        assertEquals(5, request.pointCount)
    }
}
