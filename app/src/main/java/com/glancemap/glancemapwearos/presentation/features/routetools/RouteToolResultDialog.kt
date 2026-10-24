package com.glancemap.glancemapwearos.presentation.features.routetools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.formatting.DurationFormatter
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import com.glancemap.glancemapwearos.presentation.ui.CompactIconHitTargetButton
import com.glancemap.glancemapwearos.presentation.ui.RenameValueDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialogButton

@Composable
internal fun RouteToolResultDialog(
    visible: Boolean,
    result: RouteToolSaveResult?,
    isMetric: Boolean,
    renameInProgress: Boolean,
    renameError: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onRenameOpen: () -> Unit,
    onRenameConfirm: (String) -> Unit,
    onStartGuidance: (() -> Unit)? = null,
) {
    val resources = LocalResources.current
    if (!visible || result == null) return

    var showRenameDialog by remember(result.filePath) { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameValueDialog(
            visible = true,
            title = stringResource(R.string.gpx_rename_gpx),
            initialValue = result.displayTitle,
            isSaving = renameInProgress,
            error = renameError,
            autoFocusInput = false,
            onDismiss = {
                if (!renameInProgress) {
                    showRenameDialog = false
                }
            },
            onConfirm = onRenameConfirm,
        )
    }

    val (distanceValue, distanceUnit) = UnitFormatter.formatDistance(result.distanceMeters, isMetric)
    val (gainValue, gainUnit) = UnitFormatter.formatElevation(result.elevationGainMeters, isMetric)
    val (lossValue, lossUnit) = UnitFormatter.formatElevation(result.elevationLossMeters, isMetric)
    val etaValue = DurationFormatter.formatDurationShort(result.estimatedDurationSec, resources = resources)

    WearActionDialog(
        visible = true,
        title = stringResource(result.messageRes),
        onDismissRequest = onDismiss,
        backgroundColor = Color.Black.copy(alpha = 0.92f),
        buttons =
            buildList {
                if (onStartGuidance != null) {
                    add(
                        WearActionDialogButton(
                            text = stringResource(R.string.common_start),
                            onClick = onStartGuidance,
                        ),
                    )
                }
                add(
                    WearActionDialogButton(
                        text = stringResource(R.string.common_done),
                        onClick = onDismiss,
                    ),
                )
            },
    ) {
        Text(
            text = result.displayTitle,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactIconHitTargetButton(
                onClick = onDelete,
                visualSize = 34.dp,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                enabled = !renameInProgress,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.gpx_delete_gpx),
                )
            }
            CompactIconHitTargetButton(
                onClick = {
                    onRenameOpen()
                    showRenameDialog = true
                },
                visualSize = 34.dp,
                containerColor = Color.White.copy(alpha = 0.10f),
                contentColor = Color.White,
                enabled = !renameInProgress,
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.gpx_rename_gpx),
                )
            }
        }
        if (renameError != null && !showRenameDialog) {
            Text(
                text = renameError,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RouteStatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.gpx_dist),
                value = stringResource(R.string.common_value_unit, distanceValue, distanceUnit),
            )
            RouteStatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.common_time),
                value = etaValue,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RouteStatTile(
                modifier = Modifier.weight(1f),
                labelIcon = Icons.Default.ArrowUpward,
                value = stringResource(R.string.common_value_unit, gainValue, gainUnit),
            )
            RouteStatTile(
                modifier = Modifier.weight(1f),
                labelIcon = Icons.Default.ArrowDownward,
                value = stringResource(R.string.common_value_unit, lossValue, lossUnit),
            )
        }
    }
}

@Composable
private fun RouteStatTile(
    modifier: Modifier,
    label: String? = null,
    labelIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    value: String,
) {
    Column(
        modifier =
            modifier
                .background(
                    Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(12.dp),
                ).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (labelIcon != null) {
            Icon(
                imageVector = labelIcon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color.White.copy(alpha = 0.72f),
            )
        } else if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
