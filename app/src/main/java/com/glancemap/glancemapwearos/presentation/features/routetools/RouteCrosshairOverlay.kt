package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Loop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.formatting.DurationFormatter
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import com.glancemap.glancemapwearos.presentation.ui.CompactIconHitTargetButton
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize

@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun BoxScope.RouteCrosshairOverlay(
    session: RouteToolSession,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    createPreview: RouteToolCreatePreview? = null,
    createPreviewInProgress: Boolean = false,
    createPreviewMessage: String? = null,
    busy: Boolean = false,
    busyMessage: String? = null,
    titleOverride: String? = null,
    instructionOverride: String? = null,
    popupTopOffset: Dp = 0.dp,
    showCapturedPoints: Boolean = true,
    onPickHere: () -> Unit,
    onCancel: () -> Unit,
    onUndoLastPoint: (() -> Unit)? = null,
    onSaveCreatePreview: (() -> Unit)? = null,
    onRefreshCreatePreview: (() -> Unit)? = null,
) {
    val resources = LocalResources.current
    val multiPointMode = session.isMultiPointCreate
    val loopPreviewMode =
        session.options.toolKind == RouteToolKind.CREATE &&
            session.options.createMode == RouteCreateMode.LOOP_AROUND_HERE &&
            session.isComplete
    val popupSpec = guidancePopupSpec(screenSize)
    val popupMaxWidth = if (multiPointMode) popupSpec.maxWidth + 14.dp else popupSpec.maxWidth
    val multiPointTitleFontSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 10.5.sp
            WearScreenSize.MEDIUM -> 10.sp
            WearScreenSize.SMALL -> 10.sp
        }
    val multiPointTitleLineHeight =
        when (screenSize) {
            WearScreenSize.LARGE -> 11.sp
            WearScreenSize.MEDIUM -> 10.sp
            WearScreenSize.SMALL -> 10.sp
        }
    val multiPointStatusFontSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 10.sp
            WearScreenSize.MEDIUM -> 10.sp
            WearScreenSize.SMALL -> 10.sp
        }
    val multiPointStatusLineHeight =
        when (screenSize) {
            WearScreenSize.LARGE -> 10.sp
            WearScreenSize.MEDIUM -> 10.sp
            WearScreenSize.SMALL -> 10.sp
        }
    val popupVerticalPadding =
        if (multiPointMode) {
            compactBannerVerticalPadding(screenSize)
        } else {
            popupSpec.verticalPadding
        }
    val popupRowSpacing = if (multiPointMode) 0.dp else popupSpec.rowSpacing
    val saveChipFontSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 10.sp
            WearScreenSize.MEDIUM -> 10.sp
            WearScreenSize.SMALL -> 10.sp
        }
    val actionButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 28.dp
            WearScreenSize.MEDIUM -> 26.dp
            WearScreenSize.SMALL -> 24.dp
        }
    val actionIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 15.dp
            WearScreenSize.MEDIUM -> 14.dp
            WearScreenSize.SMALL -> 13.dp
        }
    val actionsBottomPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 38.dp
            WearScreenSize.MEDIUM -> 34.dp
            WearScreenSize.SMALL -> 30.dp
        }
    val popupTopPadding =
        (if (multiPointMode) popupSpec.topPadding + 2.dp else popupSpec.topPadding) +
            popupTopOffset
    val effectiveActionsBottomPadding = if (multiPointMode) actionsBottomPadding + 2.dp else actionsBottomPadding
    val actionVisualOffsetY = if (multiPointMode) (-2).dp else 0.dp
    if (session.usesCrosshair) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(46.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                RouteToolBusySpinner(size = 30.dp)
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outerRadius = size.minDimension * 0.34f
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.45f),
                        radius = outerRadius + 6f,
                        center = center,
                    )
                    drawCircle(
                        color = Color(0xFFF7C948),
                        radius = outerRadius,
                        center = center,
                        style = Stroke(width = 3f),
                    )
                    drawLine(
                        color = Color(0xFFF7C948),
                        start = Offset(center.x - 16f, center.y),
                        end = Offset(center.x + 16f, center.y),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color(0xFFF7C948),
                        start = Offset(center.x, center.y - 16f),
                        end = Offset(center.x, center.y + 16f),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = Color(0xFFF7C948),
                        radius = 2.8f,
                        center = center,
                    )
                }
            }
        }
    }

    Box(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = popupTopPadding),
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = popupMaxWidth)
                    .background(
                        Color.Black.copy(alpha = 0.82f),
                        RoundedCornerShape(popupSpec.cornerRadius),
                    ).padding(
                        horizontal = popupSpec.horizontalPadding,
                        vertical = popupVerticalPadding,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(popupRowSpacing),
        ) {
            Text(
                text =
                    titleOverride ?: if (multiPointMode) {
                        stringResource(
                            R.string.gpx_crosshair_summary,
                            stringResource(session.modeTitleRes),
                            session.chainPoints.size,
                        )
                    } else {
                        stringResource(session.modeTitleRes)
                    },
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontSize = if (multiPointMode) multiPointTitleFontSize else popupSpec.modeTitleFontSize,
                        lineHeight = if (multiPointMode) multiPointTitleLineHeight else popupSpec.modeTitleFontSize,
                    ),
                color = Color(0xFFF7C948),
                textAlign = TextAlign.Center,
            )
            if (multiPointMode) {
                val previewLines =
                    createPreview?.let {
                        formatCreatePreviewLines(preview = it, isMetric = isMetric, resources = resources)
                    }
                if (previewLines != null && createPreviewMessage == null && !createPreviewInProgress) {
                    Text(
                        text = previewLines.primary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = multiPointStatusFontSize),
                        textAlign = TextAlign.Center,
                        lineHeight = multiPointStatusLineHeight,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = previewLines.secondary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = multiPointStatusFontSize),
                        textAlign = TextAlign.Center,
                        lineHeight = multiPointStatusLineHeight,
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text =
                            compactMultiPointBannerText(
                                session = session,
                                preview = createPreview,
                                createPreviewInProgress = createPreviewInProgress,
                                createPreviewMessage = createPreviewMessage,
                                isMetric = isMetric,
                                resources = resources,
                            ),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = multiPointStatusFontSize),
                        textAlign = TextAlign.Center,
                        lineHeight = multiPointStatusLineHeight,
                        color =
                            if (createPreviewMessage != null) {
                                Color(0xFFFFCC80)
                            } else {
                                Color.White.copy(alpha = 0.90f)
                            },
                        maxLines = if (createPreviewMessage != null) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (
                    canShowCreatePreviewSaveChip(
                        session = session,
                        createPreview = createPreview,
                        createPreviewInProgress = createPreviewInProgress,
                        onSaveCreatePreview = onSaveCreatePreview,
                    )
                ) {
                    Text(
                        text = stringResource(R.string.gpx_save_gpx),
                        modifier =
                            Modifier
                                .background(
                                    Color(0xFFF7C948).copy(alpha = 0.94f),
                                    RoundedCornerShape(8.dp),
                                ).clickable(onClick = requireNotNull(onSaveCreatePreview))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = saveChipFontSize),
                        color = Color.Black,
                    )
                }
            } else if (loopPreviewMode) {
                val previewLines =
                    createPreview?.let {
                        formatCreatePreviewLines(preview = it, isMetric = isMetric, resources = resources)
                    }
                when {
                    createPreviewInProgress -> {
                        Text(
                            text = stringResource(R.string.gpx_finding_loop),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = popupSpec.instructionFontSize),
                            textAlign = TextAlign.Center,
                            lineHeight = popupSpec.instructionLineHeight,
                            color = Color.White.copy(alpha = 0.90f),
                        )
                    }

                    createPreviewMessage != null -> {
                        Text(
                            text = createPreviewMessage,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = popupSpec.instructionFontSize),
                            textAlign = TextAlign.Center,
                            lineHeight = popupSpec.instructionLineHeight,
                            color = Color(0xFFFFCC80),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    previewLines != null -> {
                        Text(
                            text = previewLines.primary,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = popupSpec.instructionFontSize),
                            textAlign = TextAlign.Center,
                            lineHeight = popupSpec.instructionLineHeight,
                            color = Color.White.copy(alpha = 0.92f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = previewLines.secondary,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = popupSpec.instructionFontSize),
                            textAlign = TextAlign.Center,
                            lineHeight = popupSpec.instructionLineHeight,
                            color = Color.White.copy(alpha = 0.88f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    else -> {
                        Text(
                            text = stringResource(R.string.gpx_check_loop_data_save_it_or_try_another),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = popupSpec.instructionFontSize),
                            textAlign = TextAlign.Center,
                            lineHeight = popupSpec.instructionLineHeight,
                            color = Color.White.copy(alpha = 0.90f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Text(
                    text = busyMessage ?: instructionOverride ?: stringResource(session.instructionTextRes),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = popupSpec.instructionFontSize),
                    textAlign = TextAlign.Center,
                    lineHeight = popupSpec.instructionLineHeight,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showCapturedPoints && session.hasCapturedPoints && !loopPreviewMode) {
                RouteCapturedPointsSummary(session = session)
            }
        }
    }

    if (session.usesCrosshair || loopPreviewMode) {
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = effectiveActionsBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactIconHitTargetButton(
                onClick = onCancel,
                enabled = !busy,
                visualSize = actionButtonSize,
                visualOffsetY = actionVisualOffsetY,
                containerColor = Color.Black.copy(alpha = 0.74f),
                contentColor = Color.White,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.gpx_close_route_tool),
                    modifier = Modifier.size(actionIconSize),
                )
            }
            if (multiPointMode && onUndoLastPoint != null) {
                CompactIconHitTargetButton(
                    onClick = onUndoLastPoint,
                    enabled = session.chainPoints.isNotEmpty() && !createPreviewInProgress && !busy,
                    visualSize = actionButtonSize,
                    visualOffsetY = actionVisualOffsetY,
                    containerColor = Color.Black.copy(alpha = 0.74f),
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.gpx_remove_last_point),
                        modifier = Modifier.size(actionIconSize),
                    )
                }
            } else if (loopPreviewMode && onRefreshCreatePreview != null) {
                CompactIconHitTargetButton(
                    onClick = onRefreshCreatePreview,
                    enabled = !createPreviewInProgress && !busy,
                    visualSize = actionButtonSize,
                    visualOffsetY = actionVisualOffsetY,
                    containerColor = Color.Black.copy(alpha = 0.74f),
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = Icons.Default.Loop,
                        contentDescription = stringResource(R.string.gpx_find_another_loop),
                        modifier = Modifier.size(actionIconSize),
                    )
                }
            }
            if (loopPreviewMode) {
                if (createPreview != null && onSaveCreatePreview != null) {
                    CompactIconHitTargetButton(
                        onClick = onSaveCreatePreview,
                        enabled = !createPreviewInProgress && !busy,
                        visualSize = actionButtonSize,
                        visualOffsetY = actionVisualOffsetY,
                        containerColor = Color(0xFFF7C948),
                        contentColor = Color.Black,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.gpx_save_loop_gpx),
                            modifier = Modifier.size(actionIconSize),
                        )
                    }
                }
            } else {
                CompactIconHitTargetButton(
                    onClick = onPickHere,
                    enabled = !createPreviewInProgress && !busy,
                    visualSize = actionButtonSize,
                    visualOffsetY = actionVisualOffsetY,
                    containerColor = Color(0xFFF7C948),
                    contentColor = Color.Black,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.gpx_select_position),
                        modifier = Modifier.size(actionIconSize),
                    )
                }
            }
        }
    }
}

