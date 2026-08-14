@file:Suppress("FunctionName", "FunctionNaming", "LongParameterList")

package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.glancemap.glancemapwearos.presentation.ui.WearWindowClass
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec

private val PickerChipBackground = Color(0xFF2B2F36)
private val PickerChipContent = Color(0xFFF1F5FB)
private val PickerChipSecondary = Color(0xFFBAC5D4)
private val PickerChipIcon = Color(0xFF9FB2C9)
private val SelectedPickerChipBackground = Color(0xFF254336)
private val SelectedPickerChipContent = Color(0xFFF1FFF5)
private val SelectedPickerChipSecondary = Color(0xFFB7DCC4)
private val SelectedPickerChipIcon = Color(0xFF8FF0A4)

internal val SectionChipBackground = Color(0xFF1F3554)
internal val SectionChipContent = Color(0xFFF4F7FB)
internal val SectionChipSecondary = Color(0xFFC9D7EA)
internal val SectionChipIcon = Color(0xFFF6C453)

@Composable
internal fun SettingsToggleChip(
    checked: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
    label: String,
    secondaryLabel: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val minHeight = rememberSettingsChipMinHeight(hasSecondaryLabel = secondaryLabel != null)

    ToggleChip(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(modifier),
        enabled = enabled,
        checked = checked,
        onCheckedChange = onCheckedChanged,
        label = {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                overflow = TextOverflow.Ellipsis,
                maxLines = if (secondaryLabel != null) 1 else 2,
            )
        },
        secondaryLabel = secondaryLabel?.let { text ->
            {
                Text(
                    text = text,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        },
        toggleControl = {
            Icon(
                imageVector = ToggleChipDefaults.switchIcon(checked),
                contentDescription = null,
            )
        },
    )
}

@Composable
internal fun SettingsPickerChip(
    label: String,
    onClick: () -> Unit,
    secondaryLabel: String? = null,
    iconImageVector: ImageVector? = Icons.Filled.UnfoldMore,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val minHeight = rememberSettingsChipMinHeight(hasSecondaryLabel = secondaryLabel != null)
    val backgroundColor = if (selected) SelectedPickerChipBackground else PickerChipBackground
    val contentColor = if (selected) SelectedPickerChipContent else PickerChipContent
    val secondaryContentColor = if (selected) SelectedPickerChipSecondary else PickerChipSecondary
    val iconColor = if (selected) SelectedPickerChipIcon else PickerChipIcon

    if (iconImageVector != null) {
        Chip(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .then(modifier),
            label = {
                Text(
                    text = label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = if (secondaryLabel != null) 1 else 2,
                )
            },
            secondaryLabel = secondaryLabel?.let { text ->
                {
                    Text(
                        text = text,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                }
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
                    backgroundColor = backgroundColor,
                    contentColor = contentColor,
                    secondaryContentColor = secondaryContentColor,
                    iconColor = iconColor,
                ),
            onClick = onClick,
        )
    } else {
        Chip(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .then(modifier),
            label = {
                Text(
                    text = label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = if (secondaryLabel != null) 1 else 2,
                )
            },
            secondaryLabel = secondaryLabel?.let { text ->
                {
                    Text(
                        text = text,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                }
            },
            colors =
                ChipDefaults.secondaryChipColors(
                    backgroundColor = backgroundColor,
                    contentColor = contentColor,
                    secondaryContentColor = secondaryContentColor,
                ),
            onClick = onClick,
        )
    }
}

@Composable
internal fun SettingsSectionChip(
    label: String,
    onClick: () -> Unit,
    iconImageVector: ImageVector = Icons.Filled.Folder,
    iconContent: (@Composable () -> Unit)? = null,
    secondaryLabel: String? = null,
    compactRoundWidthFraction: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val adaptive = rememberWearAdaptiveSpec()
    val minHeight = rememberSettingsChipMinHeight(hasSecondaryLabel = secondaryLabel != null)
    val useCompactWidth =
        adaptive.isRound &&
            (adaptive.windowClass == WearWindowClass.COMPACT || adaptive.fontScale >= 1.25f)
    val widthFraction = if (useCompactWidth) compactRoundWidthFraction else 1f

    Chip(
        modifier =
            Modifier
                .fillMaxWidth(widthFraction)
                .heightIn(min = minHeight)
                .then(modifier),
        label = {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                overflow = TextOverflow.Ellipsis,
                maxLines = if (secondaryLabel != null) 1 else 2,
            )
        },
        secondaryLabel = secondaryLabel?.let { text ->
            {
                Text(
                    text = text,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        },
        icon = {
            if (iconContent != null) {
                iconContent()
            } else {
                Icon(
                    imageVector = iconImageVector,
                    contentDescription = null,
                    modifier = Modifier.size(ChipDefaults.IconSize),
                )
            }
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

@Composable
internal fun DownloadSettingsSectionChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val minHeight = rememberSettingsChipMinHeight(hasSecondaryLabel = false)

    Chip(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(modifier),
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(ChipDefaults.IconSize),
                    tint = Color.White,
                )
                Text("settings")
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Filled.Folder,
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

@Composable
private fun rememberSettingsChipMinHeight(hasSecondaryLabel: Boolean): Dp {
    val adaptive = rememberWearAdaptiveSpec()
    return when {
        adaptive.fontScale >= 1.45f && hasSecondaryLabel -> 76.dp
        adaptive.fontScale >= 1.45f -> 64.dp
        adaptive.fontScale >= 1.25f && hasSecondaryLabel -> 68.dp
        adaptive.fontScale >= 1.25f -> 56.dp
        hasSecondaryLabel -> 52.dp
        else -> 48.dp
    }
}
