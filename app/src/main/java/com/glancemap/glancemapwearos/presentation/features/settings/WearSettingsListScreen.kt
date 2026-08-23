@file:Suppress("FunctionName")

package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.ScreenScaffold

@Composable
internal fun WearSettingsListScreen(
    listTokens: SettingsListTokens = rememberSettingsListTokens(),
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberSettingsScalingLazyListState(topPadding = listTokens.topPadding)

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding =
                PaddingValues(
                    start = listTokens.horizontalPadding,
                    end = listTokens.horizontalPadding,
                    top = listTokens.topPadding,
                    bottom = listTokens.bottomPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(listTokens.itemSpacing),
            anchorType = SettingsListAnchorType,
            autoCentering = SettingsListAutoCentering,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}
