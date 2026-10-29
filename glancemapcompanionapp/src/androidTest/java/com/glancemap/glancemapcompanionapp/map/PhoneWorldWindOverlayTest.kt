package com.glancemap.glancemapcompanionapp.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.trailcore.geo.GeoPoint
import earth.worldwind.render.Color
import earth.worldwind.shape.Placemark
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneWorldWindOverlayTest {
    @Test
    fun updatedLocationMovesTheMarkerAndStaleFixUsesTheHistoricalColor() {
        val first = phoneWorldWindGpxLayer(emptyList(), 0xff000000.toInt(), GeoPoint(46.0, 11.0))
            .getRenderable(0) as Placemark
        val stale = phoneWorldWindGpxLayer(emptyList(), 0xff000000.toInt(), GeoPoint(46.1, 11.2), false)
            .getRenderable(0) as Placemark
        assertEquals(46.0, first.position.latitude.inDegrees, 0.00001)
        assertEquals(46.1, stale.position.latitude.inDegrees, 0.00001)
        assertEquals(11.2, stale.position.longitude.inDegrees, 0.00001)
        val expected = Color().set(PHONE_LOCATION_MARKER_HISTORICAL_ARGB)
        assertEquals(expected.red, stale.attributes.imageColor.red, 0.00001f)
        assertEquals(expected.green, stale.attributes.imageColor.green, 0.00001f)
        assertEquals(expected.blue, stale.attributes.imageColor.blue, 0.00001f)
    }
}
