package com.glancemap.glancemapcompanionapp.library

import androidx.annotation.StringRes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelState
import com.glancemap.glancemapcompanionapp.map.PhoneMapSource

@Composable
internal fun libraryItemRow(
    row: PhoneLibraryRowState,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
    onDetails: () -> Unit,
) {
    val item = row.item
    val visibility = phoneLibraryVisibility(item, state.gpx.items, state.gpx.globalVisible, state.poi.globalVisible)
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = row.enabled,
                    onClick = {
                        if (item.gpxKind() == PhoneLibraryGpxKind.RECORDING) {
                            onDetails()
                        } else if (item.isNavigableGpx() && row.onPreviewGpx != null) {
                            row.onPreviewGpx.invoke(item)
                        } else {
                            onDetails()
                        }
                    },
                    onLongClick =
                        row.onOpenGpxInNewHikeTab
                            ?.takeIf { item.isNavigableGpx() }
                            ?.let { openInNewHikeTab -> { openInNewHikeTab(item) } },
                ).testTag("library-item-${item.key}"),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(item.icon(row.organization), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                libraryRowText(row, visibility, Modifier.weight(1f))
                libraryRowTrailing(row, visibility, state, actions, onDetails)
            }
            libraryRowActions(row, visibility, state, actions)
        }
    }
}

@Composable
private fun libraryRowText(
    row: PhoneLibraryRowState,
    visibility: PhoneLibraryVisibility?,
    modifier: Modifier,
) {
    val item = row.item
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(libraryItemSummary(item), style = MaterialTheme.typography.bodySmall)
        if (item is PhoneLibraryItem.Bundle) libraryOfflineCapabilities(item)
        if (item is PhoneLibraryItem.OfflineMap) libraryMapCoverageLabels(item.coverage)
        libraryItemStatus(item, visibility)?.let { status ->
            Text(stringResource(status), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Routes and POIs stay quiet unless something stops them showing on the map. */
@StringRes
internal fun libraryItemStatus(
    item: PhoneLibraryItem,
    visibility: PhoneLibraryVisibility?,
): Int? =
    when {
        visibility?.hiddenByLayers == true -> R.string.library_hidden_by_layers
        visibility?.available == false -> R.string.library_unavailable_on_map
        visibility != null || item is PhoneLibraryItem.ReceivedGpx -> null
        item is PhoneLibraryItem.Bundle && !item.isOfflineReady() -> R.string.library_bundle_attention
        item is PhoneLibraryItem.Bundle -> R.string.library_bundle_ready
        item is PhoneLibraryItem.OfflineData && !item.isUsable -> R.string.library_offline_invalid
        item is PhoneLibraryItem.OfflineData -> R.string.library_offline_ready
        else -> R.string.library_on_phone
    }

@Composable
private fun libraryRowTrailing(
    row: PhoneLibraryRowState,
    visibility: PhoneLibraryVisibility?,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
    onDetails: () -> Unit,
) {
    val item = row.item
    when {
        item is PhoneLibraryItem.Gpx || item is PhoneLibraryItem.FolderGpx ->
            libraryGpxOverflowMenu(row, actions, onDetails)
        item is PhoneLibraryItem.Pois || item is PhoneLibraryItem.ReceivedGpx ->
            IconButton(onClick = onDetails, enabled = row.enabled) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.hike_route_details))
            }
        visibility == null -> {
            if (item is PhoneLibraryItem.OfflineMap) {
                libraryMapDataButton(item, row.enabled, state.maps, actions.maps)
            }
            libraryMapControl(row, state, actions)
        }
    }
}

internal fun PhoneLibraryItem.isNavigableGpx(): Boolean =
    when (this) {
        is PhoneLibraryItem.Gpx, is PhoneLibraryItem.FolderGpx, is PhoneLibraryItem.ReceivedGpx -> true
        else -> false
    }

@Composable
private fun libraryMapControl(
    row: PhoneLibraryRowState,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    val item = row.item
    if (item is PhoneLibraryItem.OfflineMap) {
        val description = stringResource(R.string.library_use_map, item.title)
        val activeMap = (state.maps.source as? PhoneMapSource.Offline)?.map
        RadioButton(
            selected = activeMap?.selectionIdentity == item.map.selectionIdentity,
            enabled = row.enabled && !state.maps.isLoading,
            onClick = { actions.maps.onSelectOffline(item.map) },
            modifier = Modifier.testTag("library-map-${item.key}").semantics { contentDescription = description },
        )
    } else {
        Icon(Icons.Filled.ChevronRight, contentDescription = null)
    }
}

private fun PhoneLibraryItem.icon(organization: PhoneLibraryOrganization): ImageVector =
    if (filter == PhoneLibraryFilter.GPX && organization.entry(this).activity == PhoneLibraryActivity.BIKE) {
        Icons.Filled.DirectionsBike
    } else if (filter == PhoneLibraryFilter.GPX && organization.entry(this).activity == PhoneLibraryActivity.HIKE) {
        Icons.Filled.Hiking
    } else {
        when (this) {
            is PhoneLibraryItem.Gpx -> if (route.isRecording) Icons.Filled.Timeline else Icons.Filled.Route
            is PhoneLibraryItem.FolderGpx -> Icons.Filled.Route
            is PhoneLibraryItem.ReceivedGpx -> Icons.Filled.Description
            is PhoneLibraryItem.Pois -> Icons.Filled.Place
            is PhoneLibraryItem.OfflineMap -> Icons.Filled.Map
            is PhoneLibraryItem.Bundle -> Icons.Filled.Inventory2
            is PhoneLibraryItem.OfflineData ->
                if (section == PhoneLibraryOfflineSection.ELEVATION) {
                    Icons.Filled.Landscape
                } else {
                    Icons.AutoMirrored.Filled.AltRoute
                }
        }
    }
