package com.glancemap.glancemapcompanionapp.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsActions
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsState

@Composable
internal fun libraryMapDataActions(
    item: PhoneLibraryItem.OfflineMap,
    enabled: Boolean,
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    var open by remember(item.key) { mutableStateOf(false) }
    val coverage = item.coverage
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        mapDataChip(
            PhoneLibraryMapDataChip(
                R.string.library_offline_elevation,
                Icons.Filled.Landscape,
                coverage?.requiredElevationTiles,
                coverage?.availableElevationTiles ?: 0,
                "library-map-data-elevation-${item.key}",
            ),
            enabled,
        ) { open = true }
        mapDataChip(
            PhoneLibraryMapDataChip(
                R.string.library_offline_routing,
                Icons.AutoMirrored.Filled.AltRoute,
                coverage?.requiredRoutingFiles,
                coverage?.availableRoutingFiles ?: 0,
                "library-map-data-routing-${item.key}",
            ),
            enabled,
        ) { open = true }
    }
    if (open) libraryMapDataDialog(item, state, actions) { open = false }
}

@Composable
private fun libraryMapDataDialog(
    item: PhoneLibraryItem.OfflineMap,
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
    onDismiss: () -> Unit,
) {
    val coverage = item.coverage
    var elevation by remember(item.key, coverage) {
        mutableStateOf(coverage?.requiredElevationTiles?.let { it > coverage.availableElevationTiles } == true)
    }
    var routing by remember(item.key, coverage) {
        mutableStateOf(coverage?.requiredRoutingFiles?.let { it > coverage.availableRoutingFiles } == true)
    }
    var startFailed by remember(item.key) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_map_data_title, item.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                libraryMapCoverageLabels(coverage)
                mapDataSwitch(stringResource(R.string.library_offline_elevation), elevation, "elevation") { elevation = it }
                mapDataSwitch(stringResource(R.string.library_map_data_brouter), routing, "routing") { routing = it }
                Text(stringResource(R.string.library_map_data_shared), style = MaterialTheme.typography.bodySmall)
                if (coverage?.bounds == null) Text(stringResource(R.string.library_map_data_no_bounds))
                if (state.offlineDataDownloadBusy || startFailed) Text(stringResource(R.string.library_map_data_busy))
            }
        },
        confirmButton = {
            TextButton(
                enabled = (elevation || routing) && coverage?.bounds != null && !state.offlineDataDownloadBusy,
                modifier = Modifier.testTag("library-map-data-start"),
                onClick = {
                    if (actions.onDownloadMapData(item.map, elevation, routing)) onDismiss() else startFailed = true
                },
            ) { Text(stringResource(R.string.library_map_data_download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

private data class PhoneLibraryMapDataChip(
    @StringRes val label: Int,
    val icon: ImageVector,
    val required: Int?,
    val available: Int,
    val tag: String,
)

// The symbol carries the status so it never depends on colour alone.
private fun PhoneLibraryMapDataStatus.icon(): ImageVector =
    when (this) {
        PhoneLibraryMapDataStatus.READY -> Icons.Filled.Check
        PhoneLibraryMapDataStatus.PARTIAL -> Icons.Filled.Contrast
        PhoneLibraryMapDataStatus.MISSING -> Icons.Filled.Download
        PhoneLibraryMapDataStatus.NOT_REQUIRED -> Icons.Filled.Remove
        PhoneLibraryMapDataStatus.UNKNOWN -> Icons.Filled.QuestionMark
    }

@Composable
private fun mapDataChip(
    chip: PhoneLibraryMapDataChip,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = libraryMapCoverageText(chip.label, chip.required, chip.available)
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(stringResource(chip.label)) },
        leadingIcon = { Icon(chip.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            Icon(
                phoneLibraryMapDataStatus(chip.required, chip.available).icon(),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = Modifier.testTag(chip.tag).semantics { contentDescription = description },
    )
}

@Composable
private fun mapDataSwitch(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag("library-map-data-$tag"))
    }
}
