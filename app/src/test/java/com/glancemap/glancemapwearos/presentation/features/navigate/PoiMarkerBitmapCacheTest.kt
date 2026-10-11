package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.data.repository.PoiType
import com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlayMarker
import com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlaySource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidBitmap

class PoiMarkerBitmapCacheTest {
    @Test
    fun successfulPreparationTransfersBitmapOwnershipToCaller() =
        runTest {
            val bitmap = RefCountTrackingBitmap()
            val bitmaps = mapOf(PoiType.PEAK to bitmap)

            val prepared = preparePoiMarkerBitmapsOnIo(StandardTestDispatcher(testScheduler)) { bitmaps }

            assertSame(bitmaps, prepared)
            assertEquals(0, bitmap.references)
            assertFalse(bitmap.destroyed)
            prepared.values.forEach(AndroidBitmap::decrementRefCount)
            assertTrue(bitmap.destroyed)
        }

    @Test
    fun cancellationAfterPreparationBeforeUiDeliveryReleasesBitmapsExactlyOnce() =
        runTest {
            val callerDispatcher = StandardTestDispatcher(testScheduler)
            val workerDispatcher = StandardTestDispatcher(testScheduler)
            val bitmap = RefCountTrackingBitmap()
            var delivered = false
            lateinit var request: Job
            request =
                launch(callerDispatcher) {
                    preparePoiMarkerBitmapsOnIo(workerDispatcher) {
                        // Queue cancellation ahead of the return dispatch to the UI.
                        backgroundScope.launch(callerDispatcher) { request.cancel() }
                        mapOf(PoiType.PEAK to bitmap)
                    }
                    delivered = true
                }

            request.join()

            assertTrue(request.isCancelled)
            assertTrue(request.isCompleted)
            assertFalse(delivered)
            assertEquals(-1, bitmap.references)
            assertTrue(bitmap.destroyed)
        }

    @Test
    fun cacheReusesOnlyMatchingTypeSizeAndStyle() {
        val cache = PoiMarkerBitmapCache<Any>()
        val markerBitmap = Any()
        val key = PoiMarkerBitmapKey(PoiType.WATER, effectiveMarkerSizePx = 24, markerStyle = "classic")

        cache.put(key, markerBitmap)

        assertSame(markerBitmap, cache[key])
        assertNull(cache[key.copy(type = PoiType.PEAK)])
        assertNull(cache[key.copy(effectiveMarkerSizePx = 28)])
        assertNull(cache[key.copy(markerStyle = "theme-icon")])
    }

    @Test
    fun accessOrderEvictsLeastRecentlyUsedAndReleasesIt() {
        val released = mutableListOf<Any>()
        val cache = PoiMarkerBitmapCache<Any>(maxEntries = 2, onEvicted = released::add)
        val firstKey = PoiMarkerBitmapKey(PoiType.PEAK, effectiveMarkerSizePx = 20, markerStyle = "classic")
        val secondKey = PoiMarkerBitmapKey(PoiType.WATER, effectiveMarkerSizePx = 20, markerStyle = "classic")
        val thirdKey = PoiMarkerBitmapKey(PoiType.HUT, effectiveMarkerSizePx = 20, markerStyle = "classic")
        val first = Any()
        val second = Any()
        val third = Any()

        cache.put(firstKey, first)
        cache.put(secondKey, second)
        assertSame(first, cache[firstKey])
        cache.put(thirdKey, third)

        assertSame(first, cache[firstKey])
        assertNull(cache[secondKey])
        assertSame(third, cache[thirdKey])
        assertEquals(listOf(second), released)

        cache.clear()
        assertEquals(listOf(second, first, third), released)
    }

    @Test
    fun cacheEvictionKeepsBitmapAliveUntilItsMarkerReleasesIt() {
        val bitmap = RefCountTrackingBitmap()
        val cache = PoiMarkerBitmapCache<AndroidBitmap>(onEvicted = { it.decrementRefCount() })
        cache.put(
            PoiMarkerBitmapKey(PoiType.PEAK, effectiveMarkerSizePx = 20, markerStyle = "classic"),
            bitmap,
        )
        val marker = createPoiOverlayMarker(LatLong(45.0, 6.0), bitmap)

        cache.clear()

        assertEquals(0, bitmap.references)
        assertFalse(bitmap.destroyed)

        marker.onDestroy()

        assertEquals(-1, bitmap.references)
        assertTrue(bitmap.destroyed)
    }

