package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.location.model.GpsEnvironmentWarning
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import kotlinx.coroutines.delay

@Composable
internal fun RecordingStatusMessageChip(
    message: String?,
    modifier: Modifier = Modifier,
) {
    if (message.isNullOrBlank()) return

    Text(
        text = message,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        color = Color.White,
        fontSize = 14.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
internal fun rememberNoActiveMapNotice(
    hasActiveMap: Boolean,
    updatingMap: Boolean,
): Boolean {
    var shown by rememberSaveable(hasActiveMap) { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hasActiveMap, updatingMap) {
        if (!hasActiveMap && updatingMap && !shown) {
            shown = true
            showBriefNoActiveMapNotice { visible = it }
        }
    }
    return !hasActiveMap && visible
}

internal suspend fun showBriefNoActiveMapNotice(onVisibilityChanged: (Boolean) -> Unit) {
    try {
        onVisibilityChanged(true)
        delay(2_000L)
    } finally {
        onVisibilityChanged(false)
    }
}

@Suppress("FunctionName")
@Composable
internal fun BoxScope.StartupLocationWaitingOverlay(visible: Boolean) {
    if (!visible) return

    Text(
        text = stringResource(R.string.navigation_waiting_for_location),
        modifier =
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        color = Color.White,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
    )
}

@Suppress("FunctionName")
@Composable
internal fun BoxScope.GpsEnvironmentWarningOverlay(
    warning: GpsEnvironmentWarning,
    visible: Boolean,
) {
    val message =
        when (warning) {
            GpsEnvironmentWarning.NONE -> null
            GpsEnvironmentWarning.LOCATION_SETTINGS_UNSATISFIED -> "Turn on watch Location"
            GpsEnvironmentWarning.WATCH_GPS_UNAVAILABLE -> null
            GpsEnvironmentWarning.AUTO_PHONE_DISCONNECTED_NO_WATCH_GPS,
            GpsEnvironmentWarning.AUTO_PHONE_DISCONNECTED_USING_WATCH_GPS,
            -> null
        }
    if (!visible || message == null) return

    Text(
        text = message,
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 38.dp, vertical = 10.dp)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White,
        fontSize = 11.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
    )
}

@Suppress("FunctionName")
@Composable
internal fun BoxScope.DiagnosticsDebugOverlay(
    label: String?,
    screenSize: WearScreenSize,
    recordingStatusChipVisible: Boolean,
    onCompassIssueNow: (() -> Unit)?,
) {
    if (label.isNullOrBlank() && onCompassIssueNow == null) return
    val overlayPadding = debugOverlayPadding(screenSize, recordingStatusChipVisible)
    val overlayTextSize = 10.sp

    Column(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = overlayPadding)
                .padding(horizontal = overlayPadding)
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = Color.White,
                fontSize = overlayTextSize,
                lineHeight = overlayTextSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
        if (onCompassIssueNow != null) {
            CompassIssueButton(
                onClick = onCompassIssueNow,
                textSize = overlayTextSize,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun CompassIssueButton(
    onClick: () -> Unit,
    textSize: androidx.compose.ui.unit.TextUnit,
) {
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        if (!saved) return@LaunchedEffect
        delay(COMPASS_ISSUE_SAVED_LABEL_MS)
        saved = false
    }
    Text(
        text = if (saved) "Compass issue saved" else "Compass issue now",
        modifier =
            Modifier
                .padding(top = 3.dp)
                .background(Color(0xFF3B3B3B), RoundedCornerShape(5.dp))
                .clickable {
                    onClick()
                    saved = true
                }.padding(horizontal = 6.dp, vertical = 4.dp),
        color = Color.White,
        fontSize = textSize,
        lineHeight = textSize,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
}

private fun debugOverlayPadding(
    screenSize: WearScreenSize,
    recordingStatusChipVisible: Boolean,
) = when {
    recordingStatusChipVisible && screenSize == WearScreenSize.LARGE -> 52.dp
    recordingStatusChipVisible && screenSize == WearScreenSize.MEDIUM -> 50.dp
    recordingStatusChipVisible -> 48.dp
    screenSize == WearScreenSize.LARGE -> 24.dp
    screenSize == WearScreenSize.MEDIUM -> 22.dp
    else -> 20.dp
}

private const val COMPASS_ISSUE_SAVED_LABEL_MS = 1_500L
