package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.routes.CompanionGpxRouteParser
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
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

    fun synchronize(
        nextRouteSources: List<PhoneMapGpxSource>,
        nextFolderSources: List<PhoneGpxFolderFile>,
        initiallyEnabledId: String?,
    ) {
        if (nextRouteSources == routeSources && nextFolderSources == folderSources) return
        routeSources = nextRouteSources
        folderSources = nextFolderSources
        loadJob?.cancel()
        if (nextRouteSources.isEmpty() && nextFolderSources.isEmpty()) {
            _uiState.value = PhoneMapGpxUiState()
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadJob =
            viewModelScope.launch {
                val loaded =
                    withContext(Dispatchers.IO) {
                        buildList {
                            nextRouteSources.forEach { source ->
                                routeLibraryGpxItem(source)?.let(::add)
                            }
                            nextFolderSources.mapNotNull(::folderGpxItem).forEach(::add)
                        }
                    }
                if (routeSources != nextRouteSources || folderSources != nextFolderSources) return@launch
                _uiState.value =
                    PhoneMapGpxUiState(
                        items = mergePhoneMapGpxItems(_uiState.value.items, loaded, initiallyEnabledId),
                    )
            }
    }

    fun toggle(id: String) {
        _uiState.value = _uiState.value.copy(items = _uiState.value.items.toggleEnabled(id))
    }

    private suspend fun routeLibraryGpxItem(source: PhoneMapGpxSource): PhoneMapGpxItem? =
        repository.routeDetails(source.id)?.let { details ->
            PhoneMapGpxItem(
                id = source.id,
                displayName = source.displayName,
                track = PhoneMapGpxTrack(source.id, details.profile.points),
                enabled = false,
            )
        }

    private fun folderGpxItem(source: PhoneGpxFolderFile): PhoneMapGpxItem? {
        val inputStream = folderSource::openInputStream
        return phoneGpxFolderTrackItem(source, inputStream)
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
            displayName = parsed.title?.takeIf(String::isNotBlank) ?: source.displayName,
            track = PhoneMapGpxTrack(source.id, parsed.points),
            enabled = false,
        )
    }.getOrNull()
