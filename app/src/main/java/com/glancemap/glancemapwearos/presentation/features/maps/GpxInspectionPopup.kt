@file:Suppress("FunctionName")

package com.glancemap.glancemapwearos.presentation.features.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.features.gpx.InspectionABUiState
import com.glancemap.glancemapwearos.presentation.features.gpx.InspectionAUiState
import com.glancemap.glancemapwearos.presentation.formatting.DurationFormatter
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import com.glancemap.glancemapwearos.presentation.ui.WearWindowClass
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.superscriptResourceText
import kotlin.math.abs

private const val DragDismissPx = 55f

private data class InspectionPopupSizing(
    val tableRowSpacing: Dp,
    val compactRowSpacing: Dp,
    val labelEndPadding: Dp,
    val actionSpacerHeight: Dp,
    val actionIconSize: Dp,
    val actionSpacerWidth: Dp,
    val selectBButtonHeight: Dp,
    val selectBButtonContentHorizontalPadding: Dp,
    val selectBButtonContentVerticalPadding: Dp,
    val surfaceCornerRadius: Dp,
    val surfacePaddingHorizontal: Dp,
    val surfaceTopPadding: Dp,
    val surfaceBottomPadding: Dp,
    val surfaceContentSpacing: Dp,
    val handleBottomPadding: Dp,
    val showEtaInline: Boolean,
    val tableWidthScale: Float = 1f,
    val headerTextSize: TextUnit? = null,
    val labelTextSize: TextUnit? = null,
    val valueTextSize: TextUnit? = null,
    val etaTextSize: TextUnit? = null,
    val buttonTextSize: TextUnit? = null,
)

