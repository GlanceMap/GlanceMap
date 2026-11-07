package com.glancemap.glancemapcompanionapp.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelState
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoi
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.map.PhoneMapUserPoiStore
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The watch's POI sections: places made by the user, waypoints linked from GPX, then imported files. */
internal enum class PhoneLibraryPoiSection(
    @StringRes val label: Int,
) {
    MY_CREATION(R.string.library_poi_section_my_creation),
    GPX_WAYPOINTS(R.string.library_poi_section_waypoints),
    FILES(R.string.library_poi_section_files),
}

internal fun PhoneMapPoiSource.section(): PhoneLibraryPoiSection =
    when {
        fileName.equals(PhoneMapUserPoiStore.USER_POI_FILE_NAME, ignoreCase = true) -> PhoneLibraryPoiSection.MY_CREATION
        fileName.endsWith(GPX_WAYPOINT_POI_SUFFIX, ignoreCase = true) -> PhoneLibraryPoiSection.GPX_WAYPOINTS
        else -> PhoneLibraryPoiSection.FILES
    }

private const val GPX_WAYPOINT_POI_SUFFIX = "__waypoints.poi"

internal val PhoneMapPoiSource.isUserPlaces: Boolean
    get() = section() == PhoneLibraryPoiSection.MY_CREATION

internal fun PhoneMapPoiSource.childCategories(parentId: Int?): List<PoiSqliteCategory> =
    categories.filter { it.parentId == parentId }.sortedBy { it.name.lowercase() }

/** A category and every category below it. */
internal fun PhoneMapPoiSource.categoryTree(id: Int): Set<Int> {
    val tree = linkedSetOf(id)
    var added = true
    while (added) {
        added = tree.addAll(categories.filter { it.parentId in tree }.map(PoiSqliteCategory::id))
    }
    return tree
}

/** POIs shown on the map out of the POIs in the scope; `null` means the whole file. */
internal fun PhoneMapPoiSource.visibilityCount(categoryId: Int?): PhoneLibraryFolderVisibilityCount {
    if (categories.isEmpty()) {
        val total = poiCount ?: 0
        return PhoneLibraryFolderVisibilityCount(if (isEnabled) total else 0, total)
    }
    val scope = categoryId?.let(::categoryTree) ?: categories.map(PoiSqliteCategory::id).toSet()
    val inScope = categories.filter { it.id in scope }
    val total = inScope.sumOf(PoiSqliteCategory::directCount)
    val shown = if (isEnabled) inScope.filterNot { it.id in disabledCategoryIds }.sumOf(PoiSqliteCategory::directCount) else 0
    return PhoneLibraryFolderVisibilityCount(shown, total)
}

