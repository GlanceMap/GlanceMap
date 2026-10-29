package com.glancemap.glancemapwearos.presentation.features.navigate

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoActiveMapNoticeTest {
    @Test
    fun noticeDisappearsAfterTwoSecondsEvenIfMapWorkContinues() =
        runTest {
            val changes = mutableListOf<Boolean>()
            val notice = launch { showBriefNoActiveMapNotice(changes::add) }
            runCurrent()
            assertEquals(listOf(true), changes)
            advanceTimeBy(1_999L)
            runCurrent()
            assertEquals(listOf(true), changes)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(true, false), changes)
            notice.join()
        }

    @Test
    fun activatingAMapOrCompletingWorkCancelsTheNoticeImmediately() =
        runTest {
            val changes = mutableListOf<Boolean>()
            val notice = launch { showBriefNoActiveMapNotice(changes::add) }
            runCurrent()
            advanceTimeBy(500L)
            notice.cancelAndJoin()
            assertEquals(listOf(true, false), changes)
            advanceTimeBy(3_000L)
            assertEquals(listOf(true, false), changes)
        }
}
