package com.glancemap.glancemapcompanionapp.livetracking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlin.math.roundToInt

@Composable
internal fun HeaderRow(
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_content_description_back),
                modifier = Modifier.size(20.dp),
            )
        }
        actions()
    }
}

@Composable
internal fun ColumnScope.ScrollableScreenContent(
    scrollState: androidx.compose.foundation.ScrollState,
    contentSpacing: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(end = 8.dp)
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
            content = content,
        )
        ScrollbarIndicator(
            scrollState = scrollState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
        )
    }
}

@Composable
private fun ScrollbarIndicator(
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
) {
    if (scrollState.maxValue <= 0) return

    val density = LocalDensity.current
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    BoxWithConstraints(
        modifier =
            modifier
                .width(4.dp)
                .padding(vertical = 2.dp),
    ) {
        val maxScroll = scrollState.maxValue
        if (maxScroll <= 0) return@BoxWithConstraints

        val viewportHeightPx = constraints.maxHeight.toFloat()
        if (viewportHeightPx <= 0f) return@BoxWithConstraints

        val contentHeightPx = viewportHeightPx + maxScroll
        val minThumbHeightPx = with(density) { 28.dp.toPx() }
        val thumbHeightPx =
            (viewportHeightPx * viewportHeightPx / contentHeightPx)
                .coerceAtLeast(minThumbHeightPx)
                .coerceAtMost(viewportHeightPx)
        val scrollFraction = scrollState.value.coerceIn(0, maxScroll).toFloat() / maxScroll.toFloat()
        val thumbOffsetPx = ((viewportHeightPx - thumbHeightPx) * scrollFraction).takeIf { it.isFinite() } ?: 0f

        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(trackColor, RoundedCornerShape(999.dp)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(x = 0, y = thumbOffsetPx.roundToInt()) }
                    .width(3.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .background(thumbColor, RoundedCornerShape(999.dp)),
        )
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun TrackingPanel(
    title: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            title?.let {
                Text(text = it, style = MaterialTheme.typography.titleSmall)
            }
            content()
        }
    }
}
