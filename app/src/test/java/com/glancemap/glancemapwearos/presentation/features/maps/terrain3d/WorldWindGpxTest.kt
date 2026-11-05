package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.shape.ShapeAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class WorldWindGpxTest {
    @Test
    fun `location changes preserve track geometry and replace only the marker`() {
        val layer = RenderableLayer()
        val track = worldWindTrackPath(listOf(LatLong(0.0, 0.0), LatLong(0.1, 0.1)), ShapeAttributes())
        layer.addRenderable(track)
        val firstMarker = worldWindTrackPath(listOf(LatLong(0.2, 0.2), LatLong(0.2, 0.3)), ShapeAttributes())
        val secondMarker = worldWindTrackPath(listOf(LatLong(0.3, 0.3), LatLong(0.3, 0.4)), ShapeAttributes())
        val first = updateWorldWindLocation(layer, null, firstMarker)
        val second = updateWorldWindLocation(layer, first, secondMarker)
        assertEquals(2, layer.count)
        assertSame(track, layer.getRenderable(0))
        assertSame(second, layer.getRenderable(1))
        updateWorldWindLocation(layer, second, null)
        assertEquals(1, layer.count)
        assertSame(track, layer.getRenderable(0))
    }

    @Test
    fun `route follows the rendered ground surface`() {
        val path = worldWindTrackPath(listOf(LatLong(0.0, 0.0), LatLong(0.1, 0.1)), ShapeAttributes())
        assertEquals(AltitudeMode.CLAMP_TO_GROUND, path.altitudeMode)
        assertTrue(path.isFollowTerrain)
    }

    @Test
    fun `track segment boundaries remain separate`() {
        val points =
            listOf(
                TrackPoint(LatLong(0.0, 0.0), null),
                TrackPoint(LatLong(0.1, 0.1), null),
                TrackPoint(LatLong(1.0, 1.0), null, startsNewSegment = true),
                TrackPoint(LatLong(1.1, 1.1), null),
            )
        val segments = worldWindTrackSegments(points)
        assertEquals(2, segments.size)
        assertEquals(LatLong(0.1, 0.1), segments[0].last())
        assertEquals(LatLong(1.0, 1.0), segments[1].first())
    }
}
