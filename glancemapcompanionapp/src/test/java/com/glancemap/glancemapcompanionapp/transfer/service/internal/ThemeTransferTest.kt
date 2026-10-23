package com.glancemap.glancemapcompanionapp.transfer.service.internal

import com.glancemap.shared.transfer.MapsforgeThemePackage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemeTransferTest {
    @Test
    fun `validated ZIP retains typed metadata through the existing selection preparation`() =
        runBlocking {
            val typedName = MapsforgeThemePackage.transferName("Specialist.zip")
            assertEquals("Specialist.mapsforge-theme.zip", typedName)
            assertTrue(MapsforgeThemePackage.isTransferName(typedName))
            assertEquals(typedName, MapsforgeThemePackage.transferName(typedName))
            val selected = prepareSelectionMetadata(listOf("uri")) { typedName to 42L }
            assertEquals(listOf(typedName), selected.items.map { it.displayName })
            assertTrue(isReplaceableTransferFileName(typedName))
        }

    @Test
    fun `normal transfer types remain supported while arbitrary ZIPs stay unsupported`() {
        listOf(
            "file.map",
            "file.gpx",
            "file.poi",
            "file.rd5",
            "N50E010.hgt",
            "N50E010.hgt.zip",
            "N50E010.hgt.gz",
        ).forEach { name ->
            assertTrue(name, isSupportedTransferFileName(name))
        }
        listOf("arbitrary.zip", "maps.zip", "theme.xml", "file.js").forEach { name ->
            assertFalse(name, isSupportedTransferFileName(name))
        }
        assertFalse(MapsforgeThemePackage.isThemeZipCandidate("N50E010.hgt.zip"))
    }

    @Test
    fun `phone preflight accepts nested theme XML but rejects unrelated ZIP and traversal`() {
        assertTrue(probe(mapOf("theme/Theme.xml" to XML, "theme/icon.png" to "image")))
        assertFalse(probe(mapOf("metadata.xml" to "<metadata/>")))
        assertFalse(probe(mapOf("readme.txt" to "hello")))
        assertFalse(probe(mapOf("Theme.xml" to XML, "../outside" to "bad")))
        assertFalse(MapsforgeThemePackage.probe(ByteArrayInputStream("broken ZIP".toByteArray())))
    }

    @Test
    fun `phone preflight does not expand XML entities`() {
        assertFalse(
            probe(
                mapOf(
                    "Theme.xml" to "<!DOCTYPE rendertheme [<!ENTITY x 'boom'>]><rendertheme>&x;</rendertheme>",
                ),
            ),
        )
    }

    @Test
    fun `package installation errors keep a useful message and never trigger transport retries`() {
        val result =
            com.glancemap.glancemapcompanionapp.transfer.strategy.TransferResult(
                false,
                "Theme resources are missing",
            )
        assertEquals("Theme resources are missing", toUserFacingTransferError(result))
        assertFalse(shouldConsiderFreshHttpRetry(result))
        assertEquals("invalid_theme_package", fallbackDecisionReason(10L, result))
    }

    private fun probe(entries: Map<String, String>): Boolean {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return MapsforgeThemePackage.probe(ByteArrayInputStream(bytes.toByteArray()))
    }

    companion object {
        private const val XML = "<rendertheme xmlns='http://mapsforge.org/renderTheme' version='5'/>"
    }
}
