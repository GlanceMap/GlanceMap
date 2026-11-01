package com.glancemap.glancemapcompanionapp.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsActions
import com.glancemap.glancemapcompanionapp.map.MapToolsMapsState
import com.glancemap.glancemapcompanionapp.map.PhoneMapRendererCatalog
import com.glancemap.glancemapcompanionapp.map.PhoneMapSource
import com.glancemap.glancemapcompanionapp.map.PhoneOnlineMapSource
import com.glancemap.glancemapcompanionapp.map.phoneMap3dCapabilityIcon

internal fun LazyListScope.libraryMapSourceEntries(
    state: MapToolsMapsState,
    actions: MapToolsMapsActions,
    query: String,
) {
    item(key = "active-map") { libraryActiveMapCard(state) }
    val onlineSources =
        state.onlineSources.filter {
            PhoneMapRendererCatalog.onlineSourceLabel(it).contains(query.trim(), ignoreCase = true)
        }
    if (onlineSources.isNotEmpty()) {
        item(key = "online-maps") {
            libraryMapSectionHeader(R.string.library_online_maps, R.string.library_online_maps_hint)
        }
    }
    items(onlineSources, key = { "online/${it.name}" }) { source ->
        libraryOnlineMapRow(
            source = source,
            selected = state.source == PhoneMapSource.Online && state.selectedOnlineSource == source,
            enabled = !state.isLoading,
            onSelect = actions.onSelectOnline,
        )
    }
    item(key = "offline-maps") {
        libraryMapSectionHeader(R.string.library_offline_maps, R.string.library_offline_maps_hint)
        if (state.offlineMaps.isEmpty() && !state.isLoading) {
            Text(stringResource(R.string.map_source_no_offline_maps))
        }
    }
}

@Composable
private fun libraryActiveMapCard(state: MapToolsMapsState) {
    val offline = state.source as? PhoneMapSource.Offline
    Card(Modifier.fillMaxWidth().testTag("library-active-map")) {
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.library_map_using_now), style = MaterialTheme.typography.labelMedium)
            Text(
                offline?.map?.displayName ?: PhoneMapRendererCatalog.onlineSourceLabel(state.selectedOnlineSource),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(if (offline == null) R.string.map_source_online else R.string.library_map_mode_offline),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun libraryMapSectionHeader(
    @StringRes title: Int,
    @StringRes hint: Int,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun libraryOnlineMapRow(
    source: PhoneOnlineMapSource,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (PhoneOnlineMapSource) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(selected, enabled, Role.RadioButton) { onSelect(source) }
                    .testTag("library-online-${source.name}")
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, enabled = enabled, onClick = null)
            Text(
                PhoneMapRendererCatalog.onlineSourceLabel(source),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            phoneMap3dCapabilityIcon()
        }
    }
}

@Composable
internal fun libraryOfflineDataRow(onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("library-offline-data")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.library_filter_bundles),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}
