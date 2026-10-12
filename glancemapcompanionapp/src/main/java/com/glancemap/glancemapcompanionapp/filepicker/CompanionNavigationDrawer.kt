package com.glancemap.glancemapcompanionapp.filepicker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.launch

@Composable
internal fun companionNavigationDrawer(
    drawerState: DrawerState,
    state: CompanionMenuState,
    actions: CompanionMenuActions,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val menuWidth = maxWidth / 2
        ModalNavigationDrawer(
            drawerState = drawerState,
            // A map pan should never open the menu; swipe-to-dismiss remains available.
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(menuWidth)) {
                    Text(
                        text = stringResource(R.string.menu_title),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    ) {
                        CompanionHomeArea.entries.forEach { area ->
                            companionMenuItem(area, state, actions)
                        }
                    }
                    HorizontalDivider()
                    companionMenuFooter(state.debugCaptureActive, actions)
                }
            },
            content = content,
        )
        // Register in this subcomposition after the map so Back dismisses the menu first.
        BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    }
}

@Composable
private fun companionMenuItem(
    area: CompanionHomeArea,
    state: CompanionMenuState,
    actions: CompanionMenuActions,
) {
    NavigationDrawerItem(
        label = { Text(stringResource(area.titleResource), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(area.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        selected = state.activeArea == area,
        badge =
            if (area == CompanionHomeArea.LIVE_HIKE) {
                {
                    val label = stringResource(R.string.menu_live_hike_sync)
                    Switch(
                        checked = state.liveHikeSyncEnabled,
                        onCheckedChange = actions.onLiveHikeSyncChanged,
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            } else {
                null
            },
        onClick = { actions.onNavigate(area) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun companionMenuFooter(
    debugCaptureActive: Boolean,
    actions: CompanionMenuActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = actions.onDebug, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription =
                    stringResource(
                        if (debugCaptureActive) {
                            R.string.home_debug_capture_stop_content_description
                        } else {
                            R.string.home_debug_capture_start_content_description
                        },
                    ),
                tint =
                    if (debugCaptureActive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
        }
        IconButton(onClick = actions.onQuickGuide, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = stringResource(R.string.common_quick_guide_content_description),
            )
        }
        IconButton(onClick = actions.onCreditsLegal, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Filled.Gavel,
                contentDescription = stringResource(R.string.settings_credits_legal_title),
            )
        }
    }
}
