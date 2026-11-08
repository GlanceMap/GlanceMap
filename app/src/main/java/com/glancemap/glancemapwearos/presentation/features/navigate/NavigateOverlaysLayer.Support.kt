package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocationDisabled
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.ArcPaddingValues
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedScope
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.angularSize
import androidx.wear.compose.foundation.angularSizeDp
import androidx.wear.compose.foundation.background
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.weight
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import com.glancemap.glancemapwearos.presentation.ui.superscriptResourceText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun BoxScope.PanningDistanceGuide(
    navMode: NavMode,
    liveDistanceEnabled: Boolean,
    liveDistanceLineStart: Offset?,
) {
    if (navMode == NavMode.PANNING && liveDistanceEnabled && liveDistanceLineStart != null) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val end = Offset(size.width * 0.5f, size.height * 0.5f)
            drawLine(
                color = Color(0xFF8B5CF6).copy(alpha = 0.90f),
                start = liveDistanceLineStart,
                end = end,
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f), 0f),
            )
        }
    }
}

// Reuse the slope indicator layout and preserve its existing rendering behavior.

/** Slope loading shorter than this shows no indicator, so quick loads do not flash it. */
internal const val SLOPE_INDICATOR_DELAY_MS = 1_000L

/**
 * True once [active] has stayed true for [delayMillis], and false as soon as it turns false, so
 * brief work shows nothing while slower work still shows its progress.
 */
@Composable
internal fun rememberShownWhenSlow(
    active: Boolean,
    delayMillis: Long = SLOPE_INDICATOR_DELAY_MS,
): Boolean {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        slow = false
        if (active) {
            delay(delayMillis)
            slow = true
        }
    }
    return active && slow
}

/** Radius of the ring through the side control buttons, used to place the terrain indicator. */
internal fun terrainIndicatorControlRadius(
    maxWidth: Dp,
    maxHeight: Dp,
    sideButtonEdgePadding: Dp,
    sideButtonSize: Dp,
): Dp = minOf(maxWidth, maxHeight) / 2 - sideButtonEdgePadding - sideButtonSize / 2 - 1.dp

/**
 * Places the 3D and slope indicators at the same spot on the control ring, 30° below the tools
 * button, when applied to an indicator aligned to the end of a full-size box.
 */
internal fun Modifier.terrainIndicatorRingOffset(
    maxWidth: Dp,
    controlRadius: Dp,
): Modifier =
    offset(
        x = -(maxWidth / 2 - controlRadius * 0.8660254f - 24.dp),
        y = controlRadius / 2,
    )

