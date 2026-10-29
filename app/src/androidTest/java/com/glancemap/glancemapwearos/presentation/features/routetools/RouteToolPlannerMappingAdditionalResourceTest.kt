package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RouteToolPlannerMappingAdditionalResourceTest {
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
    fun coordinatesRouteMapsCurrentStartToEnteredDestination() {
        val current = LatLong(42.5, 1.6)
        val request =
            coordinateSession(
                startSource = RouteEndpointSource.CURRENT_LOCATION,
                destinationSource = RouteEndpointSource.COORDINATES,
                destination = LatLong(42.6, 1.7),
            ).toRoutePlannerRequest(currentLocation = current, resources = resources)

        assertEquals(current, request.origin)
        assertEquals(LatLong(42.6, 1.7), request.destination)
    }

    @Test
    fun coordinatesRouteMapsEnteredStartToCurrentDestination() {
        val current = LatLong(42.5, 1.6)
        val request =
            coordinateSession(
                startSource = RouteEndpointSource.COORDINATES,
                start = LatLong(42.4, 1.5),
                destinationSource = RouteEndpointSource.CURRENT_LOCATION,
            ).toRoutePlannerRequest(currentLocation = current, resources = resources)

        assertEquals(LatLong(42.4, 1.5), request.origin)
        assertEquals(current, request.destination)
    }

    @Test
    fun coordinatesRouteMapsTwoEnteredEndpointsWithoutCurrentLocation() {
        val request =
            coordinateSession(
                startSource = RouteEndpointSource.COORDINATES,
                start = LatLong(42.4, 1.5),
                destinationSource = RouteEndpointSource.COORDINATES,
                destination = LatLong(42.6, 1.7),
            ).toRoutePlannerRequest(currentLocation = null, resources = resources)

        assertEquals(LatLong(42.4, 1.5), request.origin)
        assertEquals(LatLong(42.6, 1.7), request.destination)
    }

    @Test
    fun coordinatesRouteRejectsCurrentToCurrent() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                coordinateSession(
                    startSource = RouteEndpointSource.CURRENT_LOCATION,
                    destinationSource = RouteEndpointSource.CURRENT_LOCATION,
                ).toRoutePlannerRequest(currentLocation = LatLong(42.5, 1.6), resources = resources)
            }

        assertTrue(error.message.orEmpty().contains("Choose coordinates"))
    }

    @Test
    fun coordinatesRouteRejectsEffectivelySameEndpoints() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                coordinateSession(
                    startSource = RouteEndpointSource.COORDINATES,
                    start = LatLong(42.500000, 1.600000),
                    destinationSource = RouteEndpointSource.COORDINATES,
                    destination = LatLong(42.500005, 1.600005),
                ).toRoutePlannerRequest(currentLocation = null, resources = resources)
            }

        assertTrue(error.message.orEmpty().contains("must be different"))
    }

    @Test
    fun multiPointRequestUsesMiddlePointsAsViaPoints() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.MULTI_POINT_CHAIN,
                    ),
                chainPoints =
                    listOf(
                        LatLong(42.50, 1.50),
                        LatLong(42.55, 1.55),
                        LatLong(42.60, 1.60),
                        LatLong(42.65, 1.65),
                    ),
            ).toRoutePlannerRequest(currentLocation = null, resources = resources)

        assertEquals(LatLong(42.50, 1.50), request.origin)
        assertEquals(LatLong(42.65, 1.65), request.destination)
        assertEquals(
            listOf(LatLong(42.55, 1.55), LatLong(42.60, 1.60)),
            request.viaPoints,
        )
    }

    private fun coordinateSession(
        startSource: RouteEndpointSource,
        start: LatLong? = null,
        destinationSource: RouteEndpointSource,
        destination: LatLong? = null,
    ): RouteToolSession =
        RouteToolSession(
            options =
                RouteToolOptions(
                    toolKind = RouteToolKind.CREATE,
                    createMode = RouteCreateMode.COORDINATES,
                    startEndpointSource = startSource,
                    startCoordinateLatitude = start?.latitude,
                    startCoordinateLongitude = start?.longitude,
                    destinationEndpointSource = destinationSource,
                    destinationCoordinateLatitude = destination?.latitude,
                    destinationCoordinateLongitude = destination?.longitude,
                ),
        )
}
