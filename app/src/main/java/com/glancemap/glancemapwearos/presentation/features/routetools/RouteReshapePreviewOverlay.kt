package com.glancemap.glancemapwearos.presentation.features.routetools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize

@Composable
internal fun BoxScope.RouteReshapePreviewOverlay(
    screenSize: WearScreenSize,
    title: String,
    instruction: String,
    busy: Boolean,
    busyMessage: String?,
    message: String?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val popupSpec = guidancePopupSpec(screenSize)
    val buttonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 30.dp
            WearScreenSize.MEDIUM -> 28.dp
            WearScreenSize.SMALL -> 26.dp
        }
    val iconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 16.dp
            WearScreenSize.MEDIUM -> 15.dp
            WearScreenSize.SMALL -> 14.dp
        }
    val bottomPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 41.dp
            WearScreenSize.MEDIUM -> 37.dp
            WearScreenSize.SMALL -> 33.dp
        }

    Column(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = popupSpec.topPadding)
                .widthIn(max = popupSpec.maxWidth + 10.dp)
                .background(
                    Color.Black.copy(alpha = 0.82f),
                    RoundedCornerShape(popupSpec.cornerRadius),
                ).padding(
                    horizontal = popupSpec.horizontalPadding,
                    vertical = popupSpec.verticalPadding,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(popupSpec.rowSpacing),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = popupSpec.modeTitleFontSize),
            color = Color(0xFFF7C948),
            textAlign = TextAlign.Center,
        )
        when {
            busy -> {
                RouteToolBusySpinner(size = 18.dp)
                Text(
                    text = busyMessage ?: stringResource(R.string.gpx_saving_gpx),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = popupSpec.instructionFontSize,
                            lineHeight = popupSpec.instructionLineHeight,
                        ),
                    color = Color.White.copy(alpha = 0.90f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            message != null -> {
                Text(
                    text = message,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = popupSpec.instructionFontSize,
                            lineHeight = popupSpec.instructionLineHeight,
                        ),
                    color = Color(0xFFFFCC80),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            else -> {
                Text(
                    text = instruction,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = popupSpec.instructionFontSize,
                            lineHeight = popupSpec.instructionLineHeight,
                        ),
                    color = Color.White.copy(alpha = 0.90f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Row(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onDismiss,
            enabled = !busy,
            modifier = Modifier.size(buttonSize),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.74f),
                    contentColor = Color.White,
                ),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.gpx_discard_reroute_preview),
                modifier = Modifier.size(iconSize),
            )
        }
        IconButton(
            onClick = onSave,
            enabled = !busy,
            modifier = Modifier.size(buttonSize),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color(0xFFF7C948),
                    contentColor = Color.Black,
                ),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.gpx_save_rerouted_gpx),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
