package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.layer.queue.Job
import org.mapsforge.map.layer.queue.JobQueue
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.model.MapViewPosition

class PhoneMapsforgeTileWorkloadTest {
    private val displayModel = DisplayModel()
    private val position = MapViewPosition(displayModel)
    private val queue = JobQueue<Job>(position, displayModel)

    @Test
    fun anEmptyQueueIsLeftAlone() {
        assertFalse(refreshPhoneMapTileJobPriorities(queue))
        assertEquals(0, queue.size())
    }

    @Test
    fun withoutARefreshAZoomBackKeepsServingTilesOfThePreviousLevel() {
        val (staleZoomJob, _) = queueTilesAtZoom14ThenReturnToZoom12()

        assertSame(staleZoomJob, queue.get())
    }

    @Test
    fun aRefreshAfterTheZoomChangeServesTheTileOnScreenFirst() {
        val (_, onScreenJob) = queueTilesAtZoom14ThenReturnToZoom12()

        assertTrue(refreshPhoneMapTileJobPriorities(queue))

        assertEquals(2, queue.size())
        assertSame(onScreenJob, queue.get())
    }

    @Test
    fun theOverdrawStillCoversTheViewDiagonalForRotation() {
        // The frame buffer is squared to hypot(width, height) x overdraw; it must not shrink below it.
        assertTrue(PHONE_MAPSFORGE_OVERDRAW_FACTOR >= 1.0)
    }

    /** Two zoom-14 tiles and one zoom-12 tile queued at zoom 14; one zoom-14 tile then completes. */
    private fun queueTilesAtZoom14ThenReturnToZoom12(): Pair<Job, Job> {
        position.setMapPosition(MapPosition(CENTER, 14.toByte()), false)
        val first = job(14)
        val staleZoom = job(14)
        val onScreen = job(12)
        listOf(first, staleZoom, onScreen).forEach(queue::add)
        assertSame(first, queue.get())
        queue.remove(first)
        position.setMapPosition(MapPosition(CENTER, 12.toByte()), false)
        return staleZoom to onScreen
    }

    private fun job(zoom: Int): Job {
        val size = displayModel.tileSize
        val x = MercatorProjection.longitudeToTileX(CENTER.longitude, zoom.toByte())
        val y = MercatorProjection.latitudeToTileY(CENTER.latitude, zoom.toByte())
        // Distinct keys at zoom 14 so both zoom-14 jobs stay queued.
        val offset = if (zoom == 14) jobsAt14++ else 0
        return Job(Tile(x + offset, y, zoom.toByte(), size), false)
    }

    private var jobsAt14 = 0

    private companion object {
        val CENTER = LatLong(47.3769, 8.5417)
    }
}
