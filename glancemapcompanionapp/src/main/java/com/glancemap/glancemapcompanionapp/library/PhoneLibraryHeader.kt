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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
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

@Composable
internal fun phoneLibraryHeader(
    selected: PhoneLibraryFilter,
    modifier: Modifier = Modifier,
    onSelect: (PhoneLibraryFilter) -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .testTag("library-tabs"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PhoneLibraryFilter.entries.forEach { filter ->
            val active = selected == filter
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
                if (filter == PhoneLibraryFilter.BUNDLES) {
                    Icon(Icons.Filled.Download, stringResource(filter.label), Modifier.size(24.dp), tint = contentColor)
                } else {
                    Text(
                        stringResource(filter.label),
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
