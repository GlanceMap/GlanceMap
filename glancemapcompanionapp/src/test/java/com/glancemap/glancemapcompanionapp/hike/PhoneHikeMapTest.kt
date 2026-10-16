package com.glancemap.glancemapcompanionapp.hike

import androidx.compose.ui.unit.IntSize
import com.glancemap.glancemapcompanionapp.map.PhoneMapCameraSnapshot
import com.glancemap.glancemapcompanionapp.map.PhoneMapPointSelectionMarkerKind
import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class PhoneHikeMapTest {
    @Test
    fun inspectionMarkerIsSeparateFromCurrentPositionAndHiddenInLiveMode() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val plan = PhoneHikePlan(model.route.id, cursorFraction = .25, inspecting = true, checkpoints = listOf(checkpoint(1, .5)))
        val ui = PhoneHikeUiState(data = PhoneHikeWorkspaceData(todayId = model.route.id, todayPlan = plan), model = model)
        val markers = phoneHikeMapMarkers(ui)
        assertEquals(2, markers.size)
        assertEquals(PhoneMapPointSelectionMarkerKind.HIKE_INSPECTION, markers.last().kind)
        val live = phoneHikeMapMarkers(ui.copy(data = ui.data.copy(todayPlan = plan.copy(inspecting = false))))
        assertEquals(listOf(PhoneMapPointSelectionMarkerKind.CHECKPOINT), live.map { it.kind })
        assertEquals(plan, ui.plan)
    }

    @Test
    fun edgePanUsesRealGroundResolutionAndWorksWithEitherScreenOrientation() {
        val camera = PhoneMapCameraSnapshot(46.0, 11.0, 12.0, bearingDegrees = 90f, groundResolutionMetersPerPixel = 2.0)
        val nearby = GeoPoint(46.0, 11.001)
        val outside = GeoPoint(46.0, 11.02)
        assertFalse(phoneHikeShouldPan(camera, IntSize(720, 1000), nearby))
        assertTrue(phoneHikeShouldPan(camera, IntSize(720, 1000), outside))
        assertTrue(phoneHikeShouldPan(camera, IntSize(1000, 720), outside))
        assertFalse(phoneHikeShouldPan(camera.copy(groundResolutionMetersPerPixel = null), IntSize(720, 1000), outside))
    }
}
