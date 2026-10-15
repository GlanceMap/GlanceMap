package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun BoxScope.phoneMapPointerOverlay(
    showPointer: Boolean,
    poiCreationActive: Boolean,
    pointerCoordinate: PhoneMapCoordinate?,
    onSelectPoiAtPointer: (PhoneMapCoordinate) -> Unit,
    onCancelPoi: () -> Unit,
) {
    if (showPointer || poiCreationActive) {
        phoneMapDiamondPointer()
    }
    if (poiCreationActive) {
        PhoneMapPopupCard(
            title = stringResource(R.string.map_poi_add_title),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp),
            onDismiss = onCancelPoi,
        ) {
            Text(text = stringResource(R.string.map_poi_tap_to_add))
            TextButton(
                onClick = { pointerCoordinate?.let(onSelectPoiAtPointer) },
                enabled = pointerCoordinate != null,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.map_poi_use_pointer))
            }
        }
    }
}

@Composable
private fun BoxScope.phoneMapDiamondPointer() {
    val description = stringResource(R.string.map_pointer_content_description)
    Canvas(
        modifier =
            Modifier
                .align(Alignment.Center)
                .size(35.dp)
                .semantics { contentDescription = description },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = 14.dp.toPx()
        val color = Color(0xFFF7C948)
        val diamond =
            Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius, center.y)
                lineTo(center.x, center.y + radius)
                lineTo(center.x - radius, center.y)
                close()
            }
        drawPath(diamond, color = Color.Black.copy(alpha = 0.55f), style = Stroke(width = 5.dp.toPx()))
        drawPath(diamond, color = color, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = Color.Black.copy(alpha = 0.55f), radius = 3.5.dp.toPx(), center = center)
        drawCircle(color = color, radius = 2.dp.toPx(), center = center)
    }
}
