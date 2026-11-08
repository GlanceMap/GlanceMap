package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldWindSessionCleanupTest {
    @Test
    fun `cancelled session closes resources and reports its end`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val job =
                launch {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        finishWorldWindResources(
                            close = { events.add("closed") },
                            onFinished = { events.add("reported") },
                        )
                    }
                }
            started.await()
            job.cancelAndJoin()
            assertEquals(listOf("closed", "reported"), events)
        }
}
