package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapStartCenteringTest {
    @Test
    fun staleFixBeforeSessionIsIgnoredButRetainedAfterAcceptance() {
        val stale = location(fixElapsedMillis = 1_000L)

        assertFalse(shouldShowPhoneMapStartupLocation(stale, false, NOW_MS, FOLLOW_INTERVAL_MS))
        assertTrue(shouldShowPhoneMapStartupLocation(stale, true, NOW_MS, FOLLOW_INTERVAL_MS))
        assertTrue(shouldShowPhoneMapStartupLocation(location(NOW_MS - 2_000L), false, NOW_MS, FOLLOW_INTERVAL_MS))
    }

    @Test
    fun startupFallbackWaitsOnlyOnlineWithoutFix() {
        assertTrue(shouldWaitForPhoneMapStartupFallback(true, gpsOff = false, hasLocation = false))
        assertFalse(shouldWaitForPhoneMapStartupFallback(true, gpsOff = false, hasLocation = true))
        assertFalse(shouldWaitForPhoneMapStartupFallback(true, gpsOff = true, hasLocation = false))
        assertFalse(shouldWaitForPhoneMapStartupFallback(false, gpsOff = false, hasLocation = false))
    }

    @Test
    fun gpxCenterIsBoundingBoxCenter() {
        assertEquals(PhoneMapCoordinate(45.5, 6.5), phoneMapGpxStartCenter(listOf(track("a"))))
        assertEquals(null, phoneMapGpxStartCenter(emptyList()))
    }

    @Test
    fun contextKeyIgnoresGpxOrder() {
        assertEquals("gpx=a|b", phoneMapStartCenterContextKey(listOf("b", "a")))
        assertEquals("gpx=", phoneMapStartCenterContextKey(emptyList()))
    }

    @Test
    fun onlineStartupWithoutGpxDefersUntilACenterExists() {
        val (waiting, deferred) =
            PhoneMapStartCenterMemory().resolve("gpx=", hasGpx = false, gpxCenter = null, deferWhenNoCenter = true)
        assertEquals(PhoneMapStartCenterAction.Deferred, deferred)

        val (_, centered) = waiting.resolve("gpx=a", hasGpx = true, gpxCenter = GPX_CENTER, deferWhenNoCenter = true)
        assertEquals(PhoneMapStartCenterAction.Center(GPX_CENTER), centered)
    }

    @Test
    fun gpsOffCentersOncePerContextAndAgainWhenGpxChanges() {
        val (applied, first) = PhoneMapStartCenterMemory().resolve("gpx=a", hasGpx = true, gpxCenter = GPX_CENTER)
        assertEquals(PhoneMapStartCenterAction.Center(GPX_CENTER), first)

        val (_, repeated) = applied.resolve("gpx=a", hasGpx = true, gpxCenter = GPX_CENTER)
        assertEquals(PhoneMapStartCenterAction.None, repeated)

        val otherCenter = PhoneMapCoordinate(46.0, 7.0)
        val (_, changed) = applied.resolve("gpx=a|b", hasGpx = true, gpxCenter = otherCenter)
        assertEquals(PhoneMapStartCenterAction.Center(otherCenter), changed)
    }

    @Test
    fun reenteringGpsOffRestoresSavedViewportOfSameContext() {
        val panned = PhoneMapCoordinate(45.9, 6.9)
        val memory =
            PhoneMapStartCenterMemory()
                .resolve("gpx=a", hasGpx = true, gpxCenter = GPX_CENTER)
                .first
                .saveViewport("gpx=a", panned)
                .reset()

        val (_, action) = memory.resolve("gpx=a", hasGpx = true, gpxCenter = GPX_CENTER)

        assertEquals(PhoneMapStartCenterAction.Center(panned), action)
    }

    @Test
    fun viewportIsNotSavedForAContextThatWasNotCentered() {
        val memory =
            PhoneMapStartCenterMemory()
                .resolve("gpx=a", hasGpx = true, gpxCenter = GPX_CENTER)
                .first
                .saveViewport("gpx=a|b", PhoneMapCoordinate(10.0, 10.0))

        assertEquals(null, memory.savedViewportContextKey)
    }

    @Test
    fun changingBaseMapKeepsTheCurrentViewportInsteadOfTheGpxCenter() {
        val current = PhoneMapCoordinate(45.9, 6.9)
        val contextKey = phoneMapStartCenterContextKey(listOf("a"))
        val centered =
            PhoneMapStartCenterMemory()
                .resolve(contextKey, hasGpx = true, gpxCenter = GPX_CENTER)
                .first
                .saveViewport(contextKey, current)

        // The base map is not part of the key, so a map switch resolves the same GPX context.
        val (_, action) = centered.resolve(contextKey, hasGpx = true, gpxCenter = GPX_CENTER)

        assertEquals(PhoneMapStartCenterAction.Center(current), action)
    }

    @Test
    fun startCenterCommandKeepsZoomAndFollowMode() {
        val state = PhoneMapUiState().requestStartCenter(GPX_CENTER)

        assertEquals(PhoneMapFollowMode.FOLLOW_LOCATION, state.mapMode.follow)
        val zoom = requireNotNull(state.cameraCommand).requestedZoom(12.0, PhoneMapSettings(), 1_080.0, 512.0)
        assertEquals(12.0, zoom, 0.0)
    }

    private fun location(fixElapsedMillis: Long) =
        PhoneMapLocation(
            latitude = 45.0,
            longitude = 6.0,
            accuracyMeters = 8f,
            fixElapsedRealtimeMillis = fixElapsedMillis,
        )

    private fun track(id: String) =
        PhoneMapGpxOverlay(
            id = id,
            displayName = id,
            segments = listOf(PhoneMapRouteSegment(listOf(GeoPoint(45.0, 6.0), GeoPoint(46.0, 7.0)))),
        )

    private companion object {
        const val NOW_MS = 600_000L
        const val FOLLOW_INTERVAL_MS = 2_000L
        val GPX_CENTER = PhoneMapCoordinate(45.5, 6.5)
    }
}
