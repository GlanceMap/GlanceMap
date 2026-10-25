package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

internal class OamExtractionJobUnavailableException(
    cause: Throwable? = null,
) : IOException(cause)

/** The foreground owner's persisted plan remains the only restart authority. */
internal class OamExtractionJobCoordinator {
    private class Request(
        val work: suspend () -> String,
    ) {
        val token: String = UUID.randomUUID().toString()
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        var worker: Job? = null
        var closed = false
    }

    private var active: Request? = null

    suspend fun execute(
        schedule: (String) -> Boolean,
        cancel: () -> Unit,
        work: suspend () -> String,
    ): String {
        val request =
            synchronized(this) {
                if (active != null) throw OamExtractionJobUnavailableException()
                Request(work).also { active = it }
            }
        try {
            if (!schedule(request.token) ||
                withTimeoutOrNull(START_TIMEOUT_MS) { request.started.await() } == null
            ) {
                throw OamExtractionJobUnavailableException()
            }
            return request.result.await()
        } finally {
            withContext(NonCancellable) {
                val worker =
                    synchronized(this@OamExtractionJobCoordinator) {
                        request.closed = true
                        request.worker
                    }
                try {
                    // Join before releasing the slot or letting the owner touch archives/partials.
                    worker?.cancelAndJoin()
                    cancel()
                } finally {
                    synchronized(this@OamExtractionJobCoordinator) {
                        if (active === request) active = null
                    }
                }
            }
        }
    }

    @Synchronized
    fun start(
        token: String,
        scope: CoroutineScope,
        finished: () -> Unit,
    ): Boolean {
        val request =
            active?.takeIf { it.token == token && !it.closed && it.worker == null }
                ?: return false
        val worker =
            scope.launch(start = CoroutineStart.LAZY) {
                runCatching { request.work() }
                    .onSuccess { request.result.complete(it) }
                    .onFailure { request.fail(it) }
            }
        request.worker = worker
        worker.invokeOnCompletion { error ->
            if (error != null) request.fail(error)
            finished()
        }
        request.started.complete(Unit)
        worker.start()
        return true
    }

    @Synchronized
    fun stop(token: String) {
        val request = active?.takeIf { it.token == token && !it.closed } ?: return
        request.closed = true
        val error = OamExtractionJobUnavailableException()
        request.started.completeExceptionally(error)
        request.result.completeExceptionally(error)
        request.worker?.cancel()
    }

    private fun Request.fail(error: Throwable) {
        result.completeExceptionally(
            if (error is CancellationException) OamExtractionJobUnavailableException(error) else error,
        )
    }

    private companion object {
        const val START_TIMEOUT_MS = 60_000L
    }
}
