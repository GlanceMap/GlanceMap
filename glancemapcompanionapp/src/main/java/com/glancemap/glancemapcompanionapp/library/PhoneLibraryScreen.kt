package com.glancemap.glancemapcompanionapp.library

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelState
import com.glancemap.glancemapcompanionapp.map.formatPhoneMapDuration
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryUiState
import com.glancemap.glancemapcompanionapp.routes.isFromWatch
import kotlin.math.roundToInt

@Composable
internal fun phoneLibraryScreen(
    routes: RouteLibraryUiState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    filter: PhoneLibraryFilter,
    searchOpen: Boolean = false,
    checkpointsContent: @Composable () -> Unit = {},
) {
    val model: PhoneLibraryViewModel = viewModel()
    val local by model.uiState.collectAsState()
    val items =
        remember(routes.routes, local.items, mapState.gpx.items, mapState.poi.sources, mapState.maps.offlineMaps) {
            val liveMaps = libraryOfflineMapItems(mapState.maps.offlineMaps, local.items)
            phoneLibraryItems(
                routes.routes,
                local.items.filterNot { it is PhoneLibraryItem.OfflineMap } + liveMaps,
                mapState.poi.sources,
                mapState.gpx.items,
            )
        }
    LaunchedEffect(
        mapState.maps.elevationFolderSync,
        mapState.maps.routingFolderSync,
        mapState.maps.offlineMapAvailability,
        mapState.maps.offlineDataVersion,
        mapState.maps.offlineMaps.map { it.rendererIdentity },
        mapState.general.storageLocation,
    ) { model.refresh() }
    libraryPanelContent(
        checkpointsContent = checkpointsContent,
        state =
            PhoneLibraryListState(
                items = items,
                filter = filter,
                isLoading = routes.isLoading || local.isLoading,
                isImporting = routes.isImporting || local.isImporting,
                message = routes.message ?: local.message,
                organization = local.organization,
                searchOpen = searchOpen,
            ),
        mapState = mapState,
        mapActions = mapActions,
        actions =
            actions.copy(
                organization =
                    PhoneLibraryOrganizationActions(
                        enabled = local.organizationReady,
                        onCreateFolder = model::createFolder,
                        onRenameFolder = { id, name -> model.updateOrganization { it.renameFolder(id, name) } },
                        onDeleteFolder = { id -> model.updateOrganization { it.deleteFolder(id) } },
                        onDeleteReceivedGpx = { deletePhoneLibraryReceivedGpx(it, model::refresh) },
                        onMoveItem = model::moveItem,
                        onActivity = model::setActivity,
                    ),
                onRefresh = {
                    model.refresh()
                    actions.onRefresh()
                    if (mapState.gpx.hasSelectedFolder) mapActions.onRescanGpxFolder()
                    if (mapState.maps.hasSelectedFolder) mapActions.maps.onRescanFolder()
                },
                onImportOfflineFiles = { uris, section ->
                    model.importOfflineFiles(uris, section, mapActions.maps.onOfflineDataChanged)
                },
            ),
    )
}

