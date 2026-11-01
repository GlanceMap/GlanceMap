package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorldWindActivityTest {
    @Test
    fun activeUntilEveryConcurrentRequestFinishesOrIsCancelled() =
        runBlocking {
            val activity = PhoneWorldWindActivity()
            assertFalse(activity.isActive.value)
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
            assertTrue(activity.isActive.value)
            second.cancelAndJoin()
            assertTrue(activity.isActive.value)
            release.complete(Unit)
            first.await()
            assertFalse(activity.isActive.value)
        }

    @Test
    fun failedRequestDoesNotLeaveTheIndicatorActive() =
        runBlocking {
            val activity = PhoneWorldWindActivity()
            runCatching { activity.track { error("test failure") } }
            assertFalse(activity.isActive.value)
        }
}
