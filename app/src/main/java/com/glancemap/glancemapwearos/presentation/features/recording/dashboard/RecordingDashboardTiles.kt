@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize

internal fun recordingDashboardMetricTileHeight(screenSize: WearScreenSize): Dp =
    when (screenSize) {
        WearScreenSize.LARGE -> 54.dp
        WearScreenSize.MEDIUM -> 50.dp
        WearScreenSize.SMALL -> 46.dp
    }

@Composable
internal fun RecordingDashboardMetricTile(
    metric: RecordingMetricValue,
    height: Dp,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .height(height)
                .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                ).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(metric.labelRes),
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 12.sp,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (metric.bluetooth) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = stringResource(R.string.recording_bluetooth),
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = metric.value,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            metric.unit?.let { unit ->
                Text(
                    text = unit,
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 12.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
