package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OamBundleDeletionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val france = bundle("france")
    private val italy = bundle("italy")

    @Test
    fun confirmedDeletionHidesImmediatelyAndBlocksConflictingWork() =
        runTest {
            val state = state()
            val finish = CompletableDeferred<Unit>()
            launch(start = CoroutineStart.UNDISPATCHED) {
                state.deleteInstalledBundle(
                    france,
                    { finish.await() },
                    { error("No rescan on success") },
                    "Delete failed",
                )
            }

            assertEquals(listOf("italy"), state.value.visibleInstalledBundles.map { it.areaId })
            assertFalse(state.value.canStartOrResumeDownload)
            assertTrue(state.value.isLibraryBusy)
            assertNull(state.value.statusMessage)
            assertNull(state.value.errorMessage)
            finish.complete(Unit)
        }

    @Test
    fun repeatedConfirmationDoesNotDeleteTwice() =
        runTest {
            val state = state()
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val delete: suspend (OamInstalledBundle) -> Unit = {
                calls++
                finish.await()
            }
            repeat(2) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.deleteInstalledBundle(france, delete, { error("No rescan") }, "Delete failed")
                }
            }
            assertEquals(1, calls)
            finish.complete(Unit)
        }

    @Test
    fun activeDownloadOrUpdateCheckPreventsDeletion() =
        runTest {
            val busyStates =
                listOf(
                    state().value.copy(isDownloading = true),
                    state().value.copy(isCheckingUpdates = true),
                )
            for (initial in busyStates) {
                val state = MutableStateFlow(initial)
                var deleted = false
                state.deleteInstalledBundle(france, { deleted = true }, { error("No rescan") }, "Delete failed")
                assertFalse(deleted)
                assertEquals(listOf("france", "italy"), state.value.visibleInstalledBundles.map { it.areaId })
            }
        }

    @Test
    fun successRemovesOnlyDeletedBundleWithoutSuccessMessage() =
        runTest {
            val state = state()
            val before = state.value.lastLibraryChangedAtMillis
            state.deleteInstalledBundle(france, {}, { error("No rescan on success") }, "Delete failed")

            assertEquals(listOf("italy"), state.value.installedBundles.map { it.areaId })
            assertEquals(setOf("italy"), state.value.bundleHealthByAreaId.keys)
            assertEquals(emptySet<String>(), state.value.selectedRefreshBundleIds)
            assertNull(state.value.deletingBundleId)
            assertNull(state.value.statusMessage)
            assertNull(state.value.errorMessage)
            assertTrue(state.value.lastLibraryChangedAtMillis > before)
            assertTrue(state.value.canStartOrResumeDownload)
        }

    @Test
    fun failureRestoresBundleWithActualRemainingFileHealth() =
        runTest {
            val state = state()
            val remainingHealth =
                mapOf(
                    "france" to OamBundleLocalHealth(listOf("france.map")),
                    "italy" to OamBundleLocalHealth(emptyList()),
                )
            state.deleteInstalledBundle(
                france,
                { throw IOException("disk failure") },
                { listOf(france, italy) to remainingHealth },
                "Delete failed",
            )

            assertEquals(listOf("france", "italy"), state.value.visibleInstalledBundles.map { it.areaId })
            assertTrue(
                state.value.bundleHealthByAreaId
                    .getValue("france")
                    .needsRepair,
            )
            assertEquals("Delete failed", state.value.errorMessage)
            assertNull(state.value.statusMessage)
            assertFalse(state.value.isLibraryBusy)
            assertTrue(state.value.lastLibraryChangedAtMillis > 0L)
        }

    @Test
    fun failureStillRestoresRowWhenRescanAlsoFails() =
        runTest {
            val state = state()
            state.deleteInstalledBundle(france, { throw IOException() }, { throw IOException() }, "Delete failed")
            assertEquals(listOf("france", "italy"), state.value.visibleInstalledBundles.map { it.areaId })
            assertEquals("Delete failed", state.value.errorMessage)
            assertFalse(state.value.isLibraryBusy)
        }

    @Test
    fun cancellationPropagatesWithoutReportingDeleteFailure() =
        runTest {
            val state = state()
            var propagated = false
            try {
                state.deleteInstalledBundle(
                    france,
                    { throw CancellationException("owner cleared") },
                    { error("No rescan") },
                    "Delete failed",
                )
            } catch (_: CancellationException) {
                propagated = true
            }
            assertTrue(propagated)
            assertNull(state.value.errorMessage)
            assertFalse(state.value.isLibraryBusy)
        }

    @Test
    fun refreshCannotBringPendingDeletionBackIntoVisibleList() =
        runTest {
            val state = state()
            val finish = CompletableDeferred<Unit>()
            launch(start = CoroutineStart.UNDISPATCHED) {
                state.deleteInstalledBundle(france, { finish.await() }, { error("No rescan") }, "Delete failed")
            }
            state.refreshInstalledBundleState { listOf(france, italy) to emptyMap() }
            assertEquals(listOf("italy"), state.value.visibleInstalledBundles.map { it.areaId })
            finish.complete(Unit)
        }

    @Test
    fun refreshStartedBeforeDeletionCannotResurrectFinishedDeletion() =
        runTest {
            val state = state()
            val releaseSnapshot = CompletableDeferred<Unit>()
            launch {
                state.refreshInstalledBundleState {
                    releaseSnapshot.await()
                    listOf(france, italy) to emptyMap()
                }
            }
            runCurrent()
            state.deleteInstalledBundle(france, {}, { error("No rescan") }, "Delete failed")
            releaseSnapshot.complete(Unit)
            runCurrent()
            assertEquals(listOf("italy"), state.value.visibleInstalledBundles.map { it.areaId })
        }

    @Test
    fun remainingFileMakesCleanupFailInsteadOfRemovingBundleMetadata() {
        val leftover = temporary.newFile("undeleted.map")
        var failed = false
        try {
            requireBundleFilesDeleted(listOf(leftover))
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(leftover.delete())
        requireBundleFilesDeleted(listOf(leftover))
    }

    private fun state() =
        MutableStateFlow(
            DownloadUiState(
                areas = listOf(OamDownloadCatalog.areas.first().copy(id = "france")),
                installedBundles = listOf(france, italy),
                bundleHealthByAreaId =
                    mapOf(
                        "france" to OamBundleLocalHealth(emptyList()),
                        "italy" to OamBundleLocalHealth(emptyList()),
                    ),
                selectedAreaIds = setOf("france"),
                selectedRefreshBundleIds = setOf("france"),
                statusMessage = "Earlier operation completed",
            ),
        )

    private fun bundle(id: String) =
        OamInstalledBundle(
            areaId = id,
            areaLabel = id,
            bundleChoice = OamBundleChoice.MAP_ONLY,
            mapFileName = "$id.map",
            poiFileName = null,
            installedAtMillis = 1L,
        )
}
