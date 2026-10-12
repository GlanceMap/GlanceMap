package com.glancemap.glancemapcompanionapp.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteCodec
import com.glancemap.glancemapcompanionapp.refuges.PoiSqlitePoint
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteWriteOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class PhoneMapPoiRepositoryTest {
    @Test
    fun corruptSourcesAreReportedAndHealthyResultsRemainVisible() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "phone-poi-errors").toFile()
            try {
                directory.resolve("a-broken.poi").writeText("not a SQLite database")
                val viewport = PhoneMapViewport(45.0, 46.0, 6.0, 7.0, 14.0)
                val repository = PhoneMapPoiRepository(directory)

                val failed = repository.queryViewport(viewport, limit = 180)
                assertTrue(failed.pois.isEmpty())
                assertEquals(1, failed.queriedSources)
                assertEquals(1, failed.failedSources)
                assertEquals(PhoneMapPoiQueryDiagnosticOutcome.FAILED, phoneMapPoiQueryOutcome(failed, true))

                val healthy = directory.resolve("b-healthy.poi")
                PoiSqliteCodec.write(
                    healthy,
                    listOf(
                        PoiSqlitePoint(
                            sourceId = 1L,
                            lat = 45.5,
                            lon = 6.5,
                            categoryName = "Water",
                            tags = mapOf("name" to "Spring", "amenity" to "drinking_water"),
                        ),
                    ),
                    PoiSqliteWriteOptions(comment = "fixture", writer = "test"),
                )
                val partial = repository.queryViewport(viewport, limit = 180)
                assertEquals("Spring", partial.pois.single().label)
                assertEquals(2, partial.queriedSources)
                assertEquals(1, partial.failedSources)
                assertEquals(PhoneMapPoiQueryDiagnosticOutcome.PARTIAL, phoneMapPoiQueryOutcome(partial, true))

                val enabledOnly = repository.queryViewport(viewport, 180, setOf(healthy.name))
                assertEquals(1, enabledOnly.pois.size)
                assertEquals(0, enabledOnly.failedSources)
                assertEquals(PhoneMapPoiQueryDiagnosticOutcome.COMPLETED, phoneMapPoiQueryOutcome(enabledOnly, true))

                val empty =
                    repository.queryViewport(viewport.copy(minLat = 47.0, maxLat = 48.0), 180, setOf(healthy.name))
                assertTrue(empty.pois.isEmpty())
                assertEquals(0, empty.failedSources)
                assertEquals(PhoneMapPoiQueryDiagnosticOutcome.COMPLETED, phoneMapPoiQueryOutcome(empty, true))
            } finally {
                directory.deleteRecursively()
            }
        }
}
