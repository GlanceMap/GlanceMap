package com.glancemap.glancemapwearos.data.repository.maps.theme

import com.glancemap.glancemapwearos.core.maps.theme.externalRenderTheme
import com.glancemap.shared.transfer.MapsforgeThemePackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExternalThemeStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "installed")

    private fun store() = ExternalThemeStore(root)

    @Test
    fun `root theme installs and its relative resources remain beside XML`() {
        val installed =
            store()
                .install(
                    archive(
                        mapOf(
                            "Theme.xml" to xml("symbols/icon.png"),
                            "symbols/icon.png" to "image",
                        ),
                    ),
                    NAME,
                ).single()
        assertEquals("Theme", installed.displayName)
        assertEquals("Theme.xml", installed.xmlPath)
        assertEquals("image", File(installed.xmlFile.parentFile, "symbols/icon.png").readText())
        val theme = externalRenderTheme(installed)
        assertEquals(installed.xmlFile.readText(), theme.renderThemeAsStream.bufferedReader().use { it.readText() })
        assertEquals(
            "image",
            theme.resourceProvider
                .createInputStream(
                    theme.relativePathPrefix,
                    "file:symbols/icon.png",
                ).bufferedReader()
                .use {
                    it.readText()
                },
        )
        assertEquals(installed, store().themes().single())
    }

    @Test
    fun `nested entrypoints and sibling resource roots are preserved`() {
        val installed =
            store()
                .install(
                    archive(
                        mapOf(
                            "package/themes/Theme.xml" to xml("../symbols/icon.png"),
                            "package/symbols/icon.png" to "image",
                        ),
                    ),
                    NAME,
                ).single()
        assertEquals("package/themes/Theme.xml", installed.xmlPath)
        assertEquals("image", File(installed.xmlFile.parentFile, "../symbols/icon.png").readText())
    }

    @Test
    fun `multiple render themes remain individually discoverable in deterministic order`() {
        val installed =
            store().install(
                archive(
                    mapOf(
                        "nested/Zebra.xml" to xml(),
                        "Alpha.xml" to xml(),
                        "metadata.xml" to "<metadata/>",
                    ),
                ),
                NAME,
            )
        assertEquals(listOf("Alpha", "Zebra"), installed.map { it.displayName })
        assertEquals(2, installed.map { it.id }.distinct().size)
        assertEquals(1, installed.map { it.packageId }.distinct().size)
    }

    @Test
    fun `traversal absolute Windows and duplicate directory file paths are rejected`() {
        listOf(
            "../outside",
            "theme/../../outside",
            "/absolute/path",
            "C:/outside",
            "theme\\outside",
            "theme/./file",
        ).forEach { path ->
            rejected(archive(mapOf("Theme.xml" to xml(), path to "bad")))
            assertTrue(store().themes().isEmpty())
        }
        rejected(archive(mapOf("Theme.xml" to xml(), "symbols" to "file", "symbols/icon.png" to "image")))
        assertFalse(File(temporary.root, "outside").exists())
    }

    @Test
    fun `a corrupt replacement keeps the installed revision and resources intact`() {
        val repository = store()
        val original = repository.install(archive(mapOf("Theme.xml" to xml())), NAME).single()
        val broken = temporary.newFile().apply { writeText("PK damaged") }
        rejected(broken, "Theme package is damaged", repository)
        assertEquals(listOf(original), repository.themes())
        assertTrue(original.xmlFile.isFile)
    }

    @Test
    fun `CRC corruption fails before publication`() {
        val zip = archive(mapOf("Theme.xml" to xml()))
        val bytes = zip.readBytes()
        val central =
            bytes.indices.first { index ->
                index + 3 < bytes.size &&
                    bytes[index] == 0x50.toByte() &&
                    bytes[index + 1] == 0x4b.toByte() &&
                    bytes[index + 2] == 1.toByte() &&
                    bytes[index + 3] == 2.toByte()
            }
        bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        zip.writeBytes(bytes)
        rejected(zip, "Theme package is damaged")
        assertTrue(store().themes().isEmpty())
    }

    @Test
    fun `no render XML and unrelated XML are rejected`() {
        rejected(archive(mapOf("readme.txt" to "hello")), "No render theme XML found")
        rejected(archive(mapOf("metadata.xml" to "<metadata/>")), "No render theme XML found")
    }

    @Test
    fun `missing resources unsafe resource schemes and entity declarations are rejected`() {
        rejected(archive(mapOf("Theme.xml" to xml("missing.svg"))), "Theme resources are missing")
        listOf(
            "file:/private/secret.png",
            "../../outside.png",
            "https://example.org/icon.png",
            "jar:/icon.png",
            "script.js",
        ).forEach { source ->
            rejected(archive(mapOf("Theme.xml" to xml(source))), "Not a valid Mapsforge theme package")
        }
        rejected(
            archive(
                mapOf(
                    "Theme.xml" to
                        "<!DOCTYPE rendertheme [<!ENTITY x 'expanded'>]>" +
                        "<rendertheme version='5'>&x;</rendertheme>",
                ),
            ),
            "Theme package is damaged",
        )
    }

    @Test
    fun `native renderer rejection is an installation failure and preserves the old package`() {
        val original = store().install(archive(mapOf("Theme.xml" to xml())), NAME).single()
        val rejecting =
            ExternalThemeStore(
                root,
                validateTheme = { throw IllegalArgumentException("unsupported render instruction") },
            )
        rejected(archive(mapOf("Other.xml" to xml())), "Theme could not be installed", rejecting)
        assertEquals(listOf(original), rejecting.themes())
    }

    @Test
    fun `replacement keeps stable theme IDs and publishes a new resource revision`() {
        val repository = store()
        val original =
            repository
                .install(
                    archive(mapOf("Theme.xml" to xml("icon.png"), "icon.png" to "old")),
                    NAME,
                ).single()
        val replacementZip = archive(mapOf("Theme.xml" to xml("icon.png"), "icon.png" to "new"))
        val replacement = repository.install(replacementZip, NAME).single()
        assertEquals(original.id, replacement.id)
        assertNotEquals(original.revision, replacement.revision)
        assertEquals("new", File(replacement.resourceRoot, "icon.png").readText())
        assertTrue(original.xmlFile.exists()) // A still-rendering layer may finish with the old resources.
        assertEquals(replacement, repository.install(replacementZip, NAME).single())
        assertEquals(listOf(replacement), store().themes())
        assertFalse(original.resourceRoot.exists()) // Orphan revisions are reclaimed at process recreation.
    }

    @Test
    fun `deletion removes every entrypoint in the package`() {
        val repository = store()
        val themes = repository.install(archive(mapOf("One.xml" to xml(), "Two.xml" to xml())), NAME)
        repository.deletePackage(themes.first().packageId)
        assertTrue(repository.themes().isEmpty())
        assertTrue(themes.none { it.xmlFile.exists() })
    }

    @Test
    fun `cancellation leaves the previous package published and clears staging`() {
        val repository = store()
        val original = repository.install(archive(mapOf("Theme.xml" to xml())), NAME).single()
        try {
            repository.install(archive(mapOf("New.xml" to xml())), NAME) { throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(listOf(original), repository.themes())
            assertTrue(File(root, ".staging").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `compressed archive individual entry and entry count limits fail cleanly`() {
        val tooBig = temporary.newFile()
        RandomAccessFile(tooBig, "rw").use { it.setLength(MapsforgeThemePackage.MAX_ARCHIVE_BYTES + 1L) }
        rejected(tooBig, "Theme package is too large")
        val entryBomb = temporary.newFile()
        ZipOutputStream(entryBomb.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("huge.png"))
            val buffer = ByteArray(8192)
            repeat((MapsforgeThemePackage.MAX_ENTRY_BYTES / buffer.size).toInt() + 1) { zip.write(buffer) }
            zip.closeEntry()
        }
        rejected(entryBomb, "Theme package is too large")
        val manyEntries = temporary.newFile()
        ZipOutputStream(manyEntries.outputStream()).use { zip ->
            repeat(MapsforgeThemePackage.MAX_ENTRIES + 1) { index ->
                zip.putNextEntry(ZipEntry("$index/"))
                zip.closeEntry()
            }
        }
        rejected(manyEntries, "Theme package is too large")
    }

    @Test
    fun `total expansion and render entrypoint counts are bounded`() {
        val expandedBomb = temporary.newFile()
        ZipOutputStream(expandedBomb.outputStream()).use { zip ->
            val buffer = ByteArray(8192)
            repeat(
                (MapsforgeThemePackage.MAX_EXPANDED_BYTES / MapsforgeThemePackage.MAX_ENTRY_BYTES).toInt() + 1,
            ) { index ->
                zip.putNextEntry(ZipEntry("$index.png"))
                repeat((MapsforgeThemePackage.MAX_ENTRY_BYTES / buffer.size).toInt()) { zip.write(buffer) }
                zip.closeEntry()
            }
        }
        rejected(expandedBomb, "Theme package is too large")
        rejected(
            archive(
                (0..MapsforgeThemePackage.MAX_RENDER_THEMES).associate {
                    "$it.xml" to xml()
                },
            ),
            "Theme package is too large",
        )
        rejected(
            archive(mapOf("Theme.xml" to xml(), "directory/" to "pretends to be a directory")),
            "Theme package is damaged",
        )
    }

    @Test
    fun `transfer finalization installs only typed packages and propagates failure before success`() {
        val repository = store()
        repository.finishTransfer("ordinary.zip")
        assertTrue(repository.themes().isEmpty())
        repository.transferDirectory.mkdirs()
        archive(mapOf("Theme.xml" to xml())).copyTo(File(repository.transferDirectory, NAME))
        repository.finishTransfer(NAME)
        val original = repository.themes().single()
        assertFalse(File(repository.transferDirectory, NAME).exists())
        File(repository.transferDirectory, NAME).writeText("damaged archive")
        try {
            repository.finishTransfer(NAME)
            fail("Invalid installation must not report successful transfer completion")
        } catch (error: ThemePackageException) {
            assertEquals("Theme package is damaged", error.message)
            assertEquals(listOf(original), repository.themes())
            assertFalse(File(repository.transferDirectory, NAME).exists())
        }
    }

    @Test
    fun `process recreation preserves resumable archive partials and removes unpublished final transfers`() {
        val repository = store()
        repository.transferDirectory.mkdirs()
        val partial = File(repository.transferDirectory, ".$NAME.part").apply { writeText("partial") }
        val completed = File(repository.transferDirectory, NAME).apply { writeText("unpublished") }
        store()
        assertTrue(partial.exists())
        assertFalse(completed.exists())
    }

    @Test
    fun `deep XML and incoming archive streams beyond their remaining budget are rejected`() {
        val deepXml =
            "<rendertheme>" + "<rule>".repeat(MapsforgeThemePackage.MAX_XML_DEPTH) +
                "</rule>".repeat(MapsforgeThemePackage.MAX_XML_DEPTH) + "</rendertheme>"
        rejected(archive(mapOf("Theme.xml" to deepXml)), "Theme package is damaged")
        val stream =
            MapsforgeThemePackage.boundedArchiveStream(
                byteArrayOf(1, 2).inputStream(),
                MapsforgeThemePackage.MAX_ARCHIVE_BYTES - 1L,
            )
        try {
            stream.use { it.readBytes() }
            fail("Stream bounds must apply when the transfer size is unknown")
        } catch (error: IllegalStateException) {
            assertEquals("Theme package is too large", error.message)
        }
    }

    @Test
    fun `SVG entity declarations are rejected before the native decoder runs`() {
        var decoded = false
        val repository = ExternalThemeStore(root, validateTheme = { decoded = true })
        val svg =
            "<!DOCTYPE svg [<!ENTITY repeated 'expanded'>]>" +
                "<svg xmlns='http://www.w3.org/2000/svg'><text>&repeated;</text></svg>"
        val malicious = archive(mapOf("Theme.xml" to xml("icon.svg"), "icon.svg" to svg))
        rejected(malicious, "Theme package is damaged", repository)
        assertFalse(decoded)
        assertTrue(repository.themes().isEmpty())
        val valid = "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><path d='M0 0L10 10'/></svg>"
        repository.install(archive(mapOf("Theme.xml" to xml("icon.svg"), "icon.svg" to valid)), NAME)
        assertTrue(decoded)
    }

    private fun archive(entries: Map<String, String>): File =
        temporary.newFile().apply {
            ZipOutputStream(outputStream()).use { zip ->
                entries.forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun rejected(
        zip: File,
        message: String? = null,
        repository: ExternalThemeStore = store(),
    ) {
        try {
            repository.install(zip, NAME)
            fail("Expected archive rejection")
        } catch (error: ThemePackageException) {
            if (message != null) assertEquals(message, error.message)
        }
    }

    private fun xml(resource: String? = null): String =
        "<rendertheme xmlns='http://mapsforge.org/renderTheme' version='5'>" +
            (resource?.let { "<rule e='any' k='*' v='*'><symbol src='file:$it'/></rule>" } ?: "") + "</rendertheme>"

    companion object {
        private const val NAME = "sample.mapsforge-theme.zip"
    }
}
