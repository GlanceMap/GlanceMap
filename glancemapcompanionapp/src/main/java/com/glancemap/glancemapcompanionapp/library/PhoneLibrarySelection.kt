package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelState

/** Null keys mean the list is not in selection mode. */
internal data class PhoneLibrarySelection(
    val keys: Set<String>? = null,
    val onToggle: (PhoneLibraryItem) -> Unit = {},
    val onSelectAll: (List<PhoneLibraryItem>) -> Unit = {},
    val onClear: () -> Unit = {},
    val onMove: () -> Unit = {},
) {
    val active: Boolean
        get() = keys != null
}

internal fun Set<String>?.toggled(key: String): Set<String> {
    val current = this.orEmpty()
    return if (key in current) current - key else current + key
}

/** Counts items shown on the map out of those that can be, for folder rows and the in-folder toggle. */
internal data class PhoneLibraryFolderVisibilityCount(
    val shown: Int,
    val total: Int,
)

internal fun phoneLibraryFolderVisibilityCount(visible: List<Boolean>): PhoneLibraryFolderVisibilityCount {
    val shown = visible.count { it }
    return PhoneLibraryFolderVisibilityCount(shown = shown, total = visible.size)
}

@Composable
internal fun librarySelectionHeader(
    items: List<PhoneLibraryItem>,
    selection: PhoneLibrarySelection,
) {
    val count = selection.keys.orEmpty().size
    Row(Modifier.fillMaxWidth().testTag("library-selection-header"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = selection.onClear, modifier = Modifier.testTag("library-selection-clear")) {
            Icon(Icons.Filled.Close, stringResource(R.string.common_action_cancel))
        }
        Text(
            pluralStringResource(R.plurals.library_selected_count, count, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { selection.onSelectAll(items.filter { it.filter == PhoneLibraryFilter.GPX }) },
            modifier = Modifier.testTag("library-select-all"),
        ) { Text(stringResource(R.string.library_select_all)) }
    }
}

/** Inside a folder: back to its parent, the folder path and, for routes, the folder's appearance. */
@Composable
internal fun libraryFolderHeader(
    organization: PhoneLibraryOrganization,
    folderId: String,
    actions: PhoneLibraryActions,
    onParent: () -> Unit,
) {
    val folder = organization.folder(folderId) ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onParent) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.library_folder_parent))
        }
        Text(
            organization.folderPath(folderId),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (folder.category == PhoneLibraryFilter.GPX) {
            IconButton(
                onClick = { actions.onFolderGpxAppearance(folder) },
                modifier = Modifier.testTag("library-folder-appearance"),
            ) {
                Icon(Icons.Filled.Palette, stringResource(R.string.gpx_appearance_customize))
            }
        }
    }
}

/** Shows or hides everything in the open folder, including its subfolders. */
@Composable
internal fun libraryFolderShowAllRow(
    folderItems: List<PhoneLibraryItem>,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    val targets = libraryFolderVisibilityTargets(folderItems, state)
    val toggle = phoneLibraryFolderToggleState(targets.map { it.second }) ?: return
    val count = phoneLibraryFolderVisibilityCount(targets.map { it.second })
    val label = stringResource(R.string.library_folder_show_all)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(
                state = toggle,
                enabled = !state.gpx.isLoading,
                onClick = { setLibraryFolderVisible(targets, toggle != ToggleableState.On, actions) },
                modifier = Modifier.testTag("library-folder-show-all").semantics { contentDescription = label },
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.library_folder_count, count.shown, count.total),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

internal fun libraryFolderVisibilityTargets(
    items: List<PhoneLibraryItem>,
    state: MapToolsPanelState,
): List<Pair<PhoneLibraryItem, Boolean>> =
    items.mapNotNull { item ->
        phoneLibraryVisibility(item, state.gpx.items, state.gpx.globalVisible, state.poi.globalVisible)
            ?.takeIf { it.available }
            ?.let { item to it.checked }
    }

internal fun setLibraryFolderVisible(
    targets: List<Pair<PhoneLibraryItem, Boolean>>,
    visible: Boolean,
    actions: MapToolsPanelActions,
) {
    targets.filter { it.second != visible }.forEach { (item, _) -> setLibraryItemVisible(item, visible, actions) }
}

/** Replaces the floating add button: adding and creating folders sit together, or Move while selecting. */
@Composable
internal fun libraryBottomBar(
    state: PhoneLibraryListState,
    actions: PhoneLibraryActions,
    browser: PhoneLibraryBrowserPresentation,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    val selection = browser.selection
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection.active) {
            Button(
                onClick = selection.onMove,
                enabled = selection.keys.orEmpty().isNotEmpty() && actions.organization.enabled,
                modifier = Modifier.weight(1f).testTag("library-selection-move"),
            ) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.library_move_to_folder))
            }
        } else {
            libraryCategoryAddAction(state.filter, state.isLoading || state.isImporting, actions, Modifier.weight(1f))
            if (state.filter.hasFolders) {
                OutlinedButton(
                    onClick = { creating = true },
                    enabled = actions.organization.enabled,
                    modifier = Modifier.weight(1f).testTag("library-new-folder"),
                ) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.library_create_folder))
                }
            }
        }
    }
    if (creating) {
        libraryCreateFolderDialog(
            onCreate = { name ->
                actions.organization.onCreateFolder(browser.browse.folderId, name, state.filter)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
}

/** Move and Select come first in a route's menu: they organise the Library rather than act on the route. */
@Composable
internal fun libraryRouteOrganizeItems(
    row: PhoneLibraryRowState,
    onClose: () -> Unit,
) {
    val item = row.item
    row.onMoveToFolder?.let { move ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_move_to_folder)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
            onClick = {
                onClose()
                move(item)
            },
            modifier = Modifier.testTag("library-gpx-move"),
        )
    }
    row.onSelect?.let { select ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_select)) },
            leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
            onClick = {
                onClose()
                select(item)
            },
            modifier = Modifier.testTag("library-gpx-select"),
        )
    }
}
