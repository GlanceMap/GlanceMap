package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneOfflineMapStartupTest {
    private val installed = PhoneOfflineMap(File("installed.map"))
    private val added = PhoneOfflineMap(File("added.map"))

    @Test
    fun installedMapsArePublishedWhileTheFolderCheckIsStillRunning() =
        runTest {
            val finishFolderCheck = CompletableDeferred<Unit>()
            var visibleMaps = emptyList<PhoneOfflineMap>()
            var loading = true
            var folderCheckStarted = false
            var discoveryCount = 0
            var finished = false
            val folderResult = PhoneOfflineMapFolderSyncResult(importedCount = 1, skippedCount = 1)
            val job =
                launch {
                    loadPhoneOfflineMapsOnStartup(
                        discover = {
                            discoveryCount += 1
                            if (discoveryCount == 1) listOf(installed) else listOf(installed, added)
                        },
                        synchronizeFolder = {
                            folderCheckStarted = true
                            finishFolderCheck.await()
                            folderResult
                        },
                        onDiscovered = { maps ->
                            visibleMaps = maps
                            loading = false
                        },
                        onSynchronized = { result, maps ->
                            assertEquals(folderResult, result)
                            visibleMaps = maps
                        },
                        onFinished = { finished = true },
                    )
                }
            runCurrent()

            assertEquals(listOf(installed), visibleMaps)
            assertFalse(loading)
            assertTrue(folderCheckStarted)
            assertFalse(finished)

            finishFolderCheck.complete(Unit)
            job.join()

            assertEquals(listOf(installed, added), visibleMaps)
            assertEquals(2, discoveryCount)
            assertTrue(finished)
        }

    @Test
    fun failedDiscoveryStillFinishesTheLoadingState() =
        runTest {
            val failure = IOException("storage unavailable")
            var finished = false
            var synchronizationStarted = false
            val result =
                runCatching {
                    loadPhoneOfflineMapsOnStartup(
                        discover = { throw failure },
                        synchronizeFolder = {
                            synchronizationStarted = true
                            PhoneOfflineMapFolderSyncResult()
                        },
                        onDiscovered = { error("No maps were discovered") },
                        onSynchronized = { _, _ -> error("The folder check did not run") },
                        onFinished = { finished = true },
                    )
                }

            assertSame(failure, result.exceptionOrNull())
            assertTrue(finished)
            assertFalse(synchronizationStarted)
        }

    @Test
    fun cancellingTheFolderCheckKeepsPublishedMapsAndClearsLoading() =
        runTest {
            val finishFolderCheck = CompletableDeferred<Unit>()
            var visibleMaps = emptyList<PhoneOfflineMap>()
            var finished = false
            val job =
                launch {
                    loadPhoneOfflineMapsOnStartup(
                        discover = { listOf(installed) },
                        synchronizeFolder = {
                            finishFolderCheck.await()
                            PhoneOfflineMapFolderSyncResult()
                        },
                        onDiscovered = { visibleMaps = it },
                        onSynchronized = { _, _ -> error("The folder check was cancelled") },
                        onFinished = { finished = true },
                    )
                }
            runCurrent()
            job.cancel()
            job.join()

            assertEquals(listOf(installed), visibleMaps)
            assertTrue(finished)
            assertTrue(job.isCancelled)
        }

    @Test
    fun availableOfflineMapsCanRenderBeforeFolderSynchronizationEnds() {
        val restored = installed.copy(contentRevision = "previous revision")

        assertFalse(
            shouldWaitForPhoneOfflineMapStartup(
                startupComplete = false,
                source = PhoneMapSource.Offline(restored),
                comparison = null,
                installedMaps = listOf(installed),
            ),
        )
    }

    @Test
    fun restoredComparisonMapsWaitUntilTheyAreDiscovered() {
        assertTrue(
            shouldWaitForPhoneOfflineMapStartup(
                startupComplete = false,
                source = PhoneMapSource.Offline(installed),
                comparison = PhoneMapComparisonLayer.Offline(added),
                installedMaps = listOf(installed),
            ),
        )
        assertFalse(
            shouldWaitForPhoneOfflineMapStartup(
                startupComplete = false,
                source = PhoneMapSource.Offline(installed),
                comparison = PhoneMapComparisonLayer.Offline(added),
                installedMaps = listOf(installed, added),
            ),
        )
    }

    @Test
    fun onlineMapsAndCompletedDiscoveryNeverWaitForTheFolderCheck() {
        assertFalse(
            shouldWaitForPhoneOfflineMapStartup(
                startupComplete = false,
                source = PhoneMapSource.Online,
                comparison = null,
                installedMaps = emptyList(),
            ),
        )
        assertFalse(
            shouldWaitForPhoneOfflineMapStartup(
                startupComplete = true,
                source = PhoneMapSource.Offline(added),
                comparison = null,
                installedMaps = emptyList(),
            ),
        )
    }
}
