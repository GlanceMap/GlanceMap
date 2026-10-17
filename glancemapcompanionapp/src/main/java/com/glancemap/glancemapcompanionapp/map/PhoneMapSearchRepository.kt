package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class PhoneMapSearchRepository(
    context: Context,
) {
    private val storage = PhoneOfflineStorage(context.applicationContext)
    private val localIndex = PhoneMapPoiNameIndex(File(context.cacheDir, "map-poi-search"))
    private val onlinePlaces = PhoneOnlinePlaceSearch()

    suspend fun search(
        request: PhoneMapSearchRequest,
        onPartial: (PhoneMapSearchReply) -> Unit,
    ): PhoneMapSearchReply =
        coroutineScope {
            // Publish in the caller context so local results do not wait for the network.
            val local = async { searchLocal(request).also(onPartial) }
            val online =
                if (request.online) {
                    async { searchOnline(request).also(onPartial) }
                } else {
                    null
                }
            val localReply = local.await()
            val onlineReply = online?.await() ?: PhoneMapSearchReply()
            PhoneMapSearchReply(
                results = mergePhoneMapSearchResults(localReply.results, onlineReply.results),
                onlineFailed = onlineReply.onlineFailed,
                localFailed = localReply.localFailed,
            )
        }

    private suspend fun searchLocal(request: PhoneMapSearchRequest): PhoneMapSearchReply =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<PhoneMapSearchResult>()
            var failed = false
            for (name in request.poiFiles.sorted()) {
                ensureActive()
                if (File(name).name != name || !name.endsWith(".poi", ignoreCase = true)) continue
                try {
                    results += localIndex.search(File(storage.poiDirectory(), name), request.query)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: SQLiteException) {
                    failed = true
                } catch (_: IOException) {
                    failed = true
                } catch (_: SecurityException) {
                    failed = true
                }
            }
            PhoneMapSearchReply(
                results =
                    results.sortedBy { phoneMapDistanceMeters(request.bias, it.point) }.take(PHONE_MAP_SEARCH_LIMIT),
                localFailed = failed,
            )
        }

    private suspend fun searchOnline(request: PhoneMapSearchRequest): PhoneMapSearchReply =
        try {
            withContext(Dispatchers.IO) { PhoneMapSearchReply(results = onlinePlaces.search(request)) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: IOException) {
            PhoneMapSearchReply(onlineFailed = true)
        }
}

/** Neither source may fill the list and hide every result from the other source. */
internal fun mergePhoneMapSearchResults(
    local: List<PhoneMapSearchResult>,
    online: List<PhoneMapSearchResult>,
): List<PhoneMapSearchResult> =
    (0 until maxOf(local.size, online.size))
        .flatMap { listOfNotNull(local.getOrNull(it), online.getOrNull(it)) }
        .distinctBy { Triple(normalizePhoneMapSearch(it.label), it.point.latitude, it.point.longitude) }
        .take(PHONE_MAP_SEARCH_LIMIT)
