package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

private const val PHONE_MAP_ZOOM_REPEAT_INTERVAL_MS = 120L

@Composable
internal fun PhoneMapZoomButton(
    onZoom: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = onZoom,
        modifier = modifier.size(40.dp).repeatZoomOnLongPress(onZoom),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Modifier.repeatZoomOnLongPress(onZoom: () -> Unit): Modifier {
    val currentOnZoom by rememberUpdatedState(onZoom)
    val longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    return pointerInput(longPressTimeoutMillis) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var remainingMillis = longPressTimeoutMillis
            var lastEventUptimeMillis = down.uptimeMillis
            var repeatStarted = false
            var repeatStartUptimeMillis = 0L
            while (remainingMillis > 0 && !repeatStarted) {
                val event = withTimeoutOrNull(remainingMillis) { awaitPointerEvent(PointerEventPass.Initial) }
                if (event == null) {
                    repeatStarted = true
                    repeatStartUptimeMillis = down.uptimeMillis + longPressTimeoutMillis
                } else {
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    remainingMillis -= (change.uptimeMillis - lastEventUptimeMillis).coerceAtLeast(0)
                    lastEventUptimeMillis = change.uptimeMillis
                    repeatStarted = remainingMillis <= 0
                    if (repeatStarted) {
                        repeatStartUptimeMillis = maxOf(down.uptimeMillis + longPressTimeoutMillis, change.uptimeMillis)
                    }
                }
            }
            if (repeatStarted) {
                currentOnZoom()
                var nextRepeatUptimeMillis = repeatStartUptimeMillis + PHONE_MAP_ZOOM_REPEAT_INTERVAL_MS
                var lastObservedUptimeMillis = repeatStartUptimeMillis
                while (true) {
                    val waitMillis = (nextRepeatUptimeMillis - lastObservedUptimeMillis).coerceAtLeast(0)
                    val event = withTimeoutOrNull(waitMillis) { awaitPointerEvent(PointerEventPass.Initial) }
                    if (event == null) {
                        currentOnZoom()
                        lastObservedUptimeMillis = nextRepeatUptimeMillis
                        nextRepeatUptimeMillis += PHONE_MAP_ZOOM_REPEAT_INTERVAL_MS
                    } else {
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) break
                        while (nextRepeatUptimeMillis <= change.uptimeMillis) {
                            currentOnZoom()
                            nextRepeatUptimeMillis += PHONE_MAP_ZOOM_REPEAT_INTERVAL_MS
                        }
                        lastObservedUptimeMillis = maxOf(lastObservedUptimeMillis, change.uptimeMillis)
                        change.consume()
                        if (!change.pressed) break
                    }
                }
            }
        }
    }
}
