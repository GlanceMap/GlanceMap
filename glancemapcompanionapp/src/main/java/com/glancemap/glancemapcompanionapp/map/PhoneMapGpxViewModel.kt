package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.routes.CompanionGpxRouteParser
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PhoneMapGpxUiState(
    val items: List<PhoneMapGpxItem> = emptyList(),
    val isLoading: Boolean = false,
)

internal enum class PhoneMapGpxLoadDiagnosticEvent(
    val value: String,
) {
    COMPLETED("gpx_load_completed"),
    STALE_RESULT_REJECTED("gpx_load_stale_result_rejected"),
    CANCELLED("gpx_load_cancelled"),
    FAILED("gpx_load_failed"),
}

internal data class PhoneMapGpxLoadCounts(
    val routeTracks: Int = 0,
    val folderTracks: Int = 0,
    val skippedSources: Int = 0,
    val points: Int = 0,
)

private data class PhoneMapGpxLoadResult(
    val items: List<PhoneMapGpxItem>,
    val counts: PhoneMapGpxLoadCounts,
)

internal fun phoneMapGpxLoadDiagnosticLine(
    event: PhoneMapGpxLoadDiagnosticEvent,
    generation: Long,
    durationMillis: Long,
    counts: PhoneMapGpxLoadCounts,
    stale: Boolean? = null,
): String {
    val totalTracks = counts.routeTracks + counts.folderTracks
    val outcome =
        when (event) {
            PhoneMapGpxLoadDiagnosticEvent.FAILED -> "failed"
            PhoneMapGpxLoadDiagnosticEvent.CANCELLED -> "cancelled"
            PhoneMapGpxLoadDiagnosticEvent.STALE_RESULT_REJECTED -> "stale"
            PhoneMapGpxLoadDiagnosticEvent.COMPLETED ->
                when {
                    counts.skippedSources > 0 && totalTracks > 0 -> "partial"
                    counts.skippedSources > 0 -> "unavailable"
                    totalTracks == 0 -> "empty"
                    else -> "loaded"
                }
        }
    return buildString {
        append("event=${event.value} generation=$generation duration_ms=${durationMillis.coerceAtLeast(0L)} ")
        append("outcome=$outcome route_tracks=${counts.routeTracks} folder_tracks=${counts.folderTracks} ")
        append("skipped_sources=${counts.skippedSources} points=${counts.points}")
        stale?.let { append(" stale=$it") }
    }
}

