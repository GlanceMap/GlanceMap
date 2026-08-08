package com.glancemap.glancemapcompanionapp.transfer.datalayer

import kotlinx.coroutines.CancellationException

/**
 * Converts an actual Wear API failure into a recoverable result, while preserving coroutine
 * cancellation. The latter is a lifecycle signal, never an API failure.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runCancellableWearableOperation(
    operation: suspend () -> T,
    onFailure: (Throwable) -> Unit,
): T? =
    try {
        operation()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        onFailure(error)
        null
    }
