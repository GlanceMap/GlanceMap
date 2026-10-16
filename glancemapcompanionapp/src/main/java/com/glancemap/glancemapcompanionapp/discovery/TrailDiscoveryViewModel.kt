package com.glancemap.glancemapcompanionapp.discovery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.BuildConfig
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DiscoveredTrailSaveState(
    val saving: Boolean = false,
    val savedTrailIds: Set<String> = emptySet(),
    val message: String? = null,
    val revision: Long = 0,
)

internal data class OfflineTrailsUiState(
    val records: List<OfflinePublishedTrail> = emptyList(),
    val loading: Boolean = false,
    val selectedTrail: PublishedTrail? = null,
    val message: String? = null,
)

internal class TrailDiscoveryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val preferences = RouteSourcePreferences(application)
    private val mutableSettings = MutableStateFlow(preferences.load())
    val settings = mutableSettings.asStateFlow()
    private val http = TrailDiscoveryHttp()
    private var repository = repository(settings.value)
    val controller = TrailDiscoveryController(viewModelScope) { query, enabled -> repository.discover(query, enabled) }
    private val library = RouteLibraryRepository(application)
    private val offlineStore = OfflinePublishedTrailStore(application.filesDir)
    private val mutableSave = MutableStateFlow(DiscoveredTrailSaveState())
    val saveState = mutableSave.asStateFlow()
    private val mutableOffline = MutableStateFlow(OfflineTrailsUiState())
    val offlineState = mutableOffline.asStateFlow()
    private var detailJob: Job? = null

    init {
        refreshOffline()
    }

    fun saveSettings(next: RouteSourceSettings) {
        val previous = settings.value
        val saved = preferences.save(next)
        mutableSettings.value = saved
        if (saved.overpassUrl != previous.overpassUrl || saved.geotrekRegistry != previous.geotrekRegistry) {
            repository = repository(saved)
        }
        controller.state.value.query
            ?.let { controller.submit(it, saved.enabled) }
    }

    @Suppress("TooGenericExceptionCaught") // Preserve cached records on filesystem failures and allow retry.
    fun refreshOffline() {
        viewModelScope.launch {
            mutableOffline.value = mutableOffline.value.copy(loading = true, message = null)
            try {
                publishOffline(withContext(Dispatchers.IO) { offlineStore.load() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableOffline.value =
                    mutableOffline.value.copy(
                        loading = false,
                        message = getApplication<Application>().getString(R.string.discovery_offline_failed),
                    )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Offline reads must never fall back to provider networking.
    fun openOffline(record: OfflinePublishedTrail) {
        detailJob?.cancel()
        mutableOffline.value = mutableOffline.value.copy(loading = true, selectedTrail = null, message = null)
        detailJob =
            viewModelScope.launch {
                try {
                    val trail =
                        withContext(Dispatchers.IO) {
                            val points =
                                record.libraryRouteId?.let { library.routeDetails(it)?.profile?.points }.orEmpty()
                            record.trail.copy(geometry = points, geometryComplete = points.size >= 2)
                        }
                    mutableOffline.value =
                        mutableOffline.value.copy(
                            loading = false,
                            selectedTrail = trail,
                            message =
                                if (record.libraryRouteId != null && trail.geometry.isEmpty()) {
                                    getApplication<Application>().getString(R.string.discovery_offline_geometry_missing)
                                } else {
                                    null
                                },
                        )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableOffline.value =
                        mutableOffline.value.copy(
                            loading = false,
                            selectedTrail = record.trail,
                            message =
                                getApplication<Application>()
                                    .getString(R.string.discovery_offline_geometry_missing),
                        )
                }
            }
    }

    @Suppress("TooGenericExceptionCaught") // Filesystem failures are retryable; external errors stay private.
    fun saveTrail(trail: PublishedTrail) {
        if (trail.sources.isEmpty() || mutableSave.value.saving || trail.id in mutableSave.value.savedTrailIds) return
        mutableSave.value = mutableSave.value.copy(saving = true, message = null)
        viewModelScope.launch {
            try {
                val records =
                    withContext(Dispatchers.IO) {
                        downloadPublishedTrail(trail, library, offlineStore)
                    }
                publishOffline(records)
                mutableSave.value =
                    mutableSave.value.copy(
                        saving = false,
                        revision = mutableSave.value.revision + if (trail.canSave) 1 else 0,
                    )
            } catch (cancelled: CancellationException) {
                mutableSave.value = mutableSave.value.copy(saving = false)
                throw cancelled
            } catch (_: Exception) {
                mutableSave.value =
                    mutableSave.value.copy(
                        saving = false,
                        message = getApplication<Application>().getString(R.string.discovery_download_failed),
                    )
            }
        }
    }

    private fun publishOffline(records: List<OfflinePublishedTrail>) {
        mutableOffline.value = mutableOffline.value.copy(records = records, loading = false)
        mutableSave.value = mutableSave.value.copy(savedTrailIds = records.map { it.trail.id }.toSet())
    }

    private fun repository(settings: RouteSourceSettings): TrailDiscoveryRepository {
        val geotrek = runCatching { parseGeotrekRegistry(settings.geotrekRegistry) }.getOrDefault(emptyList())
        return TrailDiscoveryRepository(
            listOf(
                OsmPublishedTrailProvider(settings.overpassUrl, http),
                UnavailableTrailProvider(
                    TrailProvider.WANDERER,
                    "No approved instance configured. Wanderer catalogues and content rights are per server; " +
                        "global search is unavailable.",
                ),
                DatatourismePublishedTrailProvider(BuildConfig.DATATOURISME_API_KEY, http),
            ) +
                if (geotrek.isEmpty()) {
                    listOf(
                        UnavailableTrailProvider(
                            TrailProvider.GEOTREK,
                            "Add a public Geotrek instance and its licence in Route Sources.",
                        ),
                    )
                } else {
                    geotrek.map { GeotrekPublishedTrailProvider(it, http) }
                },
        )
    }
}
