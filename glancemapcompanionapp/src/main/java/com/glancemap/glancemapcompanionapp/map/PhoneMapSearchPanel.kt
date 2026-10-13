package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
@Suppress("FunctionNaming") // Matches the shared map popup entry point.
internal fun PhoneMapSearchPanel(
    state: PhoneMapSearchUiState,
    actions: PhoneMapSearchActions,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val submit = {
        keyboard?.hide()
        actions.onSearch()
    }
    PhoneMapPopupCard(
        onDismiss = {
            keyboard?.hide()
            actions.onDismiss()
        },
        title = stringResource(R.string.map_search_title),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChanged,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = {
                    val hint = if (state.online) R.string.map_search_hint else R.string.map_search_poi_hint
                    Text(stringResource(hint))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
            FilledTonalIconButton(onClick = submit, enabled = state.query.trim().length >= 2 && !state.loading) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.map_search_action))
            }
        }
        if (!state.online) {
            Text(
                stringResource(R.string.map_search_offline_scope),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        phoneMapSearchStatus(state)
        phoneMapSearchResults(state.reply.results) { result ->
            keyboard?.hide()
            actions.onResultSelected(result)
        }
        if (state.online) {
            TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }) {
                Text(stringResource(R.string.map_search_attribution), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun phoneMapSearchResults(
    results: List<PhoneMapSearchResult>,
    onSelected: (PhoneMapSearchResult) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
        items(results, key = PhoneMapSearchResult::id) { result ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(result) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(result.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (result.detail.isNotBlank()) {
                    Text(
                        result.detail,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun phoneMapSearchStatus(state: PhoneMapSearchUiState) {
    val message =
        when {
            state.loading -> R.string.map_search_loading
            state.reply.onlineFailed -> R.string.map_search_online_failed
            state.reply.localFailed -> R.string.map_search_local_failed
            state.searched && state.reply.results.isEmpty() -> R.string.map_search_empty
            else -> null
        }
    message?.let {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
        }
    }
}