@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun BoxScope.SlopeOverlayStatusIndicator(
    visible: Boolean,
    processing: Boolean,
    progressPercent: Int?,
    enabled: Boolean,
    screenSize: WearScreenSize,
    modifier: Modifier = Modifier,
    terrain3d: Boolean = false,
    renderError: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val terrainError = terrain3d && renderError
    val terrainWaitingForProgress = terrain3d && processing && progressPercent == null
    val slopeIndicatorButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 28.dp
            WearScreenSize.MEDIUM -> 26.dp
            WearScreenSize.SMALL -> 24.dp
        }
    val slopeIndicatorIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 16.dp
            WearScreenSize.MEDIUM -> 15.dp
            WearScreenSize.SMALL -> 14.dp
        }
    val slopeIndicatorModifier = modifier.align(Alignment.CenterEnd)

    AnimatedVisibility(
        visible = visible && enabled && (processing || terrain3d),
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(180)),
        modifier = slopeIndicatorModifier,
    ) {
        val progress = if (terrain3d && !processing) 100 else (progressPercent ?: 0).coerceIn(0, 100)
        val sweepDeg by animateFloatAsState(
            targetValue = 360f * (progress / 100f),
            animationSpec = tween(durationMillis = 220),
            label = "slope_render_progress_sweep",
        )
        val startAngle =
            if (terrainWaitingForProgress && !terrainError) {
                val transition = rememberInfiniteTransition(label = "terrain_loading")
                val angle by transition.animateFloat(
                    initialValue = -90f,
                    targetValue = 270f,
                    animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                    label = "terrain_loading_angle",
                )
                angle
            } else {
                -90f
            }

        val angleLabel = stringResource(R.string.map_3d_viewing_angle)
        // The slope and 3D indicators share one 48 dp box so their rings land on the same spot.
        val target =
            if (onClick != null) {
                Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = angleLabel, onClick = onClick)
            } else {
                Modifier.size(48.dp)
            }
        Box(modifier = target, contentAlignment = Alignment.Center) {
            Box(
                modifier =
                    Modifier
                        .size(slopeIndicatorButtonSize)
                        .background(Color.Black.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(1.dp),
                ) {
                    val ringStroke = (size.minDimension * 0.12f).coerceAtLeast(1.5f)
                    drawArc(
                        color = Color.White.copy(alpha = 0.25f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = ringStroke),
                    )
                    drawArc(
                        color = if (terrainError) Color(0xFFE53935) else Color(0xFFFFB300),
                        startAngle = startAngle,
                        sweepAngle =
                            when {
                                terrainError -> 360f
                                terrainWaitingForProgress -> 100f
                                else -> sweepDeg
                            },
                        useCenter = false,
                        style = Stroke(width = ringStroke, cap = StrokeCap.Round),
                    )
                }
                if (terrain3d) {
                    val description =
                        stringResource(
                            when {
                                terrainError -> R.string.navigate_3d_terrain_partial_error
                                processing -> R.string.navigate_3d_terrain_loading
                                else -> R.string.map_terrain_3d_experimental
                            },
                        )
                    Text(
                        text = if (terrainError) "3D!" else "3D",
                        modifier = Modifier.semantics { contentDescription = description },
                        color = if (terrainError) Color(0xFFE53935) else Color(0xFFFFB300),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = stringResource(R.string.navigate_slope_rendering_in_progress),
                        modifier = Modifier.size(slopeIndicatorIconSize),
                        tint = Color(0xFFFFB300),
                    )
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod", "FunctionName", "LongMethod", "LongParameterList")
@Composable
internal fun BoxScope.PanningLiveMetricsOverlay(
    navMode: NavMode,
    liveElevationEnabled: Boolean,
    liveElevationLabel: String?,
    liveDistanceEnabled: Boolean,
    liveDistanceLabel: String?,
    liveElevationIconSize: Dp,
    edgePadding: Dp,
    sideButtonSize: Dp,
    clockWidth: Dp,
) {
    cappedFontScale(maxFontScale = 1.15f) {
        if (navMode == NavMode.PANNING && (liveElevationEnabled || liveDistanceEnabled)) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(liveElevationIconSize),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(14.dp)
                            .background(Color.Black.copy(alpha = 0.34f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Adjust,
                        contentDescription = stringResource(R.string.navigate_live_elevation_center),
                        modifier = Modifier.size(9.dp),
                        tint = Color(0xFF8B5CF6),
                    )
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val radius = minOf(maxWidth.value, maxHeight.value) / 2f
            val arc = liveMetricUpperArc(radius, edgePadding.value, sideButtonSize.value, clockWidth.value)
            if (navMode == NavMode.PANNING && arc.sweep > 0f) {
                LiveMetricsUpperArc(
                    elevationText =
                        if (liveElevationEnabled) {
                            stringResource(R.string.navigation_spaced_elevation, liveElevationLabel ?: "--")
                        } else {
                            null
                        },
                    distanceText = liveDistanceLabel.takeIf { liveDistanceEnabled && !it.isNullOrBlank() },
                    edgePadding = edgePadding,
                    arc = arc,
                    radius = radius,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun LiveMetricsUpperArc(
    elevationText: String?,
    distanceText: String?,
    edgePadding: Dp,
    arc: LiveMetricArc,
    radius: Float,
) {
    if (elevationText == null && distanceText == null) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bothVisible = elevationText != null && distanceText != null
    val firstText = if (rtl) elevationText else distanceText
    val secondText = if (rtl) distanceText else elevationText
    val scale = liveMetricScale(elevationText, distanceText, edgePadding, arc, radius)
    if (scale <= 0f) return
    CurvedLayout(
        modifier = Modifier.fillMaxSize(),
        anchor = arc.start,
        anchorType = if (rtl) AnchorType.End else AnchorType.Start,
        angularDirection = CurvedDirection.Angular.Normal,
    ) {
        curvedRow(modifier = CurvedModifier.angularSize(arc.sweep)) {
            curvedComposable(modifier = CurvedModifier.weight(1f)) { }
            liveMetricClockSideInset(bothVisible, rtl, scale)
            if (firstText != null) {
                liveMetricChip(firstText, edgePadding, elevation = rtl, scale = scale)
            }
            if (bothVisible) {
                // Rounded chip ends extend beyond their measured arc.
                curvedComposable(modifier = CurvedModifier.angularSizeDp(14.dp * scale)) { }
                curvedComposable(modifier = CurvedModifier.weight(1f)) { }
            }
            if (secondText != null) {
                liveMetricChip(secondText, edgePadding, elevation = !rtl, scale = scale)
            }
            liveMetricClockSideInset(bothVisible, !rtl, scale)
            curvedComposable(modifier = CurvedModifier.weight(1f)) { }
        }
    }
}

private fun CurvedScope.liveMetricClockSideInset(
    bothVisible: Boolean,
    clockSide: Boolean,
    scale: Float,
) {
    if (bothVisible && clockSide) {
        curvedComposable(modifier = CurvedModifier.angularSizeDp(4.dp * scale)) { }
    }
}

@Composable
private fun liveMetricScale(
    elevationText: String?,
    distanceText: String?,
    edgePadding: Dp,
    arc: LiveMetricArc,
    radius: Float,
): Float {
    if (elevationText == null || distanceText == null) return 1f
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val elevationSize = textMeasurer.measure(elevationText, style = textStyle).size
    val distanceSize = textMeasurer.measure(distanceText, style = textStyle).size
    val textWidth = (elevationSize.width + distanceSize.width) / density.density
    val textHeight = maxOf(elevationSize.height, distanceSize.height) / density.density
    val chipThickness = maxOf(textHeight, 11f) + 2f
    // Reserve the rounded outer ends and the gap between the two chips once.
    val contentWidth = liveMetricCompactContentWidth(textWidth, chipThickness)
    val innerRadius = (radius - edgePadding.value - chipThickness).coerceAtLeast(0f)
    val available = innerRadius * arc.sweep * PI.toFloat() / 180f
    return liveMetricFitScale(available, contentWidth)
}

private fun CurvedScope.liveMetricChip(
    text: String,
    edgePadding: Dp,
    elevation: Boolean,
    scale: Float,
) {
    curvedRow(
        modifier =
            CurvedModifier
                .padding(ArcPaddingValues(outer = edgePadding))
                .background(Color.Black.copy(alpha = 0.94f), cap = StrokeCap.Round)
                // Let the content extend into the rounded ends instead of leaving them empty.
                .padding(radial = 1.dp * scale, angular = -LIVE_METRIC_END_TRIM_DP.dp * scale),
    ) {
        curvedComposable {
            Icon(
                painter =
                    painterResource(
                        if (elevation) {
                            R.drawable.ic_live_elevation_altitude
                        } else {
                            R.drawable.ic_live_distance_step_out
                        },
                    ),
                contentDescription =
                    stringResource(
                        if (elevation) R.string.navigate_live_elevation else R.string.navigate_live_distance,
                    ),
                modifier = Modifier.size(11.dp * scale).rotate(if (elevation) 0f else 90f),
                tint = if (elevation) Color(0xFF34D399) else Color.White.copy(alpha = 0.92f),
            )
        }
        basicCurvedText(
            text = text,
            style = CurvedTextStyle(color = Color.White, fontSize = 11.sp * scale, fontWeight = FontWeight.Medium),
        )
    }
}

internal data class LiveMetricArc(
    val start: Float,
    val sweep: Float,
)

private const val LIVE_METRIC_END_TRIM_DP = 3f

internal fun liveMetricCompactContentWidth(
    textWidth: Float,
    chipThickness: Float,
): Float = textWidth + 22f - 4f * LIVE_METRIC_END_TRIM_DP + chipThickness + 18f

internal fun liveMetricFitScale(
    available: Float,
    contentWidth: Float,
): Float = if (contentWidth > 0f) (available / contentWidth).coerceIn(0f, 1f) else 1f

internal fun liveMetricUpperArc(
    radius: Float,
    edgePadding: Float,
    buttonSize: Float,
    clockWidth: Float,
): LiveMetricArc {
    val centerRadius = radius - edgePadding - buttonSize / 2f
    if (centerRadius <= buttonSize / 2f || radius <= edgePadding) return LiveMetricArc(180f, 0f)
    val buttonHalfAngle = asin((buttonSize / 2f / centerRadius).coerceIn(0f, 1f)) * 180f / PI.toFloat()
    val clockHalfAngle = asin((clockWidth / 2f / (radius - edgePadding)).coerceIn(0f, 1f)) * 180f / PI.toFloat()
    val start = 180f + buttonHalfAngle
    return LiveMetricArc(start, (270f - clockHalfAngle - start).coerceAtLeast(0f))
}

@Composable
internal fun BoxScope.PoiTapMessageOverlay(
    poiTapMessage: String?,
    poiTapCanExpand: Boolean,
    poiTapCanCreateGpx: Boolean,
    poiTapExpanded: Boolean,
    onPoiTapExpandToggle: () -> Unit,
    onPoiTapCreateGpx: () -> Unit,
    onPoiTapDismiss: () -> Unit,
    onPoiTapScrollInProgressChanged: (Boolean) -> Unit,
    screenSize: WearScreenSize,
    sideButtonEdgePadding: Dp,
    sideButtonSize: Dp,
    navButtonBottomPadding: Dp,
    navButtonSize: Dp,
) {
    AnimatedVisibility(
        visible = poiTapMessage != null,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(200)),
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = sideButtonEdgePadding + sideButtonSize + 8.dp,
                    end = sideButtonEdgePadding + sideButtonSize + 8.dp,
                    top =
                        when (screenSize) {
                            WearScreenSize.LARGE -> 36.dp
                            WearScreenSize.MEDIUM -> 32.dp
                            WearScreenSize.SMALL -> 28.dp
                        },
                    bottom = navButtonBottomPadding + navButtonSize + 12.dp,
                ),
    ) {
        val popupMaxWidth =
            when (screenSize) {
                WearScreenSize.LARGE -> 176.dp
                WearScreenSize.MEDIUM -> 162.dp
                WearScreenSize.SMALL -> 148.dp
            }
        val popupExpandedMaxHeight =
            when (screenSize) {
                WearScreenSize.LARGE -> 120.dp
                WearScreenSize.MEDIUM -> 108.dp
                WearScreenSize.SMALL -> 96.dp
            }
        val popupActionSpacing =
            when (screenSize) {
                WearScreenSize.LARGE -> 5.dp
                WearScreenSize.MEDIUM -> 4.dp
                WearScreenSize.SMALL -> 3.dp
            }
        val popupSecondaryActionHorizontalPadding =
            when (screenSize) {
                WearScreenSize.LARGE -> 8.dp
                WearScreenSize.MEDIUM -> 7.dp
                WearScreenSize.SMALL -> 6.dp
            }
        val popupSecondaryActionVerticalPadding =
            when (screenSize) {
                WearScreenSize.LARGE -> 3.dp
                WearScreenSize.MEDIUM -> 3.dp
                WearScreenSize.SMALL -> 2.dp
            }
        val popupSecondaryActionFontSize =
            when (screenSize) {
                WearScreenSize.LARGE -> 10.sp
                WearScreenSize.MEDIUM -> 10.sp
                WearScreenSize.SMALL -> 10.sp
            }
        val popupSecondaryActionLineHeight =
            when (screenSize) {
                WearScreenSize.LARGE -> 10.sp
                WearScreenSize.MEDIUM -> 10.sp
                WearScreenSize.SMALL -> 10.sp
            }
        val popupPrimaryActionHorizontalPadding =
            when (screenSize) {
                WearScreenSize.LARGE -> 9.dp
                WearScreenSize.MEDIUM -> 8.dp
                WearScreenSize.SMALL -> 7.dp
            }
        val popupPrimaryActionVerticalPadding =
            when (screenSize) {
                WearScreenSize.LARGE -> 4.dp
                WearScreenSize.MEDIUM -> 4.dp
                WearScreenSize.SMALL -> 3.dp
            }
        val popupPrimaryActionFontSize =
            when (screenSize) {
                WearScreenSize.LARGE -> 10.sp
                WearScreenSize.MEDIUM -> 10.sp
                WearScreenSize.SMALL -> 10.sp
            }
        val popupPrimaryActionLineHeight =
            when (screenSize) {
                WearScreenSize.LARGE -> 11.sp
                WearScreenSize.MEDIUM -> 11.sp
                WearScreenSize.SMALL -> 10.sp
            }
        val popupCloseButtonSize =
            when (screenSize) {
                WearScreenSize.LARGE -> 26.dp
                WearScreenSize.MEDIUM -> 24.dp
                WearScreenSize.SMALL -> 22.dp
            }
        val popupCloseIconSize =
            when (screenSize) {
                WearScreenSize.LARGE -> 13.dp
                WearScreenSize.MEDIUM -> 12.dp
                WearScreenSize.SMALL -> 11.dp
            }
        val useExpandedScroll =
            remember(poiTapMessage) {
                val message = poiTapMessage.orEmpty()
                message.length > 170 || message.count { it == '\n' } > 5
            }
        val density = LocalDensity.current
        val popupScrollState = rememberScrollState()
        var popupViewportHeightPx by remember { mutableIntStateOf(0) }
        LaunchedEffect(poiTapMessage, poiTapExpanded) {
            if (!poiTapExpanded) popupScrollState.scrollTo(0)
        }
        LaunchedEffect(poiTapMessage, poiTapExpanded, useExpandedScroll) {
            if (poiTapMessage == null || !poiTapExpanded || !useExpandedScroll) {
                onPoiTapScrollInProgressChanged(false)
                return@LaunchedEffect
            }
            snapshotFlow { popupScrollState.isScrollInProgress }.collect { isScrolling ->
                onPoiTapScrollInProgressChanged(isScrolling)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!poiTapExpanded) {
                Text(
                    text = poiTapMessage.orEmpty(),
                    modifier =
                        Modifier
                            .widthIn(max = popupMaxWidth)
                            .background(Color.Black.copy(alpha = 0.84f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
            } else if (useExpandedScroll) {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = popupMaxWidth)
                            .background(Color.Black.copy(alpha = 0.84f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = poiTapMessage.orEmpty(),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = popupExpandedMaxHeight)
                                .onSizeChanged { popupViewportHeightPx = it.height }
                                .verticalScroll(popupScrollState)
                                .padding(end = 6.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.sp,
                        lineHeight = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (popupScrollState.maxValue > 0 && popupViewportHeightPx > 0) {
                        Canvas(
                            modifier =
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(2.dp)
                                    .height(with(density) { popupViewportHeightPx.toDp() })
                                    .padding(vertical = 2.dp),
                        ) {
                            val maxScroll = popupScrollState.maxValue.toFloat().coerceAtLeast(1f)
                            val viewport = popupViewportHeightPx.toFloat().coerceAtLeast(1f)
                            val content = viewport + popupScrollState.maxValue.toFloat()
                            val thumbHeight =
                                (size.height * (viewport / content))
                                    .coerceAtLeast(size.height * 0.18f)
                                    .coerceAtMost(size.height)
                            val thumbTop =
                                (size.height - thumbHeight) *
                                    (popupScrollState.value.toFloat() / maxScroll)
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.22f),
                                size = Size(size.width, size.height),
                            )
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.85f),
                                topLeft = Offset(0f, thumbTop),
                                size = Size(size.width, thumbHeight),
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = poiTapMessage.orEmpty(),
                    modifier =
                        Modifier
                            .widthIn(max = popupMaxWidth)
                            .background(Color.Black.copy(alpha = 0.84f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Row(
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .widthIn(max = popupMaxWidth),
                horizontalArrangement = Arrangement.spacedBy(popupActionSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (poiTapCanExpand) {
                    Text(
                        text =
                            if (poiTapExpanded) {
                                stringResource(
                                    R.string.navigate_less,
                                )
                            } else {
                                stringResource(R.string.navigate_more)
                            },
                        modifier =
                            Modifier
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
                                .clickable(onClick = onPoiTapExpandToggle)
                                .padding(
                                    horizontal = popupSecondaryActionHorizontalPadding,
                                    vertical = popupSecondaryActionVerticalPadding,
                                ),
                        color = Color(0xFFD3E3FF),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = popupSecondaryActionFontSize,
                        lineHeight = popupSecondaryActionLineHeight,
                        maxLines = 1,
                    )
                }
                if (poiTapCanCreateGpx) {
                    Row(
                        modifier =
                            Modifier
                                .background(Color(0xFF8B5CF6).copy(alpha = 0.88f), RoundedCornerShape(12.dp))
                                .clickable(onClick = onPoiTapCreateGpx)
                                .padding(
                                    horizontal = popupPrimaryActionHorizontalPadding,
                                    vertical = popupPrimaryActionVerticalPadding,
                                ),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Route,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color.White,
                        )
                        Text(
                            text = stringResource(R.string.navigate_to_here),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = popupPrimaryActionFontSize,
                            lineHeight = popupPrimaryActionLineHeight,
                        )
                    }
                }
                IconButton(
                    onClick = onPoiTapDismiss,
                    modifier = Modifier.size(popupCloseButtonSize),
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.75f),
                            contentColor = Color.White,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.navigate_close_poi_details),
                        modifier = Modifier.size(popupCloseIconSize),
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun BoxScope.GpxInspectionBSelectionPromptOverlay(
    visible: Boolean,
    screenSize: WearScreenSize,
    navButtonBottomPadding: Dp,
    navButtonSize: Dp,
    onCancel: () -> Unit,
) {
    if (!visible) return

    val promptBottomPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 8.dp
            WearScreenSize.MEDIUM -> 7.dp
            WearScreenSize.SMALL -> 6.dp
        }
    val promptHorizontalPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 10.dp
            WearScreenSize.MEDIUM -> 9.dp
            WearScreenSize.SMALL -> 8.dp
        }
    val promptVerticalPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 4.dp
            WearScreenSize.MEDIUM -> 4.dp
            WearScreenSize.SMALL -> 3.dp
        }
    val closeButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 24.dp
            WearScreenSize.MEDIUM -> 22.dp
            WearScreenSize.SMALL -> 20.dp
        }
    val closeIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 12.dp
            WearScreenSize.MEDIUM -> 11.dp
            WearScreenSize.SMALL -> 10.dp
        }
    val textSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 10.sp
            WearScreenSize.MEDIUM -> 10.sp
            WearScreenSize.SMALL -> 10.sp
        }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navButtonBottomPadding + navButtonSize + promptBottomPadding),
    ) {
        Row(
            modifier =
                Modifier
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                    .padding(start = promptHorizontalPadding, end = 3.dp)
                    .padding(vertical = promptVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    superscriptResourceText(R.string.navigation_select_second_point_label, textSize * 0.72f),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = textSize,
                lineHeight = textSize,
                maxLines = 1,
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(closeButtonSize),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.navigate_cancel_point_b_selection),
                    modifier = Modifier.size(closeIconSize),
                )
            }
        }
    }
}

private enum class NavButtonTrustState {
    GOOD,
    SEARCHING,
    CAUTION,
    LOST,
    UNAVAILABLE,
}

internal enum class NavModeAction {
    RECENTER,
    TOGGLE_ORIENTATION,
}

internal fun navModeButtonAction(
    navMode: NavMode,
): NavModeAction = if (navMode == NavMode.PANNING) NavModeAction.RECENTER else NavModeAction.TOGGLE_ORIENTATION

@Suppress("CyclomaticComplexMethod", "FunctionName", "FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun BoxScope.NavModeButtonOverlay(
    visible: Boolean,
    mapView: MapView,
    navMode: NavMode,
    isOfflineMode: Boolean,
    gpsIndicatorState: GpsFixIndicatorState,
    watchGpsDegradedWarning: Boolean,
    lastKnownLocation: LatLong?,
    triggerHaptic: () -> Unit,
    navButtonBottomPadding: Dp,
    navButtonSize: Dp,
    navButtonIconSize: Dp,
    onRecenter: () -> Unit,
    onRecenterRequested: () -> Unit,
    autoRecenterEnabled: Boolean,
    autoRecenterDelay: Int,
    onToggleAutoRecenter: () -> Unit,
    onToggleOrientation: () -> Unit,
    navigationMarkerAnchorMode: String,
    isScreenInteractive: Boolean,
    onTerrainRecenter: ((LatLong) -> Unit)? = null,
) {
    if (!visible) return
    val navIcon =
        when {
            isOfflineMode -> Icons.Default.LocationDisabled
            navMode == NavMode.PANNING -> Icons.Default.MyLocation
            navMode == NavMode.COMPASS_FOLLOW -> Icons.Default.Explore
            else -> Icons.Default.Navigation
        }
    val navTint =
        when {
            isOfflineMode -> Color(0xFFE53935)
            navMode != NavMode.PANNING -> MaterialTheme.colorScheme.primary
            else -> Color.White
        }
    val trustState =
        resolveNavButtonTrustState(
            isOfflineMode = isOfflineMode,
            gpsIndicatorState = gpsIndicatorState,
            watchGpsDegradedWarning = watchGpsDegradedWarning,
        )
    var pulseOn by remember(trustState) { mutableStateOf(true) }
    LaunchedEffect(trustState, isScreenInteractive) {
        if (!shouldRunSearchingNavButtonPulse(isScreenInteractive, trustState == NavButtonTrustState.SEARCHING)) {
            if (trustState != NavButtonTrustState.SEARCHING) {
                pulseOn = true
            }
            return@LaunchedEffect
        }
        pulseOn = true
        while (true) {
            delay(420L)
            pulseOn = !pulseOn
        }
    }
    val trustContainerSize = navButtonSize + 6.dp
    val trustRingColor =
        when (trustState) {
            NavButtonTrustState.SEARCHING ->
                if (pulseOn) {
                    Color(0xFF81D4FA)
                } else {
                    Color(0xFF81D4FA).copy(alpha = 0.32f)
                }
            NavButtonTrustState.CAUTION -> Color(0xFFFFB74D).copy(alpha = 0.92f)
            NavButtonTrustState.LOST -> Color(0xFFEF5350).copy(alpha = 0.95f)
            NavButtonTrustState.UNAVAILABLE -> Color(0xFFEF5350).copy(alpha = 0.95f)
            NavButtonTrustState.GOOD -> Color.Transparent
        }
    val trustGlowColor =
        when (trustState) {
            NavButtonTrustState.SEARCHING ->
                if (pulseOn) {
                    Color(0xFF29B6F6).copy(alpha = 0.26f)
                } else {
                    Color(0xFF29B6F6).copy(alpha = 0.08f)
                }
            NavButtonTrustState.CAUTION -> Color(0xFFFFA726).copy(alpha = 0.16f)
            NavButtonTrustState.LOST -> Color(0xFFE53935).copy(alpha = 0.18f)
            NavButtonTrustState.UNAVAILABLE -> Color(0xFFE53935).copy(alpha = 0.18f)
            NavButtonTrustState.GOOD -> Color.Transparent
        }
    val trustRingStroke = 2.dp
    val autoRecenterDescription =
        if (autoRecenterEnabled) {
            stringResource(R.string.navigation_auto_recenter_on, autoRecenterDelay)
        } else {
            stringResource(R.string.navigation_auto_recenter_off)
        }
    val toggleAutoRecenterLabel = stringResource(R.string.navigation_toggle_auto_recenter)
    var autoRecenterFeedback by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(autoRecenterFeedback) {
        if (autoRecenterFeedback != null) {
            delay(2_000L)
            autoRecenterFeedback = null
        }
    }

    Box(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navButtonBottomPadding)
                .size(maxOf(48.dp, trustContainerSize))
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    enabled = !isOfflineMode,
                    role = Role.Button,
                    onLongClickLabel = toggleAutoRecenterLabel,
                    hapticFeedbackEnabled = false,
                    onLongClick = {
                        if (!isOfflineMode) {
                            triggerHaptic()
                            autoRecenterFeedback = !autoRecenterEnabled
                            onToggleAutoRecenter()
                        }
                    },
                    onClick = {
                        triggerHaptic()
                        if (navModeButtonAction(navMode) == NavModeAction.RECENTER) {
                            lastKnownLocation?.let {
                                if (onTerrainRecenter != null) {
                                    onTerrainRecenter(it)
                                } else {
                                    mapView.setCenterForNavigationMarker(it, navigationMarkerAnchorMode)
                                }
                            }
                            onRecenter()
                            onRecenterRequested()
                        } else {
                            onToggleOrientation()
                        }
                    },
                ).semantics { stateDescription = autoRecenterDescription },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Reserve real hit bounds above the native map without moving the visible button.
        Box(Modifier.size(trustContainerSize), contentAlignment = Alignment.Center) {
            if (trustState != NavButtonTrustState.GOOD) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = trustRingStroke.toPx()
                    val radius = size.minDimension / 2f
                    drawCircle(
                        color = trustGlowColor,
                        radius = radius,
                    )
                    drawCircle(
                        color = trustRingColor,
                        radius = radius - strokePx / 2f,
                        style = Stroke(width = strokePx),
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .size(navButtonSize)
                        .background(
                            Color.Black.copy(
                                alpha =
                                    if (trustState == NavButtonTrustState.SEARCHING && pulseOn) {
                                        0.78f
                                    } else {
                                        0.7f
                                    },
                            ),
                            CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (navMode == NavMode.NORTH_UP_FOLLOW) {
                    NorthUpLockedModeIcon(
                        iconSize = navButtonIconSize,
                        tint = navTint,
                    )
                } else {
                    Icon(
                        imageVector = navIcon,
                        contentDescription = stringResource(R.string.navigate_nav_mode),
                        modifier = Modifier.size(navButtonIconSize),
                        tint = navTint,
                    )
                }
            }
            if (autoRecenterEnabled) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(10.dp)
                            .background(Color.Black.copy(alpha = 0.88f), CircleShape)
                            .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    cappedFontScale(maxFontScale = 1f) {
                        Text(
                            text = stringResource(R.string.navigation_auto_recenter_badge),
                            color = Color.White,
                            fontSize = 8.sp,
                            lineHeight = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    autoRecenterFeedback?.let { enabled ->
        cappedFontScale(maxFontScale = 1f) {
            RecordingStatusMessageChip(
                message =
                    if (enabled) {
                        stringResource(R.string.navigation_auto_recenter_on, autoRecenterDelay)
                    } else {
                        stringResource(R.string.navigation_auto_recenter_off)
                    },
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

internal fun shouldRunSearchingNavButtonPulse(
    isScreenInteractive: Boolean,
    isSearching: Boolean,
): Boolean = isScreenInteractive && isSearching

private fun resolveNavButtonTrustState(
    isOfflineMode: Boolean,
    gpsIndicatorState: GpsFixIndicatorState,
    watchGpsDegradedWarning: Boolean,
): NavButtonTrustState {
    if (isOfflineMode) return NavButtonTrustState.GOOD
    return when {
        gpsIndicatorState == GpsFixIndicatorState.UNAVAILABLE -> NavButtonTrustState.UNAVAILABLE
        gpsIndicatorState == GpsFixIndicatorState.SEARCHING -> NavButtonTrustState.SEARCHING
        gpsIndicatorState == GpsFixIndicatorState.LOST -> NavButtonTrustState.LOST
        watchGpsDegradedWarning || gpsIndicatorState == GpsFixIndicatorState.POOR -> NavButtonTrustState.CAUTION
        else -> NavButtonTrustState.GOOD
    }
}

@Composable
internal fun NorthUpLockedModeIcon(
    iconSize: Dp,
    tint: Color,
) {
    Box(
        modifier = Modifier.size(iconSize),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.54f
            val stroke = w * 0.09f
            val ringRadius = w * 0.44f
            val ringColor = tint.copy(alpha = 0.95f)

            drawArc(
                color = ringColor,
                startAngle = -70f,
                sweepAngle = 320f,
                useCenter = false,
                topLeft = Offset(cx - ringRadius, cy - ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(width = stroke * 0.72f, cap = StrokeCap.Round),
            )

            val tip = Offset(cx, h * 0.23f)
            val rightBase = Offset(w * 0.72f, h * 0.73f)
            val pivot = Offset(cx, h * 0.58f)
            val leftBase = Offset(w * 0.28f, h * 0.73f)

            val arrowPath =
                Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(rightBase.x, rightBase.y)
                    lineTo(pivot.x, pivot.y)
                    lineTo(leftBase.x, leftBase.y)
                    close()
                }
            drawPath(
                path = arrowPath,
                color = ringColor,
            )

            val innerPath =
                Path().apply {
                    moveTo(cx, h * 0.33f)
                    lineTo(w * 0.40f, h * 0.62f)
                    lineTo(cx, h * 0.57f)
                    close()
                }
            drawPath(
                path = innerPath,
                color = Color.Black.copy(alpha = 0.88f),
            )
        }
        Text(
            text = stringResource(R.string.navigate_n),
            color = tint.copy(alpha = 0.98f),
            fontWeight = FontWeight.ExtraBold,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-1).dp),
        )
    }
}

@Composable
internal fun StandardScaleBar(
    width: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .width(width)
                .height(10.dp),
    ) {
        val borderColor = Color.White.copy(alpha = 0.75f)
        val barColor = Color.Black.copy(alpha = 0.95f)
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(borderColor, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(barColor, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(10.dp)
                    .background(borderColor, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .width(2.dp)
                    .height(10.dp)
                    .background(barColor, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(3.dp)
                    .height(10.dp)
                    .background(borderColor, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(2.dp)
                    .height(10.dp)
                    .background(barColor, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
internal fun BoxScope.NorthIndicatorOverlay(
    northIndicatorMode: String,
    navMode: NavMode,
    mapRotationDeg: Float,
    compassHeadingDeg: Float,
    indicatorButtonSize: Dp,
    indicatorIconSize: Dp,
) {
    val mode = northIndicatorMode.trim().uppercase(Locale.US)
    val isPanning = navMode == NavMode.PANNING

    val showNorthIndicator =
        when (mode) {
            "ALWAYS" -> true
            "COMPASS_ONLY" -> navMode == NavMode.COMPASS_FOLLOW || isPanning
            "NORTH_UP_ONLY" -> navMode == NavMode.NORTH_UP_FOLLOW || isPanning
            "NEVER" -> false
            else -> false
        }
    if (!showNorthIndicator) return

    val effectiveMapRotationDeg =
        if (navMode == NavMode.NORTH_UP_FOLLOW) {
            -compassHeadingDeg
        } else {
            mapRotationDeg
        }
    val northAnchor = mapRotationToNorthAnchor(effectiveMapRotationDeg)
    CurvedLayout(
        modifier = Modifier.fillMaxSize(),
        anchor = northAnchor,
        anchorType = AnchorType.Center,
    ) {
        curvedComposable {
            IconButton(
                onClick = { },
                modifier = Modifier.size(indicatorButtonSize),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.Red,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = stringResource(R.string.navigate_north),
                    modifier = Modifier.size(indicatorIconSize),
                    tint = Color.Red,
                )
            }
        }
    }
}

internal fun mapRotationToNorthAnchor(mapRotationDeg: Float): Float {
    val rawAnchor = 270f + mapRotationDeg
    return ((rawAnchor % 360f) + 360f) % 360f
}

internal fun projectLatLongToScreenOffset(
    mapView: MapView,
    latLong: LatLong,
    mapRotationDeg: Float,
    rotationPivot: ScreenAnchor = ScreenAnchor(mapView.width / 2.0, mapView.height / 2.0),
): Offset? {
    if (mapView.width <= 0 || mapView.height <= 0) return null

    return runCatching {
        mapView.mapViewProjection.toPixels(latLong)
    }.getOrNull()?.let { mapPoint ->
        val screen =
            rotateMapSpaceToScreen(
                point = ScreenAnchor(mapPoint.x, mapPoint.y),
                mapWidth = mapView.width.toDouble(),
                mapHeight = mapView.height.toDouble(),
                mapRotationDeg = mapRotationDeg.toDouble(),
                pivot = rotationPivot,
            )
        Offset(screen.x.toFloat(), screen.y.toFloat())
    }
}

internal fun rotateMapSpaceToScreen(
    point: ScreenAnchor,
    mapWidth: Double,
    mapHeight: Double,
    mapRotationDeg: Double,
    pivot: ScreenAnchor = ScreenAnchor(mapWidth / 2.0, mapHeight / 2.0),
): ScreenAnchor {
    if (mapWidth <= 0.0 || mapHeight <= 0.0 || kotlin.math.abs(mapRotationDeg) < 0.001) return point

    val rad = Math.toRadians(mapRotationDeg)
    val c = cos(rad)
    val s = sin(rad)

    val dx = point.x - pivot.x
    val dy = point.y - pivot.y

    val rx = dx * c - dy * s
    val ry = dx * s + dy * c

    return ScreenAnchor(pivot.x + rx, pivot.y + ry)
}
