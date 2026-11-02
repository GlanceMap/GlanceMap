package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream

class PhoneWorldWindElevationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun adaptiveZoomKeepsOnlineTileCountBoundedAtTheEquatorAndHighLatitudes() {
        for ((south, west, north, east) in listOf(
            listOf(-85.0, -180.0, 85.0, 180.0),
            listOf(45.0, 10.0, 46.0, 11.0),
            listOf(84.0, 10.0, 85.0, 11.0),
            listOf(46.0, 10.0, 46.001, 10.001),
        )) {
            val zoom = phoneWorldWindOnlineDemZoom(south, west, north, east, 64)
            val first = phoneOnlineDemPoint(PhoneMapCoordinate(north, west.coerceAtLeast(-179.999999)), zoom)!!.tile
            val last = phoneOnlineDemPoint(PhoneMapCoordinate(south, east.coerceAtMost(179.999999)), zoom)!!.tile
            assertTrue(zoom in 0..14)
            assertTrue((last.x - first.x + 1) * (last.y - first.y + 1) <= 4)
        }
    }

    @Test
    fun gridSamplesCellCentersSoNeighbouringTilesDoNotRepeatTheirSharedEdge() {
        assertEquals(0.5, phoneWorldWindCellCenter(0.0, 64.0, 0, 64), 1e-9)
        assertEquals(63.5, phoneWorldWindCellCenter(0.0, 64.0, 63, 64), 1e-9)
        val lastOfFirstTile = phoneWorldWindCellCenter(0.0, 1.0, 63, 64)
        val firstOfNextTile = phoneWorldWindCellCenter(1.0, 2.0, 0, 64)
        assertEquals(1.0 / 64.0, firstOfNextTile - lastOfFirstTile, 1e-9)
        assertEquals(46.0 - 0.5 / 64.0, phoneWorldWindCellCenter(46.0, 45.0, 0, 64), 1e-9)
    }

    @Test
    fun resolutionFollowsTheActualCenterTileAndOnlineFallback() {
        assertEquals(3, phoneWorldWindDemResolutionSeconds(1200, false))
        assertEquals(1, phoneWorldWindDemResolutionSeconds(3600, false))
        assertEquals(1, phoneWorldWindDemResolutionSeconds(1200, true))
        assertEquals(1, phoneWorldWindDemResolutionSeconds(null, false))
    }

    @Test
    fun groupedGridReadsEachHgtTileOnceAndKeepsVoidSamplesMissing() =
        runBlocking {
            val root = folder.newFolder()
            DataOutputStream(root.resolve("N00E000.hgt").outputStream()).use { out ->
                repeat(4) { out.writeShort(123) }
            }
            DataOutputStream(root.resolve("N00E001.hgt").outputStream()).use { out ->
                repeat(4) { out.writeShort(Short.MIN_VALUE.toInt()) }
            }
            val local = PhoneElevationRepository(listOf(root))
            var accesses = 0
            local.onTileAccess = { _, _ -> accesses++ }
            val result =
                phoneWorldWindElevationGrid(
                    PhoneWorldWindBounds(0.1, 0.1, 0.9, 1.9),
                    8 to 8,
                    local,
                    null,
                    0,
                )
            assertEquals(2, accesses)
            assertEquals(123.toShort(), result!![0])
            assertEquals(Short.MIN_VALUE, result[7])
        }

    @Test
    fun coarseGridSkipsLocalFilesAndUsesOnlineElevation() =
        runBlocking {
            val root = folder.newFolder()
            val local = PhoneElevationRepository(listOf(root))
            var accesses = 0
            local.onTileAccess = { _, _ -> accesses++ }
            val wide = PhoneWorldWindBounds(0.1, 0.1, 2.9, 2.9)
            val offline = phoneWorldWindElevationGrid(wide, 8 to 8, local, null, 0)
            assertEquals(0, accesses)
            assertTrue(offline!!.all { it == Short.MIN_VALUE })
            val online = PhoneOnlineElevationRepository(fetchTile = { PhoneTerrariumTile(IntArray(4) { 0xff801400.toInt() }, 2) })
            assertTrue(phoneWorldWindElevationGrid(wide, 8 to 8, local, online, 5)!!.all { it == 20.toShort() })
            assertEquals(0, accesses)
            phoneWorldWindElevationGrid(PhoneWorldWindBounds(0.1, 0.1, 1.9, 1.9), 8 to 8, local, null, 0)
            assertEquals(PHONE_WORLDWIND_MAX_LOCAL_DEM_TILES, accesses)
        }

    @Test
    fun localTilesToPrepareMatchTheGridAndSkipCoarseGrids() {
        assertEquals(
            listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1),
            phoneWorldWindGridLocalDemTiles(PhoneWorldWindBounds(0.1, 0.1, 1.9, 1.9), 8 to 8).sortedBy { it.first * 10 + it.second },
        )
        val southWest = PhoneWorldWindBounds(-0.9, -0.9, -0.1, -0.1)
        assertEquals(listOf(-1 to -1), phoneWorldWindGridLocalDemTiles(southWest, 8 to 8))
        assertTrue(phoneWorldWindGridLocalDemTiles(PhoneWorldWindBounds(0.1, 0.1, 2.9, 2.9), 8 to 8).isEmpty())
    }

    private val gridBounds = PhoneWorldWindBounds(0.1, 0.1, 0.9, 0.9)

    @Test
    fun failedOnlineTileRejectsTheGridSoWorldWindRetriesIt() =
        runBlocking {
            val online = PhoneOnlineElevationRepository(fetchTile = { null })
            assertNull(phoneWorldWindElevationGrid(gridBounds, 4 to 4, null, online, 5))
        }

    @Test
    fun loadedOnlineTileWithoutDataKeepsItsGrid() =
        runBlocking {
            val online = PhoneOnlineElevationRepository(fetchTile = { PhoneTerrariumTile(IntArray(4), 2) })
            val heights = phoneWorldWindElevationGrid(gridBounds, 4 to 4, null, online, 5)
            assertTrue(heights!!.all { it == Short.MIN_VALUE })
        }

    @Test
    fun absentLocalTileKeepsItsGridButExhaustedMemoryDoesNot() =
        runBlocking {
            val root = folder.newFolder()
            val absent = PhoneElevationRepository(listOf(root))
            assertTrue(phoneWorldWindElevationGrid(gridBounds, 4 to 4, absent, null, 0) != null)

            var fail = true
            val local =
                PhoneElevationRepository(
                    {
                        if (fail) throw OutOfMemoryError()
                        listOf(root)
                    },
                    false,
                )
            assertNull(phoneWorldWindElevationGrid(gridBounds, 4 to 4, local, null, 0))
            assertTrue(local.tileExceededMemory(0, 0))
            fail = false
            assertTrue(phoneWorldWindElevationGrid(gridBounds, 4 to 4, local, null, 0) != null)
            assertFalse(local.tileExceededMemory(0, 0))
        }

    @Test
    fun localSamplesWinAndOnlineIsUsedOnlyWhenEnabled() =
        runBlocking {
            val root = folder.newFolder()
            DataOutputStream(root.resolve("N00E000.hgt").outputStream()).use { out -> repeat(4) { out.writeShort(123) } }
            val local = PhoneElevationRepository(listOf(root))
            var loads = 0
            val online =
                PhoneOnlineElevationRepository(fetchTile = {
                    loads++
                    PhoneTerrariumTile(IntArray(4) { 0xff801400.toInt() }, 2)
                })
            assertEquals(123.0, phoneWorldWindElevationAt(0.25, 0.25, local, online, 5)!!, 0.0)
            assertEquals(0, loads)
            assertNull(phoneWorldWindElevationAt(1.25, 0.25, local, null, 5))
            assertEquals(0, loads)
            assertEquals(20.0, phoneWorldWindElevationAt(1.25, 0.25, local, online, 5)!!, 0.0)
            assertEquals(1, loads)
            assertEquals(20.0, phoneWorldWindElevationAt(1.25, 0.25, null, online, 5)!!, 0.0)
            assertEquals(1, loads)
        }
}
