package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorldWindActivityTest {
    @Test
    fun progressCountsEveryConcurrentRequestUntilItFinishesOrIsCancelled() =
        runBlocking {
            val activity = PhoneWorldWindActivity()
            assertTrue(activity.progress.value.complete)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first =
                async {
                    activity.track {
                        started.complete(Unit)
                        release.await()
                    }
                }
            started.await()
            val secondStarted = CompletableDeferred<Unit>()
            val second =
                async {
                    activity.track {
                        secondStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            secondStarted.await()
            assertEquals(PhoneWorldWindProgress(0, 2), activity.progress.value)
            second.cancelAndJoin()
            assertEquals(0.5f, activity.progress.value.fraction)
            release.complete(Unit)
            first.await()
            assertTrue(activity.progress.value.complete)
            assertEquals(1f, activity.progress.value.fraction)
        }

    @Test
    fun ringStaysFullUntilTheNextRequestStartsANewBatch() =
        runBlocking {
            val activity = PhoneWorldWindActivity()
            val release = CompletableDeferred<Unit>()
            val batch = List(3) { async { activity.track { release.await() } } }
            while (activity.progress.value.started < 3) kotlinx.coroutines.yield()
            release.complete(Unit)
            batch.forEach { it.await() }
            assertEquals(PhoneWorldWindProgress(3, 3), activity.progress.value)
            val started = CompletableDeferred<Unit>()
            val releaseNext = CompletableDeferred<Unit>()
            val next =
                async {
                    activity.track {
                        started.complete(Unit)
                        releaseNext.await()
                    }
                }
            started.await()
            assertEquals(PhoneWorldWindProgress(0, 1), activity.progress.value)
            assertFalse(activity.progress.value.complete)
            releaseNext.complete(Unit)
            next.await()
        }

    @Test
    fun failedRequestStillCountsAsFinished() =
        runBlocking {
            val activity = PhoneWorldWindActivity()
            runCatching { activity.track { error("test failure") } }
            assertTrue(activity.progress.value.complete)
        }
}
