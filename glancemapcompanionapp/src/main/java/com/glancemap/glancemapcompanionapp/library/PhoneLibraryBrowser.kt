package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            )
        },
    )

internal data class PhoneLibraryBrowserPresentation(
    val browse: PhoneLibraryBrowseState,
    val navigation: PhoneLibraryBrowserActions,
    val allItems: List<PhoneLibraryItem> = emptyList(),
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
    Box(Modifier.fillMaxSize()) {
        libraryBrowserList(state, mapState, mapActions, actions, browser)
        if (state.filter != PhoneLibraryFilter.BUNDLES) {
            libraryCategoryAddAction(
                state.filter,
                state.isLoading || state.isImporting,
                actions,
                Modifier.align(Alignment.BottomEnd).padding(bottom = 12.dp),
            )
        }
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
    LazyColumn(
        Modifier.fillMaxSize().testTag("library-list"),
        // Leaves the last row reachable above the floating add button.
        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.filter == PhoneLibraryFilter.BUNDLES) {
            item(key = "offline-sections") { libraryOfflineSections(browse, navigation.onBrowse) }
        }
        item(key = "controls") {
            if (state.filter == PhoneLibraryFilter.BUNDLES) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.onFilterChanged(PhoneLibraryFilter.MAPS) }) {
                        Text(stringResource(R.string.map_tool_maps_title))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = actions.onRefresh, enabled = !state.isLoading && !state.isImporting) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.library_refresh))
                    }
                }
                librarySearch(browse.query) { navigation.onBrowse(browse.copy(query = it)) }
                libraryOfflineControls(browse, state.isImporting, actions, mapState.maps, mapActions.maps)
            } else {
                libraryCategoryControls(
                    state.filter,
                    state.isLoading || state.isImporting,
                    actions,
                    browse.query,
                    { navigation.onBrowse(browse.copy(query = it)) },
                    navigation.onCheckpoints,
                )
            }
        }
        if (state.filter == PhoneLibraryFilter.GPX) {
            item(key = "grouping") { libraryGpxGrouping(browse, navigation.onBrowse) }
        }
        if (state.filter == PhoneLibraryFilter.MAPS) {
            libraryMapSourceEntries(mapState.maps, mapActions.maps, browse.query)
        }
        item(key = "count") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (browse.folderId == null) {
                        libraryCount(state)
                    } else {
                        libraryFolderNavigation(state.organization, browse, state.filter, actions.organization) {
                            navigation.onBrowse(state.organization.parentBrowse(browse))
                        }
                    }
                }
                if (browse.folderId == null && state.filter.hasFolders) {
                    libraryFolderNavigation(state.organization, browse, state.filter, actions.organization) {}
                }
            }
            if (browse.folderId != null) libraryCount(state)
        }
        libraryFolderEntries(state, mapState, mapActions, actions, browser)
        libraryEntries(state, mapState, mapActions, actions, navigation.onDetails)
        if (state.filter == PhoneLibraryFilter.MAPS) {
            item(key = "offline-data") {
                libraryOfflineDataRow { actions.onFilterChanged(PhoneLibraryFilter.BUNDLES) }
            }
        }
    }
}
