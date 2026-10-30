package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapwearos.data.repository.maps.theme.ExternalThemeStore
import com.glancemap.glancemapwearos.domain.model.maps.theme.RenderThemeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class PhoneCustomThemeTest {
    @Test
    fun importedThemeCanBeSelectedAndMissingThemesFallBack() {
        val source = RenderThemeSource.External("external:test", "package", "Alps", "theme.xml", File("theme"), "one")
        try {
            PhoneOfflineThemeCatalog.setExternalThemes(listOf(source))
            val selected = PhoneOfflineThemeCatalog.resolve(source.id, null)
            assertEquals(source.id, selected.themeId)
            assertEquals("one", selected.externalRevision)
            assertEquals("Alps", PhoneOfflineThemeCatalog.themeFor(source.id).label)
            PhoneOfflineThemeCatalog.setExternalThemes(emptyList())
            assertEquals(PhoneOfflineThemeCatalog.defaultConfig, PhoneOfflineThemeCatalog.resolve(selected))
        } finally {
            PhoneOfflineThemeCatalog.setExternalThemes(emptyList())
        }
    }

    @Test
    fun replacingThemeChangesTheTileCacheIdentity() {
        val source = RenderThemeSource.External("external:test", "package", "Alps", "theme.xml", File("theme"), "one")
        try {
            PhoneOfflineThemeCatalog.setExternalThemes(listOf(source))
            val first = PhoneOfflineThemeCatalog.resolve(source.id, null)
            PhoneOfflineThemeCatalog.setExternalThemes(listOf(source.copy(revision = "two")))
            val second = PhoneOfflineThemeCatalog.resolve(source.id, null)
            assertNotEquals(first, second)
            assertNotEquals(
                phoneMapsforgeTileCacheId(PhoneMapsforgeBaseLayerIdentity("same-map", first)),
                phoneMapsforgeTileCacheId(PhoneMapsforgeBaseLayerIdentity("same-map", second)),
            )
        } finally {
            PhoneOfflineThemeCatalog.setExternalThemes(emptyList())
        }
    }

    @Test
    fun importedThemeLoadsItsOwnNestedResources() {
        val root = createTempDirectory("phone-custom-theme-").toFile()
        try {
            val archive = File(root, "theme.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                val xml =
                    "<rendertheme xmlns='http://mapsforge.org/renderTheme' version='5'>" +
                        "<rule e='any' k='*' v='*'><symbol src='file:../images/icon.png'/></rule></rendertheme>"
                mapOf("styles/theme.xml" to xml, "images/icon.png" to "image-data").forEach { (path, value) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            val installed = ExternalThemeStore(File(root, "installed")).install(archive, "theme.mapsforge-theme.zip").single()
            val rendered = phoneExternalRenderTheme(installed, XmlRenderThemeMenuCallback { emptySet() })
            assertEquals(
                "image-data",
                rendered.resourceProvider
                    .createInputStream(rendered.relativePathPrefix, "file:../images/icon.png")
                    .bufferedReader()
                    .use { it.readText() },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
