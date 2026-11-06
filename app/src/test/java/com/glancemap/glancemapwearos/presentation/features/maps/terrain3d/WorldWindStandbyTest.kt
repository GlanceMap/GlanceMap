package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorldWindStandbyTest {
    @Test
    fun shortSleepKeepsSessionAndLongSleepClosesItUntilWake() =
        runTest {
            val interactive = MutableStateFlow(false)
            var starts = 0
            var closes = 0
            val job =
                launch {
                    runWorldWindWithStandby(interactive, 30_000, { testScheduler.currentTime }) {
                        starts++
                        try {
                            awaitCancellation()
                        } finally {
                            closes++
                        }
                    }
                }
            runCurrent()
            assertEquals(0, starts)
            interactive.value = true
            runCurrent()
            assertEquals(1, starts)
            interactive.value = false
            runCurrent()
            advanceTimeBy(29_000)
            interactive.value = true
            runCurrent()
            assertEquals(0, closes)
            assertEquals(1, starts)
            interactive.value = false
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(1, closes)
            assertEquals(1, starts)
            interactive.value = true
            runCurrent()
            assertEquals(2, starts)
            job.cancelAndJoin()
            assertEquals(2, closes)
        }

    @Test
    fun failedSessionRetriesOnlyAfterTheNextWake() =
        runTest {
            val interactive = MutableStateFlow(true)
            var starts = 0
            val job =
                launch {
                    runWorldWindWithStandby(interactive, 30_000, { testScheduler.currentTime }) { starts++ }
                }
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, starts)
            interactive.value = false
            runCurrent()
            interactive.value = true
            runCurrent()
            assertEquals(2, starts)
            job.cancelAndJoin()
        }

    @Test
    fun leavingNavigationClosesTheSessionWithoutWaitingForStandby() =
        runTest {
            val interactive = MutableStateFlow(true)
            var closes = 0
            val job =
                launch {
                    runWorldWindWithStandby(interactive, 30_000, { testScheduler.currentTime }) {
                        try {
                            awaitCancellation()
                        } finally {
                            closes++
                        }
                    }
                }
            runCurrent()
            interactive.value = false
            runCurrent()
            job.cancelAndJoin()
            assertEquals(1, closes)
        }

    @Test
    fun wakeAfterDeepSleepExpiresSessionEvenIfTheDelayDidNotRun() =
        runTest {
            val interactive = MutableStateFlow(true)
            var elapsed = 1_000L
            var starts = 0
            var closes = 0
            val job =
                launch {
                    runWorldWindWithStandby(interactive, 30_000, { elapsed }) {
                        starts++
                        try {
                            awaitCancellation()
                        } finally {
                            closes++
                        }
                    }
                }
            runCurrent()
            interactive.value = false
            runCurrent()
            elapsed += 31_000
            interactive.value = true
            runCurrent()
            assertEquals(1, closes)
            assertEquals(2, starts)
            job.cancelAndJoin()
        }
}
