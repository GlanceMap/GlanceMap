package com.glancemap.glancemapwearos.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

@OptIn(ExperimentalCoroutinesApi::class)
class PoiFileMetadataCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `unchanged metadata and absent coverage are reused`() =
        runTest {
            val file = temporaryFolder.newFile("unchanged.poi")
            val cache = PoiFileMetadataCache<String?>()
            var databaseReads = 0
            repeat(2) {
                assertNull(
                    cache.getOrLoad(file) {
                        databaseReads++
                        null
                    },
                )
            }
            assertEquals(1, databaseReads)
        }

    @Test
    fun `point counts keep separate category selections and reuse equivalent sets`() =
        runTest {
            val file = temporaryFolder.newFile("categories.poi")
            val cache = PoiFileMetadataCache<Int>()
            var databaseReads = 0

            suspend fun count(ids: Set<Int>): Int =
                cache.getOrLoad(file, ids) {
                    databaseReads++
                    ids.sum()
                }

            val mutableSelection = mutableSetOf(1, 2)
            assertEquals(3, count(mutableSelection))
            mutableSelection.clear()
            assertEquals(3, count(setOf(2, 1)))
            assertEquals(1, count(setOf(1)))
            assertEquals(3, count(setOf(1, 2)))
            assertEquals(2, databaseReads)
        }

    @Test
    fun `atomic replacement with equal size and timestamp invalidates metadata`() =
        runTest {
            val file = temporaryFolder.newFile("replaced.poi").apply { writeText("old") }
            val cache = PoiFileMetadataCache<String>()
            var databaseReads = 0

            suspend fun read(): String =
                cache.getOrLoad(file) {
                    databaseReads++
                    file.readText()
                }

            assertEquals("old", read())
            val timestamp = Files.getLastModifiedTime(file.toPath())
            val replacement = temporaryFolder.newFile("replacement.part").apply { writeText("new") }
            Files.setLastModifiedTime(replacement.toPath(), timestamp)
            Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)

            assertEquals(timestamp, Files.getLastModifiedTime(file.toPath()))
            assertEquals("new", read())
            assertEquals(2, databaseReads)
        }

    @Test
    fun `in place changes and deletion cannot reuse old metadata`() =
        runTest {
            val file = temporaryFolder.newFile("changed.poi").apply { writeText("first") }
            val cache = PoiFileMetadataCache<String?>()
            var databaseReads = 0

            suspend fun read(): String? =
                cache.getOrLoad(file) {
                    databaseReads++
                    file.takeIf { it.exists() }?.readText()
                }

            assertEquals("first", read())
            val timestamp = Files.getLastModifiedTime(file.toPath()).toMillis()
            file.writeText("other")
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(timestamp + 1_000))
            assertEquals("other", read())
            file.writeText("larger content")
            assertEquals("larger content", read())
            assertTrue(file.delete())
            assertNull(read())
            file.writeText("recreated")
            assertEquals("recreated", read())
            assertEquals(5, databaseReads)
        }

    @Test
    fun `renamed file is loaded under its new path`() =
        runTest {
            val file = temporaryFolder.newFile("original.poi")
            val renamed = temporaryFolder.root.resolve("renamed.poi")
            val cache = PoiFileMetadataCache<String>()
            assertEquals(file.name, cache.getOrLoad(file) { file.name })
            Files.move(file.toPath(), renamed.toPath())
            assertEquals(renamed.name, cache.getOrLoad(renamed) { renamed.name })
        }

    @Test
    fun `invalidation does not wait for a database scan or cache its old result`() =
        runTest {
            val file = temporaryFolder.newFile("in-flight.poi")
            val cache = PoiFileMetadataCache<String>()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val oldRead =
                async {
                    cache.getOrLoad(file) {
                        started.complete(Unit)
                        finish.await()
                        "old"
                    }
                }
            started.await()
            val invalidation = async { cache.invalidate(file.absolutePath) }
            runCurrent()
            assertTrue(invalidation.isCompleted)
            finish.complete(Unit)
            assertEquals("old", oldRead.await())
            assertEquals("new", cache.getOrLoad(file) { "new" })
        }

    @Test
    fun `file changed during a scan is not stored as a warm result`() =
        runTest {
            val file = temporaryFolder.newFile("changing.poi").apply { writeText("old") }
            val cache = PoiFileMetadataCache<String>()
            assertEquals(
                "old",
                cache.getOrLoad(file) {
                    file.writeText("new content")
                    "old"
                },
            )
            assertEquals("new content", cache.getOrLoad(file) { file.readText() })
        }

    @Test
    fun `least recently used entries are bounded without discarding recent reads`() =
        runTest {
            val files = List(3) { temporaryFolder.newFile("file-$it.poi") }
            val cache = PoiFileMetadataCache<Int>(maxEntries = 2)
            val reads = IntArray(3)

            suspend fun read(index: Int): Int =
                cache.getOrLoad(files[index]) {
                    reads[index]++
                    index
                }

            read(0)
            read(1)
            read(0)
            read(2)
            read(0)
            read(1)
            assertEquals(listOf(1, 2, 1), reads.toList())
        }
}
