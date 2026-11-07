package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelState
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceSessionStore

private val LIBRARY_ROW_BUTTON_SIZE = 48.dp

/**
 * One line for routes and POIs: the map toggle leads and Start and the menu trail. A title that does not fit
 * beside the buttons moves above them and uses the full width.
 */
@Composable
internal fun libraryCompactRow(
    row: PhoneLibraryRowState,
    visibility: PhoneLibraryVisibility?,
    state: MapToolsPanelState,
    actions: MapToolsPanelActions,
    onDetails: () -> Unit,
) {
    val item = row.item
    val guidance by PhoneGuidanceSessionStore.state.collectAsState()
    val guided = item.isNavigableGpx() && guidance.session?.trackId == item.key
    val selecting = row.selected != null
    val buttonCount =
        when {
            selecting -> 0
            guided -> 3
            item.isNavigableGpx() -> 2
            else -> 1
        }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = row.selected == true,
                onCheckedChange = { row.onToggleSelected(item) },
                modifier = Modifier.testTag("library-select-${item.key}"),
            )
        } else if (visibility != null) {
            libraryVisibilitySwitch(row, visibility, state, actions)
        } else {
            Box(Modifier.size(LIBRARY_ROW_BUTTON_SIZE), contentAlignment = Alignment.Center) {
                Icon(item.icon(row.organization), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(8.dp))
        libraryCompactBody(row, visibility, LIBRARY_ROW_BUTTON_SIZE * buttonCount, Modifier.weight(1f)) {
            if (!selecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (guided) {
                        libraryGuidanceControls(paused = guidance.paused)
                    } else if (item.isNavigableGpx()) {
                        libraryStartButton(row)
                    }
                    libraryRowTrailing(row, visibility, state, actions, onDetails)
                }
            }
        }
    }
}

@Composable
private fun libraryCompactBody(
    row: PhoneLibraryRowState,
    visibility: PhoneLibraryVisibility?,
    buttonsWidth: Dp,
    modifier: Modifier,
    buttons: @Composable () -> Unit,
) {
    val title = row.item.title
    val titleStyle = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val besideWidth = with(density) { (maxWidth - buttonsWidth).roundToPx() }
        val fitsBeside =
            remember(title, titleStyle, besideWidth) {
                besideWidth > 0 && measurer.measure(title, titleStyle, maxLines = 1).size.width <= besideWidth
            }
        if (fitsBeside) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = titleStyle, maxLines = 1)
                    libraryCompactDetails(row, visibility)
                }
                buttons()
            }
        } else {
            Column {
                Text(title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { libraryCompactDetails(row, visibility) }
                    buttons()
                }
            }
        }
    }
}

/** The activity icon moves into the stats line so the toggle can lead the row. */
@Composable
private fun libraryCompactDetails(
    row: PhoneLibraryRowState,
    visibility: PhoneLibraryVisibility?,
) {
    val item = row.item
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (visibility != null) {
            Icon(
                item.icon(row.organization),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(libraryItemSummary(item), style = MaterialTheme.typography.bodySmall)
    }
    libraryItemStatus(item, visibility)?.let { status ->
        Text(stringResource(status), style = MaterialTheme.typography.labelSmall)
    }
}
