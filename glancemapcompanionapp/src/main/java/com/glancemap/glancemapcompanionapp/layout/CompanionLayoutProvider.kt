package com.glancemap.glancemapcompanionapp.layout

import android.app.Activity
import android.graphics.Rect
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

    return if (foldingFeature == null || usableBounds == null || !usableBounds.intersects(foldingFeature.bounds)) {
        CompanionWindowTopology.SingleRegion
    } else {
        foldingFeature.toCompanionWindowTopology(usableBounds, density)
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

private data class CompanionWindowPixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun intersects(bounds: Rect): Boolean = overlapsHorizontally(bounds) && overlapsVertically(bounds)

    private fun overlapsHorizontally(bounds: Rect): Boolean = left < bounds.right && right > bounds.left

    private fun overlapsVertically(bounds: Rect): Boolean = top < bounds.bottom && bottom > bounds.top
}

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
