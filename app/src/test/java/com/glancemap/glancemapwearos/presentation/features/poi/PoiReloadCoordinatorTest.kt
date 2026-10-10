package com.glancemap.glancemapwearos.presentation.features.poi

import com.glancemap.glancemapwearos.data.repository.PoiFileMetadataCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PoiReloadCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `sync burst during initial load publishes latest files and reuses completed metadata`() =
        runTest {
            val firstFile = temporaryFolder.newFile("original.poi")
            var files = listOf(firstFile)
            val cache = PoiFileMetadataCache<String>()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val published = CompletableDeferred<List<String>>()
            val databaseReads = mutableListOf<String>()
            val passes = mutableListOf<String>()
            var activePasses = 0
            var maximumActivePasses = 0
            lateinit var coordinator: PoiReloadCoordinator
            coordinator =
                PoiReloadCoordinator(
                    scope = backgroundScope,
                    reload = { request ->
                        activePasses++
                        maximumActivePasses = maxOf(maximumActivePasses, activePasses)
                        passes += request.reason
                        try {
                            val snapshot = files.toList()
                            val rows =
                                snapshot.map { file ->
                                    cache.getOrLoad(file) {
                                        databaseReads += file.name
                                        if (request.reason == "init") {
                                            started.complete(Unit)
                                            finish.await()
                                        }
                                        file.name
                                    }
                                }
                            if (coordinator.isCurrent(request)) published.complete(rows)
                        } finally {
                            activePasses--
                        }
                    },
                    onFailure = { throw it },
                )

            coordinator.request(reason = "init", collapseAll = false)
            assertTrue(coordinator.isLoading)
            started.await()
            files += temporaryFolder.newFile("imported.poi")
            coordinator.request(reason = "sync", collapseAll = false)
            coordinator.request(reason = "latest_sync", collapseAll = false)
            finish.complete(Unit)

            assertEquals(listOf("original.poi", "imported.poi"), published.await())
            assertEquals(listOf("init", "latest_sync"), passes)
            assertEquals(listOf("original.poi", "imported.poi"), databaseReads)
            assertEquals(1, maximumActivePasses)
            assertFalse(coordinator.isLoading)
        }

    @Test
    fun `pending deletion supersedes an in flight snapshot`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val published = CompletableDeferred<List<String>>()
            var files = listOf("removed.poi", "kept.poi")
            lateinit var coordinator: PoiReloadCoordinator
            coordinator =
                PoiReloadCoordinator(
                    scope = backgroundScope,
                    reload = { request ->
                        val snapshot = files
                        if (request.reason == "init") {
                            started.complete(Unit)
                            finish.await()
                        }
                        if (coordinator.isCurrent(request)) published.complete(snapshot)
                    },
                    onFailure = { throw it },
                )
            coordinator.request("init", false)
            started.await()
            files = listOf("kept.poi")
            coordinator.request("delete", false)
            finish.complete(Unit)
            assertEquals(listOf("kept.poi"), published.await())
        }

    @Test
    fun `coalescing retains an explicit request to collapse rows`() =
        runTest {
            val completed = CompletableDeferred<PoiReloadRequest>()
            val coordinator = PoiReloadCoordinator(backgroundScope, { completed.complete(it) }, { throw it })
            coordinator.request("external_refresh", true)
            coordinator.request("sync", false)
            val request = completed.await()
            assertEquals("sync", request.reason)
            assertTrue(request.collapseAll)
            assertFalse(coordinator.isLoading)
        }

    @Test
    fun `database failure leaves the owner available for a later retry`() =
        runTest {
            val failed = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val coordinator =
                PoiReloadCoordinator(
                    backgroundScope,
                    reload = { request ->
                        if (request.reason == "broken") throw IOException("fixture read failure")
                        completed.complete(Unit)
                    },
                    onFailure = { failed.complete(Unit) },
                )
            coordinator.request("broken", false)
            failed.await()
            assertFalse(coordinator.isLoading)
            coordinator.request("retry", false)
            completed.await()
            assertFalse(coordinator.isLoading)
        }

    @Test
    fun `owner cancellation prevents publication and is not treated as a database failure`() =
        runTest {
            val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var published = false
            var failures = 0
            val coordinator =
                PoiReloadCoordinator(
                    scope,
                    reload = {
                        started.complete(Unit)
                        finish.await()
                        published = true
                    },
                    onFailure = { failures++ },
                )
            coordinator.request("init", false)
            started.await()
            scope.cancel()
            finish.complete(Unit)
            runCurrent()
            assertFalse(published)
            assertEquals(0, failures)
        }
}
