package com.glancemap.glancemapcompanionapp.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RouteLibraryWatchImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aResentWatchFileIsStoredOnceAndKeepsItsWatchLabel() =
        runBlocking<Unit> {
            val repository = RouteLibraryRepository(context)
            val sha = "test-" + System.nanoTime()
            val file = File(context.cacheDir, "Lakes loop.gpx").apply { writeText(PLANNED_ROUTE) }
            try {
                val first = repository.importWatchRoute(file, sha)
                val second = repository.importWatchRoute(file, sha.uppercase())
                assertTrue(first is WatchRouteImport.Added)
                assertTrue(second is WatchRouteImport.AlreadyInLibrary)
                assertEquals(first.route.id, second.route.id)
                assertEquals("Lakes loop", first.route.displayName)
                assertFalse(first.route.isRecording)
                assertTrue(first.route.isFromWatch)
                assertEquals(1, repository.load().routes.count { it.sourceExportId == watchRouteExportId(sha) })
                repository.deleteRoute(first.route.id)
            } finally {
                file.delete()
            }
        }

    private companion object {
        const val PLANNED_ROUTE =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>Lakes loop</name><trkseg>
    <trkpt lat="45.9000" lon="6.8700"><ele>1200</ele></trkpt>
    <trkpt lat="45.9050" lon="6.8750"><ele>1250</ele></trkpt>
    <trkpt lat="45.9100" lon="6.8800"><ele>1300</ele></trkpt>
  </trkseg></trk>
</gpx>"""
    }
}
