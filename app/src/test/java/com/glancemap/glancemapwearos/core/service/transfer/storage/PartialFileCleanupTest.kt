package com.glancemap.glancemapwearos.core.service.transfer.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PartialFileCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `manual cleanup finds fragments and retained map and POI archives only`() {
        val maps = temporary.newFolder("maps")
        val downloads = temporary.newFolder("oam_downloads")
        val partial = File(maps, ".Bayern.map.part").apply { writeText("partial") }
        val mapZip = File(downloads, "germany-bayern.map.zip").apply { writeText("zip") }
        val poiZip = File(downloads, "germany-bayern.poi.zip").apply { writeText("zip") }
        val zipPart = File(downloads, ".germany-bayern.map.zip.part").apply { writeText("partial") }
        val installed = File(maps, "Bayern.map").apply { writeText("installed") }
        val importedZip = File(maps, "imported.map.zip").apply { writeText("user file") }
        val unrelated = File(downloads, "theme.zip").apply { writeText("other") }

        val found = findPartialCleanupFiles(listOf(maps, downloads), downloads, true)
        assertEquals(setOf(partial, mapZip, poiZip, zipPart), found.toSet())
        val guard = PartialFileUseGuard()
        found.forEach { assertEquals(true, guard.deleteIfIdle(it)) }
        assertTrue(found.none(File::exists))
        assertTrue(listOf(installed, importedZip, unrelated).all(File::exists))
    }

    @Test
    fun `automatic cleanup retains completed archives and keeps fragment scope`() {
        val downloads = temporary.newFolder("oam_downloads")
        val archive = File(downloads, "area.map.zip").apply { writeText("retained") }
        val fragment = File(downloads, ".area.map.zip.part").apply { writeText("partial") }
        assertEquals(listOf(fragment), findPartialCleanupFiles(listOf(downloads), downloads, false))
        assertTrue(archive.exists())
    }

    @Test
    fun `only direct staging archives qualify and overlapping roots do not duplicate files`() {
        val downloads = temporary.newFolder("oam_downloads")
        val nested = File(downloads, "nested").apply { mkdir() }
        File(nested, "user.map.zip").writeText("preserve")
        val fragment = File(nested, ".map.part").apply { writeText("partial") }
        val archive = File(downloads, "area.poi.zip").apply { writeText("retained") }
        val found = findPartialCleanupFiles(listOf(downloads, nested), downloads, true)
        assertEquals(setOf(fragment, archive), found.toSet())
        assertEquals(2, found.size)
    }

    @Test
    fun `active operation keeps files until its job completes`() {
        val file = temporary.newFile("area.map.zip")
        val job = Job()
        val guard = PartialFileUseGuard()
        try {
            guard.protectUntilComplete(job)
            assertNull(guard.deleteIfIdle(file))
            assertTrue(file.exists())
            job.complete()
            assertEquals(true, guard.deleteIfIdle(file))
            assertFalse(file.exists())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `overlapping operations and duplicate registrations release exactly once`() {
        val first = Job()
        val second = Job()
        val guard = PartialFileUseGuard()
        val file = temporary.newFile("area.map.zip")
        try {
            guard.protectUntilComplete(first)
            guard.protectUntilComplete(first)
            guard.protectUntilComplete(second)
            first.complete()
            assertNull(guard.deleteIfIdle(file))
            second.complete()
            assertEquals(true, guard.deleteIfIdle(file))
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    @Test
    fun `cancellation protects files until real child cleanup finishes`() =
        runTest {
            val operation = Job()
            val scope = CoroutineScope(coroutineContext + operation)
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val file = temporary.newFile("area.map.zip")
            val guard = PartialFileUseGuard()
            scope.launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        allowCleanup.await()
                    }
                }
            }
            try {
                runCurrent()
                guard.protectUntilComplete(operation)
                operation.cancel()
                cleanupStarted.await()
                assertNull(guard.deleteIfIdle(file))
                allowCleanup.complete(Unit)
                operation.join()
                assertEquals(true, guard.deleteIfIdle(file))
            } finally {
                allowCleanup.complete(Unit)
                operation.cancel()
                operation.join()
            }
        }
}
