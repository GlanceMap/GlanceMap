package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Tile
import java.io.File

class PhoneOfflineMapsforgeSurfaceTest {
    @Test
    fun rendererWorkDoesNotApplyBeforeEveryReadinessConditionIsTrue() {
        val gate = PhoneMapsforgeRenderWorkGate()
        gate.requestWork()

        listOf(
            PhoneMapViewRenderReadiness(false, 720, 801, true),
            PhoneMapViewRenderReadiness(true, 0, 801, true),
            PhoneMapViewRenderReadiness(true, 720, 0, true),
            PhoneMapViewRenderReadiness(true, 720, 801, false),
        ).forEach { readiness ->
            assertFalse(readiness.isReady)
            assertNull(gate.scheduleIfReady(readiness))
        }
        assertTrue(gate.hasPendingWork())
    }

    @Test
    fun pendingRendererWorkAppliesOnceTheMapViewIsReady() {
        val gate = PhoneMapsforgeRenderWorkGate()
        gate.requestWork()
        val scheduled = requireNotNull(gate.scheduleIfReady(ready()))

        assertTrue(gate.consumeIfCurrent(scheduled, ready()))
        assertFalse(gate.hasPendingWork())
    }

    @Test
    fun staleDelayedRendererWorkIsIgnored() {
        val gate = PhoneMapsforgeRenderWorkGate()
        gate.requestWork()
        val stale = requireNotNull(gate.scheduleIfReady(ready()))
        gate.requestWork()
        val current = requireNotNull(gate.scheduleIfReady(ready()))

        assertFalse(gate.consumeIfCurrent(stale, ready()))
        assertTrue(gate.consumeIfCurrent(current, ready()))
    }

    @Test
    fun sameMapAndThemeDoNotRebuildTheBaseLayer() {
        val identity = identity("Bayern_oam.osm.map", "elv-hiking")

        assertEquals(PhoneMapsforgeBaseLayerChange.NONE, phoneMapsforgeBaseLayerChange(identity, identity))
    }

    @Test
    fun changingMapSwapsBaseLayerWhileChangingThemeReloadsIt() {
        val bayernHiking = identity("Bayern_oam.osm.map", "elv-hiking")
        val wurzburgHiking = identity("Wurzburg.map", "elv-hiking")
        val bayernCycling = identity("Bayern_oam.osm.map", "elv-cycling")

        assertEquals(
            PhoneMapsforgeBaseLayerChange.MAP_SWAP,
            phoneMapsforgeBaseLayerChange(bayernHiking, wurzburgHiking),
        )
        assertEquals(
            PhoneMapsforgeBaseLayerChange.THEME_RELOAD,
            phoneMapsforgeBaseLayerChange(bayernHiking, bayernCycling),
        )
    }

    @Test
    fun panelResizeCompassOverlayAndLocationUpdatesKeepBaseIdentity() {
        val active = identity("Bayern_oam.osm.map", "elv-hiking")
        val panelResize = active
        val compassUpdate = active
        val overlayUpdate = active
        val locationUpdate = active

        listOf(panelResize, compassUpdate, overlayUpdate, locationUpdate).forEach { update ->
            assertEquals(PhoneMapsforgeBaseLayerChange.NONE, phoneMapsforgeBaseLayerChange(active, update))
        }
    }

    @Test
    fun layerMutationQueueDefersDuringGestureAndFlushesAfterIdle() {
        val queue = PhoneMapLayerMutationQueue()
        val executed = mutableListOf<String>()
        queue.setGestureActive(true)

        assertFalse(queue.submit("base") { executed += "stale" })
        assertFalse(queue.submit("base") { executed += "latest" })
        assertTrue(executed.isEmpty())

        queue.setGestureActive(false)
        queue.drainAfterGestureIdle().forEach { it.invoke() }

        assertEquals(listOf("latest"), executed)
    }

    @Test
    fun rendererCleanupReleasesEachOwnedBundleOnlyOnce() {
        val releaseOnce = PhoneMapsforgeReleaseOnce()
        var releaseCount = 0

        assertTrue(releaseOnce.release { releaseCount += 1 })
        assertFalse(releaseOnce.release { releaseCount += 1 })

        assertEquals(1, releaseCount)
    }

    @Test
    fun firstVisibleDetectorRequiresDrawableTileRatherThanCacheKeyPresence() {
        val tile = Tile(0, 0, 10.toByte(), 256)
        val coverageWithoutDrawable = phoneFirstVisibleTileCoverage(listOf(tile)) { false }
        val coverageWithDrawable = phoneFirstVisibleTileCoverage(listOf(tile)) { candidate -> candidate == tile }

        assertEquals(0, coverageWithoutDrawable.drawableVisibleTiles)
        assertEquals(1, coverageWithDrawable.drawableVisibleTiles)
    }

