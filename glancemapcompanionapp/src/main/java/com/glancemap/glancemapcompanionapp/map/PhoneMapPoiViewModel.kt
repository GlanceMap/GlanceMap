package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PhoneMapPoiUiState(
    val pois: List<PhoneMapPoi> = emptyList(),
    val sources: List<PhoneMapPoiSource> = emptyList(),
)

internal enum class PhoneMapPoiQueryDiagnosticOutcome(
    val value: String,
) {
    COMPLETED("completed"),
    PARTIAL("partial"),
    STALE_RESULT_REJECTED("stale_result_rejected"),
    CANCELLED("cancelled"),
    FAILED("failed"),
}

internal fun phoneMapPoiQueryOutcome(
    result: PhoneMapPoiQueryResult,
    isCurrent: Boolean,
): PhoneMapPoiQueryDiagnosticOutcome =
    when {
        !isCurrent -> PhoneMapPoiQueryDiagnosticOutcome.STALE_RESULT_REJECTED
        result.failedSources == 0 -> PhoneMapPoiQueryDiagnosticOutcome.COMPLETED
        result.failedSources == result.queriedSources -> PhoneMapPoiQueryDiagnosticOutcome.FAILED
        else -> PhoneMapPoiQueryDiagnosticOutcome.PARTIAL
    }

internal fun phoneMapPoiQueryDiagnosticLine(
    outcome: PhoneMapPoiQueryDiagnosticOutcome,
    requestId: Long,
    resultCount: Int,
    durationMillis: Long,
    sourceCounts: PhoneMapPoiQueryResult = PhoneMapPoiQueryResult(),
): String =
    "event=poi_query_${outcome.value} request=$requestId results=${resultCount.coerceAtLeast(0)} " +
        "duration_ms=${durationMillis.coerceAtLeast(0L)} " +
        "queried_sources=${sourceCounts.queriedSources.coerceAtLeast(0)} " +
        "failed_sources=${sourceCounts.failedSources.coerceAtLeast(0)}"

private fun recordPhoneMapPoiQuery(
    outcome: PhoneMapPoiQueryDiagnosticOutcome,
    requestId: Long,
    result: PhoneMapPoiQueryResult,
    startedAtNanos: Long,
    category: String? = null,
) {
    val line =
        phoneMapPoiQueryDiagnosticLine(
            outcome,
            requestId,
            result.pois.size,
            (System.nanoTime() - startedAtNanos) / 1_000_000L,
            result,
        )
    PhoneDebugCapture.log("PhoneMapPoi", category?.let { "$line category=$it" } ?: line)
}

/** Owns cancellable, debounced phone POI viewport queries outside the MapLibre composable. */
internal class PhoneMapPoiViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = PhoneMapPoiRepository(application)
    private val sourceVisibility = PhoneMapPoiSourceVisibilityPreferences(application)
    private val _uiState = MutableStateFlow(PhoneMapPoiUiState())
    val uiState: StateFlow<PhoneMapPoiUiState> = _uiState.asStateFlow()

    private var currentViewport: PhoneMapViewport? = null
    private var poiVisible = true
    private var requestId = 0L
    private var queryJob: Job? = null

    init {
        refreshSources()
    }

    fun onViewportChanged(viewport: PhoneMapViewport) {
        if (currentViewport == viewport) return
        currentViewport = viewport
        if (poiVisible) query(viewport)
    }

    fun setPoiVisible(visible: Boolean) {
        if (poiVisible == visible) return
        poiVisible = visible
        if (!visible) {
            queryJob?.cancel()
            _uiState.value = _uiState.value.copy(pois = emptyList())
        } else {
            currentViewport?.let(::query)
        }
    }

    fun refresh() {
        refreshSources()
    }

    fun setSourceVisible(
        fileName: String,
        visible: Boolean,
    ) {
        sourceVisibility.setEnabled(fileName, visible)
        _uiState.value =
            _uiState.value.copy(
                pois = emptyList(),
                sources =
                    _uiState.value.sources.map { source ->
                        if (source.fileName == fileName) source.copy(isEnabled = visible) else source
                    },
            )
        if (poiVisible) currentViewport?.let(::query)
    }

    suspend fun renameSource(
        fileName: String,
        newName: String,
    ): String {
        val renamedFileName = repository.renameSource(fileName, newName)
        sourceVisibility.rename(fileName, renamedFileName)
        refreshSourcesNow()
        return renamedFileName
    }

    suspend fun deleteSource(fileName: String) {
        repository.deleteSource(fileName)
        sourceVisibility.remove(fileName)
        refreshSourcesNow()
    }

    private fun refreshSources() {
        viewModelScope.launch {
            refreshSourcesNow()
        }
    }

    private suspend fun refreshSourcesNow() {
        queryJob?.cancel()
        requestId += 1L
        _uiState.value =
            _uiState.value.copy(
                pois = emptyList(),
                sources = repository.sources(sourceVisibility.disabledFileNames()),
            )
        if (poiVisible) currentViewport?.let(::query)
    }

    private fun query(viewport: PhoneMapViewport) {
        queryJob?.cancel()
        val expectedRequestId = ++requestId
        val enabledSourceFileNames = _uiState.value.sources.enabledFileNames()
        if (enabledSourceFileNames.isEmpty()) {
            _uiState.value = _uiState.value.copy(pois = emptyList())
            return
        }
        queryJob =
            viewModelScope.launch {
                executeQuery(viewport, enabledSourceFileNames, expectedRequestId)
            }
    }

    private suspend fun executeQuery(
        viewport: PhoneMapViewport,
        enabledSourceFileNames: Set<String>,
        expectedRequestId: Long,
    ) {
        delay(QUERY_DEBOUNCE_MILLIS)
        val startedAtNanos = System.nanoTime()
        val queryResult =
            runCatching {
                repository.queryViewport(
                    viewport = viewport,
                    limit = MAXIMUM_POI_RESULTS,
                    enabledSourceFileNames = enabledSourceFileNames,
                )
            }
        val result =
            queryResult.getOrElse { failure ->
                val stale = !poiVisible || expectedRequestId != requestId
                val outcome =
                    if (failure is CancellationException && stale) {
                        PhoneMapPoiQueryDiagnosticOutcome.STALE_RESULT_REJECTED
                    } else if (failure is CancellationException) {
                        PhoneMapPoiQueryDiagnosticOutcome.CANCELLED
                    } else {
                        PhoneMapPoiQueryDiagnosticOutcome.FAILED
                    }
                recordPhoneMapPoiQuery(
                    outcome,
                    expectedRequestId,
                    PhoneMapPoiQueryResult(),
                    startedAtNanos,
                    category = if (outcome == PhoneMapPoiQueryDiagnosticOutcome.FAILED) "query_error" else null,
                )
                throw failure
            }
        val isCurrent = poiVisible && expectedRequestId == requestId
        if (isCurrent) _uiState.value = _uiState.value.copy(pois = result.pois)
        recordPhoneMapPoiQuery(
            phoneMapPoiQueryOutcome(result, isCurrent),
            expectedRequestId,
            result,
            startedAtNanos,
            category = "source_read_error".takeIf { result.failedSources > 0 },
        )
    }

    private companion object {
        private const val MAXIMUM_POI_RESULTS = 180
        private const val QUERY_DEBOUNCE_MILLIS = 200L
    }
}
