package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun phoneMapSearchControl(
    presentation: PhoneMapSearchControlState,
    actions: PhoneMapSearchActions,
    modifier: Modifier = Modifier,
) {
    val state = presentation.search
    val expanded = presentation.expanded
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var inputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else if (inputFocused) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    val search: () -> Unit = {
        if (expanded && state.query.trim().length >= 2 && !state.loading) {
            keyboard?.hide()
            focusManager.clearFocus()
            actions.onSearch()
        } else {
            actions.onFocus()
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val availableWidth = maxWidth
        phoneMapSearchInput(
            state = state,
            actions = actions,
            onSearch = search,
            inputModifier =
                Modifier.focusRequester(focusRequester).onFocusChanged {
                    inputFocused = it.isFocused
                    if (it.isFocused) actions.onFocus()
                },
            scaleContent = { phoneMapSearchScale(presentation.camera, presentation.isMetric, availableWidth) },
        )
    }
}

@Composable
private fun phoneMapSearchInput(
    state: PhoneMapSearchUiState,
    actions: PhoneMapSearchActions,
    onSearch: () -> Unit,
    inputModifier: Modifier,
    scaleContent: @Composable () -> Unit,
) {
    val hint = stringResource(R.string.map_search_hint)
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onSearch, enabled = !state.loading, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Search, stringResource(R.string.map_search_action), Modifier.size(18.dp))
            }
            BasicTextField(
                value = state.query,
                onValueChange = actions.onQueryChanged,
                modifier =
                    inputModifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("map-search-input")
                        .semantics { contentDescription = hint },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = contentColor),
                cursorBrush = SolidColor(contentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { input ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.query.isEmpty()) {
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        input()
                    }
                },
            )
            scaleContent()
        }
    }
}

@Composable
private fun phoneMapSearchScale(
    camera: PhoneMapCameraSnapshot,
    isMetric: Boolean,
    availableWidth: Dp,
) {
    val scaleBudget = (availableWidth * 0.34f).coerceAtMost(80.dp)
    // Keep the scale tied to physical pixels even while the adjacent input is being edited.
    val scaleViewport = scaleBudget / PHONE_MAP_SCALE_TARGET_RATIO.toFloat()
    val scaleViewportPx = with(LocalDensity.current) { scaleViewport.toPx().toDouble() }
    val indicator =
        remember(camera.groundResolutionMetersPerPixel, scaleViewportPx, isMetric) {
            camera.groundResolutionMetersPerPixel?.let {
                calculatePhoneMapScaleIndicator(it, scaleViewportPx, isMetric)
            }
        }
    indicator?.let {
        Box(modifier = Modifier.width(scaleBudget), contentAlignment = Alignment.CenterEnd) {
            phoneMapSearchScaleBar(it, scaleViewport * it.widthRatio)
        }
    }
}

@Composable
private fun phoneMapSearchScaleBar(
    indicator: PhoneMapScaleIndicator,
    width: Dp,
) {
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    Column(horizontalAlignment = Alignment.End) {
        Text(
            indicator.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Canvas(modifier = Modifier.width(width).height(8.dp).testTag("map-search-scale-bar")) {
            val centerY = size.height / 2f
            val stroke = 1.5.dp.toPx()
            drawLine(color, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = stroke)
            drawLine(color, Offset(stroke / 2f, 0f), Offset(stroke / 2f, size.height), strokeWidth = stroke)
            drawLine(
                color,
                Offset(size.width - stroke / 2f, 0f),
                Offset(size.width - stroke / 2f, size.height),
                strokeWidth = stroke,
            )
        }
    }
}
