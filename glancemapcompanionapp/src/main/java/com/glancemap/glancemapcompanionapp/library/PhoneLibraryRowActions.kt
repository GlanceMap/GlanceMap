package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelState
import com.glancemap.glancemapcompanionapp.map.PhoneRecordingService

@Composable
internal fun libraryVisibilitySwitch(
    row: PhoneLibraryRowState,
    visibility: PhoneLibraryVisibility,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    val item = row.item
    val description = stringResource(R.string.library_show_on_map, item.title)
    Switch(
        checked = visibility.checked,
        enabled = row.enabled && visibility.available && (item is PhoneLibraryItem.Pois || !state.gpx.isLoading),
        onCheckedChange = { visible -> setLibraryItemVisible(item, visible, actions) },
        modifier = Modifier.testTag("library-visibility-${item.key}").semantics { contentDescription = description },
    )
}

/** Icon-only so compact rows fit; the content description still names the route. */
@Composable
internal fun libraryStartButton(row: PhoneLibraryRowState) {
    val item = row.item
    val description =
        stringResource(
            if (row.guidanceLoading) R.string.phone_guidance_starting_cd else R.string.phone_guidance_start_cd,
            item.title,
        )
    FilledTonalIconButton(
        onClick = { row.onStartGuidance(item) },
        enabled = row.enabled && row.guidanceEnabled && !row.guidanceLoading,
        modifier =
            Modifier.testTag("library-guidance-${item.key}").semantics {
                contentDescription = description
            },
    ) {
        if (row.guidanceLoading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** Replaces Start on the guided GPX, in the same tonal icon style. */
@Composable
internal fun libraryGuidanceControls(paused: Boolean) {
    val context = LocalContext.current
    Row {
        FilledTonalIconButton(
            onClick = {
                PhoneRecordingService.request(
                    context,
                    if (paused) {
                        PhoneRecordingService.ACTION_GUIDANCE_RESUME
                    } else {
                        PhoneRecordingService.ACTION_GUIDANCE_PAUSE
                    },
                )
            },
            modifier = Modifier.testTag("library-guidance-pause"),
        ) {
            Icon(
                imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription =
                    stringResource(if (paused) R.string.common_action_resume else R.string.common_action_pause),
                modifier = Modifier.size(20.dp),
            )
        }
        FilledTonalIconButton(
            onClick = { PhoneRecordingService.request(context, PhoneRecordingService.ACTION_GUIDANCE_STOP) },
            modifier = Modifier.testTag("library-guidance-stop"),
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = stringResource(R.string.phone_guidance_stop),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal fun setLibraryItemVisible(
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
