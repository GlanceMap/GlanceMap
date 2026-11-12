package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.IOException

internal class OamMetadataHttpException(
    message: String,
) : IOException(message)

internal data class OamRemoteMetadataCheck(
    val fileName: String,
    val comparison: RemoteMetadataComparison,
    val checked: Boolean = false,
    val previous: OamRemoteFileMetadata? = null,
    val current: OamRemoteFileMetadata? = null,
)

internal suspend fun checkRemoteMetadata(
    requests: List<RemoteFileRequest>,
    previousByUrl: Map<String, OamRemoteFileMetadata>,
    fetch: suspend (RemoteFileRequest) -> OamRemoteFileMetadata,
): List<OamRemoteMetadataCheck> =
    coroutineScope {
        requests.chunked(4).flatMap { batch ->
            batch
                .map { request ->
                    async {
                        val previous = previousByUrl[request.url]
                        if (previous == null || !previous.isComparable()) {
                            return@async OamRemoteMetadataCheck(request.fileName, RemoteMetadataComparison.UNKNOWN)
                        }
                        val current = fetchMetadataWithRetry(request, fetch)
                        if (current == null || !current.isComparable()) {
                            return@async OamRemoteMetadataCheck(request.fileName, RemoteMetadataComparison.UNKNOWN)
                        }
                        OamRemoteMetadataCheck(request.fileName, previous.compareWith(current), true, previous, current)
                    }
                }.awaitAll()
        }
    }

private suspend fun fetchMetadataWithRetry(
    request: RemoteFileRequest,
    fetch: suspend (RemoteFileRequest) -> OamRemoteFileMetadata,
): OamRemoteFileMetadata? =
    try {
        fetch(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: OamMetadataHttpException) {
        null
    } catch (_: IOException) {
        delay(250)
        try {
            fetch(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    } catch (_: Exception) {
        null
    }
