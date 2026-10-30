package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsActions
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsState

@Composable
internal fun libraryMapDataButton(
    item: PhoneLibraryItem.OfflineMap,
    enabled: Boolean,
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    var open by remember(item.key) { mutableStateOf(false) }
    val coverage = item.coverage
    var elevation by remember(item.key, coverage) {
        mutableStateOf(coverage?.requiredElevationTiles?.let { it > coverage.availableElevationTiles } == true)
    }
    var routing by remember(item.key, coverage) {
        mutableStateOf(coverage?.requiredRoutingFiles?.let { it > coverage.availableRoutingFiles } == true)
    }
    var startFailed by remember(item.key) { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("library-map-data-${item.key}")) {
        Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.library_map_data_action, item.title))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
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
                        if (actions.onDownloadMapData(item.map, elevation, routing)) open = false else startFailed = true
                    },
                ) { Text(stringResource(R.string.library_map_data_download)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

@Composable
private fun mapDataSwitch(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag("library-map-data-$tag"))
    }
}
