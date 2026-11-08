package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong

class WorldWindStartCenterTest {
    private val bayern = BoundingBox(47.27, 8.97, 50.56, 13.84)

    @Test
    fun `waits for the restored position after a cold start at 0,0`() =
        runBlocking {
            val centers = ArrayDeque(listOf(LatLong(0.0, 0.0), LatLong(0.0, 0.0), LatLong(49.7984, 9.9360)))
            var reads = 0

            val center =
                awaitWorldWindStartCenter(bayern, intervalMs = 1) {
                    reads++
                    centers.removeFirst()
                }

            assertEquals(LatLong(49.7984, 9.9360), center)
            assertEquals(3, reads)
        }

    @Test
    fun `a center already inside the map is used without waiting`() =
        runBlocking {
            var reads = 0
            val center =
                awaitWorldWindStartCenter(bayern, intervalMs = 1) {
                    reads++
                    LatLong(47.3466, 11.0718)
                }
            assertEquals(LatLong(47.3466, 11.0718), center)
            assertEquals(1, reads)
        }

    @Test
    fun `a center that never enters the map is returned after the bounded wait`() =
        runBlocking {
            var reads = 0
            val center =
                awaitWorldWindStartCenter(bayern, attempts = 5, intervalMs = 1) {
                    reads++
                    LatLong(45.0, 6.0)
                }
            assertEquals(LatLong(45.0, 6.0), center)
            assertEquals(6, reads)
        }
}
