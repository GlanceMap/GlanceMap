package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicInteger

class PhoneOnlineElevationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun terrariumDecodesDocumentedValueSeaLevelNegativeAndVoids() {
        assertEquals(2523.265625, phoneTerrariumElevation(0xff89db44.toInt())!!, 0.000001)
        assertEquals(0.0, phoneTerrariumElevation(0xff800000.toInt())!!, 0.0)
        assertEquals(-12.5, phoneTerrariumElevation(0xff7ff380.toInt())!!, 0.0)
        assertNull(phoneTerrariumElevation(0xff000000.toInt()))
        assertNull(phoneTerrariumElevation(0x00800000))
    }

    @Test
    fun interpolationUsesPixelCentersAndIgnoresVoidSamples() {
        val tile =
            PhoneTerrariumTile(
                intArrayOf(0xff800000.toInt(), 0xff800a00.toInt(), 0xff801400.toInt(), 0xff801e00.toInt()),
                2,
            )
        assertEquals(15.0, tile.elevationAt(0.5, 0.5)!!, 0.0)
        assertEquals(0.0, tile.elevationAt(-0.5, -0.5)!!, 0.0)
        val missing = PhoneTerrariumTile(intArrayOf(0, 0xff800a00.toInt(), 0, 0), 2)
        assertEquals(10.0, missing.elevationAt(0.5, 0.5)!!, 0.0)
        assertNull(PhoneTerrariumTile(IntArray(4), 2).elevationAt(0.5, 0.5))
    }

    @Test
    fun tileAddressingUsesXyzAndWrapsAntimeridianWithoutSamplingOtherPolarLocations() {
        val equator = phoneOnlineDemPoint(PhoneMapCoordinate(0.0, 0.0))!!
        assertEquals(PhoneOnlineDemTileId(14, 8192, 8192), equator.tile)
        assertEquals(-0.5, equator.pixelX, 0.000001)
        assertEquals(-0.5, equator.pixelY, 0.000001)
        val north = phoneOnlineDemPoint(PhoneMapCoordinate(45.0, 0.0))!!
        assertEquals(5893, north.tile.y)
        assertEquals(0, phoneOnlineDemPoint(PhoneMapCoordinate(0.0, 180.0))!!.tile.x)
        assertEquals(16383, phoneOnlineDemPoint(PhoneMapCoordinate(0.0, 179.99999))!!.tile.x)
        assertNull(phoneOnlineDemPoint(PhoneMapCoordinate(86.0, 0.0)))
        assertNull(phoneOnlineDemPoint(PhoneMapCoordinate(Double.NaN, 0.0)))
        assertNull(phoneOnlineDemPoint(PhoneMapCoordinate(0.0, Double.POSITIVE_INFINITY)))
    }

    @Test
    fun concurrentReadsReuseTileAndMemoryCacheEvictsLeastRecentlyUsed() =
        runBlocking {
            var loads = 0
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository =
                PhoneOnlineElevationRepository(fetchTile = {
                    loads += 1
                    if (loads == 1) {
                        started.complete(Unit)
                        release.await()
                    }
                    constantTile(10)
                })
            val first = async { repository.elevationAt(PhoneMapCoordinate(0.0, 0.0)) }
            started.await()
            val second = async { repository.elevationAt(PhoneMapCoordinate(0.0, 0.001)) }
            release.complete(Unit)
            assertEquals(10.0, first.await()!!, 0.0)
            assertEquals(10.0, second.await()!!, 0.0)
            assertEquals(1, loads)
            (1..32).forEach { longitude -> repository.elevationAt(PhoneMapCoordinate(0.0, longitude.toDouble())) }
            assertEquals(33, loads)
            repository.elevationAt(PhoneMapCoordinate(0.0, 32.0))
            assertEquals(33, loads)
            repository.elevationAt(PhoneMapCoordinate(0.0, 0.0))
            assertEquals(34, loads)
        }

    @Test
    fun differentTilesLoadTogetherUpToFourAtOnce() =
        runBlocking(Dispatchers.Default) {
            val running = AtomicInteger()
            val peak = AtomicInteger()
            val fourRunning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository =
                PhoneOnlineElevationRepository(fetchTile = {
                    val now = running.incrementAndGet()
                    peak.accumulateAndGet(now, ::maxOf)
                    if (now == 4) fourRunning.complete(Unit)
                    release.await()
                    running.decrementAndGet()
                    constantTile(5)
                })
            val tiles = (0 until 6).map { PhoneOnlineDemTileId(14, 8192 + it, 8192) }
            val preload = async { repository.preload(tiles + tiles.first()) }
            withTimeout(5_000) { fourRunning.await() }
            release.complete(Unit)
            preload.await()
            assertEquals(4, peak.get())
            tiles.forEach { tile ->
                val center = PhoneMapCoordinate(0.0, (tile.x - 8192 + 0.5) * 360.0 / (1 shl 14))
                assertEquals(5.0, repository.elevationAt(center)!!, 0.0)
            }
            assertEquals(0, running.get())
        }

    @Test
    fun waitersForOneTileShareItsLoadWhileAnotherTileLoads() =
        runBlocking(Dispatchers.Default) {
            val calls = mutableMapOf<PhoneOnlineDemTileId, Int>()
            val slowStarted = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            val slow = PhoneOnlineDemTileId(14, 8192, 8192)
            val repository =
                PhoneOnlineElevationRepository(fetchTile = { tile ->
                    synchronized(calls) { calls[tile] = (calls[tile] ?: 0) + 1 }
                    if (tile == slow) {
                        slowStarted.complete(Unit)
                        releaseSlow.await()
                    }
                    constantTile(7)
                })
            val first = async { repository.elevationAt(PhoneMapCoordinate(0.0, 0.0)) }
            withTimeout(5_000) { slowStarted.await() }
            val second = async { repository.elevationAt(PhoneMapCoordinate(0.0, 0.001)) }
            // A different tile is not held up by the pending one.
            assertEquals(7.0, withTimeout(5_000) { repository.elevationAt(PhoneMapCoordinate(0.0, 10.0)) }!!, 0.0)
            releaseSlow.complete(Unit)
            assertEquals(7.0, first.await()!!, 0.0)
            assertEquals(7.0, second.await()!!, 0.0)
            assertEquals(1, calls[slow])
        }

    @Test
    fun failuresAreBrieflyCachedAndRetried() =
        runBlocking {
            var now = 0L
            var loads = 0
            val repository =
                PhoneOnlineElevationRepository(
                    fetchTile = {
                        loads += 1
                        if (loads == 1) null else constantTile(20)
                    },
                    clockMillis = { now },
                )
            val point = PhoneMapCoordinate(0.0, 0.0)
            assertNull(repository.elevationAt(point))
            now = 29_999L
            assertNull(repository.elevationAt(point))
            assertEquals(1, loads)
            now = 30_000L
            assertEquals(20.0, repository.elevationAt(point)!!, 0.0)
            assertEquals(2, loads)
        }

    @Test
    fun cancellationReleasesPendingTileAndDoesNotCacheFailure() =
        runBlocking {
            var loads = 0
            val started = CompletableDeferred<Unit>()
            val repository =
                PhoneOnlineElevationRepository(fetchTile = {
                    loads += 1
                    if (loads == 1) {
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                    constantTile(30)
                })
            val pending = async { repository.elevationAt(PhoneMapCoordinate(0.0, 0.0)) }
            started.await()
            pending.cancelAndJoin()
            assertEquals(30.0, repository.elevationAt(PhoneMapCoordinate(0.0, 0.0))!!, 0.0)
            assertEquals(2, loads)
        }

    @Test
    fun localDemWinsIncludingSeaLevelAndDisabledFallbackDoesNotFetch() =
        runBlocking {
            val folder = temporaryFolder.newFolder()
            DataOutputStream(folder.resolve("N00E000.hgt").outputStream()).use { output ->
                repeat(4) { output.writeShort(0) }
            }
            var loads = 0
            val online =
                PhoneOnlineElevationRepository(fetchTile = {
                    loads += 1
                    constantTile(99)
                })
            val local = PhoneElevationRepository(listOf(folder))
            assertEquals(0.0, phoneMapElevationAt(PhoneMapCoordinate(0.25, 0.25), local, online)!!, 0.0)
            assertEquals(0, loads)
            assertNull(phoneMapElevationAt(PhoneMapCoordinate(1.25, 0.25), local, null))
            assertEquals(0, loads)
            assertEquals(99.0, phoneMapElevationAt(PhoneMapCoordinate(1.25, 0.25), local, online)!!, 0.0)
            assertEquals(1, loads)
        }

    @Test
    fun liveSampleCannotPublishAfterTargetMovesOrDisappears() {
        val sampled = PhoneMapCoordinate(45.0, 6.0)
        assertTrue(phoneMapElevationTargetIsCurrent(sampled, sampled))
        assertTrue(phoneMapElevationTargetIsCurrent(sampled, PhoneMapCoordinate(45.00001, 6.0)))
        assertFalse(phoneMapElevationTargetIsCurrent(sampled, PhoneMapCoordinate(45.001, 6.0)))
        assertFalse(phoneMapElevationTargetIsCurrent(sampled, null))
    }

    @Test
    fun liveReadingUsesDemFirstAndOnlyNearbyGpsFallback() {
        val target = PhoneMapCoordinate(45.0, 6.0)
        val location = PhoneMapLocation(45.0, 6.0, 5f, 1L, altitudeMeters = 100.0)
        assertEquals(0.0, phoneMapLiveElevation(0.0, target, location)!!, 0.0)
        assertEquals(100.0, phoneMapLiveElevation(null, target, location)!!, 0.0)
        assertEquals(120.0, phoneMapLiveElevation(null, target, location.copy(altitudeMeters = 120.0))!!, 0.0)
        assertNull(phoneMapLiveElevation(null, PhoneMapCoordinate(45.01, 6.0), location))
        assertNull(phoneMapLiveElevation(null, target, null))
    }

    private fun constantTile(height: Int): PhoneTerrariumTile =
        PhoneTerrariumTile(
            pixels = IntArray(4) { 0xff000000.toInt() or ((height + 32768) shl 8) },
            size = 2,
        )
}
