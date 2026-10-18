package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs

/** All selected surfaces must support the same perspective for comparison maps to align. */
internal fun phoneMapSupportsTilt(
    base: PhoneMapSource,
    comparison: PhoneMapComparisonLayer?,
): Boolean = base == PhoneMapSource.Online && comparison !is PhoneMapComparisonLayer.Offline

internal fun phoneMapTiltDegrees(
    enabled: Boolean,
    base: PhoneMapSource,
    comparison: PhoneMapComparisonLayer?,
): Double = if (enabled && phoneMapSupportsTilt(base, comparison)) 45.0 else 0.0

@Composable
internal fun phoneMap3dCapabilityIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.ViewInAr,
        contentDescription = stringResource(R.string.map_tools_maps_3d_supported),
        modifier = modifier.size(20.dp),
    )
}

internal fun MapLibreMap.applyPhoneMapTilt(tiltDegrees: Double) {
    if (abs(cameraPosition.tilt - tiltDegrees) > 0.01) {
        moveCamera(CameraUpdateFactory.tiltTo(tiltDegrees))
    }
}
