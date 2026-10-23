package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.MapFocusControl
import kotlinx.coroutines.delay

@Stable
internal class NavigateMapFocusState {
    var mapFocusMode by mutableStateOf(false)
        private set
    var hintVisible by mutableStateOf(false)
        private set
    var exitHighlightVisible by mutableStateOf(false)
        private set
    private var hintShown = false

    fun enter(onDismissShortcuts: () -> Unit) {
        onDismissShortcuts()
        mapFocusMode = true
        exitHighlightVisible = true
        hintVisible = !hintShown
        hintShown = true
    }

    fun exit() {
        mapFocusMode = false
        hintVisible = false
        exitHighlightVisible = false
    }

    fun dismissExitHighlight() {
        exitHighlightVisible = false
    }

    fun dismissHint() {
        hintVisible = false
    }
}

internal data class MapFocusChromePolicy(
    val showRoutineChrome: Boolean,
    val showExitControl: Boolean,
    val showToolsControl: Boolean,
    private val visibleControls: Set<MapFocusControl> = emptySet(),
) {
    fun shows(control: MapFocusControl): Boolean = showRoutineChrome || control in visibleControls
}

internal fun mapFocusChromePolicy(
    mapFocusMode: Boolean,
    dashboardFullscreen: Boolean,
    visibleControls: Set<MapFocusControl> = emptySet(),
    toolsAvailable: Boolean = true,
): MapFocusChromePolicy {
    val showTools =
        toolsAvailable && !dashboardFullscreen && (!mapFocusMode || MapFocusControl.TOOLS_BUTTON in visibleControls)
    return MapFocusChromePolicy(
        showRoutineChrome = !mapFocusMode,
        showExitControl = mapFocusMode && !dashboardFullscreen && !showTools,
        showToolsControl = showTools,
        visibleControls = visibleControls,
    )
}

@Composable
@Suppress("FunctionNaming")
internal fun MapFocusModeEffects(
    state: NavigateMapFocusState,
    dashboardFullscreen: Boolean,
    onExitMapFocus: (String) -> Unit,
) {
    // Existing dashboards take over normally and return to the ordinary map when closed.
    LaunchedEffect(dashboardFullscreen, state.mapFocusMode) {
        if (dashboardFullscreen && state.mapFocusMode) onExitMapFocus("dashboard")
    }
    LaunchedEffect(state.mapFocusMode) {
        if (state.mapFocusMode) {
            delay(1_000L)
            state.dismissExitHighlight()
        }
    }
    LaunchedEffect(state.hintVisible) {
        if (state.hintVisible) {
            delay(2_000L)
            state.dismissHint()
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun BoxScope.MapFocusExitControl(
    edgePadding: Dp,
    anchorSize: Dp,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val emphasis by
        animateFloatAsState(
            targetValue = if (highlighted) 1f else 0f,
            animationSpec = tween(durationMillis = 250),
            label = "Map focus exit highlight",
        )
    val iconSize = 18.dp + 4.dp * emphasis
    IconButton(
        onClick = onClick,
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = edgePadding).size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = Color.White),
    ) {
        // Match the tools anchor's visible center while keeping the full touch target on-screen.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier =
                    Modifier
                        .size(anchorSize)
                        .background(Color.Black.copy(alpha = 0.78f * emphasis), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.FullscreenExit,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize).offset(y = 1.dp),
                    tint = Color.Black.copy(alpha = 0.4f),
                )
                Icon(
                    imageVector = Icons.Default.FullscreenExit,
                    contentDescription = stringResource(R.string.navigation_exit_map_focus),
                    modifier = Modifier.size(iconSize),
                    tint = Color.White.copy(alpha = 0.6f + 0.4f * emphasis),
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun BoxScope.MapFocusHint(visible: Boolean) {
    if (!visible) return
    Text(
        text = stringResource(R.string.navigation_map_focus_hint),
        color = Color.White,
        fontSize = 11.sp,
        maxLines = 1,
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 36.dp)
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
