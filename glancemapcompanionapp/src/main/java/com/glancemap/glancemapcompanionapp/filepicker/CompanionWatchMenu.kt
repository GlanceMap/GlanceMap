package com.glancemap.glancemapcompanionapp.filepicker

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun companionWatchMenu(
    state: CompanionMenuState,
    actions: CompanionMenuActions,
) {
    val watchSelected =
        state.activeArea == CompanionHomeArea.SEND_TO_WATCH || state.activeArea == CompanionHomeArea.LIVE_HIKE
    var expanded by rememberSaveable { mutableStateOf(watchSelected) }
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.map_navigation_watch)) },
        icon = { Icon(Icons.Filled.Watch, contentDescription = null, modifier = Modifier.size(20.dp)) },
        badge = { Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null) },
        selected = watchSelected || expanded,
        onClick = { expanded = !expanded },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    if (expanded) {
        NavigationDrawerItem(
            label = {
                Text(
                    stringResource(R.string.transfer_title_send_to_watch),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            selected = state.activeArea == CompanionHomeArea.SEND_TO_WATCH,
            onClick = { actions.onNavigate(CompanionHomeArea.SEND_TO_WATCH) },
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        )
        val syncLabel = stringResource(R.string.menu_live_hike_sync)
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.menu_live_hike), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            badge = {
                Switch(
                    checked = state.liveHikeSyncEnabled,
                    onCheckedChange = actions.onLiveHikeSyncChanged,
                    modifier = Modifier.semantics { contentDescription = syncLabel },
                )
            },
            selected = state.activeArea == CompanionHomeArea.LIVE_HIKE,
            onClick = { actions.onNavigate(CompanionHomeArea.LIVE_HIKE) },
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        )
    }
}