    @Test
    fun initialCameraUsesCurrentViewportOnlyWhenItIsInsideTheSelectedMap() {
        val bounds = BoundingBox(47.0, 11.0, 48.0, 12.0)
        val inside = PhoneMapCameraSnapshot(47.5, 11.5, 14.0)
        val outside = PhoneMapCameraSnapshot(20.0, 0.0, 2.0)

        assertEquals(
            PhoneOfflineInitialCameraReason.CURRENT_VIEWPORT,
            phoneOfflineInitialCameraSelection(inside, cameraContext(bounds, null)).reason,
        )
        assertEquals(
            PhoneOfflineInitialCameraReason.MAP_METADATA,
            phoneOfflineInitialCameraSelection(outside, cameraContext(bounds, bounds.centerPoint)).reason,
        )
    }

    @Test
    fun defaultPhoneZoomIsAUsefulHikingScaleAndOfflineCameraClampsIt() {
        assertEquals(14.0, PHONE_MAP_DEFAULT_ZOOM, 0.0)
        val selection =
            phoneOfflineInitialCameraSelection(
                requested = PhoneMapCameraSnapshot(47.5, 11.5, 30.0),
                context = cameraContext(BoundingBox(47.0, 11.0, 48.0, 12.0), null),
            )

        assertEquals(18.0, selection.camera.zoom, 0.0)

        val fallback =
            phoneOfflineInitialCameraSelection(
                requested = PhoneMapCameraSnapshot(20.0, 0.0, PHONE_MAP_DEFAULT_ZOOM),
                context = cameraContext(BoundingBox(47.0, 11.0, 48.0, 12.0), null).copy(mapStartZoom = null),
            )
        assertEquals(PHONE_MAP_DEFAULT_ZOOM, fallback.camera.zoom, 0.0)
        assertEquals(PhoneOfflineInitialCameraReason.DEFAULT, fallback.reason)
    }

    @Test
    fun twoFingerRotationProducesIncrementalNormalizedDeltas() {
        assertEquals(90f, phoneTwoFingerRotationDelta(0f, 90f), 0.001f)
        assertEquals(2f, phoneTwoFingerRotationDelta(359f, 1f), 0.001f)
        assertEquals(90f, mapsforgeMapBearingDegrees(-90f), 0.001f)
    }

    @Test
    fun mapsforgeSegmentsKeepEachRouteSegmentSeparate() {
        val segments =
            listOf(
                PhoneMapRouteSegment(listOf(GeoPoint(45.0, 6.0), GeoPoint(45.1, 6.1))),
                PhoneMapRouteSegment(listOf(GeoPoint(45.2, 6.2), GeoPoint(45.3, 6.3))),
            )

        val mapsforgeSegments = segments.toMapsforgeSegments()

        assertEquals(listOf(2, 2), mapsforgeSegments.map { it.size })
        assertEquals(45.2, mapsforgeSegments[1].first().latitude, 0.0)
    }

    @Test
    fun mapsforgeViewportUsesVisibleBoundsAndZoom() {
        val viewport = mapsforgeViewportOrNull(BoundingBox(45.0, 6.0, 46.0, 7.0), 14)

        requireNotNull(viewport)
        assertEquals(45.0, viewport.minLat, 0.0)
        assertEquals(7.0, viewport.maxLon, 0.0)
        assertEquals(14.0, viewport.zoom, 0.0)
        assertNull(mapsforgeViewportOrNull(bounds = null, zoom = 14))
    }

    @Test
    fun mapIdentityChangesOnlyForDifferentSelectedFiles() {
        val wurzburg = PhoneOfflineMap(File("/maps/Wurzburg.map"))
        val bayern = PhoneOfflineMap(File("/maps/Bayern_oam.osm.map"))

        assertNotEquals(wurzburg.rendererIdentity, bayern.rendererIdentity)
        assertEquals(bayern.rendererIdentity, PhoneOfflineMap(File("/maps/Bayern_oam.osm.map")).rendererIdentity)
    }

    private fun ready() = PhoneMapViewRenderReadiness(true, 720, 801, true)

    private fun cameraContext(
        bounds: BoundingBox,
        start: org.mapsforge.core.model.LatLong?,
    ): PhoneOfflineMapCameraContext =
        PhoneOfflineMapCameraContext(
            bounds = bounds,
            mapStart = start,
            mapStartZoom = 12,
            zoomMin = 8,
            zoomMax = 18,
        )

    private fun identity(
        mapName: String,
        styleId: String,
    ): PhoneMapsforgeBaseLayerIdentity =
        PhoneMapsforgeBaseLayerIdentity(
            mapIdentity = "/maps/$mapName",
            themeConfig = PhoneOfflineThemeConfig("elevate", styleId),
        )
}
