package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Shortcut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.glancemap.glancemapwearos.presentation.ui.WearWindowClass
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec

@Composable
internal fun GeneralSettingsShortcutChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    applyTopPadding: Boolean = true,
) {
    AdaptiveSettingsShortcutChip(
        standardLabel = "General Settings",
        compactLabel = "General",
        standardSecondaryLabel = "Back to settings menu",
        compactSecondaryLabel = "Settings menu",
        iconImageVector = Icons.AutoMirrored.Filled.Shortcut,
        applyTopPadding = applyTopPadding,
        compactRoundWidthFraction = 0.78f,
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
internal fun AdaptiveSettingsShortcutChip(
    standardLabel: String,
    compactLabel: String,
    standardSecondaryLabel: String,
    compactSecondaryLabel: String,
    iconImageVector: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    applyTopPadding: Boolean = false,
    compactRoundWidthFraction: Float = 1f,
    standardMinHeight: Dp = 52.dp,
    compactMinHeight: Dp = 84.dp,
) {
    val adaptive = rememberWearAdaptiveSpec()
    val useCompactLabels = adaptive.windowClass == WearWindowClass.COMPACT || adaptive.fontScale >= 1.25f
    val topPadding = if (applyTopPadding) rememberSettingsFirstItemTopPadding() else 0.dp
    val widthFraction =
        if (adaptive.isRound && useCompactLabels) {
            compactRoundWidthFraction
        } else {
            1f
        }

    Chip(
        modifier =
            modifier
                .padding(top = topPadding)
                .fillMaxWidth(widthFraction)
                .heightIn(min = if (useCompactLabels) compactMinHeight else standardMinHeight),
        label = {
            Text(
                text = if (useCompactLabels) compactLabel else standardLabel,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        },
        secondaryLabel = {
            Text(
                text = if (useCompactLabels) compactSecondaryLabel else standardSecondaryLabel,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        },
        icon = {
            Icon(
                imageVector = iconImageVector,
                contentDescription = null,
                modifier = Modifier.size(ChipDefaults.IconSize),
            )
        },
        colors =
            ChipDefaults.secondaryChipColors(
                backgroundColor = SectionChipBackground,
                contentColor = SectionChipContent,
                secondaryContentColor = SectionChipSecondary,
                iconColor = SectionChipIcon,
            ),
        onClick = onClick,
    )
}
