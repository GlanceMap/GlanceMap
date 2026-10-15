package com.glancemap.glancemapcompanionapp.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleStore
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMapStore
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorageLocation
import com.glancemap.glancemapcompanionapp.map.phoneOfflineBundleHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

internal data class PhoneLibraryLocalState(
    val items: List<PhoneLibraryItem> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val organization: PhoneLibraryOrganization = PhoneLibraryOrganization(),
    val organizationReady: Boolean = false,
    val isImporting: Boolean = false,
)

internal class PhoneLibraryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val storage = PhoneOfflineStorage(application)
    private val maps = PhoneOfflineMapStore(application)
    private val bundles = PhoneOfflineBundleStore(application)
    private val state = MutableStateFlow(PhoneLibraryLocalState())
    val uiState = state.asStateFlow()
    private var refreshJob: Job? = null
    private val organizationStore = PhoneLibraryOrganizationStore(application)
    private val organizationMutex = Mutex()

    init {
        viewModelScope.launch {
            organizationMutex.withLock {
                try {
                    val organization = withContext(Dispatchers.IO) { organizationStore.load() }
                    state.value = state.value.copy(organization = organization, organizationReady = true)
                } catch (_: IOException) {
                    organizationError(R.string.library_organization_load_error)
                } catch (_: SecurityException) {
                    organizationError(R.string.library_organization_load_error)
                }
            }
        }
    }

    fun createFolder(
        parentId: String?,
        name: String,
    ) {
        updateOrganization { it.createFolder(UUID.randomUUID().toString(), name, parentId) }
    }

    fun moveItem(
        item: PhoneLibraryItem,
        folderId: String?,
    ) {
        updateOrganization { it.move(item, folderId) }
    }

    fun setActivity(
        item: PhoneLibraryItem,
        activity: PhoneLibraryActivity,
    ) {
        updateOrganization { it.classify(item, activity) }
    }

    private fun updateOrganization(update: (PhoneLibraryOrganization) -> PhoneLibraryOrganization) {
        viewModelScope.launch {
            organizationMutex.withLock {
                if (!state.value.organizationReady) return@withLock
                try {
                    val next = update(state.value.organization)
                    withContext(Dispatchers.IO) { organizationStore.save(next) }
                    state.value = state.value.copy(organization = next, message = null)
                } catch (_: IOException) {
                    organizationError(R.string.library_organization_save_error)
                } catch (_: SecurityException) {
                    organizationError(R.string.library_organization_save_error)
                } catch (_: IllegalArgumentException) {
                    organizationError(R.string.library_folder_invalid)
                }
            }
        }
    }

    private fun organizationError(message: Int) {
        state.value = state.value.copy(message = getApplication<Application>().getString(message))
    }

    fun importOfflineFiles(
        uris: List<Uri>,
        section: PhoneLibraryOfflineSection,
        onDataChanged: () -> Unit,
    ) {
        if (uris.isEmpty() || state.value.isImporting || section == PhoneLibraryOfflineSection.AREAS) return
        state.value = state.value.copy(isImporting = true, message = null)
        viewModelScope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        importPhoneLibraryOfflineFiles(getApplication(), uris, section)
                    }
                onDataChanged()
                state.value =
                    state.value.copy(
                        message =
                            if (result.hasError) {
                                getApplication<Application>().getString(R.string.library_offline_import_error)
                            } else {
                                getApplication<Application>().getString(
                                    R.string.library_offline_import_result,
                                    result.added,
                                    result.reused,
                                    result.invalid,
                                )
                            },
                    )
                refresh()
            } catch (_: IOException) {
                organizationError(R.string.library_offline_import_error)
            } catch (_: SecurityException) {
                organizationError(R.string.library_offline_import_error)
            } finally {
                state.value = state.value.copy(isImporting = false)
            }
        }
    }

    private fun loadItems(): List<PhoneLibraryItem> {
        val installed = bundles.list()
        val root = storage.activeRoot().absolutePath
        val internalRoot = getApplication<Application>().filesDir.absolutePath
        val location =
            if (root == internalRoot || root.startsWith(internalRoot + java.io.File.separator)) {
                PhoneOfflineStorageLocation.INTERNAL
            } else {
                PhoneOfflineStorageLocation.EXTERNAL
            }
        val data =
            discoverPhoneLibraryOfflineData(
                storage.elevationDirectory(),
                storage.routingDirectory(),
                installed,
                location,
                state.value.items.filterIsInstance<PhoneLibraryItem.OfflineData>(),
            )
        val verified = data.associate { it.file.absolutePath to it.isUsable }
        val areas =
            installed.map { bundle ->
                val health =
                    phoneOfflineBundleHealth(
                        getApplication(),
                        maps,
                        bundle,
                        bundles.findRecovery(bundle.areaId),
                        verified,
                    )
                PhoneLibraryItem.Bundle(
                    bundle,
                    health,
                    maps.findValidBundleMap(bundle.mapFileName),
                    phoneLibraryBundleCapabilities(bundle, health, data),
                )
            }
        return receivedPhoneLibraryFiles(storage.watchGpxExportsDirectory()) +
            maps.discover().map { map ->
                val previous =
                    state.value.items
                        .filterIsInstance<PhoneLibraryItem.OfflineMap>()
                        .firstOrNull { it.map.rendererIdentity == map.rendererIdentity }
                val bounds = previous?.coverage?.bounds ?: readLibraryMapBounds(map)
                PhoneLibraryItem.OfflineMap(map, map.file.length(), phoneLibraryMapCoverage(bounds, data))
            } + areas +
            data
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                state.value = state.value.copy(isLoading = true, message = null)
                try {
                    val items =
                        withContext(Dispatchers.IO) {
                            loadItems()
                        }
                    state.value = state.value.copy(items = items, isLoading = false)
                } catch (_: IOException) {
                    showError(R.string.library_load_error)
                } catch (_: SecurityException) {
                    showError(R.string.library_load_error)
                }
            }
    }

    private fun showError(message: Int) {
        state.value =
            state.value.copy(
                isLoading = false,
                message = getApplication<Application>().getString(message),
            )
    }
}
