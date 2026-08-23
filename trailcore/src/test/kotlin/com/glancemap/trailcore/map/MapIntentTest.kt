package com.glancemap.trailcore.map

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapIntentTest {
    @Test
    fun mapIntentKeepsSemanticCameraLayersAndAppearanceSeparateFromRenderers() {
        val intent =
            MapIntent(
                mode = MapMode.OFFLINE,
                camera =
                    MapCameraIntent(
                        center = GeoPoint(latitude = 46.5, longitude = 11.9),
                        zoomLevel = 14.5,
                        orientation = MapOrientation.HEADING_UP,
                        followLocation = true,
                    ),
                content = MapContentVisibility(gpxTracks = true, routes = false, pois = true),
                appearance = MapAppearancePreferences(hillshadeEnabled = true, slopeOverlayEnabled = true),
            )

        assertEquals(MapMode.OFFLINE, intent.mode)
        assertEquals(14.5, intent.camera.zoomLevel ?: 0.0, 0.0)
        assertTrue(intent.camera.followLocation)
        assertTrue(intent.content.gpxTracks)
        assertTrue(intent.appearance.hillshadeEnabled)
    }
}