/** All, some or no categories of the scope are shown; a hidden file shows none. */
internal fun PhoneMapPoiSource.visibilityState(categoryId: Int?): ToggleableState {
    val scope = categoryId?.let(::categoryTree) ?: categories.map(PoiSqliteCategory::id).toSet()
    val hidden = scope.count { it in disabledCategoryIds }
    return when {
        !isEnabled || (scope.isNotEmpty() && hidden == scope.size) -> ToggleableState.Off
        hidden == 0 -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

/** Showing a category also shows its file, so switching one on always puts it on the map. */
internal fun setPoiScopeVisible(
    source: PhoneMapPoiSource,
    categoryId: Int?,
    visible: Boolean,
    actions: PhoneLibraryActions,
    mapActions: MapToolsPanelActions,
) {
    val scope = categoryId?.let(source::categoryTree) ?: source.categories.map(PoiSqliteCategory::id).toSet()
    if (categoryId == null) mapActions.onPoiSourceVisibilityChanged(source.fileName, visible)
    if (visible && !source.isEnabled) mapActions.onPoiSourceVisibilityChanged(source.fileName, true)
    if (scope.isNotEmpty() && (categoryId != null || visible)) {
        actions.onPoiCategoriesVisible(source.fileName, scope, visible)
    }
}

internal fun PhoneLibraryBrowseState.poiParent(source: PhoneMapPoiSource?): PhoneLibraryBrowseState {
    val categoryId = poiCategoryId ?: return copy(poiFile = null, poiCategoryId = null)
    val parent = source?.categories?.firstOrNull { it.id == categoryId }?.parentId
    return copy(poiCategoryId = parent)
}

/** A scope with no sub-categories lists its POIs; a file with one category goes straight to them. */
internal fun PhoneMapPoiSource.listsPois(categoryId: Int?): Boolean {
    val children = childCategories(categoryId)
    return children.isEmpty() || (categoryId == null && children.size == 1 && childCategories(children[0].id).isEmpty())
}

internal fun LazyListScope.libraryPoiDrillEntries(
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    browser: PhoneLibraryBrowserPresentation,
) {
    val browse = browser.browse
    val source = mapState.poi.sources.firstOrNull { it.fileName == browse.poiFile } ?: return
    val categoryId = browse.poiCategoryId
    item(key = "poi-header") {
        libraryPoiHeader(source, categoryId) { browser.navigation.onBrowse(browse.poiParent(source)) }
        libraryMessage(state)
    }
    item(key = "poi-show-all") { libraryPoiShowAllRow(source, categoryId, actions, mapActions) }
    if (source.listsPois(categoryId)) {
        val listed = categoryId ?: source.childCategories(null).singleOrNull()?.id
        item(key = "poi-points-$listed") { libraryPoiPoints(source, listed, actions, mapActions) }
    } else {
        items(source.childCategories(categoryId), key = { "poi-category/${it.id}" }) { category ->
            libraryPoiCategoryRow(source, category, actions, mapActions) {
                browser.navigation.onBrowse(browse.copy(poiCategoryId = category.id, query = ""))
            }
        }
    }
}

@Composable
private fun libraryPoiHeader(
    source: PhoneMapPoiSource,
    categoryId: Int?,
    onBack: () -> Unit,
) {
    val path =
        buildList {
            var current = source.categories.firstOrNull { it.id == categoryId }
            while (current != null) {
                add(0, current.name)
                current = source.categories.firstOrNull { it.id == current?.parentId }
            }
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("library-poi-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.library_folder_parent))
        }
        Text(
            (listOf(source.fileName.removeSuffix(".poi")) + path).joinToString(" / "),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun libraryPoiShowAllRow(
    source: PhoneMapPoiSource,
    categoryId: Int?,
    actions: PhoneLibraryActions,
    mapActions: MapToolsPanelActions,
) {
    val toggle = source.visibilityState(categoryId)
    val count = source.visibilityCount(categoryId)
    val label = stringResource(R.string.library_folder_show_all)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(
                state = toggle,
                onClick = { setPoiScopeVisible(source, categoryId, toggle != ToggleableState.On, actions, mapActions) },
                modifier = Modifier.testTag("library-poi-show-all").semantics { contentDescription = label },
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

@Composable
private fun libraryPoiCategoryRow(
    source: PhoneMapPoiSource,
    category: PoiSqliteCategory,
    actions: PhoneLibraryActions,
    mapActions: MapToolsPanelActions,
    onOpen: () -> Unit,
) {
    val toggle = source.visibilityState(category.id)
    val count = source.visibilityCount(category.id)
    val hasChildren = source.childCategories(category.id).isNotEmpty()
    val description = stringResource(R.string.library_show_on_map, category.name)
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("library-poi-category-${category.id}")) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val onToggle = { setPoiScopeVisible(source, category.id, toggle != ToggleableState.On, actions, mapActions) }
            val toggleModifier =
                Modifier.testTag("library-poi-category-visibility-${category.id}").semantics { contentDescription = description }
            if (hasChildren) {
                TriStateCheckbox(state = toggle, onClick = onToggle, modifier = toggleModifier)
            } else {
                Switch(checked = toggle == ToggleableState.On, onCheckedChange = { onToggle() }, modifier = toggleModifier)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (hasChildren) Icons.Filled.Folder else Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(category.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    stringResource(R.string.library_folder_count, count.shown, count.total),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun libraryPoiPoints(
    source: PhoneMapPoiSource,
    categoryId: Int?,
    actions: PhoneLibraryActions,
    mapActions: MapToolsPanelActions,
) {
    val scope = categoryId?.let(source::categoryTree) ?: source.categories.map(PoiSqliteCategory::id).toSet()
    var points by remember(source.fileName, scope, source.poiCount) { mutableStateOf<List<PhoneMapPoi>?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(source.fileName, scope, revision) {
        points =
            try {
                actions.loadCategoryPois(source.fileName, scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            points == null -> CircularProgressIndicator(Modifier.padding(12.dp))
            points.orEmpty().isEmpty() -> Text(stringResource(R.string.library_poi_none_here))
            else ->
                points.orEmpty().forEach { poi ->
                    libraryPoiPointRow(poi, source.isUserPlaces, actions, { revision++ }) { mapActions.onShowPoi(poi) }
                }
        }
    }
}

@Composable
private fun libraryPoiPointRow(
    poi: PhoneMapPoi,
    editable: Boolean,
    actions: PhoneLibraryActions,
    onChanged: () -> Unit,
    onShow: () -> Unit,
) {
    Card(onClick = onShow, modifier = Modifier.fillMaxWidth().testTag("library-poi-point-${poi.id}")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    poi.label ?: stringResource(R.string.library_poi_unnamed),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                poi.details?.typeLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (editable) libraryUserPoiMenu(poi, actions, onChanged)
        }
    }
}

@Composable
private fun libraryUserPoiMenu(
    poi: PhoneMapPoi,
    actions: PhoneLibraryActions,
    onChanged: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by rememberSaveable(poi.id) { mutableStateOf(false) }
    var deleting by rememberSaveable(poi.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("library-poi-point-menu-${poi.id}")) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.library_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_content_action_rename)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    renaming = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_content_action_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    deleting = true
                },
            )
        }
    }
    if (renaming) {
        libraryRenameTextDialog(poi.label.orEmpty(), onDismiss = { renaming = false }) { name ->
            scope.launch {
                runCatching { actions.onRenameUserPoi(poi, name) }
                onChanged()
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.map_content_delete_title)) },
            text = { Text(stringResource(R.string.map_content_delete_message, poi.label.orEmpty())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        scope.launch {
                            runCatching { actions.onDeleteUserPoi(poi) }
                            onChanged()
                        }
                    },
                    modifier = Modifier.testTag("library-poi-point-delete-confirm"),
                ) { Text(stringResource(R.string.map_content_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

@Composable
private fun libraryRenameTextDialog(
    initial: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_content_rename_title)) },
        text = {
            OutlinedTextField(
                name,
                onValueChange = { name = it.take(80) },
                singleLine = true,
                modifier = Modifier.testTag("library-poi-point-rename-name"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRename(name.trim())
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

/** A POI file's ⋮: organise it like a route, or rename and delete the file. */
@Composable
internal fun libraryPoiFileMenu(
    row: PhoneLibraryRowState,
    source: PhoneMapPoiSource,
    mapActions: MapToolsPanelActions,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = row.enabled,
            modifier = Modifier.testTag("library-poi-menu-${row.item.key}"),
        ) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.library_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            row.onMoveToFolder?.let { move ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_move_to_folder)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                    onClick = {
                        expanded = false
                        move(row.item)
                    },
                    modifier = Modifier.testTag("library-poi-move"),
                )
            }
            if (!source.isUserPlaces) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.map_content_action_rename)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        mapActions.onRenamePoiSource(source)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_content_action_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    mapActions.onDeletePoiSource(source)
                },
            )
        }
    }
}
