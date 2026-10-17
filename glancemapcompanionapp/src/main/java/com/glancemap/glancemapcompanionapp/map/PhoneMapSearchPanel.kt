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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            phoneMapOnlineSearchToggle(state.online, actions.onOnlineChanged) {
                keyboard?.hide()
                actions.onDismiss()
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
                val frenchAddress = state.reply.results.any { it.id.startsWith("ban:") }
                val sourceUrl =
                    if (frenchAddress) {
                        "https://adresse.data.gouv.fr/outils/api-doc/adresse"
                    } else {
                        "https://www.openstreetmap.org/copyright"
                    }
                val credit = if (frenchAddress) R.string.map_search_ban_attribution else R.string.map_search_attribution
                TextButton(onClick = { uriHandler.openUri(sourceUrl) }) {
                    Text(stringResource(credit), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun phoneMapOnlineSearchToggle(
    online: Boolean,
    onOnlineChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val onlineLabel = stringResource(R.string.map_search_online_enabled)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(onlineLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = online,
            onCheckedChange = onOnlineChanged,
            modifier = Modifier.semantics { contentDescription = onlineLabel },
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_action_close))
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
