package com.glancemap.glancemapcompanionapp.library

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
    val guidanceDescription =
        stringResource(
            if (row.guidanceLoading) R.string.phone_guidance_starting_cd else R.string.phone_guidance_start_cd,
            item.title,
        )
    val visibility = phoneLibraryVisibility(item, state.gpx.items, state.gpx.globalVisible, state.poi.globalVisible)
    Card(
        onClick = { if (item.isNavigableGpx() && row.onPreviewGpx != null) row.onPreviewGpx.invoke(item) else onDetails() },
        enabled = row.enabled,
        modifier = Modifier.fillMaxWidth().testTag("library-item-${item.key}"),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(item.icon(row.organization), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(libraryItemSummary(item), style = MaterialTheme.typography.bodySmall)
                if (item is PhoneLibraryItem.Bundle) libraryOfflineCapabilities(item)
                if (item is PhoneLibraryItem.OfflineMap) libraryMapCoverageLabels(item.coverage)
                if (item.filter == PhoneLibraryFilter.GPX) {
                    val activity = row.organization.entry(item).activity ?: PhoneLibraryActivity.HIKE
                    Text(stringResource(activity.label), style = MaterialTheme.typography.labelSmall)
                }
                val status =
                    when {
                        visibility?.hiddenByLayers == true -> R.string.library_hidden_by_layers
                        visibility?.available == false -> R.string.library_unavailable_on_map
                        item is PhoneLibraryItem.Bundle && !item.isOfflineReady() -> R.string.library_bundle_attention
                        item is PhoneLibraryItem.Bundle -> R.string.library_bundle_ready
                        item is PhoneLibraryItem.OfflineData && !item.isUsable -> R.string.library_offline_invalid
                        item is PhoneLibraryItem.OfflineData -> R.string.library_offline_ready
                        else -> R.string.library_on_phone
                    }
                Text(stringResource(status), style = MaterialTheme.typography.labelSmall)
            }
            libraryMapControl(row, visibility, state, actions)
            if (item.isNavigableGpx()) {
                IconButton(onClick = onDetails, enabled = row.enabled, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.hike_route_details))
                }
                IconButton(
                    onClick = { row.onStartGuidance(item) },
                    enabled = row.enabled && row.guidanceEnabled && !row.guidanceLoading,
                    modifier =
                        Modifier.size(40.dp).testTag("library-guidance-${item.key}").semantics {
                            contentDescription = guidanceDescription
                        },
                ) {
                    if (row.guidanceLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.Navigation,
                            contentDescription = stringResource(R.string.phone_guidance_start),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun PhoneLibraryItem.isNavigableGpx(): Boolean = this is PhoneLibraryItem.Gpx || this is PhoneLibraryItem.FolderGpx || this is PhoneLibraryItem.ReceivedGpx

@Composable
private fun libraryMapControl(
    row: PhoneLibraryRowState,
    visibility: PhoneLibraryVisibility?,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    val item = row.item
    if (visibility != null) {
        val description = stringResource(R.string.library_show_on_map, item.title)
        Switch(
            checked = visibility.checked,
            enabled = row.enabled && visibility.available && (item is PhoneLibraryItem.Pois || !state.gpx.isLoading),
            onCheckedChange = { visible -> setLibraryItemVisible(item, visible, actions) },
            modifier =
                Modifier.testTag("library-visibility-${item.key}").semantics { contentDescription = description },
        )
    } else if (item is PhoneLibraryItem.OfflineMap) {
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

private fun setLibraryItemVisible(
    item: PhoneLibraryItem,
    visible: Boolean,
    actions: MapToolsPanelActions,
) {
    when (item) {
        is PhoneLibraryItem.Gpx -> actions.onGpxItemToggled(item.route.id)
        is PhoneLibraryItem.FolderGpx -> actions.onGpxItemToggled(item.item.id)
        is PhoneLibraryItem.Pois -> actions.onPoiSourceVisibilityChanged(item.source.fileName, visible)
        else -> Unit
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
