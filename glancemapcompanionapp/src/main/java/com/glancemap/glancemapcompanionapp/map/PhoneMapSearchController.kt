package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

internal data class PhoneMapSearchUiState(
    val query: String = "",
    val online: Boolean = true,
    val loading: Boolean = false,
    val searched: Boolean = false,
    val reply: PhoneMapSearchReply = PhoneMapSearchReply(),
)

/** Editing, closing or changing source invalidates pending work before it can publish stale results. */
internal class PhoneMapSearchController(
    private val scope: CoroutineScope,
    private val search: suspend (PhoneMapSearchRequest, (PhoneMapSearchReply) -> Unit) -> PhoneMapSearchReply,
) {
    private val mutableState = MutableStateFlow(PhoneMapSearchUiState())
    val state = mutableState.asStateFlow()
    private var sourceFiles = emptySet<String>()
    private var generation = 0L
    private var job: Job? = null

    fun setContext(
        online: Boolean,
        poiFiles: Set<String>,
    ) {
        if (state.value.online == online && sourceFiles == poiFiles) return
        cancel()
        sourceFiles = poiFiles.toSet()
        mutableState.value = PhoneMapSearchUiState(query = state.value.query, online = online)
    }

    fun edit(query: String) {
        cancel()
        mutableState.value =
            PhoneMapSearchUiState(query = query.take(PHONE_MAP_SEARCH_QUERY_LIMIT), online = state.value.online)
    }

    fun submit(bias: PhoneMapCoordinate) {
        val current = state.value
        val query = current.query.trim()
        if (current.loading || query.length < 2 || !bias.isValidSearchPoint()) return
        cancel()
        val requestGeneration = generation
        val request = PhoneMapSearchRequest(query, current.online, bias, sourceFiles)
        val started = System.nanoTime()
        PhoneDebugCapture.log("PhoneMapSearch", "event=search_started online=${request.online}")
        mutableState.value = current.copy(loading = true, searched = true, reply = PhoneMapSearchReply())
        job =
            scope.launch {
                val reply =
                    try {
                        search(request) { partial ->
                            if (generation == requestGeneration) mutableState.value = state.value.copy(reply = partial)
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: IOException) {
                        PhoneMapSearchReply(onlineFailed = request.online, localFailed = true)
                    }
                if (generation == requestGeneration) {
                    mutableState.value = state.value.copy(loading = false, reply = reply)
                    // Search events omit query text, addresses and source filenames.
                    PhoneDebugCapture.log(
                        "PhoneMapSearch",
                        "event=search_complete online=${request.online} results=${reply.results.size} " +
                            "online_failed=${reply.onlineFailed} local_failed=${reply.localFailed} " +
                            "duration_ms=${(System.nanoTime() - started) / 1_000_000}",
                    )
                }
            }
    }

    fun cancel() {
        generation += 1L
        job?.cancel()
        job = null
        mutableState.value = state.value.copy(loading = false)
    }
}
