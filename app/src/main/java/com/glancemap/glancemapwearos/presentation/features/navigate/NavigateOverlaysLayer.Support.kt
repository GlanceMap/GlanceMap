package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import java.util.Locale
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

@Composable
internal fun BoxScope.SlopeOverlayStatusIndicator(
    visible: Boolean,
    processing: Boolean,
    progressPercent: Int?,
    enabled: Boolean,
    screenSize: WearScreenSize,
    sideButtonEdgePadding: Dp,
    sideButtonSize: Dp,
    modifier: Modifier = Modifier,
) {
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
    val slopeIndicatorToolGap =
        when (screenSize) {
            WearScreenSize.LARGE -> 5.dp
            WearScreenSize.MEDIUM -> 4.dp
            WearScreenSize.SMALL -> 3.dp
        }
    val slopeIndicatorModifier =
        modifier
            .align(Alignment.CenterEnd)
            .padding(end = sideButtonEdgePadding + sideButtonSize + slopeIndicatorToolGap)

    AnimatedVisibility(
        visible = visible && enabled && processing,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(180)),
        modifier = slopeIndicatorModifier,
    ) {
        val progress = (progressPercent ?: 0).coerceIn(0, 100)
        val sweepDeg by animateFloatAsState(
            targetValue = 360f * (progress / 100f),
            animationSpec = tween(durationMillis = 220),
            label = "slope_render_progress_sweep",
        )

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
                    color = Color(0xFFFFB300),
                    startAngle = -90f,
                    sweepAngle = sweepDeg,
                    useCenter = false,
                    style = Stroke(width = ringStroke, cap = StrokeCap.Round),
                )
            }
            Icon(
                imageVector = Icons.Default.Landscape,
                contentDescription = "Slope rendering in progress",
                modifier = Modifier.size(slopeIndicatorIconSize),
                tint = Color(0xFFFFB300),
            )
        }
    }
}

@Composable
internal fun BoxScope.PanningLiveMetricsOverlay(
    navMode: NavMode,
    liveElevationEnabled: Boolean,
    liveElevationLabel: String?,
    liveDistanceEnabled: Boolean,
    liveDistanceLabel: String?,
    zoomLabelTopPadding: Dp,
    liveElevationIconSize: Dp,
    navButtonBottomPadding: Dp,
    navButtonSize: Dp,
) {
    cappedFontScale {
        if (navMode == NavMode.PANNING && liveElevationEnabled) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = zoomLabelTopPadding + 28.dp)
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_live_elevation_altitude),
                    contentDescription = "live_elevation",
                    modifier = Modifier.size(10.dp),
                    tint = Color(0xFF34D399),
                )
                Text(
                    text = " ${liveElevationLabel ?: "--"}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                )
            }
        }

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
                        contentDescription = "live_elevation_center",
                        modifier = Modifier.size(9.dp),
                        tint = Color(0xFF8B5CF6),
                    )
                }
            }
        }

        if (navMode == NavMode.PANNING && liveDistanceEnabled && !liveDistanceLabel.isNullOrBlank()) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navButtonBottomPadding + navButtonSize + 8.dp)
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_live_distance_step_out),
                    contentDescription = "live_distance",
                    modifier =
                        Modifier
                            .size(10.dp)
                            .rotate(90f),
                    tint = Color.White.copy(alpha = 0.92f),
                )
                Text(
                    text = liveDistanceLabel,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                )
            }
        }
    }
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
                        text = if (poiTapExpanded) "Less" else "More",
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
                            text = "To here",
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
                        contentDescription = "Close POI details",
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
                    buildAnnotatedString {
                        append("Select 2")
                        withStyle(
                            SpanStyle(
                                baselineShift = BaselineShift.Superscript,
                                fontSize = textSize * 0.72f,
                            ),
                        ) {
                            append("nd")
                        }
                        append(" point")
                    },
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
                    contentDescription = "Cancel point B selection",
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

@Suppress("CyclomaticComplexMethod", "FunctionName", "FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun BoxScope.NavModeButtonOverlay(
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
    onToggleOrientation: () -> Unit,
    navigationMarkerAnchorMode: String,
) {
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
    LaunchedEffect(trustState) {
        if (trustState != NavButtonTrustState.SEARCHING) {
            pulseOn = true
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

    Box(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navButtonBottomPadding)
                .size(trustContainerSize)
                .pointerInput(navMode, lastKnownLocation, isOfflineMode) {
                    detectTapGestures(
                        onTap = {
                            if (isOfflineMode) return@detectTapGestures
                            triggerHaptic()
                            if (navMode == NavMode.PANNING) {
                                lastKnownLocation?.let {
                                    mapView.setCenterForNavigationMarker(it, navigationMarkerAnchorMode)
                                }
                                onRecenter()
                                onRecenterRequested()
                            } else {
                                onToggleOrientation()
                            }
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
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
                    contentDescription = "Nav mode",
                    modifier = Modifier.size(navButtonIconSize),
                    tint = navTint,
                )
            }
        }
    }
}

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
            text = "N",
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
                    contentDescription = "North",
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
