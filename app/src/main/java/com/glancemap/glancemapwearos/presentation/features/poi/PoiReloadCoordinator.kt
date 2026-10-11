package com.glancemap.glancemapwearos.presentation.features.poi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class PoiReloadRequest(
    val generation: Long,
    val reason: String,
    val collapseAll: Boolean,
)

internal class PoiReloadCoordinator(
    scope: CoroutineScope,
    private val reload: suspend (PoiReloadRequest) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val requests = Channel<PoiReloadRequest>(Channel.CONFLATED)
    private val requestedGeneration = AtomicLong()
    private val completedGeneration = AtomicLong()
    private val pendingCollapseAll = AtomicBoolean()
    private val requestLock = Any()

    val isLoading: Boolean
        get() = completedGeneration.get() < requestedGeneration.get()

    init {
        scope.launch {
            for (request in requests) {
                runCatching {
                    val nextRequest =
                        synchronized(requestLock) {
                            request
                                .takeIf(::isCurrent)
                                ?.copy(collapseAll = pendingCollapseAll.getAndSet(false))
                        }
                    if (nextRequest != null) reload(nextRequest)
                }.onFailure { error ->
                    if (error is CancellationException || error !is Exception) throw error
                    onFailure(error)
                }
                completedGeneration.updateAndGet { completed -> maxOf(completed, request.generation) }
            }
        }
    }

    fun request(
        reason: String,
        collapseAll: Boolean,
    ) {
        synchronized(requestLock) {
            if (collapseAll) pendingCollapseAll.set(true)
            val generation = requestedGeneration.incrementAndGet()
            check(requests.trySend(PoiReloadRequest(generation, reason, collapseAll)).isSuccess) {
                "POI reload owner is closed"
            }
        }
    }

    fun isCurrent(request: PoiReloadRequest): Boolean = requestedGeneration.get() == request.generation
}
