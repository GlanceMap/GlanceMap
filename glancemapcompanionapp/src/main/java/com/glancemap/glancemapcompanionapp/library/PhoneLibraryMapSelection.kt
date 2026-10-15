package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsActions
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsState
import com.glancemap.glancemapcompanionapp.map.PhoneMapSource
import com.glancemap.glancemapcompanionapp.map.mapToolsOnlineSourcePicker

@Composable
internal fun libraryOnlineMapSelection(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    Column {
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        libraryMapSourceToggle(state, actions)
        if (state.source == PhoneMapSource.Online) {
            mapToolsOnlineSourcePicker(state.onlineSources, state.selectedOnlineSource, actions.onSelectOnline)
        }
        Text(stringResource(R.string.library_offline_maps))
    }
}

internal fun libraryOfflineMapToActivate(state: MapToolsMapsState) =
    state.preferredOfflineMapName?.let { name -> state.offlineMaps.firstOrNull { it.displayName == name } }
        ?: state.offlineMaps.firstOrNull()

@Composable
private fun libraryMapSourceToggle(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
) {
    val offline = state.source as? PhoneMapSource.Offline
    val selected = libraryOfflineMapToActivate(state)
    val sourceAvailable = if (offline != null) state.onlineSources.isNotEmpty() else selected != null
    val description = stringResource(R.string.library_map_offline_toggle)
    ListItem(
        headlineContent = { Text(stringResource(R.string.map_source_selector_title)) },
        supportingContent = {
            Text(
                if (offline == null) {
                    stringResource(R.string.map_source_online)
                } else {
                    stringResource(R.string.map_source_offline, offline.map.displayName)
                },
            )
        },
        trailingContent = {
            Switch(
                checked = offline != null,
                enabled = !state.isLoading && sourceAvailable,
                onCheckedChange = { useOffline ->
                    if (useOffline) selected?.let(actions.onSelectOffline) else actions.onSwitchToOnline()
                },
                modifier = Modifier.testTag("library-map-source-toggle").semantics { contentDescription = description },
            )
        },
    )
    if (offline == null && state.offlineMaps.isEmpty() && !state.isLoading) {
        Text(stringResource(R.string.map_source_no_offline_maps))
    }
}
