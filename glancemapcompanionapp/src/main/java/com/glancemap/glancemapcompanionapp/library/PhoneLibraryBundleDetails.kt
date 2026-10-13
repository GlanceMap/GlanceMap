package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleStatus
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap

@Composable
internal fun libraryBundleDetails(
    item: PhoneLibraryItem.Bundle,
    onOpenMap: (PhoneOfflineMap) -> Unit,
    onManage: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        stringResource(
            when (item.health.status) {
                PhoneOfflineBundleStatus.COMPLETE -> R.string.library_bundle_ready
                PhoneOfflineBundleStatus.PARTIAL -> R.string.map_bundle_area_partial
                PhoneOfflineBundleStatus.RECOVERY_NEEDED -> R.string.map_bundle_area_recovery
                PhoneOfflineBundleStatus.NOT_INSTALLED -> R.string.library_bundle_missing
            },
        ),
    )
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.library_hide_contents else R.string.library_bundle_contents))
    }
    if (expanded) {
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.library_bundle_map, item.bundle.mapFileName))
            Text(stringResource(R.string.library_bundle_poi, item.bundle.poiFileName))
            item.bundle.refugesInfoFileName?.let { Text(it) }
            Text(stringResource(R.string.library_bundle_routing, item.bundle.routingFileNames.size))
            Text(stringResource(R.string.library_bundle_elevation, item.bundle.demTileIds.size))
        }
    }
    item.map?.let { map ->
        OutlinedButton(onClick = { onOpenMap(map) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.library_open_map))
        }
    }
    OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.map_tools_settings_download_bundle))
    }
}
