package com.glancemap.glancemapcompanionapp.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal data class TrailMapSelectionState(
    val requested: Map<String, PublishedTrail> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
) {
    val visibleTrails: List<PublishedTrail>
        get() = requested.values.filter { it.id !in loading && it.canSave }
}

/** Selected geometry stays in memory and is independent of the offline route library. */
internal class TrailMapSelectionController(
    private val scope: CoroutineScope,
    private val load: suspend (PublishedTrail) -> PublishedTrail,
) {
    private val mutableState = MutableStateFlow(TrailMapSelectionState())
    val state = mutableState.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val tokens = mutableMapOf<String, Any>()

    fun setVisible(
        trail: PublishedTrail,
        visible: Boolean,
    ) {
        val id = trail.id
        if (!visible) {
            tokens.remove(id)
            jobs.remove(id)?.cancel()
            mutableState.value =
                mutableState.value.copy(
                    requested = mutableState.value.requested - id,
                    loading = mutableState.value.loading - id,
                    failed = mutableState.value.failed - id,
                )
        } else if (id !in mutableState.value.requested) {
            mutableState.value =
                mutableState.value.copy(
                    requested = mutableState.value.requested + (id to trail),
                    failed = mutableState.value.failed - id,
                )
            if (!trail.canSave) {
                mutableState.value = mutableState.value.copy(loading = mutableState.value.loading + id)
                val token = Any()
                tokens[id] = token
                val job = scope.launch { loadSelected(trail, token) }
                if (tokens[id] === token) jobs[id] = job
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Provider failures are represented without exposing remote messages.
    private suspend fun loadSelected(trail: PublishedTrail, token: Any) {
        val id = trail.id
        try {
            val loaded = load(trail)
            coroutineContext.ensureActive()
            require(loaded.id == id && loaded.canSave)
            if (tokens[id] === token) {
                mutableState.value =
                    mutableState.value.copy(
                        requested = mutableState.value.requested + (id to loaded),
                        loading = mutableState.value.loading - id,
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (tokens[id] === token) {
                mutableState.value =
                    mutableState.value.copy(
                        requested = mutableState.value.requested - id,
                        loading = mutableState.value.loading - id,
                        failed = mutableState.value.failed + id,
                    )
            }
        } finally {
            if (tokens[id] === token) {
                tokens.remove(id)
                jobs.remove(id)
            }
        }
    }
}
