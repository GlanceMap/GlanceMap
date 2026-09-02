package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRouteModificationTest {
    @Test
    fun `replaces selected section and keeps route endpoints`() {
        val original = routePoints(0.0, 1.0, 2.0, 3.0)
        val replacement = routePoints(1.0, 1.5, 2.0)

        val modified =
            replacePhoneRouteSection(
                original = original,
                pointA = GeoPoint(1.0, 0.0),
                pointB = GeoPoint(2.0, 0.0),
                replacement = replacement,
            )

        assertEquals(listOf(0.0, 1.0, 1.5, 2.0, 3.0), modified.map { point -> point.location.latitude })
    }

    @Test
    fun `reverses replacement when endpoints are selected in reverse order`() {
        val modified =
            replacePhoneRouteSection(
                original = routePoints(0.0, 1.0, 2.0, 3.0),
                pointA = GeoPoint(2.0, 0.0),
                pointB = GeoPoint(1.0, 0.0),
                replacement = routePoints(2.0, 1.5, 1.0),
            )

        assertEquals(listOf(0.0, 1.0, 1.5, 2.0, 3.0), modified.map { point -> point.location.latitude })
    }

    @Test
    fun `keeps only the selected section in route order`() {
        val original = routePoints(0.0, 1.0, 2.0, 3.0)

        val kept =
            keepPhoneRouteSection(
                original = original,
                pointA = GeoPoint(2.0, 0.0),
                pointB = GeoPoint(1.0, 0.0),
            )

        assertEquals(listOf(1.0, 2.0), kept.map { point -> point.location.latitude })
    }

    @Test
    fun `trims either route endpoint at the nearest point`() {
        val original = routePoints(0.0, 1.0, 2.0, 3.0)

        assertEquals(
            listOf(2.0, 3.0),
            trimPhoneRouteStart(original, GeoPoint(2.0, 0.0)).map { point -> point.location.latitude },
        )
        assertEquals(
            listOf(0.0, 1.0),
            trimPhoneRouteEnd(original, GeoPoint(1.0, 0.0)).map { point -> point.location.latitude },
        )
    }

    @Test
    fun `reverses route and merges adjacent duplicate points`() {
        val original = routePoints(0.0, 1.0, 2.0)
        val reversed = reversePhoneRoute(original)
        val merged = mergePhoneRoutePoints(routePoints(0.0, 1.0), routePoints(1.0, 2.0))

        assertEquals(listOf(2.0, 1.0, 0.0), reversed.map { point -> point.location.latitude })
        assertEquals(listOf(0.0, 1.0, 2.0), merged.map { point -> point.location.latitude })
    }

    @Test
    fun `encodes valid gpx and escapes title`() {
        val gpx = encodePhoneRouteGpx("A & B", routePoints(0.0, 1.0)).toString(Charsets.UTF_8)

        assertTrue(gpx.contains("<name>A &amp; B</name>"))
        assertTrue(gpx.contains("<trkpt lat=\"0.0000000\" lon=\"0.0000000\">"))
    }

    private fun routePoints(vararg latitudes: Double): List<TrailPoint> =
        latitudes.map { latitude ->
            TrailPoint(location = GeoPoint(latitude = latitude, longitude = 0.0))
        }
}
