package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelState
import kotlinx.coroutines.launch

internal fun LazyListScope.libraryFolderEntries(
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    browser: PhoneLibraryBrowserPresentation,
) {
    val browse = browser.browse
    val folders =
        state.organization.folders
            .filter { it.category == state.filter && it.parentId == browse.folderId && browse.query.isBlank() }
            .sortedBy { it.name }
    val regionPoiNames = phoneLibraryRegionPoiNames(browser.allItems)
    val deleteEnabled = !state.isImporting && !actions.guidanceActive && actions.guidanceStartingKey == null
    items(folders, key = { "folder/${it.id}" }) { folder ->
        libraryFolderRow(
            PhoneLibraryFolderRowState(
                folder,
                state.organization.folderItems(folder.id, browser.allItems),
                regionPoiNames,
                deleteEnabled,
            ),
            mapState,
            mapActions,
            actions,
        ) { browser.navigation.onBrowse(browse.copy(folderId = folder.id, query = "")) }
    }
}

@Composable
internal fun libraryFolderRow(
    row: PhoneLibraryFolderRowState,
    state: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    onOpen: () -> Unit,
) {
    val folder = row.folder
    var renaming by rememberSaveable(folder.id) { mutableStateOf(false) }
    var deleting by rememberSaveable(folder.id) { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("library-folder-${folder.id}")) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                val shown = libraryFolderVisibilityTargets(row.items, state).map { it.second }
                val count = phoneLibraryFolderVisibilityCount(shown)
                if (count.total > 0) {
                    Text(
                        stringResource(R.string.library_folder_count, count.shown, count.total),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("library-folder-count-${folder.id}"),
                    )
                }
            }
            libraryFolderVisibility(row, state, mapActions)
            libraryFolderMenu(row, actions, { renaming = true }, { deleting = true })
        }
    }
    if (renaming) {
        libraryFolderRenameDialog(folder, { actions.organization.onRenameFolder(folder.id, it) }) { renaming = false }
    }
    if (deleting) libraryFolderDeleteDialog(row, mapActions, actions) { deleting = false }
}

@Composable
private fun libraryFolderVisibility(
    row: PhoneLibraryFolderRowState,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    val targets = libraryFolderVisibilityTargets(row.items, state)
    val toggle = phoneLibraryFolderToggleState(targets.map { it.second }) ?: return
    val description = stringResource(R.string.library_show_on_map, row.folder.name)
    TriStateCheckbox(
        state = toggle,
        enabled = !state.gpx.isLoading,
        onClick = { setLibraryFolderVisible(targets, toggle != ToggleableState.On, actions) },
        modifier =
            Modifier
                .testTag("library-folder-visibility-${row.folder.id}")
                .semantics { contentDescription = description },
    )
}

@Composable
private fun libraryFolderMenu(
    row: PhoneLibraryFolderRowState,
    actions: PhoneLibraryActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val folder = row.folder
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("library-folder-menu-${folder.id}")) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.library_folder_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (folder.category == PhoneLibraryFilter.GPX) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gpx_appearance_customize)) },
                    onClick = {
                        expanded = false
                        actions.onFolderGpxAppearance(folder)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_content_action_rename)) },
                enabled = actions.organization.enabled,
                modifier = Modifier.testTag("library-folder-rename"),
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_content_action_delete)) },
                enabled = actions.organization.enabled && row.deleteEnabled,
                modifier = Modifier.testTag("library-folder-delete"),
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun libraryFolderRenameDialog(
    folder: PhoneLibraryFolder,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(folder.id) { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_content_rename_title)) },
        text = {
            OutlinedTextField(
                name,
                onValueChange = { name = it.take(80) },
                label = { Text(stringResource(R.string.library_folder_name)) },
                singleLine = true,
                modifier = Modifier.testTag("library-folder-rename-name"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRename(name.trim())
                    onDismiss()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("library-folder-rename-confirm"),
            ) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

@Composable
private fun libraryFolderDeleteDialog(
    row: PhoneLibraryFolderRowState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    onDismiss: () -> Unit,
) {
    val deletion = remember(row.items, row.regionPoiNames) { phoneLibraryFolderDeletion(row.items, row.regionPoiNames) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.library_folder_delete_title)) },
        text = { libraryFolderDeleteText(row.folder, deletion, failed) },
        confirmButton = {
            TextButton(
                enabled = !busy,
                modifier = Modifier.testTag("library-folder-delete-confirm"),
                onClick = {
                    scope.launch {
                        busy = true
                        failed = false
                        val remaining =
                            deletePhoneLibraryItems(deletion.deleted) { item ->
                                deleteLibraryItem(item, mapActions, actions)
                            }
                        if (remaining.isEmpty()) {
                            actions.organization.onDeleteFolder(row.folder.id)
                            onDismiss()
                        } else {
                            failed = true
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.map_content_action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}

@Composable
private fun libraryFolderDeleteText(
    folder: PhoneLibraryFolder,
    deletion: PhoneLibraryFolderDeletion,
    failed: Boolean,
) {
    val count = deletion.deleted.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pluralStringResource(R.plurals.library_folder_delete_message, count, folder.name, count))
        if (deletion.deviceFiles > 0) {
            Text(
                pluralStringResource(
                    R.plurals.library_folder_delete_device_files,
                    deletion.deviceFiles,
                    deletion.deviceFiles,
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (deletion.keptRegionPois > 0) {
            Text(
                pluralStringResource(
                    R.plurals.library_folder_delete_region_pois,
                    deletion.keptRegionPois,
                    deletion.keptRegionPois,
                ),
            )
        }
        if (failed) Text(stringResource(R.string.library_folder_delete_failed), color = MaterialTheme.colorScheme.error)
    }
}

private suspend fun deleteLibraryItem(
    item: PhoneLibraryItem,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
) {
    when (item) {
        is PhoneLibraryItem.Gpx -> actions.onDeleteRoute(item.route.id)
        is PhoneLibraryItem.FolderGpx -> mapActions.onDeleteGpxItemNow(item.item)
        is PhoneLibraryItem.ReceivedGpx -> actions.organization.onDeleteReceivedGpx(item)
        is PhoneLibraryItem.Pois -> mapActions.onDeletePoiSourceNow(item.source)
        else -> error("Folders only hold routes and POIs")
    }
}
