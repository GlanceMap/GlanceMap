package com.glancemap.glancemapcompanionapp.map

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.oam.OamDownloadCatalog

/** Minimal temporary surface for the phone's first map+POI bundle download flow. */
@Composable
@Suppress("FunctionNaming") // Compose screen functions follow the project naming convention.
internal fun PhoneOfflineBundleDialog(
    uiState: PhoneOfflineBundleUiState,
    onDismiss: () -> Unit,
    onStart: (areaId: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedAreaId by remember { mutableStateOf<String?>(null) }
    val state = uiState.download
    val isDownloading = state is PhoneOfflineBundleDownloadState.Downloading
    val selectedArea = OamDownloadCatalog.areas.firstOrNull { it.id == selectedAreaId }
    val selectorState =
        PhoneOfflineBundleSelectorState(
            query = query,
            selectedAreaId = selectedAreaId,
            installedAreaIds = uiState.installedAreaIds,
        )

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text(stringResource(R.string.map_bundle_selector_title)) },
        text = {
            phoneOfflineBundleDialogContent(
                state = state,
                selectorState = selectorState,
                onQueryChanged = { query = it },
                onAreaSelected = { selectedAreaId = it },
            )
        },
        confirmButton = {
            when (state) {
                is PhoneOfflineBundleDownloadState.Downloading ->
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_action_cancel)) }
                else ->
                    TextButton(
                        enabled = selectedArea != null,
                        onClick = { selectedArea?.id?.let(onStart) },
                    ) {
                        Text(stringResource(R.string.map_bundle_action_download))
                    }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
            }
        },
    )
}

@Composable
private fun phoneOfflineBundleDialogContent(
    state: PhoneOfflineBundleDownloadState,
    selectorState: PhoneOfflineBundleSelectorState,
    onQueryChanged: (String) -> Unit,
    onAreaSelected: (String) -> Unit,
) {
    Column {
        when (state) {
            is PhoneOfflineBundleDownloadState.Downloading -> {
                Text(stringResource(state.progress.phase.stringResource()))
                bundleProgress(state.progress)
            }
            is PhoneOfflineBundleDownloadState.Completed ->
                Text(stringResource(R.string.map_bundle_status_complete, state.bundle.areaLabel))
            is PhoneOfflineBundleDownloadState.Failed -> Text(stringResource(state.reason.stringResource()))
            PhoneOfflineBundleDownloadState.Cancelled ->
                Text(stringResource(R.string.map_bundle_status_cancelled))
            PhoneOfflineBundleDownloadState.Idle ->
                phoneOfflineBundleAreaSelector(
                    selectorState = selectorState,
                    onQueryChanged = onQueryChanged,
                    onAreaSelected = onAreaSelected,
                )
        }
    }
}

@Composable
private fun phoneOfflineBundleAreaSelector(
    selectorState: PhoneOfflineBundleSelectorState,
    onQueryChanged: (String) -> Unit,
    onAreaSelected: (String) -> Unit,
) {
    val matchingAreas =
        remember(selectorState.query) {
            OamDownloadCatalog.areas.filter { area ->
                area.region.contains(selectorState.query, ignoreCase = true) ||
                    area.continent.contains(selectorState.query, ignoreCase = true)
            }
        }
    TextField(
        value = selectorState.query,
        onValueChange = onQueryChanged,
        label = { Text(stringResource(R.string.map_bundle_search_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
        items(matchingAreas, key = { it.id }) { area ->
            TextButton(
                onClick = { onAreaSelected(area.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(area.region)
                    Text(
                        stringResource(
                            R.string.map_bundle_area_sizes,
                            area.mapSizeLabel,
                            area.poiSizeLabel,
                        ),
                    )
                    if (area.id in selectorState.installedAreaIds) {
                        Text(stringResource(R.string.map_bundle_area_installed))
                    } else if (area.id == selectorState.selectedAreaId) {
                        Text(stringResource(R.string.map_bundle_area_selected))
                    }
                }
            }
        }
    }
    if (matchingAreas.isEmpty()) {
        Text(
            text = stringResource(R.string.map_bundle_no_areas),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private data class PhoneOfflineBundleSelectorState(
    val query: String,
    val selectedAreaId: String?,
    val installedAreaIds: Set<String>,
)

@Composable
private fun bundleProgress(progress: PhoneOfflineBundleProgress) {
    val context = LocalContext.current
    val current = Formatter.formatFileSize(context, progress.bytesDownloaded)
    val totalBytes = progress.totalBytes
    if (totalBytes == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.map_bundle_progress_unknown, current))
    } else {
        val total = Formatter.formatFileSize(context, totalBytes)
        LinearProgressIndicator(
            progress = { (progress.bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.map_bundle_progress_known, current, total))
    }
}

private fun PhoneOfflineBundlePhase.stringResource(): Int =
    when (this) {
        PhoneOfflineBundlePhase.DOWNLOADING_MAP -> R.string.map_bundle_phase_downloading_map
        PhoneOfflineBundlePhase.INSTALLING_MAP -> R.string.map_bundle_phase_installing_map
        PhoneOfflineBundlePhase.DOWNLOADING_POI -> R.string.map_bundle_phase_downloading_poi
        PhoneOfflineBundlePhase.INSTALLING_POI -> R.string.map_bundle_phase_installing_poi
    }

private fun PhoneOfflineBundleFailure.stringResource(): Int =
    when (this) {
        PhoneOfflineBundleFailure.NETWORK -> R.string.map_bundle_error_network
        PhoneOfflineBundleFailure.HTTP -> R.string.map_bundle_error_http
        PhoneOfflineBundleFailure.STORAGE -> R.string.map_bundle_error_storage
        PhoneOfflineBundleFailure.ARCHIVE -> R.string.map_bundle_error_archive
        PhoneOfflineBundleFailure.INVALID_MAP -> R.string.map_bundle_error_invalid_map
        PhoneOfflineBundleFailure.INVALID_POI -> R.string.map_bundle_error_invalid_poi
        PhoneOfflineBundleFailure.CANCELLED -> R.string.map_bundle_status_cancelled
    }
