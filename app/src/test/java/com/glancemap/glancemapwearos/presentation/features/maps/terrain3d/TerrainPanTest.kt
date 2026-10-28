package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.navigate.latLongSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class TerrainPanTest {
    @Test
    fun loadingIndicatorReflectsInitializationAndRefreshButNotFailure() {
        val session = TerrainSession()
        assertFalse(session.isLoading)
        session.initializing = true
        assertTrue(session.isLoading)
        session.initializing = false
        session.refreshing = true
        assertTrue(session.isLoading)
        session.failed = true
        assertFalse(session.isLoading)
        session.failed = false
        session.refreshing = false
        assertFalse(session.isLoading)
    }

    @Test
    fun draggingMovesTheCameraOppositeToTheFingerAndScalesWithZoomAndPitch() {
        val (east, north) = terrainPanDelta(100f, 100f, 4000f, 30f, 400)
        assertEquals(-535.9f, east, 0.2f)
        assertEquals(1071.8f, north, 0.4f)
        val (closerEast, _) = terrainPanDelta(100f, 0f, 2000f, 30f, 400)
        assertEquals(east / 2f, closerEast, 0.01f)
    }

    @Test
    fun invalidViewportOrGestureDoesNotMoveTheCamera() {
        assertEquals(0f to 0f, terrainPanDelta(Float.NaN, 0f, 4000f, 30f, 400))
        assertEquals(0f to 0f, terrainPanDelta(10f, 10f, 4000f, 30f, 0))
    }

    @Test
    fun panFollowsScreenDirectionWhenCameraFacesEast() {
        val (east, north) = terrainPanDelta(100f, 0f, 4000f, 30f, 400, 90f)
        assertEquals(0f, east, 0.1f)
        assertEquals(535.9f, north, 0.2f)
    }

    @Test
    fun geographicPanAndTextureCoordinatesUseTheSameExtent() {
        val area = TerrainArea(0.0, 0.0, 4000.0)
        val moved = area.coordinateForMeters(1000.0, 1000.0)
        assertEquals(0.00898, moved.first, 0.0001)
        assertEquals(0.00898, moved.second, 0.0001)
        val (u, v) = area.texturePosition(moved.first, moved.second)
        assertEquals(0.75, u, 0.00001)
        assertEquals(0.25, v, 0.00001)
    }

    @Test
    fun panRefreshesAtOneQuarterOfTheTerrainWidth() {
        val origin = LatLong(0.0, 0.0)
        val shortPan = terrainPanDestination(origin, 300f, 300f)
        assertFalse(shortPan.requiresRefresh)
        assertTrue(shortPan.center.latitude > 0.0)
        assertTrue(shortPan.center.longitude > 0.0)
        assertFalse(terrainPanDestination(origin, 700f, 0f).requiresRefresh)
        assertTrue(terrainPanDestination(origin, 1000f, 0f).requiresRefresh)
        assertFalse(terrainPanDestination(origin, 1000f, 0f, 8000f).requiresRefresh)
        assertTrue(terrainPanDestination(origin, 2000f, 0f, 8000f).requiresRefresh)
    }

    @Test
    fun preparedWidthUsesCameraDistanceAndRemainsBounded() {
        assertEquals(4000f, terrainPreparedWidth(1000f, 60f), 0.1f)
        assertEquals(8000f, terrainPreparedWidth(2000f, 60f), 5f)
        assertTrue(terrainPreparedWidth(2000f, 70f) > 12000f)
        assertTrue(terrainPreparedWidth(14000f, 60f) > terrainPreparedWidth(2000f, 60f))
    }

    @Test
    fun zoomRequestsANewExtentAndInvalidatesOlderPreparation() {
        val session = TerrainSession()
        val origin = LatLong(0.0, 0.0)
        session.renderedCenter = origin
        session.center = origin
        session.requestedCenter = origin
        session.widthMeters = 8000f
        session.requestedWidthMeters = 8000f
        val revision = session.revision.get()
        session.requestZoomExtent(4000f)
        assertTrue(session.requestedWidthMeters > session.widthMeters)
        assertTrue(session.revision.get() > revision)
        assertEquals(origin, session.requestedCenter)
        session.requestZoomExtent(1000f)
        assertTrue(session.requestedWidthMeters < session.widthMeters)
    }

    @Test
    fun safeRadiusShrinksWhenTheCameraApproachesTheTerrainEdge() {
        assertEquals(1600f, terrainSafeRadius(8000f, 2000f, 60f), 5f)
        assertTrue(terrainSafeRadius(8000f, 3000f, 60f) < 1000f)
    }

    @Test
    fun coordinatesRemainAlignedWhenTheTerrainWindowMoves() {
        val first = TerrainArea(0.0, 0.0, 8000.0)
        val point = first.coordinateForMeters(1000.0, 500.0)
        val next = TerrainArea(point.first, point.second, 8000.0)
        val (east, north) = next.metersForCoordinate(point.first, point.second)
        assertEquals(0f, east, 0.01f)
        assertEquals(0f, north, 0.01f)
        assertEquals(0.5, next.texturePosition(point.first, point.second).first, 0.000001)
        assertEquals(0.5, next.texturePosition(point.first, point.second).second, 0.000001)
    }

    @Test
    fun recenterOutsideTheSafeRegionRequestsTheGpsPosition() {
        val session = TerrainSession()
        val origin = LatLong(0.0, 0.0)
        val gps = LatLong(0.05, 0.05)
        session.renderedCenter = origin
        session.center = origin
        session.requestedCenter = origin
        session.recenter(gps)
        assertEquals(gps, session.center)
        assertEquals(gps, session.requestedCenter)
        assertEquals(origin, session.renderedCenter)
    }

    @Test
    fun newerPanInvalidatesAnOlderTerrainPreparation() {
        val session = TerrainSession()
        val origin = LatLong(0.0, 0.0)
        session.renderedCenter = origin
        session.center = origin
        session.requestedCenter = origin
        session.dragging = true
        session.updatePan(origin, 1100f, 0f)
        val oldRevision = session.revision.get()
        val oldTarget = session.requestedCenter
        session.updatePan(origin, 1600f, 0f)
        assertTrue(session.revision.get() > oldRevision)
        assertTrue(session.requestedCenter != oldTarget)
        assertEquals(session.center, session.requestedCenter)
        var installed = false
        assertFalse(installIfCurrent(session.revision, oldRevision) { installed = true })
        assertFalse(installed)
        assertTrue(installIfCurrent(session.revision, session.revision.get()) { installed = true })
        assertTrue(installed)
    }

    @Test
    fun returningInsideSafeRegionCancelsTheOldRefresh() {
        val session = TerrainSession()
        val origin = LatLong(0.0, 0.0)
        session.renderedCenter = origin
        session.center = origin
        session.requestedCenter = origin
        session.dragging = true
        session.updatePan(origin, 1100f, 0f)
        val oldRevision = session.revision.get()
        session.updatePan(origin, 100f, 0f)
        assertEquals(origin, session.requestedCenter)
        assertTrue(session.revision.get() > oldRevision)
    }

    @Test
    fun sessionTiltSurvivesUnchangedSettings() {
        val session = TerrainSession()
        session.setViewingAngle(60)
        session.setInteractiveViewingAngle(67f)
        session.setViewingAngle(60)
        assertEquals(67f, session.interactiveViewingAngle, 0f)
        session.resetInteractiveViewingAngle()
        assertEquals(60f, session.interactiveViewingAngle, 0f)
        session.setViewingAngle(55)
        assertEquals(55f, session.interactiveViewingAngle, 0f)
    }

    @Test
    fun sessionPreservesPannedCenterAndRequestsRefreshAtTheThreshold() {
        val origin = LatLong(0.0, 0.0)
        val session = TerrainSession()
        session.renderedCenter = origin
        session.center = origin
        session.requestedCenter = origin
        session.dragging = true
        var panEvents = 0
        session.onUserPanStarted = { panEvents++ }

        session.finishPan(origin, 300f, 300f)
        assertFalse(session.dragging)
        assertEquals(origin, session.requestedCenter)
        assertTrue(requireNotNull(session.center).latitude > 0.0)

        session.dragging = true
        session.finishPan(origin, 1000f, 0f)
        assertFalse(session.dragging)
        assertEquals(session.center, session.requestedCenter)
        assertEquals(2, panEvents)
    }

    @Test
    fun gpxSegmentBreaksRemainSeparateAndProjectToTerrainTexture() {
        val area = TerrainArea(0.0, 0.0, 4000.0)
        val points =
            listOf(
                TrackPoint(LatLong(0.0, 0.0), elevation = null),
                TrackPoint(LatLong(0.0, 0.005), elevation = null),
                TrackPoint(LatLong(0.005, 0.0), elevation = null, startsNewSegment = true),
                TrackPoint(LatLong(0.005, 0.005), elevation = null),
            )
        val segments = points.latLongSegments()
        assertEquals(listOf(2, 2), segments.map { it.size })
        val first = segments[0].first()
        val secondSegmentStart = segments[1].first()
        assertEquals(0.5, area.texturePosition(first.latitude, first.longitude).first, 0.00001)
        assertEquals(0.5, area.texturePosition(first.latitude, first.longitude).second, 0.00001)
        assertTrue(area.texturePosition(secondSegmentStart.latitude, secondSegmentStart.longitude).second < 0.5)
    }

    @Test
    fun datelineCrossingDoesNotDrawAcrossTheWholeMap() {
        assertTrue(terrainGpxCrossesDateline(179.0, -179.0))
        assertTrue(terrainGpxCrossesDateline(-179.0, 179.0))
        assertFalse(terrainGpxCrossesDateline(-179.0, -178.0))
    }
}
