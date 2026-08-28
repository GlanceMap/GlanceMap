package com.glancemap.glancemapcompanionapp.filepicker

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.FileTransferHistoryItem
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun DownloadActionButton(
    label: String,
    buttonHeight: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(buttonHeight),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                icon()
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    containerPadding: PaddingValues = PaddingValues(10.dp),
    titleContentSpacing: Dp = 6.dp,
    headerAction: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(containerPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                headerAction?.invoke(this)
            }
            Spacer(modifier = Modifier.height(titleContentSpacing))
            content()
        }
    }
}

@Composable
internal fun HistoryRow(item: FileTransferHistoryItem) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    stringResource(
                        if (item.success) {
                            R.string.transfer_history_success
                        } else {
                            R.string.transfer_history_failure
                        },
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = if (item.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
        }
        if (item.detail.isNotBlank()) {
            Text(
                text = item.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun HistoryScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)

    Box(
        modifier =
            modifier
                .width(4.dp)
                .padding(vertical = 2.dp)
                .drawBehind {
                    val radius = 4.dp.toPx()
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = CornerRadius(radius, radius),
                    )

                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val totalItemsCount = layoutInfo.totalItemsCount
                    if (visibleItems.isEmpty() || totalItemsCount == 0) return@drawBehind

                    val viewportHeight = size.height
                    if (viewportHeight <= 0f) return@drawBehind

                    val avgItemSize =
                        visibleItems
                            .map { it.size }
                            .average()
                            .toFloat()
                            .coerceAtLeast(1f)
                    val estimatedContentHeight = avgItemSize * totalItemsCount
                    if (estimatedContentHeight <= viewportHeight) return@drawBehind

                    val firstVisible = visibleItems.first()
                    val firstIndexOffsetPx = (firstVisible.index * avgItemSize) - firstVisible.offset
                    val maxScrollPx = (estimatedContentHeight - viewportHeight).coerceAtLeast(1f)
                    val scrollFraction = (firstIndexOffsetPx / maxScrollPx).coerceIn(0f, 1f)

                    val thumbHeightPx =
                        (viewportHeight * (viewportHeight / estimatedContentHeight))
                            .coerceIn(24.dp.toPx(), viewportHeight)
                    val thumbOffsetPx = (viewportHeight - thumbHeightPx) * scrollFraction

                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(0f, thumbOffsetPx),
                        size = Size(size.width, thumbHeightPx),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                },
    ) {
        // Drawn via drawBehind to avoid composition churn from frequently-changing list layout info.
    }
}

@Composable
internal fun PageScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)

    Box(
        modifier =
            modifier
                .width(4.dp)
                .padding(vertical = 2.dp)
                .drawBehind {
                    val maxScrollPx = scrollState.maxValue.toFloat()
                    val viewportHeight = size.height
                    if (maxScrollPx <= 0f || viewportHeight <= 0f) return@drawBehind

                    val radius = 4.dp.toPx()
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = CornerRadius(radius, radius),
                    )

                    val contentHeight = viewportHeight + maxScrollPx
                    val thumbHeightPx =
                        (viewportHeight * (viewportHeight / contentHeight))
                            .coerceIn(24.dp.toPx(), viewportHeight)
                    val scrollFraction = (scrollState.value / maxScrollPx).coerceIn(0f, 1f)
                    val thumbOffsetPx = (viewportHeight - thumbHeightPx) * scrollFraction

                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(0f, thumbOffsetPx),
                        size = Size(size.width, thumbHeightPx),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                },
    )
}

@Composable
internal fun PhoneStoredFilesSummaryRow(
    label: String,
    group: PhoneStoredFilesGroup,
    context: Context,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = formatPhoneStoredFilesSummary(context, group),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