@Composable
private fun rememberInspectionPopupSizing(): InspectionPopupSizing {
    val adaptive = rememberWearAdaptiveSpec()
    return when (adaptive.windowClass) {
        WearWindowClass.EXPANDED -> {
            if (adaptive.isRound) {
                InspectionPopupSizing(
                    tableRowSpacing = 6.dp,
                    compactRowSpacing = 4.dp,
                    labelEndPadding = 2.dp,
                    actionSpacerHeight = 1.dp,
                    actionIconSize = 16.dp,
                    actionSpacerWidth = 4.dp,
                    selectBButtonHeight = 40.dp,
                    selectBButtonContentHorizontalPadding = 10.dp,
                    selectBButtonContentVerticalPadding = 4.dp,
                    surfaceCornerRadius = 16.dp,
                    surfacePaddingHorizontal = 12.dp,
                    surfaceTopPadding = 10.dp,
                    surfaceBottomPadding = 4.dp,
                    surfaceContentSpacing = 3.dp,
                    handleBottomPadding = 2.dp,
                    showEtaInline = true,
                )
            } else {
                InspectionPopupSizing(
                    tableRowSpacing = 6.dp,
                    compactRowSpacing = 4.dp,
                    labelEndPadding = 2.dp,
                    actionSpacerHeight = 1.dp,
                    actionIconSize = 16.dp,
                    actionSpacerWidth = 4.dp,
                    selectBButtonHeight = 40.dp,
                    selectBButtonContentHorizontalPadding = 10.dp,
                    selectBButtonContentVerticalPadding = 4.dp,
                    surfaceCornerRadius = 16.dp,
                    surfacePaddingHorizontal = 10.dp,
                    surfaceTopPadding = 8.dp,
                    surfaceBottomPadding = 4.dp,
                    surfaceContentSpacing = 3.dp,
                    handleBottomPadding = 2.dp,
                    showEtaInline = true,
                )
            }
        }

        WearWindowClass.STANDARD -> {
            if (adaptive.isRound) {
                InspectionPopupSizing(
                    tableRowSpacing = 5.dp,
                    compactRowSpacing = 3.5.dp,
                    labelEndPadding = 2.dp,
                    actionSpacerHeight = 1.dp,
                    actionIconSize = 15.dp,
                    actionSpacerWidth = 3.dp,
                    selectBButtonHeight = 38.dp,
                    selectBButtonContentHorizontalPadding = 10.dp,
                    selectBButtonContentVerticalPadding = 3.dp,
                    surfaceCornerRadius = 15.dp,
                    surfacePaddingHorizontal = 11.dp,
                    surfaceTopPadding = 9.dp,
                    surfaceBottomPadding = 3.dp,
                    surfaceContentSpacing = 2.dp,
                    handleBottomPadding = 1.5.dp,
                    showEtaInline = true,
                )
            } else {
                InspectionPopupSizing(
                    tableRowSpacing = 5.dp,
                    compactRowSpacing = 3.5.dp,
                    labelEndPadding = 2.dp,
                    actionSpacerHeight = 1.dp,
                    actionIconSize = 15.dp,
                    actionSpacerWidth = 3.dp,
                    selectBButtonHeight = 38.dp,
                    selectBButtonContentHorizontalPadding = 10.dp,
                    selectBButtonContentVerticalPadding = 3.dp,
                    surfaceCornerRadius = 15.dp,
                    surfacePaddingHorizontal = 9.dp,
                    surfaceTopPadding = 8.dp,
                    surfaceBottomPadding = 3.dp,
                    surfaceContentSpacing = 2.dp,
                    handleBottomPadding = 1.5.dp,
                    showEtaInline = true,
                )
            }
        }

        WearWindowClass.COMPACT -> {
            if (adaptive.isRound) {
                InspectionPopupSizing(
                    tableRowSpacing = 4.dp,
                    compactRowSpacing = 2.dp,
                    labelEndPadding = 1.dp,
                    actionSpacerHeight = 0.dp,
                    actionIconSize = 14.dp,
                    actionSpacerWidth = 2.dp,
                    selectBButtonHeight = 35.dp,
                    selectBButtonContentHorizontalPadding = 9.dp,
                    selectBButtonContentVerticalPadding = 2.dp,
                    surfaceCornerRadius = 14.dp,
                    surfacePaddingHorizontal = 10.dp,
                    surfaceTopPadding = 8.dp,
                    surfaceBottomPadding = 3.dp,
                    surfaceContentSpacing = 2.dp,
                    handleBottomPadding = 1.dp,
                    showEtaInline = true,
                    tableWidthScale = 1f,
                    headerTextSize = 10.sp,
                    labelTextSize = 10.sp,
                    valueTextSize = 10.sp,
                    etaTextSize = 10.sp,
                    buttonTextSize = 10.sp,
                )
            } else {
                InspectionPopupSizing(
                    tableRowSpacing = 4.dp,
                    compactRowSpacing = 3.dp,
                    labelEndPadding = 1.dp,
                    actionSpacerHeight = 0.dp,
                    actionIconSize = 14.dp,
                    actionSpacerWidth = 2.dp,
                    selectBButtonHeight = 35.dp,
                    selectBButtonContentHorizontalPadding = 9.dp,
                    selectBButtonContentVerticalPadding = 2.dp,
                    surfaceCornerRadius = 14.dp,
                    surfacePaddingHorizontal = 8.dp,
                    surfaceTopPadding = 7.dp,
                    surfaceBottomPadding = 3.dp,
                    surfaceContentSpacing = 2.dp,
                    handleBottomPadding = 1.dp,
                    showEtaInline = true,
                )
            }
        }
    }
}