private data class CreatePreviewLines(
    val primary: String,
    val secondary: String,
)

private fun canShowCreatePreviewSaveChip(
    session: RouteToolSession,
    createPreview: RouteToolCreatePreview?,
    createPreviewInProgress: Boolean,
    onSaveCreatePreview: (() -> Unit)?,
): Boolean {
    var canShow = createPreview != null
    canShow = canShow && onSaveCreatePreview != null
    canShow = canShow && session.chainPoints.size >= 2
    canShow = canShow && !createPreviewInProgress
    return canShow
}

// Keep the existing UI API; resource-backed defaults and labels do not change its inputs.
@Suppress("LongParameterList")
private fun compactMultiPointBannerText(
    session: RouteToolSession,
    preview: RouteToolCreatePreview?,
    createPreviewInProgress: Boolean,
    createPreviewMessage: String?,
    isMetric: Boolean,
    resources: Resources,
): String =
    when {
        createPreviewInProgress -> resources.getString(R.string.gpx_updating_route)
        createPreviewMessage != null -> createPreviewMessage
        preview != null -> formatCreatePreviewLines(preview, isMetric, resources = resources).primary
        session.chainPoints.isEmpty() -> resources.getString(R.string.gpx_start_point)
        session.chainPoints.size == 1 -> resources.getString(R.string.gpx_next_point)
        else -> resources.getString(R.string.gpx_add_point)
    }

