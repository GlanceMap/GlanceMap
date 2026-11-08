package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorldWindTileDiskCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private var clock = 1_000_000_000_000L
    private val key = WorldWindTileKey("map:1:100", "hiking-3d:1041:abc", 1f, 14, 8_700, 5_700, 256)

    @Test
    fun `saved image is read back and other themes or maps do not share it`() {
        val cache = WorldWindTileDiskCache(temp.newFolder("tiles"), now = { clock })
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertEquals(4L, cache.write(key, bytes))

        assertArrayEquals(bytes, cache.read(key))
        assertNull(cache.read(key.copy(theme = "selected:1041:abc")))
        assertNull(cache.read(key.copy(map = "map:2:100")))
        assertNull(cache.read(key.copy(x = 8_701)))
        assertNotEquals(worldWindTileIdentity(key), worldWindTileIdentity(key.copy(scale = 1.2f)))
    }

    @Test
    fun `least recently used images are removed above the size limit`() {
        val cache = WorldWindTileDiskCache(temp.newFolder("tiles"), maxBytes = 10, now = { clock })
        val keys = (0 until 3).map { key.copy(x = key.x + it) }
        keys.forEach { tile ->
            cache.write(tile, ByteArray(4))
            clock += 1_000
        }
        cache.read(keys[0])

        cache.trim()

        assertEquals(8L, cache.sizeBytes())
        assertNull(cache.read(keys[1]))
        assertEquals(4, cache.read(keys[0])?.size)
        assertEquals(4, cache.read(keys[2])?.size)
    }

    @Test
    fun `images unused for longer than the age limit are removed`() {
        val cache = WorldWindTileDiskCache(temp.newFolder("tiles"), maxAgeMs = 60_000, now = { clock })
        cache.write(key, ByteArray(4))
        clock += 30_000
        cache.write(key.copy(x = 1), ByteArray(4))
        clock += 45_000

        cache.trim()

        assertNull(cache.read(key))
        assertEquals(4, cache.read(key.copy(x = 1))?.size)
    }

    @Test
    fun `a store that cannot be written reports nothing saved`() {
        val blocked = temp.newFile("blocked")
        val cache = WorldWindTileDiskCache(blocked, now = { clock })

        assertEquals(0L, cache.write(key, ByteArray(4)))
        assertNull(cache.read(key))
    }
}
