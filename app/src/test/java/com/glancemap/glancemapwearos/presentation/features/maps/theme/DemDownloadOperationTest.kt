package com.glancemap.glancemapwearos.presentation.features.maps.theme

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemDownloadOperationTest {
    @Test
    fun duplicateStartCannotReplaceTheActiveMap() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val maps = mutableListOf<String>()
            val finished = mutableListOf<Boolean>()
            val operation =
                DemDownloadOperation(
                    scope = this,
                    download = { map ->
                        maps += map
                        release.await()
                    },
                    onFinished = { finished += it },
                )

            assertTrue(operation.start("first.map"))
            assertFalse(operation.start("second.map"))
            runCurrent()
            assertEquals(listOf("first.map"), maps)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf(true), finished)
        }

    @Test
    fun serviceDestructionBeforeDispatchStillReleasesItsNetwork() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val finished = mutableListOf<Boolean>()
            val operation = DemDownloadOperation(owner, { error("Download must not start") }, { finished += it })

            operation.start("first.map")
            owner.cancel()
            runCurrent()

            assertEquals(listOf(false), finished)
        }

    @Test
    fun cancelStopsTheTransferAndRunsCleanupOnce() =
        runTest {
            var cleaned = 0
            val finished = mutableListOf<Boolean>()
            val operation =
                DemDownloadOperation(
                    scope = this,
                    download = {
                        try {
                            awaitCancellation()
                        } finally {
                            cleaned++
                        }
                    },
                    onFinished = { finished += it },
                )

            operation.start("first.map")
            runCurrent()
            operation.cancel()
            runCurrent()
            operation.cancel()
            runCurrent()

            assertEquals(1, cleaned)
            assertEquals(listOf(false), finished)
        }

    @Test
    fun completionAllowsTheNextMapToDownload() =
        runTest {
            val maps = mutableListOf<String>()
            val operation = DemDownloadOperation(this, { maps += it }, {})

            assertTrue(operation.start("first.map"))
            runCurrent()
            assertTrue(operation.start("second.map"))
            runCurrent()

            assertEquals(listOf("first.map", "second.map"), maps)
        }
}
