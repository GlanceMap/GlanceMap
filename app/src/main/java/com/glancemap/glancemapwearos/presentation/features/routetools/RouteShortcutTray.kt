package com.glancemap.glancemapwearos.presentation.features.routetools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ViewComfyAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale

@Composable
internal fun BoxScope.RouteShortcutTray(
    expanded: Boolean,
    keepAppOpen: Boolean,
    edgePadding: Dp,
    anchorSize: Dp,
    adjacentAccessoryWidth: Dp,
    actionHeight: Dp,
    iconSize: Dp,
    recordingActive: Boolean,
    recordingPaused: Boolean,
    recordingSaving: Boolean,
    headingReferenceTestActive: Boolean,
    onToggleExpanded: () -> Unit,
    onKeepAppOpenClick: () -> Unit,
    onGpxToolsClick: () -> Unit,
    onCreatePoiClick: () -> Unit,
    onRecordingClick: () -> Unit,
    onHeadingReferenceMark: (Float) -> Unit,
) {
    var headingReferenceIndex by remember(headingReferenceTestActive) { mutableIntStateOf(0) }
    val headingReferenceDeg = headingReferenceIndex * 90f
    Box(
        modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = edgePadding),
    ) {
        AnimatedVisibility(
            visible = expanded,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = anchorSize + adjacentAccessoryWidth + 20.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (headingReferenceTestActive) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShortcutActionChip(
                            text = "Ref ${headingReferenceLabel(headingReferenceIndex)}",
                            height = actionHeight,
                            onClick = { headingReferenceIndex = (headingReferenceIndex + 1) % 4 },
                        )
                        ShortcutActionChip(
                            text = "Mark",
                            height = actionHeight,
                            onClick = { onHeadingReferenceMark(headingReferenceDeg) },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShortcutActionChip(
                        text = "POI",
                        height = actionHeight,
                        onClick = onCreatePoiClick,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = Color(0xFFFFD54F),
                        )
                    }
                    ShortcutActionChip(
                        text = "Stay",
                        height = actionHeight,
                        onClick = onKeepAppOpenClick,
                    ) {
                        Icon(
                            imageVector =
                                if (keepAppOpen) {
                                    Icons.Default.Visibility
                                } else {
                                    Icons.Default.VisibilityOff
                                },
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint =
                                if (keepAppOpen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White
                                },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShortcutActionChip(
                        text = "GPX",
                        height = actionHeight,
                        onClick = onGpxToolsClick,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = Color.White,
                        )
                    }
                    ShortcutActionChip(
                        text =
                            when {
                                recordingSaving -> "Save"
                                else -> "REC"
                            },
                        height = actionHeight,
                        onClick = onRecordingClick,
                    ) {
                        RecordingShortcutIcon(
                            iconSize = iconSize,
                            recordingSaving = recordingSaving,
                            recordingPaused = recordingPaused,
                            recordingActive = recordingActive,
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onToggleExpanded,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(anchorSize),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.78f),
                    contentColor = Color.White,
                ),
        ) {
            if (expanded) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close shortcuts",
                    modifier = Modifier.size(iconSize),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ViewComfyAlt,
                    contentDescription = "Open shortcuts",
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

private fun headingReferenceLabel(index: Int): String =
    when (index) {
        0 -> "N"
        1 -> "E"
        2 -> "S"
        else -> "W"
    }

@Composable
private fun ShortcutActionChip(
    text: String,
    height: Dp,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    val hitTargetHeight = height.coerceAtLeast(48.dp)
    val hitTargetWidth = 68.dp.coerceAtLeast(48.dp)
    val visualWidth = 64.dp
    val visualHeight = (height + 6.dp).coerceIn(32.dp, 36.dp)

    Box(
        modifier =
            Modifier
                .width(hitTargetWidth)
                .height(hitTargetHeight)
                .clickable(
                    role = Role.Button,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            modifier =
                Modifier
                    .width(visualWidth)
                    .height(visualHeight)
                    .background(
                        color = Color.Black.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(percent = 50),
                    ).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cappedFontScale(maxFontScale = 1f) {
                icon?.invoke()
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RecordingShortcutIcon(
    iconSize: Dp,
    recordingSaving: Boolean,
    recordingPaused: Boolean,
    recordingActive: Boolean,
) {
    Box(
        modifier =
            Modifier
                .size((iconSize * 0.82f).coerceAtLeast(13.dp))
                .background(
                    color = Color.White.copy(alpha = 0.18f),
                    shape = CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size((iconSize * 0.54f).coerceAtLeast(8.dp))
                    .background(
                        color =
                            when {
                                recordingSaving -> Color(0xFFFFD54F)
                                recordingPaused -> Color(0xFFFFB74D)
                                recordingActive -> Color(0xFFFF1744)
                                else -> Color(0xFFFF5252)
                            },
                        shape = CircleShape,
                    ),
        )
    }
}