/** Loads Route Library and direct SAF-folder GPXs into one independently visible map list. */
internal class PhoneMapGpxViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = RouteLibraryRepository(application)
    private val folderSource = PhoneGpxFolderSource(application)
    private val _uiState = MutableStateFlow(PhoneMapGpxUiState())
    val uiState: StateFlow<PhoneMapGpxUiState> = _uiState.asStateFlow()
    private var routeSources: List<PhoneMapGpxSource> = emptyList()
    private var folderSources: List<PhoneGpxFolderFile> = emptyList()
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    val loadedSources: Pair<List<PhoneMapGpxSource>, List<PhoneGpxFolderFile>>?
        get() = if (_uiState.value.isLoading) null else routeSources to folderSources

    fun synchronize(
        nextRouteSources: List<PhoneMapGpxSource>,
        nextFolderSources: List<PhoneGpxFolderFile>,
        initiallyEnabledId: String?,
    ) {
        if (nextRouteSources == routeSources && nextFolderSources == folderSources) return
        routeSources = nextRouteSources
        folderSources = nextFolderSources
        val generation = ++loadGeneration
        loadJob?.cancel()
        if (nextRouteSources.isEmpty() && nextFolderSources.isEmpty()) {
            _uiState.value = PhoneMapGpxUiState()
            PhoneDebugCapture.log(
                TAG,
                phoneMapGpxLoadDiagnosticLine(
                    PhoneMapGpxLoadDiagnosticEvent.COMPLETED,
                    generation,
                    0L,
                    PhoneMapGpxLoadCounts(),
                ),
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadSources(generation, nextRouteSources, nextFolderSources, initiallyEnabledId)
    }

    private fun loadSources(
        generation: Long,
        routeSources: List<PhoneMapGpxSource>,
        folderSources: List<PhoneGpxFolderFile>,
        initiallyEnabledId: String?,
    ) {
        val startedAtNanos = System.nanoTime()
        PhoneDebugCapture.log(
            TAG,
            "event=gpx_load_started generation=$generation route_sources=${routeSources.size} " +
                "folder_sources=${folderSources.size}",
        )
        loadJob =
            viewModelScope.launch {
                runLoad(generation, routeSources, folderSources, initiallyEnabledId, startedAtNanos)
            }
    }

    private suspend fun runLoad(
        generation: Long,
        routeSources: List<PhoneMapGpxSource>,
        folderSources: List<PhoneGpxFolderFile>,
        initiallyEnabledId: String?,
        startedAtNanos: Long,
    ) {
        val loaded =
            runCatching { loadGpxItems(routeSources, folderSources) }
                .getOrElse { failure ->
                    val durationMillis = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND
                    if (failure is CancellationException) {
                        PhoneDebugCapture.log(
                            TAG,
                            phoneMapGpxLoadDiagnosticLine(
                                PhoneMapGpxLoadDiagnosticEvent.CANCELLED,
                                generation,
                                durationMillis,
                                PhoneMapGpxLoadCounts(),
                                stale = generation != loadGeneration,
                            ),
                        )
                    } else {
                        recordLoadFailure(generation, startedAtNanos)
                    }
                    throw failure
                }
        val durationMillis = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND
        if (!isCurrentLoad(generation, routeSources, folderSources)) {
            recordLoad(
                generation,
                durationMillis,
                loaded.counts,
                PhoneMapGpxLoadDiagnosticEvent.STALE_RESULT_REJECTED,
            )
            return
        }
        _uiState.value =
            PhoneMapGpxUiState(
                items = mergePhoneMapGpxItems(_uiState.value.items, loaded.items, initiallyEnabledId),
            )
        recordLoad(generation, durationMillis, loaded.counts, PhoneMapGpxLoadDiagnosticEvent.COMPLETED)
    }

    private suspend fun loadGpxItems(
        routeSources: List<PhoneMapGpxSource>,
        folderSources: List<PhoneGpxFolderFile>,
    ): PhoneMapGpxLoadResult =
        withContext(Dispatchers.IO) {
            var routeCount = 0
            var folderCount = 0
            var skippedCount = 0
            val items =
                buildList {
                    routeSources.forEach { source ->
                        val item = routeLibraryGpxItem(source)
                        if (item == null) {
                            skippedCount += 1
                        } else {
                            routeCount += 1
                            add(item)
                        }
                    }
                    folderSources.forEach { source ->
                        val item = folderGpxItem(source)
                        if (item == null) {
                            skippedCount += 1
                        } else {
                            folderCount += 1
                            add(item)
                        }
                    }
                }
            PhoneMapGpxLoadResult(
                items = items,
                counts =
                    PhoneMapGpxLoadCounts(
                        routeTracks = routeCount,
                        folderTracks = folderCount,
                        skippedSources = skippedCount,
                        points = items.sumOf { item -> item.track.points.size },
                    ),
            )
        }

    private fun isCurrentLoad(
        generation: Long,
        routeSources: List<PhoneMapGpxSource>,
        folderSources: List<PhoneGpxFolderFile>,
    ): Boolean = generation == loadGeneration && this.routeSources == routeSources && this.folderSources == folderSources

    private fun recordLoad(
        generation: Long,
        durationMillis: Long,
        counts: PhoneMapGpxLoadCounts,
        event: PhoneMapGpxLoadDiagnosticEvent,
    ) {
        PhoneDebugCapture.log(TAG, phoneMapGpxLoadDiagnosticLine(event, generation, durationMillis, counts))
    }

    private fun recordLoadFailure(
        generation: Long,
        startedAtNanos: Long,
    ) {
        PhoneDebugCapture.log(
            TAG,
            phoneMapGpxLoadDiagnosticLine(
                PhoneMapGpxLoadDiagnosticEvent.FAILED,
                generation,
                (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND,
                PhoneMapGpxLoadCounts(),
            ) + " category=load_error",
        )
    }

    fun toggle(id: String) {
        _uiState.value = _uiState.value.copy(items = _uiState.value.items.toggleEnabled(id))
    }

    private suspend fun routeLibraryGpxItem(source: PhoneMapGpxSource): PhoneMapGpxItem? =
        repository.routeDetails(source.id)?.let { details ->
            PhoneMapGpxItem(
                id = source.id,
                displayName = phoneGpxDisplayNameFromFileName(source.displayName),
                track = PhoneMapGpxTrack(source.id, details.profile.points),
                enabled = false,
            )
        }

    private fun folderGpxItem(source: PhoneGpxFolderFile): PhoneMapGpxItem? {
        val inputStream = folderSource::openInputStream
        return phoneGpxFolderTrackItem(source, inputStream)
    }

    private companion object {
        const val TAG = "PhoneMapGpx"
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** A malformed or unavailable folder document is omitted without interrupting other GPX sources. */
internal fun phoneGpxFolderTrackItem(
    source: PhoneGpxFolderFile,
    openInputStream: (PhoneGpxFolderFile) -> java.io.InputStream?,
): PhoneMapGpxItem? =
    runCatching {
        val input = openInputStream(source) ?: return null
        val parsed = input.use(CompanionGpxRouteParser::parse)
        PhoneMapGpxItem(
            id = source.id,
            displayName = phoneGpxDisplayNameFromFileName(source.displayName),
            track = PhoneMapGpxTrack(source.id, parsed.points),
            enabled = false,
            isEditable = source.isWritable,
        )
    }.getOrNull()
