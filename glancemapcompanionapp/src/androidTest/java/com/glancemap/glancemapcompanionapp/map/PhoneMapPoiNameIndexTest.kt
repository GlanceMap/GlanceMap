package com.glancemap.glancemapcompanionapp.map

import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteCodec
import com.glancemap.glancemapcompanionapp.refuges.PoiSqlitePoint
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteWriteOptions
import com.glancemap.glancemapcompanionapp.refuges.visitPoiSqlitePoints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PhoneMapPoiNameIndexTest {
    @Test
    fun namesAndTranslationsAreIndexedWithoutChangingImportedFilesAndReplacementRefreshesResults() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "phone-poi-search-").toFile()
            try {
                val source = File(directory, "source.poi")
                writeFixture(source, "Café Würzburg")
                source.setLastModified(10_000L)
                val originalHash = hash(source)
                val index = PhoneMapPoiNameIndex(File(directory, "derived"))
                assertEquals("Café Würzburg", index.search(source, "cafe wurz").single().label)
                assertEquals("Café Würzburg", index.search(source, "coffee").single().label)
                assertTrue(index.search(source, "unmatched").isEmpty())
                assertTrue(index.search(source, "cafe OR missing").isEmpty())
                assertArrayEquals(originalHash, hash(source))
                assertEquals(10_000L, source.lastModified())

                writeFixture(source, "New Museum")
                source.setLastModified(20_000L)
                assertTrue(index.search(source, "cafe").isEmpty())
                assertEquals("New Museum", index.search(source, "new mus").single().label)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun cancelledSourceVisitStopsReadingAndCorruptSourcesAreFailures() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "phone-poi-cancel-").toFile()
            try {
                val source = File(directory, "source.poi")
                writeFixture(source, "Museum")
                val cancellation = CancellationSignal()
                var visited = 0
                val result =
                    runCatching {
                        visitPoiSqlitePoints(source, cancellation) {
                            visited += 1
                            cancellation.cancel()
                        }
                    }
                assertTrue(result.isFailure)
                assertEquals(1, visited)
                source.writeText("corrupt fixture")
                val index = PhoneMapPoiNameIndex(File(directory, "derived"))
                assertTrue(runCatching { index.search(source, "museum") }.isFailure)
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun writeFixture(
        source: File,
        name: String,
    ) {
        PoiSqliteCodec.write(
            source,
            listOf(
                PoiSqlitePoint(1L, 48.85, 2.35, "Cafe", mapOf("name" to name, "name:de" to "Coffee House")),
                PoiSqlitePoint(2L, 48.86, 2.36, "Water", mapOf("amenity" to "drinking_water")),
            ),
            PoiSqliteWriteOptions(comment = "fixture", writer = "test"),
        )
    }

    private fun hash(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