@Composable
internal fun libraryPanelContent(
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    checkpointsContent: @Composable () -> Unit = {},
) {
    var browse by rememberSaveable(stateSaver = phoneLibraryBrowseSaver) { mutableStateOf(PhoneLibraryBrowseState()) }
    var checkpointsOpen by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var moveKeys by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var selectedKeys by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var appearanceTarget by remember { mutableStateOf<PhoneLibraryGpxAppearanceTarget?>(null) }
    val appearanceActions =
        actions.copy(
            onGpxAppearance = { item ->
                selectedKey = null
                appearanceTarget = PhoneLibraryGpxAppearanceTarget(item.organizationKey(), item.title, false)
            },
            onFolderGpxAppearance = { folder ->
                appearanceTarget = PhoneLibraryGpxAppearanceTarget(folder.id, folder.name, true)
            },
        )
    val addingActions =
        libraryAddingActions(appearanceActions) {
            browse = PhoneLibraryBrowseState(offlineSection = browse.offlineSection)
        }
    val visible =
        remember(state.items, state.filter, browse, state.organization) {
            browsePhoneLibraryItems(state.items, state.filter, browse, state.organization)
        }
    LaunchedEffect(state.filter, state.organization) {
        if (state.organization.folder(browse.folderId)?.category != state.filter) {
            browse = browse.copy(folderId = null)
        }
    }
    val openPoiSource = mapState.poi.sources.firstOrNull { it.fileName == browse.poiFile }
    LaunchedEffect(state.filter, openPoiSource == null) {
        if (state.filter != PhoneLibraryFilter.POIS || openPoiSource == null) {
            browse = browse.copy(poiFile = null, poiCategoryId = null)
        }
    }
    BackHandler(enabled = browse.poiFile != null) { browse = browse.poiParent(openPoiSource) }
    LaunchedEffect(state.searchOpen) {
        if (!state.searchOpen) browse = browse.copy(query = "")
    }
    BackHandler(enabled = state.filter == PhoneLibraryFilter.BUNDLES && browse.folderId == null) {
        actions.onFilterChanged(PhoneLibraryFilter.MAPS)
    }
    BackHandler(enabled = browse.folderId != null) { browse = state.organization.parentBrowse(browse) }
    BackHandler(enabled = checkpointsOpen && state.filter == PhoneLibraryFilter.POIS) { checkpointsOpen = false }
    BackHandler(enabled = selectedKeys != null) { selectedKeys = null }
    LaunchedEffect(state.filter) { selectedKeys = null }
    val selection =
        PhoneLibrarySelection(
            keys = selectedKeys?.toSet(),
            onToggle = { item -> selectedKeys = selectedKeys?.toSet().toggled(item.key).toList() },
            onSelectAll = { items -> selectedKeys = items.map(PhoneLibraryItem::key) },
            onClear = { selectedKeys = null },
            onMove = {
                moveKeys = selectedKeys
                selectedKeys = null
            },
        )
    val rowActions =
        addingActions.copy(
            onMoveItem = { item -> moveKeys = listOf(item.key) },
            onSelectItem = { item -> selectedKeys = listOf(item.key) },
        )
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (checkpointsOpen && state.filter == PhoneLibraryFilter.POIS) {
            libraryPoiGrouping(checkpoints = true, Modifier.padding(vertical = 8.dp)) { checkpointsOpen = false }
            Box(Modifier.weight(1f)) { checkpointsContent() }
        } else {
            libraryBrowserWithAdd(
                state.copy(items = visible),
                mapState,
                mapActions,
                rowActions,
                PhoneLibraryBrowserPresentation(
                    browse,
                    PhoneLibraryBrowserActions({ browse = it }, { checkpointsOpen = true }) { item ->
                        // A POI file opens like a folder, onto its categories.
                        if (item is PhoneLibraryItem.Pois) {
                            browse = browse.copy(poiFile = item.source.fileName, poiCategoryId = null, query = "")
                        } else {
                            selectedKey = item.key
                        }
                    },
                    state.items,
                    selection = selection,
                ),
            )
        }
    }
    state.items.firstOrNull { it.key == selectedKey }?.let { item ->
        libraryItemDetails(
            item = item,
            actions =
                appearanceActions.copy(onMoveItem = { selected ->
                    selectedKey = null
                    moveKeys = listOf(selected.key)
                }),
            onOpenOfflineMap = mapActions.maps.onSelectOffline,
            onDismiss = { selectedKey = null },
            organization = state.organization,
        )
    }
    moveKeys?.let { keys ->
        val moving = state.items.filter { it.key in keys }
        if (moving.isNotEmpty()) libraryMoveDialog(moving, state.organization, actions.organization) { moveKeys = null }
    }
    appearanceTarget?.let { target ->
        libraryGpxAppearanceDialog(target, state.organization, mapState.gpx, mapActions) { appearanceTarget = null }
    }
}

private fun libraryAddingActions(
    actions: PhoneLibraryActions,
    onAdd: () -> Unit,
): PhoneLibraryActions =
    actions.copy(
        onImportGpx = { uri, recorded, exportId ->
            onAdd()
            actions.onImportGpx(uri, recorded, exportId)
        },
        onMapAction = { action ->
            onAdd()
            actions.onMapAction(action)
        },
        onImportOfflineFiles = { uris, section ->
            onAdd()
            actions.onImportOfflineFiles(uris, section)
        },
    )

internal fun LazyListScope.libraryEntries(
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    browser: PhoneLibraryBrowserPresentation,
) {
    val selection = browser.selection
    if (state.items.isEmpty()) {
        item(key = "empty") {
            if (state.isLoading || (state.filter == PhoneLibraryFilter.GPX && mapState.gpx.isLoading)) {
                CircularProgressIndicator()
            } else {
                Text(stringResource(R.string.library_entries_empty), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.library_entries_empty_description))
            }
        }
    }
    val sections =
        if (state.filter == PhoneLibraryFilter.POIS) {
            state.items
                .groupBy { (it as? PhoneLibraryItem.Pois)?.source?.section() ?: PhoneLibraryPoiSection.FILES }
                .toSortedMap(compareBy(PhoneLibraryPoiSection::ordinal))
        } else {
            sortedMapOf(compareBy(PhoneLibraryPoiSection::ordinal), PhoneLibraryPoiSection.FILES to state.items)
        }
    sections.forEach { (section, sectionItems) ->
        if (state.filter == PhoneLibraryFilter.POIS) {
            item(key = "poi-section/${section.name}") {
                Text(stringResource(section.label), style = MaterialTheme.typography.labelLarge)
            }
        }
        libraryEntryRows(sectionItems, state, mapState, mapActions, actions, browser)
    }
}

