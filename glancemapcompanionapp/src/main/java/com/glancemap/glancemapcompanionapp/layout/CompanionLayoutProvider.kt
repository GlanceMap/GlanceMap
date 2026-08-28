package com.glancemap.glancemapcompanionapp.layout

import android.app.Activity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlin.math.roundToInt

internal val LocalCompanionLayoutContext =
    staticCompositionLocalOf { CompanionLayoutContext.Default }

@Composable
internal fun companionLayoutProvider(
    activity: Activity,
    content: @Composable () -> Unit,
) {
    var contentBoundsInWindow by remember { mutableStateOf<CompanionWindowPixelBounds?>(null) }
    val windowLayoutInfo by
        produceState<WindowLayoutInfo?>(initialValue = null, activity) {
            WindowInfoTracker
                .getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { value = it }
        }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    contentBoundsInWindow = coordinates.toWindowPixelBounds()
                },
    ) {
        val density = LocalDensity.current
        val topology =
            remember(windowLayoutInfo, contentBoundsInWindow, density.density) {
                windowLayoutInfo.toCompanionWindowTopology(
                    contentBounds = contentBoundsInWindow,
                    density = density.density,
                )
            }
        val layoutContext =
            remember(maxWidth, maxHeight, density.fontScale, topology) {
                CompanionLayoutContext(
                    availableWidth = maxWidth,
                    availableHeight = maxHeight,
                    widthClass = companionWidthClass(maxWidth),
                    heightCapacity = companionHeightCapacity(maxHeight),
                    fontScale = density.fontScale,
                    windowTopology = topology,
                )
            }

        CompositionLocalProvider(LocalCompanionLayoutContext provides layoutContext) {
            content()
        }
    }
}

private fun WindowLayoutInfo?.toCompanionWindowTopology(
    contentBounds: CompanionWindowPixelBounds?,
    density: Float,
): CompanionWindowTopology {
    val foldingFeature =
        this
            ?.displayFeatures
            .orEmpty()
            .filterIsInstance<FoldingFeature>()
            .firstOrNull { it.isSeparating }
    val usableBounds = contentBounds
    val foldOrientation =
        when (foldingFeature?.orientation) {
            FoldingFeature.Orientation.VERTICAL -> CompanionFoldOrientation.VERTICAL
            FoldingFeature.Orientation.HORIZONTAL -> CompanionFoldOrientation.HORIZONTAL
            else -> null
        }
    val foldBounds = foldingFeature?.bounds

    return when {
        foldingFeature == null || usableBounds == null -> CompanionWindowTopology.SingleRegion
        foldOrientation == null || foldBounds == null -> CompanionWindowTopology.SingleRegion
        !usableBounds.intersectsSeparatingFold(
            foldBounds =
                CompanionWindowPixelBounds(
                    left = foldBounds.left,
                    top = foldBounds.top,
                    right = foldBounds.right,
                    bottom = foldBounds.bottom,
                ),
            orientation = foldOrientation,
        ) -> CompanionWindowTopology.SingleRegion

        else -> foldingFeature.toCompanionWindowTopology(usableBounds, density)
    }
}

private fun FoldingFeature.toCompanionWindowTopology(
    usableBounds: CompanionWindowPixelBounds,
    density: Float,
): CompanionWindowTopology =
    when (orientation) {
        FoldingFeature.Orientation.VERTICAL ->
            verticalTopology(
                usableBounds = usableBounds,
                density = density,
            )

        FoldingFeature.Orientation.HORIZONTAL ->
            horizontalTopology(
                usableBounds = usableBounds,
                density = density,
            )

        else -> CompanionWindowTopology.SingleRegion
    }

private fun FoldingFeature.verticalTopology(
    usableBounds: CompanionWindowPixelBounds,
    density: Float,
): CompanionWindowTopology.VerticalSeparator {
    val separatorLeft = bounds.left.coerceIn(usableBounds.left, usableBounds.right)
    val separatorRight = bounds.right.coerceIn(separatorLeft, usableBounds.right)

    return CompanionWindowTopology.VerticalSeparator(
        leftWidth = (separatorLeft - usableBounds.left).toDp(density),
        separatorWidth = (separatorRight - separatorLeft).toDp(density),
        rightWidth = (usableBounds.right - separatorRight).toDp(density),
    )
}

private fun FoldingFeature.horizontalTopology(
    usableBounds: CompanionWindowPixelBounds,
    density: Float,
): CompanionWindowTopology.HorizontalSeparator {
    val separatorTop = bounds.top.coerceIn(usableBounds.top, usableBounds.bottom)
    val separatorBottom = bounds.bottom.coerceIn(separatorTop, usableBounds.bottom)

    return CompanionWindowTopology.HorizontalSeparator(
        topHeight = (separatorTop - usableBounds.top).toDp(density),
        separatorHeight = (separatorBottom - separatorTop).toDp(density),
        bottomHeight = (usableBounds.bottom - separatorBottom).toDp(density),
    )
}

internal data class CompanionWindowPixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal enum class CompanionFoldOrientation {
    VERTICAL,
    HORIZONTAL,
}

internal fun CompanionWindowPixelBounds.intersectsSeparatingFold(
    foldBounds: CompanionWindowPixelBounds,
    orientation: CompanionFoldOrientation,
): Boolean =
    when (orientation) {
        CompanionFoldOrientation.VERTICAL ->
            rangeOverlapsOrContainsLine(left, right, foldBounds.left, foldBounds.right) &&
                rangesOverlap(top, bottom, foldBounds.top, foldBounds.bottom)

        CompanionFoldOrientation.HORIZONTAL ->
            rangeOverlapsOrContainsLine(top, bottom, foldBounds.top, foldBounds.bottom) &&
                rangesOverlap(left, right, foldBounds.left, foldBounds.right)
    }

private fun rangeOverlapsOrContainsLine(
    rangeStart: Int,
    rangeEnd: Int,
    foldStart: Int,
    foldEnd: Int,
): Boolean =
    if (foldStart == foldEnd) {
        foldStart in rangeStart..rangeEnd
    } else {
        rangesOverlap(rangeStart, rangeEnd, foldStart, foldEnd)
    }

private fun rangesOverlap(
    firstStart: Int,
    firstEnd: Int,
    secondStart: Int,
    secondEnd: Int,
): Boolean = firstStart < secondEnd && firstEnd > secondStart

private fun LayoutCoordinates.toWindowPixelBounds(): CompanionWindowPixelBounds {
    val position = positionInWindow()
    val left = position.x.roundToInt()
    val top = position.y.roundToInt()
    return CompanionWindowPixelBounds(
        left = left,
        top = top,
        right = left + size.width,
        bottom = top + size.height,
    )
}

private fun Int.toDp(density: Float): Dp = (toFloat() / density).dp
