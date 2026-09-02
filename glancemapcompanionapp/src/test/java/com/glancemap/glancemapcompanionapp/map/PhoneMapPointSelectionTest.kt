package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMapPointSelectionTest {
    @Test
    fun pointToPointSelectionExplainsNextTapAndKeepsBothMarkers() {
        val pointA = GeoPoint(46.0, 6.0)
        val pointB = GeoPoint(46.1, 6.1)
        val selectingB =
            PhoneRouteToolsUiState(
                isOpen = true,
                mode = PhoneRouteCreationMode.POINT_A_TO_B,
                pointA = pointA,
            )

        assertEquals(PhoneMapPointSelectionPhase.POINT_B, selectingB.pointSelectionPhase())
        assertEquals(
            listOf(PhoneMapPointSelectionMarkerKind.POINT_A),
            phoneMapPointSelectionMarkers(null, selectingB).map { marker -> marker.kind },
        )

        val pointsSelected = selectingB.copy(pointB = pointB)
        assertNull(pointsSelected.pointSelectionPhase())
        assertEquals(
            listOf(
                PhoneMapPointSelectionMarkerKind.POINT_A,
                PhoneMapPointSelectionMarkerKind.POINT_B,
            ),
            phoneMapPointSelectionMarkers(null, pointsSelected).map { marker -> marker.kind },
        )
    }

    @Test
    fun destinationAndPoiUseDistinctVisibleMarkers() {
        val destinationState =
            PhoneRouteToolsUiState(
                isOpen = true,
                mode = PhoneRouteCreationMode.CURRENT_TO_DESTINATION,
                destination = GeoPoint(45.0, 7.0),
            )
        val markers =
            phoneMapPointSelectionMarkers(
                poiPoint = PhoneMapCoordinate(44.0, 6.0),
                routeTools = destinationState,
            )

        assertEquals(
            listOf(
                PhoneMapPointSelectionMarkerKind.POI,
                PhoneMapPointSelectionMarkerKind.DESTINATION,
            ),
            markers.map { marker -> marker.kind },
        )
        assertEquals(44.0, markers.first().point.latitude, 0.0)
        assertEquals(7.0, markers.last().point.longitude, 0.0)
    }

    @Test
    fun multiPointSelectionKeepsEveryWaypointMarker() {
        val points = listOf(GeoPoint(46.0, 6.0), GeoPoint(46.1, 6.1), GeoPoint(46.2, 6.2))
        val state =
            PhoneRouteToolsUiState(
                isOpen = true,
                mode = PhoneRouteCreationMode.MULTI_POINT_CHAIN,
                chainPoints = points,
            )

        assertEquals(PhoneMapPointSelectionPhase.CHAIN_POINT, state.pointSelectionPhase())
        assertEquals(
            listOf(
                PhoneMapPointSelectionMarkerKind.WAYPOINT,
                PhoneMapPointSelectionMarkerKind.WAYPOINT,
                PhoneMapPointSelectionMarkerKind.WAYPOINT,
            ),
            phoneMapPointSelectionMarkers(null, state).map { marker -> marker.kind },
        )
    }

    @Test
    fun reshapeRequestsAnchorThenBend() {
        val state =
            PhoneRouteToolsUiState(
                isOpen = true,
                mode = PhoneRouteCreationMode.MODIFY_ROUTE,
                modificationMode = PhoneRouteModificationMode.RESHAPE_ROUTE,
                selectedRouteId = "route",
                pointA = GeoPoint(46.0, 6.0),
            )

        assertEquals(PhoneMapPointSelectionPhase.RESHAPE_BEND, state.pointSelectionPhase())
        assertEquals(
            R.string.map_route_tools_reshape_bend_hint,
            state.pointSelectionHintResource(PhoneMapPointSelectionPhase.RESHAPE_BEND),
        )
    }
}
