package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.haversineDistanceMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal data class TrailDiscoveryUiState(
    val query: TrailDiscoveryQuery? = null,
    val enabled: Set<TrailProvider> = setOf(TrailProvider.OPEN_STREET_MAP),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val reply: TrailDiscoveryReply = TrailDiscoveryReply(),
)

/** Generation checks also protect against a non-cooperative adapter completing after cancellation. */
internal class TrailDiscoveryController(
    private val scope: CoroutineScope,
    private val discover: suspend (TrailDiscoveryQuery, Set<TrailProvider>) -> TrailDiscoveryReply,
) {
    private val mutableState = MutableStateFlow(TrailDiscoveryUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun submit(
        query: TrailDiscoveryQuery,
        enabled: Set<TrailProvider>,
    ) {
        cancel()
        val request = generation
        mutableState.value = TrailDiscoveryUiState(query, enabled, loading = enabled.isNotEmpty(), searched = true)
        if (enabled.isEmpty()) return
        job =
            scope.launch {
                delay(350)
                val reply = discover(query, enabled)
                coroutineContext.ensureActive()
                if (request == generation) mutableState.value = mutableState.value.copy(loading = false, reply = reply)
            }
    }

    fun cancelIfMoved(center: GeoPoint) {
        val request = mutableState.value.query ?: return
        if (mutableState.value.loading && haversineDistanceMeters(request.center, center) > 500.0) {
            cancel()
            mutableState.value = mutableState.value.copy(query = null, searched = false, reply = TrailDiscoveryReply())
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(loading = false)
    }
}
