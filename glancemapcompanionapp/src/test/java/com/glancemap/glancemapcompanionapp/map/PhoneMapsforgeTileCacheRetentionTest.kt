package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.core.model.Tile
import org.mapsforge.map.layer.cache.FileSystemTileCache
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.queue.Job
import java.io.File
import java.io.OutputStream

class PhoneMapsforgeTileCacheRetentionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun staleBucketsAreDeletedUnlessTheyAreLive() {
        val buckets =
            listOf(
                bucket("phone-offline-stale", ageMs = PHONE_MAP_TILE_CACHE_MAX_AGE_MS + 1),
                bucket("phone-offline-live", ageMs = PHONE_MAP_TILE_CACHE_MAX_AGE_MS + 1),
                bucket("phone-offline-fresh", ageMs = PHONE_MAP_TILE_CACHE_MAX_AGE_MS - 1),
            )

        val deleted = phoneMapsforgeTileCacheBucketsToDelete(buckets, NOW, liveIds = setOf("phone-offline-live"))

        assertEquals(listOf("phone-offline-stale"), deleted)
    }

    @Test
    fun bucketsUnderTheSoftLimitAreKept() {
        val buckets = (0 until 8).map { bucket("phone-offline-$it", ageMs = it * HOUR, sizeBytes = 10 * MEBIBYTE) }

        assertTrue(phoneMapsforgeTileCacheBucketsToDelete(buckets, NOW, emptySet()).isEmpty())
    }

    @Test
    fun overTheSoftLimitTheOldestBucketsOutsideTheRecentSetGoFirstUntilTheTarget() {
        // 8 x 25 MiB = 200 MiB: above the 150 MiB soft limit, so trim to the 100 MiB target.
        val buckets = (0 until 8).map { bucket("phone-offline-$it", ageMs = it * HOUR, sizeBytes = 25 * MEBIBYTE) }

        val deleted = phoneMapsforgeTileCacheBucketsToDelete(buckets, NOW, liveIds = setOf("phone-offline-7"))

        // The three oldest non-recent buckets go first; then the oldest recent non-live one.
        assertEquals(listOf("phone-offline-6", "phone-offline-5", "phone-offline-4", "phone-offline-3"), deleted)
        assertFalse("phone-offline-7" in deleted)
    }

    @Test
    fun aLiveBucketIsNeverDeletedEvenWhenItAloneExceedsTheLimit() {
        val buckets = listOf(bucket("phone-offline-live", ageMs = 0L, sizeBytes = 400 * MEBIBYTE))

        assertTrue(phoneMapsforgeTileCacheBucketsToDelete(buckets, NOW, setOf("phone-offline-live")).isEmpty())
    }

    @Test
    fun cleanupDeletesOnlyNonLiveStaleTileBuckets() {
        val root = temporaryFolder.newFolder("cache")
        val stale = bucketDir(root, "phone-offline-stale", lastUsedMs = NOW - PHONE_MAP_TILE_CACHE_MAX_AGE_MS - HOUR)
        val live = bucketDir(root, "phone-offline-live", lastUsedMs = NOW - PHONE_MAP_TILE_CACHE_MAX_AGE_MS - HOUR)
        val hillshade =
            bucketDir(root, "phone-hillshade-old", lastUsedMs = NOW - PHONE_MAP_TILE_CACHE_MAX_AGE_MS - HOUR)
        PhoneMapsforgeTileCacheRetention.acquire(live.name)
        try {
            val result = PhoneMapsforgeTileCacheRetention.cleanup(listOf(root), NOW)

            assertEquals(1, result.deletedBuckets)
            assertEquals(2, result.bucketsBefore)
            assertEquals(result.bytesBefore / 2, result.bytesAfter)
            assertFalse(stale.exists())
            assertTrue(live.exists())
            assertTrue(hillshade.exists())
            assertEquals(listOf("phone-hillshade-old", "phone-offline-live"), root.list()!!.sorted())
        } finally {
            PhoneMapsforgeTileCacheRetention.release(live.name)
        }
        assertEquals(0, PhoneMapsforgeTileCacheRetention.liveCount(live.name))
    }

    @Test
    fun markUsedRefreshesAnExistingBucketWithoutCreatingMissingOnes() {
        val root = temporaryFolder.newFolder("marker")
        val dir = bucketDir(root, "phone-offline-a", lastUsedMs = NOW - PHONE_MAP_TILE_CACHE_MAX_AGE_MS - HOUR)

        PhoneMapsforgeTileCacheRetention.markUsed("phone-offline-a", listOf(root), nowMs = NOW)
        PhoneMapsforgeTileCacheRetention.markUsed("phone-offline-missing", listOf(root), nowMs = NOW)

        assertEquals(NOW, File(dir, PHONE_MAP_TILE_CACHE_MARKER_NAME).lastModified())
        assertFalse(File(root, "phone-offline-missing").exists())
        assertEquals(0, PhoneMapsforgeTileCacheRetention.cleanup(listOf(root), NOW).deletedBuckets)
    }

    @Test
    fun destroyingTheDrawableCacheReleasesItsBucketExactlyOnce() {
        var releases = 0
        val bucket =
            PhoneMapsforgePersistentBucket(
                reader = NoDiskTiles,
                onReleased = { releases++ },
            )
        val cache = PhoneMapsforgeTileCache(InMemoryTileCache(1), InMemoryTileCache(1), bucket = bucket)

        cache.destroy()
        cache.destroy()

        assertEquals(1, releases)
    }

    @Test
    fun cacheIdSeparatesTileSizeAndAppVersionWithoutChangingTheThemeIdentity() {
        val identity = PhoneMapsforgeBaseLayerIdentity("map:rev", PhoneOfflineThemeConfig("elevate", "elv-hiking"))
        val ids =
            listOf(
                phoneMapsforgeTileCacheId(identity, renderSignature = phoneMapsforgeTileRenderSignature(512, 10)),
                phoneMapsforgeTileCacheId(identity, renderSignature = phoneMapsforgeTileRenderSignature(768, 10)),
                phoneMapsforgeTileCacheId(identity, renderSignature = phoneMapsforgeTileRenderSignature(512, 11)),
            )

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith(PHONE_MAP_TILE_CACHE_DIR_PREFIX) })
        // "phone-offline-<map hash>-<theme and render hash>": the map segment is unchanged.
        val unsigned = phoneMapsforgeTileCacheId(identity)
        assertTrue(ids.all { it.split('-')[2] == unsigned.split('-')[2] })
        assertNotEquals(unsigned, ids.first())
    }

    /**
     * Before/after for the reopen path: a renderer surface that is destroyed and recreated for the
     * same map and theme finds none of its rendered tiles when the bucket is not persistent.
     */
    @Test
    fun recreatedSurfaceReusesRenderedTilesOnlyWithAPersistentBucket() {
        val rendered = (0 until 25).map { index -> Job(Tile(index % 5, index / 5, 12, 512), false) }

        assertEquals(0, reusableTilesAfterRecreation(rendered, persistent = false))
        assertEquals(rendered.size, reusableTilesAfterRecreation(rendered, persistent = true))
    }

    private fun reusableTilesAfterRecreation(
        rendered: List<Job>,
        persistent: Boolean,
    ): Int {
        val dir = temporaryFolder.newFolder("bucket-$persistent")
        FileSystemTileCache(CAPACITY, dir, null, persistent).apply {
            rendered.forEach { put(it, FileTileBitmap()) }
            destroy()
        }
        val reopened = FileSystemTileCache(CAPACITY, dir, null, persistent)
        try {
            // The persistent index is rebuilt on Mapsforge's background directory reader.
            val deadline = System.currentTimeMillis() + 5_000L
            while (persistent && rendered.any { !reopened.containsKey(it) } && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L)
            }
            return rendered.count(reopened::containsKey)
        } finally {
            reopened.destroy()
        }
    }

    private fun bucket(
        name: String,
        ageMs: Long,
        sizeBytes: Long = MEBIBYTE,
    ) = PhoneMapsforgeTileCacheBucket(name = name, lastUsedMs = NOW - ageMs, sizeBytes = sizeBytes)

    private fun bucketDir(
        root: File,
        name: String,
        lastUsedMs: Long,
    ): File =
        File(root, name).apply {
            File(this, "12/0").mkdirs()
            File(this, "12/0/0.tile").writeBytes(ByteArray(16))
            File(this, PHONE_MAP_TILE_CACHE_MARKER_NAME).apply {
                createNewFile()
                setLastModified(lastUsedMs)
            }
            File(this, "12/0").setLastModified(lastUsedMs)
            File(this, "12").setLastModified(lastUsedMs)
            setLastModified(lastUsedMs)
        }

    private object NoDiskTiles : PhoneMapsforgeDiskTileReader {
        override fun hasTile(job: Job): Boolean = false

        override fun read(job: Job): TileBitmap? = null
    }

    private class FileTileBitmap : TileBitmap {
        override fun compress(outputStream: OutputStream) = outputStream.write(ByteArray(64))

        override fun decrementRefCount() = Unit

        override fun getHeight(): Int = 512

        override fun getWidth(): Int = 512

        override fun incrementRefCount() = Unit

        override fun isDestroyed(): Boolean = false

        override fun scaleTo(
            width: Int,
            height: Int,
        ) = Unit

        override fun setBackgroundColor(color: Int) = Unit

        override fun getMutex(): Any = this

        override fun getTimestamp(): Long = 0L

        override fun isExpired(): Boolean = false

        override fun setExpiration(expiration: Long) = Unit

        override fun setTimestamp(timestamp: Long) = Unit
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60L * 60L * 1000L
        const val MEBIBYTE = 1024L * 1024L
        const val CAPACITY = 100
    }
}
