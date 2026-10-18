package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class GpxRowDialog {
    NONE,
    RENAME,
    DELETE,
}

@Composable
internal fun libraryGpxOverflowMenu(
    row: PhoneLibraryRowState,
    mapActions: MapToolsPanelActions,
    onDetails: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var dialog by remember(row.item.key) { mutableStateOf(GpxRowDialog.NONE) }
    val item = row.item

    IconButton(
        onClick = { expanded = true },
        enabled = row.enabled,
        modifier = Modifier.testTag("library-gpx-overflow-${item.key}"),
    ) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.hike_route_details))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.hike_route_details)) },
            onClick = {
                expanded = false
                onDetails()
            },
            modifier = Modifier.testTag("library-gpx-details"),
        )
        if (item is PhoneLibraryItem.Gpx || item is PhoneLibraryItem.FolderGpx) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_content_action_rename)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    if (item is PhoneLibraryItem.Gpx) dialog = GpxRowDialog.RENAME else mapActions.onRenameGpxItem((item as PhoneLibraryItem.FolderGpx).item)
                },
                modifier = Modifier.testTag("library-gpx-rename"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_content_action_delete)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    if (item is PhoneLibraryItem.Gpx) dialog = GpxRowDialog.DELETE else mapActions.onDeleteGpxItem((item as PhoneLibraryItem.FolderGpx).item)
                },
                modifier = Modifier.testTag("library-gpx-delete"),
            )
        }
    }

    if (item is PhoneLibraryItem.Gpx) {
        when (dialog) {
            GpxRowDialog.RENAME -> libraryGpxRenameDialog(item, row) { dialog = GpxRowDialog.NONE }
            GpxRowDialog.DELETE -> libraryGpxDeleteDialog(item, row) { dialog = GpxRowDialog.NONE }
            GpxRowDialog.NONE -> Unit
        }
    }
}

@Composable
private fun libraryGpxRenameDialog(
    item: PhoneLibraryItem.Gpx,
    row: PhoneLibraryRowState,
    onDismiss: () -> Unit,
) {
    var name by remember(item.route.id) { mutableStateOf(item.route.displayName) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failureMessage = stringResource(R.string.map_content_rename_failed)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.map_content_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; failed = false },
                    label = { Text(stringResource(R.string.map_poi_name_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("library-gpx-rename-name"),
                )
                if (failed) Text(failureMessage)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.testTag("library-gpx-rename-confirm"),
                onClick = {
                    scope.launch {
                        busy = true
                        failed = false
                        try {
                            row.onRenameRoute(item.route.id, name.trim())
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.common_action_cancel))
            }
        },
    )
}

@Composable
private fun libraryGpxDeleteDialog(
    item: PhoneLibraryItem.Gpx,
    row: PhoneLibraryRowState,
    onDismiss: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failureMessage = stringResource(R.string.map_content_delete_failed)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.map_content_delete_title)) },
        text = {
            Column {
                Text(stringResource(R.string.map_content_delete_message, item.route.displayName))
                if (failed) Text(failureMessage)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                modifier = Modifier.testTag("library-gpx-delete-confirm"),
                onClick = {
                    scope.launch {
                        busy = true
                        failed = false
                        try {
                            row.onDeleteRoute(item.route.id)
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.map_content_action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.common_action_cancel))
            }
        },
    )
}
