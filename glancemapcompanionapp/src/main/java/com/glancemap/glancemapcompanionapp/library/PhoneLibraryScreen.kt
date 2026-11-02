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
    var moveKey by rememberSaveable { mutableStateOf<String?>(null) }
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
    BackHandler(enabled = state.filter == PhoneLibraryFilter.BUNDLES && browse.folderId == null) {
        actions.onFilterChanged(PhoneLibraryFilter.MAPS)
    }
    BackHandler(enabled = browse.folderId != null) { browse = state.organization.parentBrowse(browse) }
    BackHandler(enabled = checkpointsOpen && state.filter == PhoneLibraryFilter.POIS) { checkpointsOpen = false }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (checkpointsOpen && state.filter == PhoneLibraryFilter.POIS) {
            TextButton(onClick = { checkpointsOpen = false }) { Text(stringResource(R.string.hike_back_pois)) }
            Box(Modifier.weight(1f)) { checkpointsContent() }
        } else {
            libraryBrowserWithAdd(
                state.copy(items = visible),
                mapState,
                mapActions,
                addingActions,
                PhoneLibraryBrowserPresentation(
                    browse,
                    PhoneLibraryBrowserActions({ browse = it }, { checkpointsOpen = true }, { selectedKey = it.key }),
                    state.items,
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
                    moveKey = selected.key
                }),
            onOpenOfflineMap = mapActions.maps.onSelectOffline,
            onDismiss = { selectedKey = null },
            organization = state.organization,
        )
    }
    state.items.firstOrNull { it.key == moveKey }?.let { item ->
        libraryMoveDialog(item, state.organization, actions.organization) { moveKey = null }
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
    onDetails: (PhoneLibraryItem) -> Unit,
) {
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
    items(state.items, key = PhoneLibraryItem::key) { item ->
        libraryItemRow(
            PhoneLibraryRowState(
                item = item,
                // The offline data rescan can take a while; map toggles do not depend on it.
                enabled = !state.isImporting,
                organization = state.organization,
                guidanceEnabled = !actions.guidanceActive && actions.guidanceStartingKey == null,
                guidanceLoading = actions.guidanceStartingKey == item.key,
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
        ) { onDetails(item) }
    }
}

@Composable
internal fun libraryCount(state: PhoneLibraryListState) {
    Column {
        Text(stringResource(R.string.library_local_count, state.items.size), style = MaterialTheme.typography.bodySmall)
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun librarySearch(
    query: String,
    onQuery: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().testTag("library-search"),
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
                item.source.poiCount?.let { stringResource(R.string.library_poi_count, it) }
                    ?: stringResource(R.string.library_poi_collection)
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
    val distance = route.summary.distanceMeters / 1000.0
    val gain = route.summary.elevationGainMeters.roundToInt()
    val summary =
        if (route.isRecording) {
            val kind = stringResource(R.string.library_item_recording)
            stringResource(R.string.library_gpx_summary, kind, distance, gain)
        } else {
            stringResource(R.string.library_route_summary, distance, gain)
        }
    return if (route.isFromWatch) stringResource(R.string.library_from_watch_summary, summary) else summary
}
