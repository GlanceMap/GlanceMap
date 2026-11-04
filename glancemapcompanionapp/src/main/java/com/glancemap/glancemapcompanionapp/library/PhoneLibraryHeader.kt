package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolContentMode
import com.glancemap.glancemapcompanionapp.map.titleResource

/** Tabs scroll on narrow panels so the search and settings buttons always stay reachable. */
@Composable
internal fun phoneLibraryHeader(
    selected: PhoneLibraryFilter,
    modifier: Modifier = Modifier,
    actions: PhoneLibraryHeaderActions = PhoneLibraryHeaderActions(),
    onSelect: (PhoneLibraryFilter) -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        phoneLibraryTabs(selected, Modifier.weight(1f), onSelect)
        IconButton(
            onClick = actions.onToggleSearch,
            modifier = Modifier.size(40.dp).testTag("library-search-toggle"),
        ) {
            Icon(
                if (actions.searchOpen) Icons.Filled.SearchOff else Icons.Filled.Search,
                stringResource(if (actions.searchOpen) R.string.library_clear_search else R.string.library_search),
            )
        }
        selected.tool?.let { tool ->
            IconButton(
                onClick = { actions.onSettings(tool) },
                modifier = Modifier.size(40.dp).testTag("library-settings"),
            ) {
                Icon(Icons.Filled.Settings, stringResource(tool.titleResource(MapToolContentMode.FEATURE_SETTINGS)))
            }
        }
    }
}

@Composable
private fun phoneLibraryTabs(
    selected: PhoneLibraryFilter,
    modifier: Modifier,
    onSelect: (PhoneLibraryFilter) -> Unit,
) {
    Row(
        modifier =
            modifier
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .testTag("library-tabs"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PhoneLibraryFilter.entries.filterNot { it == PhoneLibraryFilter.BUNDLES }.forEach { filter ->
            val active =
                selected == filter ||
                    (selected == PhoneLibraryFilter.BUNDLES && filter == PhoneLibraryFilter.MAPS)
            val contentColor =
                if (active) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(filter) })
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("library-tab-${filter.name}")
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(filter.label),
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
