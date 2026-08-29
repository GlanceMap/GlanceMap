package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mapsforge.core.model.BoundingBox
import java.io.File

class PhoneOfflineMapsforgeSurfaceTest {
    @Test
    fun mapsforgeSegmentsKeepEachRouteSegmentSeparate() {
        val segments =
            listOf(
                PhoneMapRouteSegment(
                    listOf(
                        GeoPoint(45.0, 6.0),
                        GeoPoint(45.1, 6.1),
                    ),
                ),
                PhoneMapRouteSegment(
                    listOf(
                        GeoPoint(45.2, 6.2),
                        GeoPoint(45.3, 6.3),
                    ),
                ),
            )

        val mapsforgeSegments = segments.toMapsforgeSegments()

        assertEquals(listOf(2, 2), mapsforgeSegments.map { it.size })
        assertEquals(45.2, mapsforgeSegments[1].first().latitude, 0.0)
    }

    @Test
    fun mapsforgeViewportUsesVisibleBoundsAndZoom() {
        val viewport =
            mapsforgeViewportOrNull(
                bounds = BoundingBox(45.0, 6.0, 46.0, 7.0),
                zoom = 14,
            )

        requireNotNull(viewport)
        assertEquals(45.0, viewport.minLat, 0.0)
        assertEquals(7.0, viewport.maxLon, 0.0)
        assertEquals(14.0, viewport.zoom, 0.0)
        assertNull(mapsforgeViewportOrNull(bounds = null, zoom = 14))
    }

    @Test
    fun rendererIdentityIgnoresThemeOverlayAndPanelPresentationChanges() {
        val state = offlineSurfaceState(PhoneOfflineMap(File("/maps/Bayern_oam.osm.map")))
        val presentationChanged =
            state.copy(
                themeConfig = PhoneOfflineThemeConfig("mapsforge", "mapsforge:DARK"),
                gpxOverlays =
                    listOf(
                        PhoneMapGpxOverlay(
                            id = "route",
                            displayName = "Route",
                            segments =
                                listOf(
                                    PhoneMapRouteSegment(
                                        listOf(GeoPoint(45.0, 6.0), GeoPoint(45.1, 6.1)),
                                    ),
                                ),
                        ),
                    ),
                mapMode = PhoneMapMode().toggleOrientation(),
                compassPresentation = phoneMapCompassPresentation(PhoneMapOrientation.HEADING_UP, 90f),
            )
        val panelChanged =
            PhoneMapUiState(source = PhoneMapSource.Offline(state.map))
                .selectTool(MapTool.MAPS)
                .expandTool()
                .collapseTool()
                .closeTool()

        assertEquals(state.map.rendererIdentity, presentationChanged.map.rendererIdentity)
        assertEquals(
            state.map.rendererIdentity,
            (panelChanged.source as PhoneMapSource.Offline).map.rendererIdentity,
        )
    }

    @Test
    fun selectingDifferentMapChangesRendererIdentity() {
        val wurzburg = PhoneOfflineMap(File("/maps/WurzburgOSMMapsforge.map"))
        val bayern = PhoneOfflineMap(File("/maps/Bayern_oam.osm.map"))

        assertNotEquals(wurzburg.rendererIdentity, bayern.rendererIdentity)
    }

    @Test
    fun selectingActiveMapAgainKeepsRendererIdentity() {
        val active = PhoneOfflineMap(File("/maps/Bayern_oam.osm.map"))
        val selectedAgain = PhoneOfflineMap(File("/maps/Bayern_oam.osm.map"))

        assertEquals(active.rendererIdentity, selectedAgain.rendererIdentity)
    }

    private fun offlineSurfaceState(map: PhoneOfflineMap) =
        PhoneOfflineMapSurfaceState(
            map = map,
            themeConfig = PhoneOfflineThemeConfig("elevate", "elv-hiking"),
            initialCamera = PhoneMapCameraSnapshot(latitude = 45.0, longitude = 6.0, zoom = 12.0),
            gpxOverlays = emptyList(),
            pois = emptyList(),
            mapMode = PhoneMapMode(),
            cameraCommand = null,
        )
}
