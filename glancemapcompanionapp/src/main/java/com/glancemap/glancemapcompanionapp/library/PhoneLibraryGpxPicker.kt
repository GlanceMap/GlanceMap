package com.glancemap.glancemapcompanionapp.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryUiState

@Composable
internal fun phoneLibraryGpxPicker(
    state: RouteLibraryUiState,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val routes = state.routes.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_choose_gpx)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_search)) },
                )
                if (state.isLoading) {
                    CircularProgressIndicator()
                } else if (routes.isEmpty()) {
                    Text(stringResource(R.string.library_picker_empty))
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(routes, key = { it.id }) { route ->
                            Card(onClick = { onPick(route.id) }, enabled = !state.isImporting) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(route.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text(libraryItemSummary(PhoneLibraryItem.Gpx(route)))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
    )
}

@Composable
internal fun todayHikeGpxActions(
    state: RouteLibraryUiState,
    onImport: (Uri) -> Unit,
    onChoose: () -> Unit,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImport(uri)
        }
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !state.isImporting) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.today_hike_add_or_change_gpx))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_choose_gpx)) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                enabled = !state.isImporting,
                onClick = {
                    expanded = false
                    onChoose()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_import_from_storage)) },
                leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                enabled = !state.isImporting,
                onClick = {
                    expanded = false
                    launcher.launch(arrayOf("*/*"))
                },
            )
        }
    }
}
