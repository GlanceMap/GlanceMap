package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun GpsSettingsShortcutChip(
    onClick: () -> Unit,
    applyTopPadding: Boolean = true,
    modifier: Modifier = Modifier,
) {
    AdaptiveSettingsShortcutChip(
        standardLabel = "GPS settings",
        compactLabel = "GPS settings",
        standardSecondaryLabel = "Back to GPS settings",
        compactSecondaryLabel = "Back",
        iconImageVector = Icons.Filled.Folder,
        applyTopPadding = applyTopPadding,
        compactRoundWidthFraction = 0.78f,
        modifier = modifier,
        onClick = onClick,
    )
}