private fun LazyListScope.libraryEntryRows(
    rows: List<PhoneLibraryItem>,
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    browser: PhoneLibraryBrowserPresentation,
) {
    val selection = browser.selection
    items(rows, key = PhoneLibraryItem::key) { item ->
        libraryItemRow(
            PhoneLibraryRowState(
                item = item,
                // The offline data rescan can take a while; map toggles do not depend on it.
                enabled = !state.isImporting,
                organization = state.organization,
                guidanceEnabled = !actions.guidanceActive && actions.guidanceStartingKey == null,
                guidanceLoading = actions.guidanceStartingKey == item.key,
                selected = selection.keys?.let { item.key in it }?.takeIf { item.filter == PhoneLibraryFilter.GPX },
                onToggleSelected = selection.onToggle,
                onSelect = actions.onSelectItem,
                onMoveToFolder = actions.onMoveItem.takeIf { item.filter.hasFolders },
                watchKnown = actions.watchKnown,
                routeUri = actions.routeUri,
                onStartGuidance = actions.onStartGuidance,
                onSendGpx = actions.onSendGpx,
                onPreviewGpx = actions.onPreviewGpx,
                onOpenGpxInNewHikeTab = actions.onOpenGpxInNewHikeTab,
                onRenameRoute = actions.onRenameRoute,
                onDeleteRoute = actions.onDeleteRoute,
            ),
            mapState,
            mapActions,
        ) { browser.navigation.onDetails(item) }
    }
}

@Composable
internal fun libraryMessage(state: PhoneLibraryListState) {
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun librarySearch(
    query: String,
    modifier: Modifier = Modifier,
    onQuery: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth().testTag("library-search"),
        placeholder = { Text(stringResource(R.string.library_search)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.library_clear_search))
                }
            }
        },
        singleLine = true,
    )
}

@Composable
internal fun libraryItemSummary(item: PhoneLibraryItem): String =
    when (item) {
        is PhoneLibraryItem.Gpx -> libraryRouteSummary(item.route)
        is PhoneLibraryItem.FolderGpx ->
            stringResource(R.string.library_folder_gpx_summary, item.item.track.points.size)
        is PhoneLibraryItem.ReceivedGpx ->
            stringResource(
                R.string.library_received_summary,
                Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes),
            )
        is PhoneLibraryItem.Pois ->
            if (item.source.isReadable) {
                val count = item.source.visibilityCount(null)
                stringResource(R.string.library_folder_count, count.shown, count.total)
            } else {
                stringResource(R.string.library_poi_unreadable)
            }
        is PhoneLibraryItem.OfflineMap ->
            item.sizeBytes?.let {
                stringResource(
                    R.string.library_offline_summary,
                    Formatter.formatShortFileSize(LocalContext.current, it),
                )
            } ?: stringResource(R.string.map_source_offline_label)
        is PhoneLibraryItem.Bundle ->
            stringResource(R.string.library_bundle_summary)
        is PhoneLibraryItem.OfflineData ->
            stringResource(
                R.string.library_offline_component,
                stringResource(item.section.label),
                Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes),
            )
    }

@Composable
private fun libraryRouteSummary(route: RouteLibraryRoute): String {
    val stats = route.summary
    val distance =
        stringResource(
            R.string.library_route_summary,
            stats.distanceMeters / 1000.0,
            stats.elevationGainMeters.roundToInt(),
            stats.elevationLossMeters.roundToInt(),
        )
    // A recording's estimate is not how long it took, so only planned routes show one.
    val summary =
        when {
            route.isRecording ->
                stringResource(R.string.library_kind_summary, stringResource(R.string.library_item_recording), distance)
            stats.estimatedDurationSeconds > 0.0 ->
                stringResource(
                    R.string.library_kind_summary,
                    distance,
                    stringResource(
                        R.string.library_route_estimated_time,
                        formatPhoneMapDuration(stats.estimatedDurationSeconds),
                    ),
                )
            else -> distance
        }
    return if (route.isFromWatch) stringResource(R.string.library_from_watch_summary, summary) else summary
}
