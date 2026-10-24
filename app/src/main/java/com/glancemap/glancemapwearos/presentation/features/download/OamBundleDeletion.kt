package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

// UI operation boundary: retain the bundle on storage errors and propagate owner cancellation.
@Suppress("TooGenericExceptionCaught")
internal suspend fun MutableStateFlow<DownloadUiState>.deleteInstalledBundle(
    bundle: OamInstalledBundle,
    delete: suspend (OamInstalledBundle) -> Unit,
    reload: suspend () -> Pair<List<OamInstalledBundle>, Map<String, OamBundleLocalHealth>>,
    failureMessage: String,
) {
    if (
        value.isLibraryBusy ||
        value.isCheckingUpdates ||
        value.installedBundles.none { it.areaId == bundle.areaId }
    ) {
        return
    }
    update {
        it.copy(
            deletingBundleId = bundle.areaId,
            selectedRefreshBundleIds = it.selectedRefreshBundleIds - bundle.areaId,
            statusMessage = null,
            errorMessage = null,
            networkWarningMessage = null,
        )
    }
    try {
        delete(bundle)
        update {
            it.copy(
                installedBundles = it.installedBundles.filterNot { installed -> installed.areaId == bundle.areaId },
                bundleHealthByAreaId = it.bundleHealthByAreaId - bundle.areaId,
                lastLibraryChangedAtMillis = maxOf(System.currentTimeMillis(), it.lastLibraryChangedAtMillis + 1L),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        val remaining =
            try {
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        update {
            it.copy(
                installedBundles = remaining?.first ?: it.installedBundles,
                bundleHealthByAreaId = remaining?.second ?: it.bundleHealthByAreaId,
                errorMessage = failureMessage,
                lastLibraryChangedAtMillis = maxOf(System.currentTimeMillis(), it.lastLibraryChangedAtMillis + 1L),
            )
        }
    } finally {
        update { it.copy(deletingBundleId = null) }
    }
}

internal suspend fun MutableStateFlow<DownloadUiState>.refreshInstalledBundleState(
    load: suspend () -> Pair<List<OamInstalledBundle>, Map<String, OamBundleLocalHealth>>,
) {
    val revision = value.lastLibraryChangedAtMillis
    val (installed, health) = load()
    update {
        if (it.lastLibraryChangedAtMillis == revision) {
            it.copy(installedBundles = installed, bundleHealthByAreaId = health)
        } else {
            it
        }
    }
}

internal fun requireBundleFilesDeleted(files: Collection<File>) {
    if (files.any { it.exists() }) throw IOException("Bundle files remain after deletion")
}
