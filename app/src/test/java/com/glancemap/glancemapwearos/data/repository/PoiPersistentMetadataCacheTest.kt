package com.glancemap.glancemapwearos.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

class PoiPersistentMetadataCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun countCache(
        directory: File,
        capacity: Int = 64,
    ) = PoiFileMetadataCache(diskStore = PoiMetadataDiskStore(directory, PoiPointCountMetadataCodec, capacity))

    @Test
    fun `new cache instances restore exact unique counts and separate selections`() =
        runTest {
            val file = temporaryFolder.newFile("counts.poi")
            val directory = temporaryFolder.newFolder()
            val cache = countCache(directory)
            assertEquals(3, cache.getOrLoad(file, setOf(1, 2)) { 3 })
            assertEquals(2, cache.getOrLoad(file, setOf(1)) { 2 })
            val restored = countCache(directory)
            val sources = mutableListOf<PoiMetadataCacheSource>()
            assertEquals(3, restored.getOrLoad(file, setOf(2, 1), sources::add) { error("Must not scan") })
            assertEquals(2, restored.getOrLoad(file, setOf(1), sources::add) { error("Must not scan") })
            assertEquals(3, restored.getOrLoad(file, setOf(1, 2), sources::add) { error("Must not scan") })
            assertEquals(
                listOf(PoiMetadataCacheSource.DISK, PoiMetadataCacheSource.DISK, PoiMetadataCacheSource.MEMORY),
                sources,
            )
            assertEquals(4, restored.getOrLoad(file, setOf(3)) { 4 })
        }

    @Test
    fun `absent coverage is restored rather than treated as a miss`() =
        runTest {
            val file = temporaryFolder.newFile("empty.poi")
            val directory = temporaryFolder.newFolder()

            fun cache() = PoiFileMetadataCache(diskStore = PoiMetadataDiskStore(directory, PoiCoverageMetadataCodec))
            assertNull(cache().getOrLoad(file) { null })
            assertNull(cache().getOrLoad(file) { error("Must not scan") })
        }

    @Test
    fun `categories and aliases survive repository recreation in the original order`() =
        runTest {
            val file = temporaryFolder.newFile("categories.poi")
            val directory = temporaryFolder.newFolder()
            val metadata =
                PoiCategoryMetadata(
                    listOf(PoiCategory(-1, "Group", null, 0, true), PoiCategory(7, "Café", -1, 1, false)),
                    mapOf(-1 to setOf(7, 8), 7 to setOf(7, 8)),
                )

            fun cache() = PoiFileMetadataCache(diskStore = PoiMetadataDiskStore(directory, PoiCategoryMetadataCodec))
            assertEquals(metadata, cache().getOrLoad(file) { metadata })
            assertEquals(metadata, cache().getOrLoad(file) { error("Must not scan") })
        }

    @Test
    fun `same size timestamp replacement and subsequent edits cannot restore stale counts`() =
        runTest {
            val file = temporaryFolder.newFile("source.poi").apply { writeText("old") }
            val directory = temporaryFolder.newFolder()
            countCache(directory).getOrLoad(file) { 1 }
            val timestamp = Files.getLastModifiedTime(file.toPath())
            val replacement = temporaryFolder.newFile("replacement.part").apply { writeText("new") }
            Files.setLastModifiedTime(replacement.toPath(), timestamp)
            Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            assertEquals(2, countCache(directory).getOrLoad(file) { 2 })
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(timestamp.toMillis() + 1_000))
            assertEquals(3, countCache(directory).getOrLoad(file) { 3 })
            file.writeText("larger")
            assertEquals(4, countCache(directory).getOrLoad(file) { 4 })
            assertTrue(file.delete())
            assertEquals(0, countCache(directory).getOrLoad(file) { 0 })
        }

    @Test
    fun `explicit invalidation removes every persisted selection`() =
        runTest {
            val file = temporaryFolder.newFile("invalidate.poi")
            val directory = temporaryFolder.newFolder()
            val cache = countCache(directory)
            cache.getOrLoad(file, setOf(1)) { 1 }
            cache.getOrLoad(file, setOf(2)) { 2 }
            cache.invalidate(file.absolutePath)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertEquals(10, countCache(directory).getOrLoad(file, setOf(1)) { 10 })
            assertEquals(20, countCache(directory).getOrLoad(file, setOf(2)) { 20 })
        }

    @Test
    fun `inflight invalidation never writes its obsolete result to disk`() =
        runTest {
            val file = temporaryFolder.newFile("inflight.poi")
            val directory = temporaryFolder.newFolder()
            val cache = countCache(directory)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val old =
                async {
                    cache.getOrLoad(file) {
                        started.complete(Unit)
                        finish.await()
                        1
                    }
                }
            started.await()
            cache.invalidate(file.absolutePath)
            finish.complete(Unit)
            assertEquals(1, old.await())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertEquals(2, countCache(directory).getOrLoad(file) { 2 })
        }

    @Test
    fun `source changed during scan is not persisted`() =
        runTest {
            val file = temporaryFolder.newFile("changing.poi")
            val directory = temporaryFolder.newFolder()
            countCache(directory).getOrLoad(file) {
                file.writeText("changed")
                1
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertEquals(2, countCache(directory).getOrLoad(file) { 2 })
        }

    @Test
    fun `disk capacity retains recent restores and bounds completed records`() =
        runTest {
            val files = List(3) { temporaryFolder.newFile("file-$it.poi") }
            val directory = temporaryFolder.newFolder()
            countCache(directory, 2).getOrLoad(files[0]) { 0 }
            countCache(directory, 2).getOrLoad(files[1]) { 1 }
            directory.listFiles().orEmpty().forEach { it.setLastModified(1L) }
            assertEquals(0, countCache(directory, 2).getOrLoad(files[0]) { error("Must not scan") })
            countCache(directory, 2).getOrLoad(files[2]) { 2 }
            assertEquals(2, directory.listFiles().orEmpty().size)
            assertEquals(0, countCache(directory, 2).getOrLoad(files[0]) { error("Must not scan") })
            assertEquals(2, countCache(directory, 2).getOrLoad(files[2]) { error("Must not scan") })
            assertEquals(10, countCache(directory, 2).getOrLoad(files[1]) { 10 })
        }

    @Test
    fun `unwritable derived cache does not prevent loading or memory reuse`() =
        runTest {
            val source = temporaryFolder.newFile("readable.poi")
            val unavailableDirectory = temporaryFolder.newFile("not-a-directory")
            val cache = countCache(unavailableDirectory)
            assertEquals(12, cache.getOrLoad(source) { 12 })
            assertEquals(12, cache.getOrLoad(source) { error("Must reuse memory") })
            assertEquals(13, countCache(unavailableDirectory).getOrLoad(source) { 13 })
            assertTrue(source.exists())
        }
}
