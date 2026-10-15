package com.glancemap.glancemapcompanionapp.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapLibraryAction
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsActions
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsState
import com.glancemap.glancemapcompanionapp.map.mapToolsElevationFolderSettings
import com.glancemap.glancemapcompanionapp.map.mapToolsRoutingFolderSettings

@Composable
internal fun libraryOfflineSections(
    browse: PhoneLibraryBrowseState,
    onBrowse: (PhoneLibraryBrowseState) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhoneLibraryOfflineSection.entries.forEach { section ->
            FilterChip(
                selected = browse.offlineSection == section,
                onClick = { onBrowse(browse.copy(offlineSection = section)) },
                modifier = Modifier.testTag("library-offline-${section.name}"),
                label = { Text(stringResource(section.label)) },
            )
        }
    }
}

@Composable
internal fun libraryOfflineControls(
    browse: PhoneLibraryBrowseState,
    importing: Boolean,
    actions: PhoneLibraryActions,
    state: MapToolsMapsState,
    mapActions: MapToolsMapsActions,
) {
    val section = browse.offlineSection
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) actions.onImportOfflineFiles(uris, section)
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                when (section) {
                    PhoneLibraryOfflineSection.AREAS -> R.string.library_offline_areas_description
                    PhoneLibraryOfflineSection.ELEVATION -> R.string.library_offline_elevation_description
                    PhoneLibraryOfflineSection.ROUTING -> R.string.library_offline_routing_description
                },
            ),
        )
        Button(
            onClick = {
                if (section == PhoneLibraryOfflineSection.AREAS) {
                    actions.onMapAction(MapLibraryAction.DOWNLOAD_BUNDLE)
                } else {
                    launcher.launch(arrayOf("*/*"))
                }
            },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (section == PhoneLibraryOfflineSection.AREAS) {
                        R.string.library_offline_download
                    } else {
                        R.string.library_offline_add_files
                    },
                ),
            )
        }
        if (section == PhoneLibraryOfflineSection.ELEVATION) {
            mapToolsElevationFolderSettings(state, mapActions, enabled = !importing)
        } else if (section == PhoneLibraryOfflineSection.ROUTING) {
            mapToolsRoutingFolderSettings(state, mapActions, enabled = !importing)
        }
    }
}
