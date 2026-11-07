package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelState

internal val phoneLibraryBrowseSaver =
    listSaver<PhoneLibraryBrowseState, String>(
        save = {
            listOf(
                it.folderId.orEmpty(),
                it.query,
                it.kind?.name.orEmpty(),
                it.activity?.name.orEmpty(),
                it.offlineSection.name,
                it.poiFile.orEmpty(),
                it.poiCategoryId?.toString().orEmpty(),
            )
        },
        restore = {
            PhoneLibraryBrowseState(
                folderId = it[0].ifEmpty { null },
                query = it[1],
                kind = PhoneLibraryGpxKind.entries.firstOrNull { kind -> kind.name == it[2] },
                activity =
                    if (it[3] == "UNSPECIFIED") {
                        PhoneLibraryActivity.HIKE
                    } else {
                        PhoneLibraryActivity.entries.firstOrNull { activity -> activity.name == it[3] }
                    },
                offlineSection =
                    PhoneLibraryOfflineSection.entries.firstOrNull { section -> section.name == it.getOrNull(4) }
                        ?: PhoneLibraryOfflineSection.AREAS,
                poiFile = it.getOrNull(5)?.ifEmpty { null },
                poiCategoryId = it.getOrNull(6)?.toIntOrNull(),
            )
        },
    )

internal data class PhoneLibraryBrowserPresentation(
    val browse: PhoneLibraryBrowseState,
    val navigation: PhoneLibraryBrowserActions,
    val allItems: List<PhoneLibraryItem> = emptyList(),
    val searchFocus: FocusRequester? = null,
    val selection: PhoneLibrarySelection = PhoneLibrarySelection(),
)

internal data class PhoneLibraryBrowserActions(
    val onBrowse: (PhoneLibraryBrowseState) -> Unit,
    val onCheckpoints: () -> Unit,
    val onDetails: (PhoneLibraryItem) -> Unit,
)

@Composable
internal fun libraryBrowserWithAdd(
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    browser: PhoneLibraryBrowserPresentation,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { libraryBrowserList(state, mapState, mapActions, actions, browser) }
        if (state.filter != PhoneLibraryFilter.BUNDLES) libraryBottomBar(state, actions, browser)
    }
}

@Composable
internal fun libraryBrowserList(
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    browser: PhoneLibraryBrowserPresentation,
) {
    val browse = browser.browse
    val navigation = browser.navigation
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    var searchWasOpen by rememberSaveable { mutableStateOf(state.searchOpen) }
    // The search row sits at the top of the list: bring it into view and focus it when it opens.
    LaunchedEffect(state.searchOpen) {
        if (state.searchOpen && !searchWasOpen) {
            listState.animateScrollToItem(librarySearchItemIndex(state.filter))
            withFrameNanos {}
            runCatching { searchFocus.requestFocus() }
        }
        searchWasOpen = state.searchOpen
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("library-list"),
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        libraryBrowserHeaderEntries(state, mapState, mapActions, actions, browser.copy(searchFocus = searchFocus))
        if (state.filter == PhoneLibraryFilter.MAPS) {
            libraryMapSourceEntries(mapState.maps, mapActions.maps, browse.query)
        }
        if (browser.selection.active) {
            item(key = "selection") { librarySelectionHeader(state.items, browser.selection) }
        }
        if (state.filter == PhoneLibraryFilter.POIS && browse.poiFile != null) {
            libraryPoiDrillEntries(state, mapState, mapActions, actions, browser)
        } else {
            libraryOpenFolderEntries(state, mapState, mapActions, actions, browser)
            libraryFolderEntries(state, mapState, mapActions, actions, browser)
            libraryEntries(state, mapState, mapActions, actions, browser)
        }
        if (state.filter == PhoneLibraryFilter.MAPS) {
            item(key = "offline-data") {
                libraryOfflineDataRow { actions.onFilterChanged(PhoneLibraryFilter.BUNDLES) }
            }
        }
    }
}

/** Bundles show their section picker and back row above the search row. */
internal fun librarySearchItemIndex(filter: PhoneLibraryFilter): Int {
    val rowsAboveSearch = if (filter == PhoneLibraryFilter.BUNDLES) 2 else 0
    return rowsAboveSearch
}

private fun LazyListScope.libraryBrowserHeaderEntries(
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    browser: PhoneLibraryBrowserPresentation,
) {
    val browse = browser.browse
    val navigation = browser.navigation
    if (state.filter == PhoneLibraryFilter.BUNDLES) {
        item(key = "offline-sections") { libraryOfflineSections(browse, navigation.onBrowse) }
    }
    if (state.filter == PhoneLibraryFilter.BUNDLES) {
        item(key = "back-to-maps") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { actions.onFilterChanged(PhoneLibraryFilter.MAPS) }) {
                    Text(stringResource(R.string.map_tool_maps_title))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = actions.onRefresh, enabled = !state.isLoading && !state.isImporting) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.library_refresh))
                }
            }
        }
    }
    if (state.searchOpen || browse.query.isNotEmpty()) {
        item(key = "search") {
            val focus = browser.searchFocus?.let { Modifier.focusRequester(it) } ?: Modifier
            librarySearch(browse.query, focus) {
                navigation.onBrowse(browse.copy(query = it))
            }
        }
    }
    if (state.filter == PhoneLibraryFilter.BUNDLES) {
        item(key = "offline-controls") {
            libraryOfflineControls(browse, state.isImporting, actions, mapState.maps, mapActions.maps)
        }
    }
    if (state.filter == PhoneLibraryFilter.GPX) {
        item(key = "grouping") { libraryGpxGrouping(browse, navigation.onBrowse) }
    }
    if (state.filter == PhoneLibraryFilter.POIS) {
        item(key = "grouping") { libraryPoiGrouping(checkpoints = false) { navigation.onCheckpoints() } }
    }
}

private fun LazyListScope.libraryOpenFolderEntries(
    state: PhoneLibraryListState,
    mapState: MapToolsPanelState,
    mapActions: MapToolsPanelActions,
    actions: PhoneLibraryActions,
    browser: PhoneLibraryBrowserPresentation,
) {
    val browse = browser.browse
    val folderId = browse.folderId
    if (folderId != null || state.message != null) {
        item(key = "folders") {
            if (folderId != null) {
                libraryFolderHeader(state.organization, folderId, actions) {
                    browser.navigation.onBrowse(state.organization.parentBrowse(browse))
                }
            }
            libraryMessage(state)
        }
    }
    if (folderId != null && !browser.selection.active) {
        item(key = "folder-show-all") {
            libraryFolderShowAllRow(state.organization.folderItems(folderId, browser.allItems), mapState, mapActions)
        }
    }
}
