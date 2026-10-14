package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneRecordingCheckpointTest {
    @Test
    fun continuousFixesCheckpointTheLatestStateWithoutPostponingTheDeadline() =
        runTest {
            var pending: Job? = null
            var points = 0
            val saved = mutableListOf<Int>()
            repeat(40) {
                points++
                pending = schedulePhoneRecordingCheckpoint(this, pending, 30_000L) { saved += points }
                runCurrent()
                advanceTimeBy(3_000L)
                runCurrent()
            }
            assertEquals(listOf(10, 20, 30, 40), saved)
        }

    @Test
    fun cancelledCheckpointCannotRewriteADiscardedDraft() =
        runTest {
            var writes = 0
            val pending = schedulePhoneRecordingCheckpoint(this, null, 30_000L) { writes++ }
            runCurrent()
            pending.cancel()
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(0, writes)
        }
}
