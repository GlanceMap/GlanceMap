package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R

@Composable
internal fun GpsSettingsShortcutChip(
    onClick: () -> Unit,
    applyTopPadding: Boolean = true,
    modifier: Modifier = Modifier,
) {
    AdaptiveSettingsShortcutChip(
        standardLabel = stringResource(R.string.gps_settings),
        compactLabel = stringResource(R.string.gps_settings),
        standardSecondaryLabel = stringResource(R.string.settings_back_to_gps_settings),
        compactSecondaryLabel = stringResource(R.string.common_back),
        iconImageVector = Icons.Filled.Folder,
        applyTopPadding = applyTopPadding,
        compactRoundWidthFraction = 0.78f,
        modifier = modifier,
        onClick = onClick,
    )
}
