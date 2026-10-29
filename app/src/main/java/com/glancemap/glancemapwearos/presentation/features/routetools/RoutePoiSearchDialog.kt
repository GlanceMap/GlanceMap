package com.glancemap.glancemapwearos.presentation.features.routetools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.features.poi.PoiSearchResultUiState
import com.glancemap.glancemapwearos.presentation.features.poi.PoiSearchUiState
import com.glancemap.glancemapwearos.presentation.ui.WearFormDialog

@Composable
internal fun RoutePoiSearchDialog(
    visible: Boolean,
    state: PoiSearchUiState,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onSelectResult: (PoiSearchResultUiState) -> Unit,
) {
    if (!visible) return

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var draftQuery by remember(visible) { mutableStateOf(state.query) }

    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(state.query) {
        if (state.query.isNotBlank() && state.query != draftQuery) {
            draftQuery = state.query
        }
    }

    WearFormDialog(
        visible = visible,
        title = stringResource(R.string.gpx_search_poi),
        onDismiss = onDismiss,
        backgroundColor = Color.Black.copy(alpha = 0.92f),
    ) { formTokens ->
        BasicTextField(
            value = draftQuery,
            onValueChange = { draftQuery = it.take(48) },
            singleLine = true,
            textStyle =
                TextStyle(
                    color = Color.White,
                    textAlign = TextAlign.Center,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier =
                formTokens.controlModifier
                    .focusRequester(focusRequester)
                    .background(
                        Color(0xFF1F1F1F),
                        RoundedCornerShape(12.dp),
                    ).padding(horizontal = 12.dp, vertical = formTokens.textFieldVerticalPadding),
            decorationBox = { innerTextField ->
                if (draftQuery.isBlank()) {
                    Text(
                        text = stringResource(R.string.gpx_type_a_poi_name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                innerTextField()
            },
        )

        Button(
            onClick = { onSearch(draftQuery) },
            modifier =
                formTokens.controlModifier
                    .heightIn(min = formTokens.buttonMinHeight),
        ) {
            Text(
                if (state.isLoading) stringResource(R.string.gpx_searching) else stringResource(R.string.common_search),
            )
        }

        state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCC80),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.results.forEach { result ->
            Button(
                onClick = { onSelectResult(result) },
                modifier = formTokens.controlModifier,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = Color.White,
                    ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text =
                            result.type.name
                                .lowercase()
                                .replace('_', ' ')
                                .replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.70f),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = result.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.54f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Button(
            onClick = onDismiss,
            modifier =
                formTokens.controlModifier
                    .heightIn(min = formTokens.buttonMinHeight),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White,
                ),
        ) {
            Text(stringResource(R.string.common_close))
        }
    }
}
