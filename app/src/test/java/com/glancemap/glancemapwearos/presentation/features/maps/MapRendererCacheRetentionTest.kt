package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.core.model.Tile
import org.mapsforge.map.layer.cache.FileSystemTileCache
import org.mapsforge.map.layer.queue.Job
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

class MapRendererCacheRetentionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `configuration A to B to A retains separate persistent tile payloads`() {
        val job = Job(Tile(1, 2, 3, 256), false)
        val firstDir = temporaryFolder.newFolder("A")
        val secondDir = temporaryFolder.newFolder("B")
        val firstPayload = byteArrayOf(1, 2, 3)
        val secondPayload = byteArrayOf(4, 5, 6)
        val firstCache = FileSystemTileCache(16, firstDir, null, true)
        firstCache.put(job, bitmap(firstPayload))

        closeMapRendererTileCache(firstCache, "A", "B")
        val secondCache = FileSystemTileCache(16, secondDir, null, true)
        secondCache.put(job, bitmap(secondPayload))
        closeMapRendererTileCache(secondCache, "B", "A")

        assertArrayEquals(firstPayload, File(firstDir, "${job.key}.tile").readBytes())
        assertArrayEquals(secondPayload, File(secondDir, "${job.key}.tile").readBytes())
    }

    @Test
    fun `explicit rebuild of the same identity removes persistent tiles`() {
        val job = Job(Tile(1, 2, 3, 256), false)
        val directory = temporaryFolder.newFolder("same")
        val cache = FileSystemTileCache(16, directory, null, true)
        cache.put(job, bitmap(byteArrayOf(1, 2, 3)))
        val file = File(directory, "${job.key}.tile")
        assertTrue(file.exists())

        closeMapRendererTileCache(cache, "same", "same")

        assertFalse(file.exists())
    }

    @Test
    fun `map and theme replacements invalidate even with equal size and modification time`() {
        val map = temporaryFolder.newFile("area.map").apply { writeText("old") }
        val theme = temporaryFolder.newFile("theme.xml").apply { writeText("old") }
        val timestamp = FileTime.fromMillis(1_700_000_000_000L)
        Files.setLastModifiedTime(map.toPath(), timestamp)
        Files.setLastModifiedTime(theme.toPath(), timestamp)
        val originalMap = computeMapRendererMapSignature(map.absolutePath)
        val originalTheme = themeSignature(theme)

        replaceFile(map, timestamp)
        replaceFile(theme, timestamp)

        assertNotEquals(originalMap, computeMapRendererMapSignature(map.absolutePath))
        assertNotEquals(originalTheme, themeSignature(theme))
    }

    @Test
    fun `map theme units and label size keep rendered buckets separate`() {
        fun identity(
            map: String = "map:A",
            theme: String = "theme:A",
            metric: Boolean = true,
            textScale: Float = 1f,
        ) = resolveMapRendererDesiredCacheId(map, theme, metric, textScale)

        val original = identity()
        assertNotEquals(original, identity(map = "map:B"))
        assertNotEquals(original, identity(theme = "theme:B"))
        assertNotEquals(original, identity(metric = false))
        assertNotEquals(original, identity(textScale = 1.75f))
    }

    private fun replaceFile(
        target: File,
        timestamp: FileTime,
    ) {
        val replacement = temporaryFolder.newFile().apply { writeText("new") }
        Files.setLastModifiedTime(replacement.toPath(), timestamp)
        Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun themeSignature(file: File): String =
        computeMapRendererThemeSignature(
            file,
            null,
            "elevate",
            hillShadingEnabled = false,
        )

    private fun bitmap(payload: ByteArray): TileBitmap =
        Proxy.newProxyInstance(TileBitmap::class.java.classLoader, arrayOf(TileBitmap::class.java)) { _, method, args ->
            check(method.name == "compress") { "Unexpected bitmap operation: ${method.name}" }
            (checkNotNull(args)[0] as OutputStream).write(payload)
            null
        } as TileBitmap
}
