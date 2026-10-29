@file:Suppress("MatchingDeclarationName")

package com.glancemap.glancemapwearos.presentation.features.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog

enum class DemSetupReason {
    GENERIC,
    HILL_SHADING,
    HILL_SHADING_MAP_REQUIRED,
    HILL_SHADING_VISIBLE_AREA,
    LIVE_ELEVATION,
    SLOPE_OVERLAY,
}

@Suppress("CyclomaticComplexMethod", "FunctionName", "LongMethod")
@Composable
fun DemSetupBottomSheet(
    visible: Boolean,
    reason: DemSetupReason = DemSetupReason.GENERIC,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val title =
        when (reason) {
            DemSetupReason.GENERIC -> stringResource(R.string.maps_dem_setup)
            DemSetupReason.HILL_SHADING -> stringResource(R.string.maps_elevation_data_needed)
            DemSetupReason.HILL_SHADING_MAP_REQUIRED -> stringResource(R.string.maps_select_a_map)
            DemSetupReason.HILL_SHADING_VISIBLE_AREA -> stringResource(R.string.maps_no_terrain_data)
            DemSetupReason.LIVE_ELEVATION -> stringResource(R.string.maps_elevation_data_needed)
            DemSetupReason.SLOPE_OVERLAY -> stringResource(R.string.maps_elevation_data_needed)
        }
    val message =
        when (reason) {
            DemSetupReason.GENERIC ->
                stringResource(R.string.maps_for_each_offline_map_use_the_dem_icon_to_download_elevation_data_dem_g)
            DemSetupReason.HILL_SHADING ->
                stringResource(R.string.maps_hill_shading_needs_dem_data_for_this_map_open_maps_and_tap_the_dem_ico)
            DemSetupReason.HILL_SHADING_MAP_REQUIRED ->
                stringResource(R.string.maps_select_an_offline_map_first)
            DemSetupReason.HILL_SHADING_VISIBLE_AREA ->
                stringResource(R.string.maps_no_detailed_or_standard_terrain_data_is_available_for_this_area_open_m)
            DemSetupReason.LIVE_ELEVATION ->
                stringResource(R.string.maps_live_elevation_needs_dem_data_for_this_map_open_maps_and_tap_the_dem_i)
            DemSetupReason.SLOPE_OVERLAY ->
                stringResource(R.string.maps_slope_overlay_needs_dem_data_for_this_map_open_maps_and_tap_the_dem_ic)
        }

    WearInfoDialog(
        visible = visible,
        title = title,
        onDismiss = onDismiss,
    ) {
        if (reason == DemSetupReason.GENERIC) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = stringResource(R.string.maps_elevation_icon),
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.maps_dem),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.maps_dem_means_elevation_data),
                        tint = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        item {
            Text(
                text = message,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
