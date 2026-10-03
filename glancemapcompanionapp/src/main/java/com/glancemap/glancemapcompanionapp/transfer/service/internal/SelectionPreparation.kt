package com.glancemap.glancemapcompanionapp.transfer.service.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SelectionFileMetadata<T>(
    val uri: T,
    val displayName: String,
    val size: Long,
)

internal data class PreparedSelectionMetadata<T>(
    val items: List<SelectionFileMetadata<T>>,
    val skippedCount: Int,
)

internal suspend fun <T> prepareSelectionMetadata(
    uris: List<T>,
    getTransferFileDetails: (T) -> Pair<String, Long>?,
): PreparedSelectionMetadata<T> =
    withContext(Dispatchers.IO) {
        val items =
            buildList {
                for (uri in uris) {
                    val (rawName, size) = getTransferFileDetails(uri) ?: continue
                    val name = rawName.ifBlank { "file.bin" }
                    if (isSupportedTransferFileName(name)) {
                        add(SelectionFileMetadata(uri, name, size))
                    }
                }
            }
        PreparedSelectionMetadata(items, (uris.size - items.size).coerceAtLeast(0))
    }

internal class SelectionRequestState<T> {
    internal data class Pending<T>(
        val requestId: Long,
        val value: T,
    )

    private var currentRequestId = 0L
    private var pending: Pending<T>? = null

    fun beginRequest(): Long {
        currentRequestId += 1L
        pending = null
        return currentRequestId
    }

    fun invalidate() {
        currentRequestId += 1L
        pending = null
    }

    fun isCurrent(requestId: Long): Boolean = requestId == currentRequestId

    fun canPublish(
        requestId: Long,
        isTransferring: Boolean,
    ): Boolean = isCurrent(requestId) && !isTransferring

    fun storePending(
        requestId: Long,
        value: T,
    ): Boolean {
        if (!isCurrent(requestId)) return false
        pending = Pending(requestId, value)
        return true
    }

    fun takePending(): Pending<T>? = pending.also { pending = null }
}
