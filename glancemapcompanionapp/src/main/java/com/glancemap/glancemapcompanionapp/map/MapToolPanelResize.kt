package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import com.glancemap.glancemapcompanionapp.R
import kotlin.math.roundToInt

@Composable
internal fun mapToolPanelResizeModifier(
    state: MapToolPanelState,
    isWide: Boolean,
    axisSizePx: Int,
    fractionRange: ClosedFloatingPointRange<Float>,
    actions: MapToolScaffoldActions,
): Modifier {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val haptics = LocalHapticFeedback.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return Modifier
        .pointerInput(isWide, isRtl, axisSizePx, fractionRange) {
            var dragFraction = DEFAULT_MAP_TOOL_PANEL_FRACTION
            var didResize = false
            val finishResize = {
                if (didResize) currentActions.onResizeFinished()
                didResize = false
            }
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    dragFraction =
                        if (currentState.mode == MapToolPanelMode.EXPANDED) {
                            fractionRange.endInclusive
                        } else {
                            currentState.splitFraction.coerceIn(fractionRange)
                        }
                },
                onDragEnd = finishResize,
                onDragCancel = finishResize,
                onDrag = { change, amount ->
                    change.consume()
                    if (axisSizePx > 0) {
                        val delta = if (isWide) amount.x * (if (isRtl) -1 else 1) else amount.y
                        if (delta != 0f) {
                            dragFraction = (dragFraction - delta / axisSizePx).coerceIn(fractionRange)
                            currentActions.onResize(dragFraction)
                            didResize = true
                        }
                    }
                },
            )
        }.then(mapToolPanelResizeSemantics(state, fractionRange, actions))
}

@Composable
private fun mapToolPanelResizeSemantics(
    state: MapToolPanelState,
    fractionRange: ClosedFloatingPointRange<Float>,
    actions: MapToolScaffoldActions,
): Modifier {
    val increaseDescription = stringResource(R.string.map_tool_resize_increase)
    val decreaseDescription = stringResource(R.string.map_tool_resize_decrease)
    val sizeDescription =
        if (state.mode == MapToolPanelMode.EXPANDED) {
            stringResource(R.string.map_tool_size_full_screen)
        } else {
            stringResource(
                R.string.map_tool_size_percent,
                (state.splitFraction.coerceIn(fractionRange) * 100).roundToInt(),
            )
        }
    return Modifier.semantics {
        stateDescription = sizeDescription
        customActions =
            listOf(
                CustomAccessibilityAction(increaseDescription) {
                    if (state.mode == MapToolPanelMode.EXPANDED) return@CustomAccessibilityAction false
                    val fraction = state.splitFraction.coerceIn(fractionRange)
                    actions.onResize((fraction + 0.05f).coerceIn(fractionRange))
                    actions.onResizeFinished()
                    true
                },
                CustomAccessibilityAction(decreaseDescription) {
                    val fraction =
                        if (state.mode == MapToolPanelMode.EXPANDED) {
                            fractionRange.endInclusive
                        } else {
                            state.splitFraction.coerceIn(fractionRange)
                        }
                    actions.onResize((fraction - 0.05f).coerceIn(fractionRange))
                    actions.onResizeFinished()
                    true
                },
            )
    }
}
