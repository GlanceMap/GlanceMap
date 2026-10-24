package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R

@Suppress("FunctionNaming") // Compose functions use PascalCase by convention.
@Composable
internal fun RecordingDashboardPageSwitcher(
    pageIndex: Int,
    pageCount: Int,
    onClick: () -> Unit,
    dashboardLabel: String = "",
    modifier: Modifier = Modifier,
) {
    val nextPageIndex = (pageIndex + 1) % pageCount
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color(0xFF1F3554), RoundedCornerShape(18.dp))
                .clickable(enabled = pageCount > 1, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text =
                dashboardLabel.takeIf(String::isNotBlank)?.let { stringResource(R.string.settings_dashboard, it) }
                    ?: stringResource(R.string.settings_dashboard_page),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.settings_page_of, pageIndex + 1, pageCount),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        RecordingDashboardPageDots(
            pageIndex = pageIndex,
            pageCount = pageCount,
        )
        Text(
            text =
                if (pageCount > 1) {
                    stringResource(R.string.settings_tap_for_page, nextPageIndex + 1)
                } else {
                    stringResource(R.string.settings_add_another_page_below)
                },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordingDashboardPageDots(
    pageIndex: Int,
    pageCount: Int,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier =
                    Modifier
                        .width(if (index == pageIndex) 14.dp else 5.dp)
                        .height(5.dp)
                        .background(
                            color =
                                if (index == pageIndex) {
                                    Color(0xFFF6C453)
                                } else {
                                    Color.White.copy(alpha = 0.28f)
                                },
                            shape = RoundedCornerShape(percent = 50),
                        ),
            )
        }
    }
}
