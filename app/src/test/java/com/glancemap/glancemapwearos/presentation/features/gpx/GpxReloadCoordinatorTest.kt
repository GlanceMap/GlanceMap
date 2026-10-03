package com.glancemap.glancemapwearos.presentation.features.gpx

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GpxReloadCoordinatorTest {
    @Test
    fun `immediate active files emission produces one initial library pass before restore`() =
        runTest {
            val events = mutableListOf<String>()
            val coordinator =
                GpxReloadCoordinator(
                    scope = backgroundScope,
                    reload = { events += "reload:$it" },
                    onFailure = { throw it },
                )
            var initialReloadId = 0L

            flowOf(setOf("active.gpx")).collect { activePaths ->
                assertEquals(setOf("active.gpx"), activePaths)
                initialReloadId = coordinator.request()
                coordinator.await(initialReloadId)
                events += "linked-poi-sync"
            }
            events += "restore-guidance"

            assertEquals(
                listOf("reload:1", "linked-poi-sync", "restore-guidance"),
                events,
            )
        }

    @Test
    fun `settings burst supersedes in flight pass at a file boundary and coalesces to latest`() =
        runTest {
            val firstFileStarted = CompletableDeferred<Unit>()
            val finishFirstFile = CompletableDeferred<Unit>()
            val processedFiles = mutableListOf<Pair<Long, String>>()
            val startedPasses = mutableListOf<Long>()
            var activePasses = 0
            var maximumActivePasses = 0
            lateinit var coordinator: GpxReloadCoordinator
            coordinator =
                GpxReloadCoordinator(
                    scope = backgroundScope,
                    reload = { generation ->
                        activePasses += 1
                        maximumActivePasses = maxOf(maximumActivePasses, activePasses)
                        startedPasses += generation
                        try {
                            processFilesUntilCurrent(
                                files = listOf("first.gpx", "second.gpx"),
                                isCurrent = { coordinator.isCurrent(generation) },
                                processFile = { file ->
                                    processedFiles += generation to file
                                    if (generation == 1L && file == "first.gpx") {
                                        firstFileStarted.complete(Unit)
                                        finishFirstFile.await()
                                    }
                                    file
                                },
                            )
                        } finally {
                            activePasses -= 1
                        }
                    },
                    onFailure = { throw it },
                )

            coordinator.request()
            firstFileStarted.await()
            coordinator.request()
            val latestGeneration = coordinator.request()
            finishFirstFile.complete(Unit)
            coordinator.await(latestGeneration)

            assertEquals(listOf(1L, latestGeneration), startedPasses)
            assertEquals(
                listOf(
                    1L to "first.gpx",
                    latestGeneration to "first.gpx",
                    latestGeneration to "second.gpx",
                ),
                processedFiles,
            )
            assertEquals(1, maximumActivePasses)
        }

    @Test
    fun `empty small and larger than cache libraries preserve every file`() =
        runTest {
            for (size in listOf(0, 3, 30)) {
                val files = List(size) { "track-$it.gpx" }
                val processed =
                    processFilesUntilCurrent(
                        files = files,
                        isCurrent = { true },
                        processFile = { it },
                    )

                assertNotNull(processed)
                assertEquals(files, processed)
            }
        }

    @Test
    fun `changed and deleted files are reflected by the next serialized snapshot`() =
        runTest {
            var library = mapOf("changed.gpx" to 1, "deleted.gpx" to 1)
            val processedSnapshots = mutableListOf<Map<String, Int>>()
            val coordinator =
                GpxReloadCoordinator(
                    scope = backgroundScope,
                    reload = { processedSnapshots += library.toMap() },
                    onFailure = { throw it },
                )

            coordinator.await(coordinator.request())
            library = mapOf("changed.gpx" to 2)
            coordinator.await(coordinator.request())

            assertEquals(
                listOf(
                    mapOf("changed.gpx" to 1, "deleted.gpx" to 1),
                    mapOf("changed.gpx" to 2),
                ),
                processedSnapshots,
            )
        }
}
