package com.glancemap.glancemapwearos.presentation.features.maps.theme.bundled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundledThemeAssetFingerprintTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val themePath = "theme/hike-ride-sight/HikeRideSight.xml"
    private val resourceRoot = "theme/hike-ride-sight"
    private val xmlEntry = "assets/$themePath"
    private val iconEntry = "assets/$resourceRoot/symbols/peak.svg"
    private val assets = linkedMapOf(xmlEntry to "<rendertheme>A</rendertheme>", iconEntry to "<svg>A</svg>")

    @Test
    fun `reinstall preserves fingerprint despite APK path timestamps compression and entry order`() {
        val original = apk("original.apk", assets, stored = true)
        val reinstalled = apk("reinstalled.apk", assets.entries.reversed().associate { it.toPair() })
        reinstalled.setLastModified(1_700_000_000_000L)

        val first = fingerprint(original)
        assertNotNull(first)
        assertEquals(first, fingerprint(reinstalled))
    }

    @Test
    fun `code and other themes can change without invalidating selected theme`() {
        val original = apk("original.apk", assets + ("classes.dex" to "old code"))
        val updated =
            apk(
                "updated.apk",
                assets +
                    mapOf(
                        "classes.dex" to "new code",
                        "assets/theme/elevate/Elevate.xml" to "other theme",
                        "assets/theme/hike-ride-sight-other/symbols/peak.svg" to "other icon",
                    ),
            )

        assertEquals(fingerprint(original), fingerprint(updated))
    }

    @Test
    fun `same size XML edits invalidate theme`() {
        val original = apk("original.apk", assets)
        val updated = apk("updated.apk", assets + (xmlEntry to "<rendertheme>B</rendertheme>"))

        assertNotEquals(fingerprint(original), fingerprint(updated))
    }

    @Test
    fun `same size nested resource edits invalidate theme`() {
        val original = apk("original.apk", assets)
        val updated = apk("updated.apk", assets + (iconEntry to "<svg>B</svg>"))

        assertNotEquals(fingerprint(original), fingerprint(updated))
    }

    @Test
    fun `added and deleted resources invalidate theme`() {
        val original = apk("original.apk", assets)
        val added = apk("added.apk", assets + ("assets/$resourceRoot/patterns/forest.svg" to "forest"))
        val removed = apk("removed.apk", assets - iconEntry)

        assertNotEquals(fingerprint(original), fingerprint(added))
        assertNotNull(fingerprint(removed))
        assertNotEquals(fingerprint(original), fingerprint(removed))
    }

    @Test
    fun `split APK assets produce the same fingerprint as one APK`() {
        val original = apk("original.apk", assets)
        val base = apk("base.apk", mapOf(xmlEntry to assets.getValue(xmlEntry)))
        val split = apk("split.apk", mapOf(iconEntry to assets.getValue(iconEntry)))

        assertEquals(fingerprint(original), fingerprint(base, split))
        assertEquals(fingerprint(original), fingerprint(split, base))
    }

    @Test
    fun `legacy resource directories and individual files are included`() {
        val path = "Elevate.xml"
        val resources = setOf("ele-res", "symbol.svg")
        val entries =
            mapOf(
                "assets/$path" to "<rendertheme />",
                "assets/ele-res/peak.svg" to "peak",
                "assets/symbol.svg" to "old symbol",
            )
        val original = apk("original.apk", entries)
        val updated = apk("updated.apk", entries + ("assets/symbol.svg" to "new symbol"))
        val first = bundledThemeAssetFingerprintOrNull(listOf(original), path, resources)

        assertNotNull(first)
        assertNotEquals(first, bundledThemeAssetFingerprintOrNull(listOf(updated), path, resources))
    }

    @Test
    fun `missing theme XML or resource roots requests conservative fallback`() {
        assertNull(fingerprint(apk("missing-xml.apk", assets - xmlEntry)))
        val xmlOnly = apk("xml-only.apk", mapOf(xmlEntry to assets.getValue(xmlEntry)))
        assertNull(bundledThemeAssetFingerprintOrNull(listOf(xmlOnly), themePath, setOf("missing-resources")))
    }

    @Test
    fun `ambiguous split APK overrides request conservative fallback`() {
        val base = apk("base.apk", assets)
        val split = apk("split.apk", mapOf(iconEntry to "replacement"))

        assertNull(fingerprint(base, split))
    }

    @Test
    fun `missing or unreadable APK requests conservative fallback`() {
        assertNull(fingerprint(File(temporaryFolder.root, "missing.apk")))
        assertNull(fingerprint(temporaryFolder.newFile("invalid.apk").apply { writeText("invalid") }))
        assertNull(fingerprint())
    }

    private fun fingerprint(vararg apkFiles: File): String? =
        bundledThemeAssetFingerprintOrNull(
            apkFiles.toList(),
            themePath,
            setOf(resourceRoot),
        )

    private fun apk(
        name: String,
        entries: Map<String, String>,
        stored: Boolean = false,
    ): File {
        val file = temporaryFolder.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(assetEntry(path, bytes, stored))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun assetEntry(
        path: String,
        bytes: ByteArray,
        stored: Boolean,
    ): ZipEntry =
        ZipEntry(path).apply {
            time = if (stored) 1_600_000_000_000L else 1_700_000_000_000L
            if (stored) {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
        }
}
