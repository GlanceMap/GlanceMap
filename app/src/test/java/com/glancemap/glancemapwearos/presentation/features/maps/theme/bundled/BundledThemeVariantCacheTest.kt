package com.glancemap.glancemapwearos.presentation.features.maps.theme.bundled

import com.glancemap.glancemapwearos.presentation.features.maps.computeMapRendererThemeSignature
import com.glancemap.glancemapwearos.presentation.features.maps.resolveMapRendererDesiredCacheId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class BundledThemeVariantCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `style A to B to A retains content and rendered tile cache identity`() {
        val directory = temporaryFolder.newFolder("elevate")
        val first = variant(directory, "A")
        val originalContent = first.readText()
        val originalIdentity = tileCacheIdentity(first)
        retainBundledThemeVariants(first)
        val second = variant(directory, "B")
        retainBundledThemeVariants(second)

        repeat(3) { retainBundledThemeVariants(File(directory, first.name)) }

        assertEquals(originalContent, first.readText())
        assertEquals(originalIdentity, tileCacheIdentity(first))
        assertTrue(second.exists())
    }

    @Test
    fun `persisted recent use protects an older variant when a fifth variant arrives`() {
        val directory = temporaryFolder.newFolder("elevate")
        val variants = listOf("A", "B", "C", "D").map { name -> variant(directory, name) }
        variants.forEach(::retainBundledThemeVariants)
        retainBundledThemeVariants(File(directory, variants.first().name))

        retainBundledThemeVariants(variant(directory, "E"))

        assertEquals(setOf("A", "C", "D", "E"), variantNames(directory))
        assertFalse(directory.resolve(".${variants[1].name}.last_used").exists())
        assertEquals(4, directory.listFiles().orEmpty().count { it.name.endsWith(".last_used") })
    }

    @Test
    fun `a cache hit trims excess variants while keeping the selected old file`() {
        val directory = temporaryFolder.newFolder("elevate")
        val selected = variant(directory, "A")
        val identity = tileCacheIdentity(selected)
        (1..8).forEach { variant(directory, "extra-$it") }

        retainBundledThemeVariants(selected)

        assertEquals(4, variantNames(directory).size)
        assertTrue(selected.exists())
        assertEquals(identity, tileCacheIdentity(selected))
    }

    @Test
    fun `resource files directories and other themes are left intact`() {
        val firstDirectory = temporaryFolder.newFolder("elevate")
        val secondDirectory = temporaryFolder.newFolder("hike-ride-sight")
        val resourceXml = firstDirectory.resolve("resource.xml").apply { writeText("resource") }
        val marker = firstDirectory.resolve(".theme_id").apply { writeText("fixture") }
        val nested = firstDirectory.resolve("dynamic_theme_assets.xml").apply { mkdir() }
        val nestedFile = nested.resolve("dynamic_theme_nested.xml").apply { writeText("nested") }
        val secondTheme = variant(secondDirectory, "A")
        retainBundledThemeVariants(secondTheme)

        (1..8).forEach { retainBundledThemeVariants(variant(firstDirectory, "first-$it")) }

        assertEquals(4, variantNames(firstDirectory).size)
        assertEquals(setOf("A"), variantNames(secondDirectory))
        assertEquals("resource", resourceXml.readText())
        assertEquals("fixture", marker.readText())
        assertEquals("nested", nestedFile.readText())
    }

    @Test
    fun `empty or missing output cannot evict valid variants`() {
        val directory = temporaryFolder.newFolder("elevate")
        (1..4).forEach { retainBundledThemeVariants(variant(directory, "valid-$it")) }
        val empty = directory.resolve("dynamic_theme_empty.xml").apply { createNewFile() }

        retainBundledThemeVariants(empty)
        retainBundledThemeVariants(directory.resolve("dynamic_theme_missing.xml"))

        assertTrue((1..4).all { directory.resolve("dynamic_theme_valid-$it.xml").exists() })
        assertFalse(directory.resolve(".${empty.name}.last_used").exists())
    }

    @Test
    fun `usage record failure does not damage or reject a readable theme`() {
        val directory = temporaryFolder.newFolder("elevate")
        val current = variant(directory, "A")
        val identity = tileCacheIdentity(current)
        directory.resolve(".${current.name}.last_used").mkdir()

        retainBundledThemeVariants(current)

        assertEquals(identity, tileCacheIdentity(current))
        assertEquals("<rendertheme>A</rendertheme>", current.readText())
    }

    private fun variant(
        directory: File,
        name: String,
    ): File =
        directory.resolve("dynamic_theme_$name.xml").apply {
            writeText("<rendertheme>$name</rendertheme>")
            Files.setLastModifiedTime(toPath(), FileTime.fromMillis(1_700_000_000_000L))
        }

    private fun variantNames(directory: File): Set<String> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("dynamic_theme_") && it.name.endsWith(".xml") }
            .map { it.name.removePrefix("dynamic_theme_").removeSuffix(".xml") }
            .toSet()

    private fun tileCacheIdentity(file: File): String {
        val signature = computeMapRendererThemeSignature(file, null, "elevate", false)
        return resolveMapRendererDesiredCacheId("map:A", signature, true, 1f)
    }
}