private fun formatCreatePreviewLines(
    preview: RouteToolCreatePreview,
    isMetric: Boolean,
    resources: Resources,
): CreatePreviewLines {
    val (distanceValue, distanceUnit) = UnitFormatter.formatDistance(preview.distanceMeters, isMetric)
    val etaText = DurationFormatter.formatDurationShort(preview.estimatedDurationSec, resources = resources)
    val gainText = compactElevationValue(preview.elevationGainMeters, isMetric, resources = resources)
    val lossText = compactElevationValue(preview.elevationLossMeters, isMetric, resources = resources)
    return CreatePreviewLines(
        primary = resources.getString(R.string.gpx_route_distance_eta, distanceValue, distanceUnit, etaText),
        secondary = resources.getString(R.string.gpx_route_ascent_descent, gainText, lossText),
    )
}

private fun compactElevationValue(
    meters: Double,
    isMetric: Boolean,
    resources: Resources,
): String {
    val (value, unit) = UnitFormatter.formatElevation(meters, isMetric)
    return if (isMetric) {
        value
    } else {
        resources.getString(R.string.common_compact_value_unit, value, unit)
    }
}

internal data class GuidancePopupSpec(
    val topPadding: androidx.compose.ui.unit.Dp,
    val maxWidth: androidx.compose.ui.unit.Dp,
    val horizontalPadding: androidx.compose.ui.unit.Dp,
    val verticalPadding: androidx.compose.ui.unit.Dp,
    val cornerRadius: androidx.compose.ui.unit.Dp,
    val rowSpacing: androidx.compose.ui.unit.Dp,
    val modeTitleFontSize: androidx.compose.ui.unit.TextUnit,
    val instructionFontSize: androidx.compose.ui.unit.TextUnit,
    val instructionLineHeight: androidx.compose.ui.unit.TextUnit,
)

