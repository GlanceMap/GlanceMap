package com.glancemap.glancemapcompanionapp.library

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapLibraryAction
import com.glancemap.glancemapcompanionapp.map.MapTool
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap

@Composable
internal fun libraryItemDetails(
    item: PhoneLibraryItem,
    actions: PhoneLibraryActions,
    onOpenOfflineMap: (PhoneOfflineMap) -> Unit,
    onDismiss: () -> Unit,
    organization: PhoneLibraryOrganization = PhoneLibraryOrganization(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item.filter == PhoneLibraryFilter.GPX) {
                    libraryActivityDetails(item)
                } else {
                    Text(libraryItemSummary(item))
                }
                if (item.filter == PhoneLibraryFilter.GPX) {
                    actions.onPreviewGpx?.takeIf { item.gpxKind() == PhoneLibraryGpxKind.RECORDING }?.let { preview ->
                        OutlinedButton(onClick = {
                            onDismiss()
                            preview(item)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.activity_preview_map))
                        }
                    }
                    OutlinedButton(onClick = { actions.onGpxAppearance(item) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.gpx_appearance_customize))
                    }
                }
                libraryDetailActions(item, actions, onOpenOfflineMap, onDismiss)
                libraryActivitySetting(item, organization, actions.organization)
                OutlinedButton(
                    onClick = { actions.onMoveItem(item) },
                    enabled = actions.organization.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.library_move_to_folder)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
    )
}

@Composable
private fun libraryDetailActions(
    item: PhoneLibraryItem,
    actions: PhoneLibraryActions,
    onOpenOfflineMap: (PhoneOfflineMap) -> Unit,
    onDismiss: () -> Unit,
) {
    when (item) {
        is PhoneLibraryItem.OfflineData -> libraryOfflineDataDetails(item)
        is PhoneLibraryItem.Gpx -> libraryGpxActions(item, actions, onDismiss)
        is PhoneLibraryItem.FolderGpx -> {
            Text(stringResource(R.string.library_folder_gpx_description))
            OutlinedButton(onClick = {
                onDismiss()
                actions.onOpenMapTools(MapTool.GPX)
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_open_gpx_tools)) }
        }
        is PhoneLibraryItem.ReceivedGpx -> {
            Text(stringResource(R.string.library_received_description))
            val importOptions =
                listOf(
                    false to R.string.library_import_route,
                    true to R.string.library_import_recording,
                )
            importOptions.forEach { (recorded, label) ->
                OutlinedButton(onClick = {
                    val uri = Uri.fromFile(item.file)
                    actions.onImportGpx(uri, recorded, item.exportId)
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
            }
        }
        is PhoneLibraryItem.Pois ->
            OutlinedButton(
                onClick = {
                    onDismiss()
                    actions.onOpenMapTools(MapTool.POI)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.library_open_poi_tools)) }
        is PhoneLibraryItem.OfflineMap ->
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onOpenOfflineMap(item.map)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.library_open_map)) }
        is PhoneLibraryItem.Bundle ->
            libraryBundleDetails(
                item = item,
                onOpenMap = { map ->
                    onDismiss()
                    onOpenOfflineMap(map)
                },
                onManage = {
                    onDismiss()
                    actions.onMapAction(MapLibraryAction.DOWNLOAD_BUNDLE)
                },
            )
    }
}

@Composable
private fun libraryGpxActions(
    item: PhoneLibraryItem.Gpx,
    actions: PhoneLibraryActions,
    onDismiss: () -> Unit,
) {
    Button(onClick = {
        onDismiss()
        actions.onUseRoute(item.route.id)
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_use_for_hike)) }
    OutlinedButton(onClick = {
        onDismiss()
        actions.onSendGpx(item.route.id)
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.transfer_title_send_to_watch)) }
    OutlinedButton(onClick = {
        onDismiss()
        actions.onOpenMapTools(MapTool.GPX)
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_open_gpx_tools)) }
}
