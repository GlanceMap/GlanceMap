package com.glancemap.glancemapcompanionapp.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapLibraryAction
import com.glancemap.glancemapcompanionapp.map.MapToolContentMode
import com.glancemap.glancemapcompanionapp.map.titleResource

@Composable
internal fun libraryCategoryControls(
    filter: PhoneLibraryFilter,
    importing: Boolean,
    actions: PhoneLibraryActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(filter.label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            filter.tool?.let { tool ->
                IconButton(onClick = { actions.onOpenSettings(tool) }) {
                    Icon(Icons.Filled.Settings, stringResource(tool.titleResource(MapToolContentMode.FEATURE_SETTINGS)))
                }
            }
        }
        if (filter == PhoneLibraryFilter.GPX) {
            libraryGpxImport(importing, actions)
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
            Button(onClick = { actions.onMapAction(action) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun libraryGpxImport(
    importing: Boolean,
    actions: PhoneLibraryActions,
) {
    var expanded by remember { mutableStateOf(false) }
    var asRecording by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) actions.onImportGpx(uri, asRecording, null)
        }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                asRecording = false
                launcher.launch(arrayOf("*/*"))
            },
            enabled = !importing,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.library_add_gpx))
        }
        Box {
            IconButton(onClick = { expanded = true }, enabled = !importing) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.library_more_import_options))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_import_recording)) },
                    onClick = {
                        asRecording = true
                        expanded = false
                        launcher.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }
}
