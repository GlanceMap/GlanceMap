package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class PhoneOfflineMapRevisionTest {
    @Test
    fun rediscoveringAnUnchangedFileRetainsItsRevisionAndCache() {
        val directory = Files.createTempDirectory("phone-map-revision-").toFile()
        try {
            File(directory, "alps.map").writeText("unchanged")
            val store = PhoneOfflineMapStore(directory)
            val first = store.discover().single()
            val second = store.discover().single()

            assertEquals(first, second)
            assertEquals(first.rendererIdentity, second.rendererIdentity)
            assertEquals(cacheId(first), cacheId(second))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun atomicSameSizeReplacementChangesRevisionEvenWithTheSameModificationTime() {
        val directory = Files.createTempDirectory("phone-map-revision-").toFile()
        try {
            val target = File(directory, "alps.map").apply { writeText("old map") }
            val modified = FileTime.fromMillis(1_700_000_000_000L)
            Files.setLastModifiedTime(target.toPath(), modified)
            val store = PhoneOfflineMapStore(directory) { PhoneOfflineMapValidation() }
            val previous = store.discover().single()
            val previousIdentity = previous.rendererIdentity
            val previousCache = cacheId(previous)

            target.inputStream().use { openReader ->
                val result =
                    runBlocking {
                        store.installBundleMap("alps.map", "new map".byteInputStream(), replaceExisting = true) {
                            Files.setLastModifiedTime(File(directory, ".alps.map.bundle.part").toPath(), modified)
                        }
                    }
                assertTrue(result is PhoneOfflineMapBundleInstallResult.Success)
                val current = store.discover().single()

                assertEquals("old map", openReader.bufferedReader().readText())
                assertEquals("new map", target.readText())
                assertEquals(previous.selectionIdentity, current.selectionIdentity)
                assertEquals(modified, Files.getLastModifiedTime(target.toPath()))
                assertNotEquals(previous, current)
                assertNotEquals(previousIdentity, current.rendererIdentity)
                assertEquals(previousIdentity, previous.rendererIdentity)
                assertNotEquals(previousCache, cacheId(current))
                assertEquals(
                    PhoneMapsforgeBaseLayerChange.MAP_SWAP,
                    phoneMapsforgeBaseLayerChange(identity(previous), identity(current)),
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectedReplacementRetainsTheInstalledRevision() {
        val directory = Files.createTempDirectory("phone-map-revision-").toFile()
        try {
            File(directory, "alps.map").writeText("old map")
            val store =
                PhoneOfflineMapStore(directory) { file ->
                    PhoneOfflineMapValidation(
                        error = PhoneOfflineMapError.INVALID.takeIf { file.readText() == "invalid" },
                    )
                }
            val previous = store.discover().single()

            val result =
                runBlocking {
                    store.installBundleMap("alps.map", "invalid".byteInputStream(), replaceExisting = true) {}
                }

            assertEquals(
                PhoneOfflineMapError.INVALID,
                (result as PhoneOfflineMapBundleInstallResult.Failure).error,
            )
            assertEquals(previous, store.discover().single())
            assertEquals(cacheId(previous), cacheId(store.discover().single()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sameMapAndThemeUseSeparateBaseAndComparisonCaches() {
        val identity = identity(PhoneOfflineMap(File("alps.map")))
        assertNotEquals(
            phoneMapsforgeTileCacheId(identity, PhoneOfflineMapRendererRole.BASE),
            phoneMapsforgeTileCacheId(identity, PhoneOfflineMapRendererRole.COMPARISON),
        )
        assertNotEquals(
            phoneMapsforgeTileCacheId(identity, PhoneOfflineMapRendererRole.COMPARISON),
            phoneMapsforgeTileCacheId(
                identity.copy(
                    themeConfig = PhoneOfflineThemeCatalog.resolve(PhoneOfflineThemeCatalog.MAPSFORGE_THEME_ID, null),
                ),
                PhoneOfflineMapRendererRole.COMPARISON,
            ),
        )
    }

    @Test
    fun customOverlaySelectionUsesAThemeSpecificTileCache() {
        val identity = identity(PhoneOfflineMap(File("alps.map")))
        val customized =
            identity.copy(
                themeConfig = identity.themeConfig.copy(enabledOverlayLayerIds = setOf("elv-h_routes")),
            )

        assertNotEquals(
            phoneMapsforgeTileCacheId(identity),
            phoneMapsforgeTileCacheId(customized),
        )
    }

    private fun identity(map: PhoneOfflineMap) =
        PhoneMapsforgeBaseLayerIdentity(map.rendererIdentity, PhoneOfflineThemeCatalog.defaultConfig)

    private fun cacheId(map: PhoneOfflineMap) = phoneMapsforgeTileCacheId(identity(map))
}
