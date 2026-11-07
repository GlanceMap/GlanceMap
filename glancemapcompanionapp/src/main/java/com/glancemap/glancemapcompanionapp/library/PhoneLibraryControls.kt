package com.glancemap.glancemapcompanionapp.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapLibraryAction

@Composable
internal fun libraryCategoryAddAction(
    filter: PhoneLibraryFilter,
    importing: Boolean,
    actions: PhoneLibraryActions,
    modifier: Modifier = Modifier,
) {
    if (filter == PhoneLibraryFilter.GPX) {
        libraryGpxImport(importing, actions, modifier)
    } else {
        val label =
            when (filter) {
                PhoneLibraryFilter.POIS -> R.string.map_tool_shortcut_add_poi
                PhoneLibraryFilter.MAPS -> R.string.library_import_map
                PhoneLibraryFilter.BUNDLES -> R.string.map_bundle_action_download
                PhoneLibraryFilter.GPX -> error("GPX has its own importer")
            }
        val action =
            when (filter) {
                PhoneLibraryFilter.POIS -> MapLibraryAction.ADD_POI
                PhoneLibraryFilter.MAPS -> MapLibraryAction.IMPORT_MAP
                PhoneLibraryFilter.BUNDLES -> MapLibraryAction.DOWNLOAD_BUNDLE
                PhoneLibraryFilter.GPX -> error("GPX has its own importer")
            }
        libraryAddButton(stringResource(label), !importing, modifier) { actions.onMapAction(action) }
    }
}

@Composable
private fun libraryGpxImport(
    importing: Boolean,
    actions: PhoneLibraryActions,
    modifier: Modifier,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) actions.onImportGpx(uri, false, null)
        }
    libraryAddButton(stringResource(R.string.library_add_gpx), !importing, modifier) {
        launcher.launch(arrayOf("*/*"))
    }
}

@Composable
private fun libraryAddButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