    @Test
    fun repeatedMarkerDestructionDoesNotReleaseAnotherMarkersReference() {
        val bitmap = RefCountTrackingBitmap()
        val cache = PoiMarkerBitmapCache<AndroidBitmap>(onEvicted = AndroidBitmap::decrementRefCount)
        cache.put(PoiMarkerBitmapKey(PoiType.PEAK, 20, "classic"), bitmap)
        val first = createPoiOverlayMarker(LatLong(45.0, 6.0), bitmap)
        val second = createPoiOverlayMarker(LatLong(45.001, 6.001), bitmap)

        first.onDestroy()
        first.onDestroy()
        cache.clear()

        assertEquals(0, bitmap.references)
        assertFalse(bitmap.destroyed)

        second.onDestroy()
        second.onDestroy()

        assertEquals(-1, bitmap.references)
        assertTrue(bitmap.destroyed)
    }

    @Test
    fun newQueryAndDisposalInvalidateQueuedPreparation() {
        val version =
            PoiMarkerPreparationVersion(
                poiMarkerPreparationConfig(emptyList(), 20, "classic"),
            )
        val queuedQuery = version.invalidate()
        assertTrue(version.isCurrent(queuedQuery))

        val newerQuery = version.invalidate()
        assertFalse(version.isCurrent(queuedQuery))
        assertTrue(version.isCurrent(newerQuery))

        version.invalidate()
        assertFalse(version.isCurrent(newerQuery))
    }

    @Test
    fun visibleTypesIncludeGenericFallbackButEmptyResultsNeedNoIcons() {
        val visibleMarkers =
            listOf(
                poiMarker("peak", PoiType.PEAK),
                poiMarker("water", PoiType.WATER),
            )

        assertTrue(requiredPoiMarkerTypes(emptyList()).isEmpty())
        assertEquals(setOf(PoiType.PEAK, PoiType.WATER, PoiType.GENERIC), requiredPoiMarkerTypes(visibleMarkers))
    }

    @Test
    fun sourceSizeAndStyleChangesInvalidateSuspendedPreparationEvenAfterReturningToOldConfig() {
        val initialConfig =
            poiMarkerPreparationConfig(
                sources = listOf(PoiOverlaySource("poi.db", "poi.db", setOf(1))),
                markerSizePx = 20,
                markerStyle = "classic",
            )
        val version = PoiMarkerPreparationVersion(initialConfig)
        val initialSnapshot = version.snapshot()

        version.update(initialConfig.copy(markerSizePx = 24))
        assertFalse(version.isCurrent(initialSnapshot))
        val afterSizeChange = version.snapshot()

        version.update(initialConfig.copy(markerStyle = "theme-icon"))
        assertFalse(version.isCurrent(afterSizeChange))
        val afterStyleChange = version.snapshot()

        version.update(
            poiMarkerPreparationConfig(
                sources = listOf(PoiOverlaySource("other.db", "other.db", setOf(2))),
                markerSizePx = 20,
                markerStyle = "classic",
            ),
        )
        assertFalse(version.isCurrent(afterStyleChange))
        version.update(initialConfig)

        assertFalse(version.isCurrent(initialSnapshot))
        assertTrue(version.isCurrent(version.snapshot()))
    }

    private fun poiMarker(
        key: String,
        type: PoiType,
    ): PoiOverlayMarker =
        PoiOverlayMarker(
            key = key,
            lat = 45.0,
            lon = 6.0,
            label = null,
            type = type,
        )

    private class RefCountTrackingBitmap : AndroidBitmap() {
        var references = 0
        var destroyed = false

        override fun incrementRefCount() {
            references += 1
        }

        override fun decrementRefCount() {
            references -= 1
            if (references < 0) destroyed = true
        }
    }
}
