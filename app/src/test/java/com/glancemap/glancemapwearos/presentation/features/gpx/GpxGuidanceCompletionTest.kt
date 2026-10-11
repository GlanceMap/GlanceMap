package com.glancemap.glancemapwearos.presentation.features.gpx

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GpxGuidanceCompletionTest {
    private val dispatcher = StandardTestDispatcher()
    private val owner =
        object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun completionWaitsForResumedScreen() =
        runTest(dispatcher) {
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            var completions = 0
            launchGpxGuidanceCompletion(owner.lifecycle) { completions += 1 }
            runCurrent()
            assertEquals(0, completions)

            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, completions)
        }

    @Test
    fun destroyedScreenCannotApplyPendingCompletion() =
        runTest(dispatcher) {
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            var completions = 0
            val completion = launchGpxGuidanceCompletion(owner.lifecycle) { completions += 1 }
            runCurrent()

            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
            assertEquals(0, completions)
            assertTrue(completion.isCancelled)
        }

    @Test
    fun disposedCompositionCannotNavigateAfterGuidanceFinishes() =
        runTest(dispatcher) {
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            val screenScope = CoroutineScope(SupervisorJob() + dispatcher)
            screenScope.cancel()
            var completions = 0
            val completion = screenScope.launchGpxGuidanceCompletion(owner.lifecycle) { completions += 1 }
            runCurrent()
            assertEquals(0, completions)
            assertTrue(completion.isCancelled)
        }
}
