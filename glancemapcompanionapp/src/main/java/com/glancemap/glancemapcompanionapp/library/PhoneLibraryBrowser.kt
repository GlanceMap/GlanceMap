package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
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
    val searchOpen: Boolean,
    val navigation: PhoneLibraryBrowserActions,
)

internal data class PhoneLibraryBrowserActions(
    val onBrowse: (PhoneLibraryBrowseState) -> Unit,
    val onSearch: () -> Unit,
    val onDetails: (PhoneLibraryItem) -> Unit,
)

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
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "folder-navigation") {
            libraryFolderNavigation(state.organization, browse, actions.organization) {
                navigation.onBrowse(state.organization.parentBrowse(browse))
            }
        }
        if (state.filter == PhoneLibraryFilter.BUNDLES) {
            item(key = "offline-sections") { libraryOfflineSections(browse, navigation.onBrowse) }
        }
        item(key = "controls") {
            val trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                IconButton(onClick = navigation.onSearch, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Search, stringResource(R.string.library_search))
                }
                IconButton(
                    onClick = actions.onRefresh,
                    enabled = !state.isLoading && !state.isImporting,
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Filled.Refresh, stringResource(R.string.library_refresh)) }
            }
            if (state.filter == PhoneLibraryFilter.BUNDLES) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = trailing)
                libraryOfflineControls(browse, state.isImporting, actions, mapState.maps, mapActions.maps)
            } else {
                libraryCategoryControls(state.filter, state.isImporting, actions, trailing)
            }
        }
        if (browser.searchOpen || browse.query.isNotEmpty()) {
            item(key = "search") { librarySearch(browse.query) { navigation.onBrowse(browse.copy(query = it)) } }
        }
        if (state.filter == PhoneLibraryFilter.GPX) {
            item(key = "grouping") { libraryGpxGrouping(browse, navigation.onBrowse) }
        }
        if (state.filter == PhoneLibraryFilter.MAPS) {
            item(key = "online-map") { libraryOnlineMapSelection(mapState.maps, mapActions.maps) }
        }
        item(key = "count") { libraryCount(state) }
        val folders =
            state.organization.folders
                .filter { it.parentId == browse.folderId && browse.query.isBlank() }
                .sortedBy { it.name }
        items(folders, key = { "folder/${it.id}" }) { folder ->
            libraryFolderRow(
                folder,
                onAppearance =
                    if (state.filter == PhoneLibraryFilter.GPX) ({ actions.onFolderGpxAppearance(folder) }) else null,
            ) { navigation.onBrowse(browse.copy(folderId = folder.id, query = "")) }
        }
        libraryEntries(state, mapState, mapActions, actions, navigation.onDetails)
    }
}
