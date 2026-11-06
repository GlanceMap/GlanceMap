package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceSessionStore
import com.glancemap.glancemapcompanionapp.map.PhoneRecordingService

@Composable
internal fun libraryRowActions(
    row: PhoneLibraryRowState,
    visibility: PhoneLibraryVisibility?,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
) {
    val item = row.item
    if (visibility == null && !item.isNavigableGpx()) return
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (visibility != null) {
            libraryVisibilitySwitch(row, visibility, state, actions)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.library_on_map), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.weight(1f))
        if (item.isNavigableGpx()) {
            val guidance by PhoneGuidanceSessionStore.state.collectAsState()
            if (guidance.session?.trackId == item.key) {
                libraryGuidanceControls(paused = guidance.paused)
            } else {
                libraryStartButton(row)
            }
        }
    }
}

@Composable
private fun libraryVisibilitySwitch(
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

@Composable
private fun libraryStartButton(row: PhoneLibraryRowState) {
    val item = row.item
    val description =
        stringResource(
            if (row.guidanceLoading) R.string.phone_guidance_starting_cd else R.string.phone_guidance_start_cd,
            item.title,
        )
    FilledTonalButton(
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
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.library_start_route))
    }
}

/** Replaces Start on the guided GPX; both buttons keep Start's height and tonal style. */
@Composable
private fun libraryGuidanceControls(paused: Boolean) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
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
        FilledTonalButton(
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