@Composable
fun GpxInspectionPopupA(
    state: InspectionAUiState,
    onDismiss: () -> Unit,
    onSelectB: () -> Unit,
    isMetric: Boolean,
) {
    val resources = LocalResources.current
    val sizing = rememberInspectionPopupSizing()
    val tableScrollState = rememberScrollState()
    val distFromStart =
        remember(state.a.distanceFromStart, isMetric) {
            UnitFormatter.formatDistance(state.a.distanceFromStart, isMetric)
        }
    val distToEnd =
        remember(state.a.distanceToEnd, isMetric) {
            UnitFormatter.formatDistance(state.a.distanceToEnd, isMetric)
        }

    val upFromStart =
        remember(state.a.elevationGainFromStart, isMetric) {
            UnitFormatter.formatElevation(state.a.elevationGainFromStart, isMetric)
        }
    val upToEnd =
        remember(state.a.elevationGainToEnd, isMetric) {
            UnitFormatter.formatElevation(state.a.elevationGainToEnd, isMetric)
        }

    val downFromStart =
        remember(state.a.elevationLossFromStart, isMetric) {
            UnitFormatter.formatElevation(state.a.elevationLossFromStart, isMetric)
        }
    val downToEnd =
        remember(state.a.elevationLossToEnd, isMetric) {
            UnitFormatter.formatElevation(state.a.elevationLossToEnd, isMetric)
        }
    val etaFromStart =
        remember(state.a.durationFromStartSec) {
            DurationFormatter.formatDurationShort(state.a.durationFromStartSec, resources = resources)
        }
    val etaToEnd =
        remember(state.a.durationToEndSec) {
            DurationFormatter.formatDurationShort(state.a.durationToEndSec, resources = resources)
        }
    val tableWidthMultiplier =
        remember(
            distFromStart.first,
            distToEnd.first,
            upFromStart.first,
            upToEnd.first,
            downFromStart.first,
            downToEnd.first,
            etaFromStart,
            etaToEnd,
        ) {
            contentWidthMultiplier(
                values =
                    listOf(
                        distFromStart.first,
                        distToEnd.first,
                        upFromStart.first,
                        upToEnd.first,
                        downFromStart.first,
                        downToEnd.first,
                        etaFromStart,
                        etaToEnd,
                    ),
            )
        }

    Dialog(onDismissRequest = onDismiss) {
        PopupSurface(onDismiss = onDismiss, sizing = sizing) {
            SwipeHandleHint(sizing)

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val tableWidth = maxWidth * tableWidthMultiplier * sizing.tableWidthScale
                Column {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(tableScrollState),
                        contentAlignment = Alignment.Center,
                    ) {
                        MetricTableCompact(
                            modifier = Modifier.width(tableWidth),
                            row1Label = "Ⓢ→Ⓐ",
                            row2Label = "Ⓐ→Ⓔ",
                            compact = true,
                            rowSpacing = sizing.compactRowSpacing,
                            labelEndPadding = 0.dp,
                            labelWeight = 0.92f,
                            metricWeight = 0.85f,
                            labelAlignEnd = false,
                            distUnit = distFromStart.second,
                            upUnit = upFromStart.second,
                            downUnit = downFromStart.second,
                            etaHeader = "ETA",
                            showEtaInline = sizing.showEtaInline,
                            row1Dist = distFromStart.first,
                            row2Dist = distToEnd.first,
                            row1Up = upFromStart.first,
                            row2Up = upToEnd.first,
                            row1Down = downFromStart.first,
                            row2Down = downToEnd.first,
                            row1Eta = etaFromStart,
                            row2Eta = etaToEnd,
                            headerTextSize = sizing.headerTextSize,
                            labelTextSize = sizing.labelTextSize,
                            valueTextSize = sizing.valueTextSize,
                            etaTextSize = sizing.etaTextSize,
                        )
                    }
                    HorizontalScrollHintBar(
                        scrollState = tableScrollState,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Button(
                    onClick = onSelectB,
                    modifier = Modifier.heightIn(min = sizing.selectBButtonHeight.coerceAtLeast(48.dp)),
                    contentPadding =
                        PaddingValues(
                            horizontal = sizing.selectBButtonContentHorizontalPadding,
                            vertical = sizing.selectBButtonContentVerticalPadding,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddLocation,
                        contentDescription = stringResource(R.string.maps_add_second_point_b),
                        modifier = Modifier.size(sizing.actionIconSize),
                    )
                    Spacer(Modifier.width(sizing.actionSpacerWidth))
                    Text(
                        text =
                            superscriptResourceText(
                                R.string.gpx_add_second_point_label,
                                sizing.buttonTextSize ?: MaterialTheme.typography.labelSmall.fontSize,
                            ),
                        style =
                            MaterialTheme.typography.labelMedium
                                .withSize(sizing.buttonTextSize),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun GpxInspectionPopupAB(
    state: InspectionABUiState,
    onDismiss: () -> Unit,
    isMetric: Boolean,
) {
    val resources = LocalResources.current
    val sizing = rememberInspectionPopupSizing()
    val tableScrollState = rememberScrollState()
    val sToADist =
        remember(state.sToA.distance, isMetric) {
            UnitFormatter.formatDistance(state.sToA.distance, isMetric)
        }
    val aToBDist =
        remember(state.aToB.distance, isMetric) {
            UnitFormatter.formatDistance(state.aToB.distance, isMetric)
        }
    val bToEDist =
        remember(state.bToE.distance, isMetric) {
            UnitFormatter.formatDistance(state.bToE.distance, isMetric)
        }

    val sToAUp =
        remember(state.sToA.elevationGain, isMetric) {
            UnitFormatter.formatElevation(state.sToA.elevationGain, isMetric)
        }
    val aToBUp =
        remember(state.aToB.elevationGain, isMetric) {
            UnitFormatter.formatElevation(state.aToB.elevationGain, isMetric)
        }
    val bToEUp =
        remember(state.bToE.elevationGain, isMetric) {
            UnitFormatter.formatElevation(state.bToE.elevationGain, isMetric)
        }

    val sToADown =
        remember(state.sToA.elevationLoss, isMetric) {
            UnitFormatter.formatElevation(state.sToA.elevationLoss, isMetric)
        }
    val aToBDown =
        remember(state.aToB.elevationLoss, isMetric) {
            UnitFormatter.formatElevation(state.aToB.elevationLoss, isMetric)
        }
    val bToEDown =
        remember(state.bToE.elevationLoss, isMetric) {
            UnitFormatter.formatElevation(state.bToE.elevationLoss, isMetric)
        }
    val sToAEta =
        remember(state.sToA.durationSec) {
            DurationFormatter.formatDurationShort(state.sToA.durationSec, resources = resources)
        }
    val aToBEta =
        remember(state.aToB.durationSec) {
            DurationFormatter.formatDurationShort(state.aToB.durationSec, resources = resources)
        }
    val bToEEta =
        remember(state.bToE.durationSec) {
            DurationFormatter.formatDurationShort(state.bToE.durationSec, resources = resources)
        }
    val tableWidthMultiplier =
        remember(
            sToADist.first,
            aToBDist.first,
            bToEDist.first,
            sToAUp.first,
            aToBUp.first,
            bToEUp.first,
            sToADown.first,
            aToBDown.first,
            bToEDown.first,
            sToAEta,
            aToBEta,
            bToEEta,
        ) {
            contentWidthMultiplier(
                values =
                    listOf(
                        sToADist.first,
                        aToBDist.first,
                        bToEDist.first,
                        sToAUp.first,
                        aToBUp.first,
                        bToEUp.first,
                        sToADown.first,
                        aToBDown.first,
                        bToEDown.first,
                        sToAEta,
                        aToBEta,
                        bToEEta,
                    ),
            )
        }

    Dialog(onDismissRequest = onDismiss) {
        PopupSurface(onDismiss = onDismiss, sizing = sizing) {
            SwipeHandleHint(sizing)

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val tableWidth = maxWidth * tableWidthMultiplier * sizing.tableWidthScale
                Column {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(tableScrollState),
                        contentAlignment = Alignment.Center,
                    ) {
                        MetricTableCompact(
                            modifier = Modifier.width(tableWidth),
                            row1Label = "Ⓢ→Ⓐ",
                            row2Label = "Ⓐ→Ⓑ",
                            row3Label = "Ⓑ→Ⓔ",
                            compact = true,
                            rowSpacing = sizing.compactRowSpacing,
                            labelEndPadding = sizing.labelEndPadding,
                            distUnit = sToADist.second,
                            upUnit = sToAUp.second,
                            downUnit = sToADown.second,
                            etaHeader = "ETA",
                            showEtaInline = sizing.showEtaInline,
                            row1Dist = sToADist.first,
                            row2Dist = aToBDist.first,
                            row3Dist = bToEDist.first,
                            row1Up = sToAUp.first,
                            row2Up = aToBUp.first,
                            row3Up = bToEUp.first,
                            row1Down = sToADown.first,
                            row2Down = aToBDown.first,
                            row3Down = bToEDown.first,
                            row1Eta = sToAEta,
                            row2Eta = aToBEta,
                            row3Eta = bToEEta,
                            headerTextSize = sizing.headerTextSize,
                            labelTextSize = sizing.labelTextSize,
                            valueTextSize = sizing.valueTextSize,
                            etaTextSize = sizing.etaTextSize,
                        )
                    }
                    HorizontalScrollHintBar(
                        scrollState = tableScrollState,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
private fun MetricTableCompact(
    modifier: Modifier = Modifier,
    row1Label: String,
    row2Label: String,
    row3Label: String? = null,
    compact: Boolean = false,
    rowSpacing: Dp = 6.dp,
    labelEndPadding: Dp = 6.dp,
    labelWeight: Float? = null,
    metricWeight: Float? = null,
    labelAlignEnd: Boolean = true,
    distUnit: String,
    upUnit: String,
    downUnit: String,
    etaHeader: String? = null,
    showEtaInline: Boolean = true,
    row1Dist: String,
    row2Dist: String,
    row3Dist: String = "",
    row1Up: String,
    row2Up: String,
    row3Up: String = "",
    row1Down: String,
    row2Down: String,
    row3Down: String = "",
    row1Eta: String = "",
    row2Eta: String = "",
    row3Eta: String = "",
    headerTextSize: TextUnit? = null,
    labelTextSize: TextUnit? = null,
    valueTextSize: TextUnit? = null,
    etaTextSize: TextUnit? = null,
) {
    val headerColor = Color.White.copy(alpha = 0.70f)
    val dividerColor = Color.White.copy(alpha = 0.10f)

    // Give label enough space; keep numbers aligned end
    val hasInlineEta = showEtaInline && etaHeader != null
    val labelW = labelWeight ?: if (hasInlineEta) 0.94f else 1.0f
    val metricW = metricWeight ?: if (hasInlineEta) 0.86f else 1f

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        // Header row: single line (no "(unit)" second line)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeaderCellOneLine(
                modifier = Modifier.weight(labelW),
                text = "",
                color = headerColor,
                alignEnd = false,
                fontSize = headerTextSize,
            )
            HeaderCellOneLine(
                modifier = Modifier.weight(metricW),
                text = stringResource(R.string.maps_dist, distUnit),
                color = headerColor,
                alignEnd = true,
                fontSize = headerTextSize,
            )
            HeaderCellOneLine(
                modifier = Modifier.weight(metricW),
                text = stringResource(R.string.map_ascent_value, upUnit),
                color = headerColor,
                alignEnd = true,
                fontSize = headerTextSize,
            )
            HeaderCellOneLine(
                modifier = Modifier.weight(metricW),
                text = stringResource(R.string.map_descent_value, downUnit),
                color = headerColor,
                alignEnd = true,
                fontSize = headerTextSize,
            )
            if (hasInlineEta) {
                HeaderCellOneLine(
                    modifier = Modifier.weight(metricW),
                    text = etaHeader.orEmpty(),
                    color = headerColor,
                    alignEnd = true,
                    fontSize = headerTextSize,
                )
            }
        }

        DividerLine(dividerColor)

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabelCell(
                modifier = Modifier.weight(labelW),
                text = row1Label,
                compact = compact,
                endPadding = labelEndPadding,
                alignEnd = labelAlignEnd,
                fontSize = labelTextSize,
            )
            ValueCell(Modifier.weight(metricW), row1Dist, compact = compact, fontSize = valueTextSize)
            ValueCell(Modifier.weight(metricW), row1Up, compact = compact, fontSize = valueTextSize)
            ValueCell(Modifier.weight(metricW), row1Down, compact = compact, fontSize = valueTextSize)
            if (hasInlineEta) {
                ValueCell(Modifier.weight(metricW), row1Eta, compact = compact, fontSize = valueTextSize)
            }
        }
        if (!hasInlineEta && etaHeader != null) {
            EtaSecondaryLine(
                text = stringResource(R.string.map_eta_row, etaHeader, row1Eta),
                compact = compact,
                fontSize = etaTextSize,
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabelCell(
                modifier = Modifier.weight(labelW),
                text = row2Label,
                compact = compact,
                endPadding = labelEndPadding,
                alignEnd = labelAlignEnd,
                fontSize = labelTextSize,
            )
            ValueCell(Modifier.weight(metricW), row2Dist, compact = compact, fontSize = valueTextSize)
            ValueCell(Modifier.weight(metricW), row2Up, compact = compact, fontSize = valueTextSize)
            ValueCell(Modifier.weight(metricW), row2Down, compact = compact, fontSize = valueTextSize)
            if (hasInlineEta) {
                ValueCell(Modifier.weight(metricW), row2Eta, compact = compact, fontSize = valueTextSize)
            }
        }
        if (!hasInlineEta && etaHeader != null) {
            EtaSecondaryLine(
                text = stringResource(R.string.map_eta_row, etaHeader, row2Eta),
                compact = compact,
                fontSize = etaTextSize,
            )
        }

        if (row3Label != null) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LabelCell(
                    modifier = Modifier.weight(labelW),
                    text = row3Label,
                    compact = compact,
                    endPadding = labelEndPadding,
                    alignEnd = labelAlignEnd,
                    fontSize = labelTextSize,
                )
                ValueCell(Modifier.weight(metricW), row3Dist, compact = compact, fontSize = valueTextSize)
                ValueCell(Modifier.weight(metricW), row3Up, compact = compact, fontSize = valueTextSize)
                ValueCell(Modifier.weight(metricW), row3Down, compact = compact, fontSize = valueTextSize)
                if (hasInlineEta) {
                    ValueCell(Modifier.weight(metricW), row3Eta, compact = compact, fontSize = valueTextSize)
                }
            }
            if (!hasInlineEta && etaHeader != null) {
                EtaSecondaryLine(
                    text = stringResource(R.string.map_eta_row, etaHeader, row3Eta),
                    compact = compact,
                    fontSize = etaTextSize,
                )
            }
        }
    }
}

@Composable
private fun EtaSecondaryLine(
    text: String,
    compact: Boolean,
    fontSize: TextUnit? = null,
) {
    val baseStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        style = baseStyle.withSize(fontSize),
        color = Color.White.copy(alpha = 0.84f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

private fun contentWidthMultiplier(values: List<String>): Float {
    val longest = values.maxOfOrNull { it.length } ?: 0
    val extraChars = (longest - 5).coerceAtLeast(0).coerceAtMost(5)
    return 1f + (extraChars * 0.12f)
}

@Composable
private fun HorizontalScrollHintBar(
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
) {
    val maxScroll = scrollState.maxValue
    if (maxScroll <= 0) return

    val progress = (scrollState.value.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
    val thumbFraction = (1f / (1f + maxScroll / 360f)).coerceIn(0.22f, 0.85f)

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(4.dp),
    ) {
        val thumbWidth = maxWidth * thumbFraction
        val travel = (maxWidth - thumbWidth)
        val start = travel * progress

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)),
        )
        Box(
            modifier =
                Modifier
                    .padding(start = start)
                    .width(thumbWidth)
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(50)),
        )
    }
}

@Composable
private fun HeaderCellOneLine(
    modifier: Modifier,
    text: String,
    color: Color,
    alignEnd: Boolean,
    fontSize: TextUnit? = null,
) {
    Text(
        modifier = modifier,
        text = text,
        style = MaterialTheme.typography.labelSmall.withSize(fontSize),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign =
            if (alignEnd) {
                androidx.compose.ui.text.style.TextAlign.End
            } else {
                androidx.compose.ui.text.style.TextAlign.Start
            },
    )
}

@Composable
@Suppress("LongParameterList")
private fun LabelCell(
    modifier: Modifier,
    text: String,
    compact: Boolean = false,
    endPadding: Dp = 6.dp,
    alignEnd: Boolean = true,
    fontSize: TextUnit? = null,
) {
    val baseStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    Text(
        modifier = modifier.padding(end = endPadding),
        text = text,
        style = baseStyle.withSize(fontSize),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign =
            if (alignEnd) {
                androidx.compose.ui.text.style.TextAlign.End
            } else {
                androidx.compose.ui.text.style.TextAlign.Start
            },
    )
}

@Composable
private fun ValueCell(
    modifier: Modifier,
    text: String,
    compact: Boolean = false,
    fontSize: TextUnit? = null,
) {
    val baseStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Text(
        modifier = modifier,
        text = text,
        style = baseStyle.withSize(fontSize),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

private fun TextStyle.withSize(size: TextUnit?): TextStyle = if (size == null) this else copy(fontSize = size)

@Composable
private fun DividerLine(color: Color) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color),
    )
}

@Composable
private fun PopupSurface(
    onDismiss: () -> Unit,
    sizing: InspectionPopupSizing,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = remember(sizing.surfaceCornerRadius) { RoundedCornerShape(sizing.surfaceCornerRadius) }
    DialogSurface(
        onDismiss = onDismiss,
        shape = shape,
        sizing = sizing,
        content = content,
    )
}

@Composable
private fun DialogSurface(
    onDismiss: () -> Unit,
    shape: RoundedCornerShape,
    sizing: InspectionPopupSizing,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { totalDrag = 0f },
                        onDragCancel = { totalDrag = 0f },
                    ) { _, dragAmount ->
                        totalDrag += dragAmount
                        if (abs(totalDrag) > DragDismissPx) {
                            onDismiss()
                            totalDrag = 0f
                        }
                    }
                }
                // Slightly tighter padding to move content upward
                .background(Color.Black.copy(alpha = 0.82f), shape)
                .padding(
                    start = sizing.surfacePaddingHorizontal,
                    end = sizing.surfacePaddingHorizontal,
                    top = sizing.surfaceTopPadding,
                    bottom = sizing.surfaceBottomPadding,
                ),
        verticalArrangement = Arrangement.spacedBy(sizing.surfaceContentSpacing),
    ) {
        content()
    }
}

/**
 * Swipe hint bar to signal downward swipe for dismiss.
 */
@Composable
private fun SwipeHandleHint(sizing: InspectionPopupSizing) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = sizing.handleBottomPadding),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .width(26.dp)
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.42f), RoundedCornerShape(50)),
        )
    }
}
