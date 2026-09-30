package com.glancemap.glancemapwearos.presentation.features.gpx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal class GpxReloadCoordinator(
    scope: CoroutineScope,
    private val reload: suspend (Long) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val requests = Channel<Long>(Channel.CONFLATED)
    private val requestedGeneration = AtomicLong(0L)
    private val completedGeneration = MutableStateFlow(0L)

    init {
        scope.launch { runReloadRequests() }
    }

    fun request(): Long {
        val generation = requestedGeneration.incrementAndGet()
        check(requests.trySend(generation).isSuccess) { "GPX reload owner is closed" }
        return generation
    }

    fun isCurrent(generation: Long): Boolean = requestedGeneration.get() == generation

    suspend fun await(generation: Long) {
        completedGeneration.first { completed ->
            completed >= generation && completed >= requestedGeneration.get()
        }
    }

    // Platform file providers and GPX parsers can fail with different exception types. Keep the
    // owner alive after logging a failed pass so later reload requests can still run.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runReloadRequests() {
        for (generation in requests) {
            try {
                if (isCurrent(generation)) reload(generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
            } finally {
                completedGeneration.value = maxOf(completedGeneration.value, generation)
            }
        }
    }
}

internal suspend fun <T, R> processFilesUntilCurrent(
    files: List<T>,
    isCurrent: () -> Boolean,
    processFile: suspend (T) -> R,
): List<R>? {
    val coroutineContext = currentCoroutineContext()
    val processed = ArrayList<R>(files.size)
    for (file in files) {
        coroutineContext.ensureActive()
        if (!isCurrent()) return null
        processed += processFile(file)
    }
    coroutineContext.ensureActive()
    return processed.takeIf { isCurrent() }
}