private fun compactBannerVerticalPadding(screenSize: WearScreenSize): Dp =
    when (screenSize) {
        WearScreenSize.LARGE -> 2.dp
        WearScreenSize.MEDIUM -> 2.dp
        WearScreenSize.SMALL -> 1.5.dp
    }

internal fun guidancePopupSpec(screenSize: WearScreenSize): GuidancePopupSpec =
    when (screenSize) {
        WearScreenSize.LARGE ->
            GuidancePopupSpec(
                topPadding = 26.dp,
                maxWidth = 112.dp,
                horizontalPadding = 6.dp,
                verticalPadding = 3.dp,
                cornerRadius = 7.dp,
                rowSpacing = 2.dp,
                modeTitleFontSize = 13.sp,
                instructionFontSize = 10.sp,
                instructionLineHeight = 11.sp,
            )

        WearScreenSize.MEDIUM ->
            GuidancePopupSpec(
                topPadding = 24.dp,
                maxWidth = 106.dp,
                horizontalPadding = 6.dp,
                verticalPadding = 3.dp,
                cornerRadius = 7.dp,
                rowSpacing = 2.dp,
                modeTitleFontSize = 12.sp,
                instructionFontSize = 10.sp,
                instructionLineHeight = 10.sp,
            )

        WearScreenSize.SMALL ->
            GuidancePopupSpec(
                topPadding = 22.dp,
                maxWidth = 100.dp,
                horizontalPadding = 5.dp,
                verticalPadding = 3.dp,
                cornerRadius = 6.dp,
                rowSpacing = 2.dp,
                modeTitleFontSize = 11.sp,
                instructionFontSize = 10.sp,
                instructionLineHeight = 10.sp,
            )
    }

private val RouteToolSession.hasCapturedPoints: Boolean
    get() =
        if (
            options.toolKind == RouteToolKind.MODIFY &&
            options.modifyMode == RouteModifyMode.RESHAPE_ROUTE
        ) {
            false
        } else if (
            options.toolKind == RouteToolKind.CREATE &&
            options.createMode == RouteCreateMode.MULTI_POINT_CHAIN
        ) {
            false
        } else {
            pointA != null || pointB != null || destination != null || loopCenter != null
        }

@Composable
// Resource-call wrapping increases line count; keep the existing flow together.
@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
private fun RouteCapturedPointsSummary(session: RouteToolSession) {
    if (
        session.options.toolKind == RouteToolKind.CREATE &&
        session.options.createMode == RouteCreateMode.POINT_A_TO_B
    ) {
        return
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        session.pointA?.let {
            CapturedPointBadge(
                label =
                    if (
                        session.options.toolKind == RouteToolKind.MODIFY &&
                        session.options.modifyMode == RouteModifyMode.RESHAPE_ROUTE
                    ) {
                        stringResource(R.string.gpx_route)
                    } else if (
                        session.options.toolKind == RouteToolKind.CREATE &&
                        session.options.createMode == RouteCreateMode.LOOP_AROUND_HERE
                    ) {
                        stringResource(R.string.common_start)
                    } else if (session.options.toolKind == RouteToolKind.CREATE) {
                        stringResource(R.string.common_start)
                    } else {
                        stringResource(R.string.navigate_a)
                    },
            )
        }
        session.pointB?.let {
            CapturedPointBadge(
                label =
                    if (session.options.toolKind ==
                        RouteToolKind.CREATE
                    ) {
                        stringResource(R.string.navigate_end)
                    } else {
                        stringResource(R.string.navigate_b)
                    },
            )
        }
        session.destination?.let {
            CapturedPointBadge(
                label =
                    if (
                        session.options.toolKind == RouteToolKind.MODIFY &&
                        session.options.modifyMode == RouteModifyMode.RESHAPE_ROUTE
                    ) {
                        stringResource(R.string.gpx_bend)
                    } else {
                        stringResource(R.string.gpx_dest)
                    },
            )
        }
        session.loopCenter?.let {
            CapturedPointBadge(label = stringResource(R.string.gpx_loop))
        }
    }
}

@Composable
private fun CapturedPointBadge(label: String) {
    Box(
        modifier =
            Modifier
                .background(Color(0x33F7C948), CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFF7C948),
        )
    }
}
