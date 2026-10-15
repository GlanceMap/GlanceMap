package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun libraryFolderNavigation(
    organization: PhoneLibraryOrganization,
    browse: PhoneLibraryBrowseState,
    actions: PhoneLibraryOrganizationActions,
    onParent: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (browse.folderId != null) {
            IconButton(onClick = onParent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.library_folder_parent))
            }
        }
        Text(
            organization.folderPath(browse.folderId).ifEmpty { stringResource(R.string.library_folder_root) },
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = { creating = true }, enabled = actions.enabled) {
            Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.library_create_folder))
        }
    }
    if (creating) {
        libraryCreateFolderDialog(
            onCreate = { name ->
                actions.onCreateFolder(browse.folderId, name)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun libraryCreateFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_create_folder)) },
        text = {
            OutlinedTextField(
                name,
                onValueChange = { name = it.take(80) },
                label = { Text(stringResource(R.string.library_folder_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.library_folder_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

@Composable
internal fun libraryFolderRow(
    folder: PhoneLibraryFolder,
    onAppearance: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("library-folder-${folder.id}")) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Folder, contentDescription = null)
            Text(folder.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            onAppearance?.let { action ->
                TextButton(onClick = action) { Text(stringResource(R.string.gpx_appearance_customize)) }
            }
        }
    }
}

@Composable
internal fun libraryGpxGrouping(
    browse: PhoneLibraryBrowseState,
    onBrowse: (PhoneLibraryBrowseState) -> Unit,
) {
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneLibraryGpxKind.entries.forEach { kind ->
                FilterChip(
                    selected = browse.kind == kind,
                    modifier = Modifier.testTag("library-kind-${kind.name}"),
                    onClick = { onBrowse(browse.copy(kind = kind.takeUnless { browse.kind == it })) },
                    label = { Text(stringResource(kind.label)) },
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneLibraryActivity.entries.forEach { activity ->
                FilterChip(
                    selected = browse.activity == activity,
                    modifier = Modifier.testTag("library-activity-${activity.name}"),
                    onClick = { onBrowse(browse.copy(activity = activity.takeUnless { browse.activity == it })) },
                    label = { Text(stringResource(activity.label)) },
                )
            }
        }
    }
}

@Composable
internal fun libraryActivitySetting(
    item: PhoneLibraryItem,
    organization: PhoneLibraryOrganization,
    actions: PhoneLibraryOrganizationActions,
) {
    if (item.filter != PhoneLibraryFilter.GPX) return
    Text(stringResource(R.string.library_activity_title), style = MaterialTheme.typography.titleSmall)
    val current = organization.entry(item).activity ?: PhoneLibraryActivity.UNSPECIFIED
    PhoneLibraryActivity.entries.forEach { activity ->
        Row(
            modifier =
                Modifier.fillMaxWidth().testTag("library-classify-${activity.name}").selectable(
                    selected = current == activity,
                    enabled = actions.enabled,
                    role = Role.RadioButton,
                    onClick = { actions.onActivity(item, activity) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = current == activity, enabled = actions.enabled, onClick = null)
            Text(stringResource(activity.label))
        }
    }
}

@Composable
internal fun libraryMoveDialog(
    item: PhoneLibraryItem,
    organization: PhoneLibraryOrganization,
    actions: PhoneLibraryOrganizationActions,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_move_item, item.title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item {
                    TextButton(onClick = {
                        actions.onMoveItem(item, null)
                        onDismiss()
                    }, enabled = actions.enabled) { Text(stringResource(R.string.library_folder_root)) }
                }
                items(organization.folders.sortedBy { organization.folderPath(it.id) }, key = { it.id }) { folder ->
                    TextButton(onClick = {
                        actions.onMoveItem(item, folder.id)
                        onDismiss()
                    }, enabled = actions.enabled) { Text(organization.folderPath(folder.id)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
    )
}
