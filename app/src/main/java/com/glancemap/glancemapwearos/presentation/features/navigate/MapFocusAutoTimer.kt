package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

@Composable
@Suppress("FunctionNaming")
internal fun MapFocusAutoTimer(
    enabled: Boolean,
    delaySeconds: Int,
    available: Boolean,
    interaction: Long,
    onEnter: () -> Unit,
) {
    val latestOnEnter = rememberUpdatedState(onEnter)
    LaunchedEffect(enabled, delaySeconds, available, interaction) {
        if (enabled && available) {
            delay(delaySeconds.toLong() * 1000L)
            latestOnEnter.value()
        }
    }
}
