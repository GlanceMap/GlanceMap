package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun libraryCreateFolderDialog(
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
                modifier = Modifier.testTag("library-folder-name"),
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
internal fun libraryGpxGrouping(
    browse: PhoneLibraryBrowseState,
    onBrowse: (PhoneLibraryBrowseState) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhoneLibraryGpxKind.entries.forEach { kind ->
            FilterChip(
                selected = browse.kind == kind,
                modifier = Modifier.testTag("library-kind-${kind.name}"),
                onClick = { onBrowse(browse.copy(kind = kind.takeUnless { browse.kind == it })) },
                label = { Text(stringResource(kind.label)) },
            )
        }
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

@Composable
internal fun libraryPoiGrouping(
    checkpoints: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = checkpoints,
            modifier = Modifier.testTag("library-poi-checkpoints"),
            onClick = onToggle,
            label = { Text(stringResource(R.string.hike_checkpoints)) },
        )
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
    val current = organization.entry(item).activity ?: PhoneLibraryActivity.HIKE
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
    items: List<PhoneLibraryItem>,
    organization: PhoneLibraryOrganization,
    actions: PhoneLibraryOrganizationActions,
    onDismiss: () -> Unit,
) {
    val category = items.firstOrNull()?.filter ?: return
    val move: (String?) -> Unit = { folderId ->
        items.forEach { actions.onMoveItem(it, folderId) }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                items.singleOrNull()?.let { stringResource(R.string.library_move_item, it.title) }
                    ?: pluralStringResource(R.plurals.library_move_items, items.size, items.size),
            )
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item {
                    TextButton(onClick = { move(null) }, enabled = actions.enabled) {
                        Text(stringResource(R.string.library_folder_root))
                    }
                }
                val folders = organization.folders.filter { it.category == category }
                items(folders.sortedBy { organization.folderPath(it.id) }, key = { it.id }) { folder ->
                    TextButton(
                        onClick = { move(folder.id) },
                        enabled = actions.enabled,
                        modifier = Modifier.testTag("library-move-to-${folder.id}"),
                    ) { Text(organization.folderPath(folder.id)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
    )
}
