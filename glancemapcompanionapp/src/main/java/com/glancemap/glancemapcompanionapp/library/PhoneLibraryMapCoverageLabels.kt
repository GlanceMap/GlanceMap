package com.glancemap.glancemapcompanionapp.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun libraryMapCoverageLabels(coverage: PhoneLibraryMapCoverage?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        libraryMapCoverageLabel(
            R.string.library_offline_elevation,
            Icons.Filled.Landscape,
            coverage?.requiredElevationTiles,
            coverage?.availableElevationTiles ?: 0,
        )
        libraryMapCoverageLabel(
            R.string.library_offline_routing,
            Icons.AutoMirrored.Filled.AltRoute,
            coverage?.requiredRoutingFiles,
            coverage?.availableRoutingFiles ?: 0,
        )
    }
}

@Composable
private fun libraryMapCoverageLabel(
    label: Int,
    icon: ImageVector,
    required: Int?,
    available: Int,
) {
    val text =
        when {
            required == null -> stringResource(R.string.library_map_coverage_unknown)
            required == 0 -> stringResource(R.string.library_map_coverage_not_required)
            available == 0 -> stringResource(R.string.map_source_capability_missing)
            available < required -> stringResource(R.string.library_map_coverage_partial, available, required)
            else -> stringResource(R.string.library_map_coverage_available, available, required)
        }
    val color =
        when {
            required != null && available >= required -> MaterialTheme.colorScheme.primary
            available > 0 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        Text(
            stringResource(R.string.library_offline_component, stringResource(label), text),
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
