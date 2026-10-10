package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mapsforge.core.model.Tile
import org.mapsforge.map.layer.queue.Job
import org.mapsforge.map.layer.queue.JobQueue
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.model.MapViewPosition
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MapTileJobSchedulingTest {
    private lateinit var position: MapViewPosition
    private lateinit var queue: JobQueue<Job>

    @Before
    fun setUp() {
        val display = DisplayModel().apply { setFixedTileSize(256) }
        position = MapViewPosition(display).apply { setZoomLevel(6, false) }
        queue = JobQueue(position, display)
    }

    @After
    fun tearDown() {
        position.destroy()
    }

    @Test
    fun `first draw and unchanged zoom allow startup prewarming`() {
        val policy = MapStartupTilePrewarmPolicy()

        assertFalse(policy.onDrawZoom(16))
        assertFalse(policy.onDrawZoom(16))
        assertTrue(policy.isAllowed())
    }

    @Test
    fun `zoom changes cancel startup prewarming including a delayed arm`() {
        val policy = MapStartupTilePrewarmPolicy()
        policy.onDrawZoom(16)

        assertTrue(policy.onDrawZoom(6))
        assertFalse(policy.isAllowed())
        assertTrue(policy.onDrawZoom(16))
        assertFalse(policy.isAllowed())
    }

    @Test
    fun `returning to an already queued zoom refreshes stale priorities`() {
        val assigned = job(6)
        val oldZoom = job(6, offset = -1)
        val currentZoom = job(12)
        queue.add(assigned)
        queue.add(oldZoom)
        queue.add(currentZoom)
        assertEquals(assigned, queue.get(4))

        position.setZoomLevel(12, false)
        queue.add(currentZoom)
        assertTrue(refreshMapTileJobPriorities(queue))

        assertEquals(2, queue.size())
        assertEquals(currentZoom, queue.get(4))
        assertEquals(oldZoom, queue.get(4))
    }

    @Test
    fun `refreshing priorities preserves every waiting job without duplicates`() {
        val jobs = setOf(job(6), job(10), job(12))
        jobs.forEach(queue::add)

        repeat(5) { assertTrue(refreshMapTileJobPriorities(queue)) }

        val remaining = mutableSetOf<Job>()
        repeat(jobs.size) {
            val next = queue.get(Int.MAX_VALUE)
            assertTrue(remaining.add(next))
            queue.remove(next)
        }
        assertEquals(jobs, remaining)
        assertEquals(0, queue.size())
    }

    @Test
    fun `empty queue refresh does not wait behind an assigned render`() {
        val assigned = job(6)
        queue.add(assigned)
        assertEquals(assigned, queue.get(1))

        assertFalse(refreshMapTileJobPriorities(queue))
        assertEquals(0, queue.size())
        queue.remove(assigned)
    }

    @Test
    fun `refresh does not release a render assigned to a worker`() {
        val assigned = job(6)
        val waiting = job(12)
        queue.add(assigned)
        assertEquals(assigned, queue.get(1))
        queue.add(waiting)
        assertTrue(refreshMapTileJobPriorities(queue))
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val next =
                executor.submit(
                    Callable {
                        entered.countDown()
                        queue.get(1)
                    },
                )
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            try {
                next.get(100, TimeUnit.MILLISECONDS)
                fail("The running render must keep the worker assignment limit occupied")
            } catch (_: TimeoutException) {
                // Still blocked by the original worker assignment.
            }
            queue.remove(assigned)
            assertEquals(waiting, next.get(1, TimeUnit.SECONDS))
            queue.remove(waiting)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun job(
        zoom: Int,
        offset: Int = 0,
    ): Job {
        val middle = 1 shl (zoom - 1)
        return Job(Tile(middle + offset, middle, zoom.toByte(), 256), false)
    }
}
