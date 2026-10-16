package com.glancemap.glancemapcompanionapp.discovery

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PublishedTrailLibraryTest {
    @Test
    fun discoveredGeometryUsesExistingLibraryIndexAndRetainsSourceInStoredGpx() =
        runBlocking {
            val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                withContext(Dispatchers.IO) {
                    val repository = RouteLibraryRepository(context)
                    val trail =
                        PublishedTrail(
                            "fixture",
                            "Lake & Ridge",
                            listOf(
                                TrailSource(
                                    TrailProvider.OPEN_STREET_MAP,
                                    "123",
                                    "https://www.openstreetmap.org/relation/123",
                                    "© OpenStreetMap contributors",
                                    "ODbL 1.0",
                                ),
                            ),
                            geometry = listOf(TrailPoint(GeoPoint(46.0, 7.0)), TrailPoint(GeoPoint(46.001, 7.001))),
                            geometryComplete = true,
                        )
                    val saved = repository.saveDiscoveredTrail(trail)
                    val route = saved.routes.single()
                    assertEquals(route.id, repository.load().selectedRouteId)
                    assertEquals("Lake & Ridge", route.displayName)
                    assertNotNull(repository.routeDetails(route.id))
                    val xml = File(PhoneOfflineStorage(context).routesDirectory(), route.storedFileName).readText()
                    assertTrue(xml.contains("© OpenStreetMap contributors"))
                    assertTrue(xml.contains("ODbL"))
                    val store = OfflinePublishedTrailStore(context.filesDir)
                    val downloaded =
                        downloadPublishedTrail(trail.copy(description = "Ridge details"), repository, store)
                    assertEquals(route.id, downloaded.single().libraryRouteId)
                    val restored = OfflinePublishedTrailStore(context.filesDir).load().single()
                    assertEquals("Ridge details", restored.trail.description)
                    assertEquals(trail.sources, restored.trail.sources)
                    val restoredGeometry = RouteLibraryRepository(context).routeDetails(restored.libraryRouteId!!)!!
                    assertEquals(trail.geometry, restoredGeometry.profile.points)
                    downloadPublishedTrail(trail, repository, store)
                    assertEquals(1, repository.load().routes.size)
                    assertEquals(1, store.load().size)
                    val preferences = RouteSourcePreferences(context)
                    val settings = preferences.save(RouteSourceSettings(enabled = setOf(TrailProvider.GEOTREK)))
                    assertEquals(settings, RouteSourcePreferences(context).load())
                }
            } finally {
                context.cleanup()
            }
        }

    @Test
    fun informationOnlyDownloadSurvivesRestartWithoutCreatingAFakeRoute() =
        runBlocking {
            val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                withContext(Dispatchers.IO) {
                    val repository = RouteLibraryRepository(context)
                    val trail =
                        PublishedTrail(
                            "info",
                            "Forest walk",
                            listOf(TrailSource(TrailProvider.GEOTREK, "1", "https://example.com/1", "Park", "CC BY")),
                            description = "A published description without a usable track.",
                        )
                    downloadPublishedTrail(trail, repository, OfflinePublishedTrailStore(context.filesDir))
                    val restored = OfflinePublishedTrailStore(context.filesDir).load().single()
                    assertEquals(trail, restored.trail)
                    assertEquals(null, restored.libraryRouteId)
                    assertTrue(repository.load().routes.isEmpty())
                }
            } finally {
                context.cleanup()
            }
        }

    @Test
    fun unreadableOfflineCatalogueIsNotOverwrittenByANewDownload() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val file = File(context.filesDir, "route-discovery/offline.json")
            file.parentFile!!.mkdirs()
            file.writeText("{damaged but recoverable data}")
            val store = OfflinePublishedTrailStore(context.filesDir)
            assertTrue(runCatching { store.load() }.isFailure)
            val record =
                OfflinePublishedTrail(
                    PublishedTrail(
                        "info",
                        "Walk",
                        listOf(
                            TrailSource(TrailProvider.GEOTREK, "1", "https://example.com/1", "Park", "CC BY"),
                        ),
                    ),
                    null,
                    1,
                )
            assertTrue(runCatching { store.save(record) }.isFailure)
            assertEquals("{damaged but recoverable data}", file.readText())
        } finally {
            context.cleanup()
        }
    }

    private class IsolatedContext(
        base: Context,
    ) : ContextWrapper(base) {
        private val prefix = "discovery-test-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        private val preferenceNames = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = directory

        override fun getSharedPreferences(
            name: String,
            mode: Int,
        ): SharedPreferences {
            val isolated = "$prefix-$name"
            preferenceNames += isolated
            return super.getSharedPreferences(isolated, mode)
        }

        fun cleanup() {
            directory.deleteRecursively()
            preferenceNames.forEach { super.deleteSharedPreferences(it) }
        }
    }
}
