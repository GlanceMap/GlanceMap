package com.glancemap.glancemapcompanionapp.library

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapTool
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryUiState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun phoneLibraryScreen(
    routes: RouteLibraryUiState,
    poiSources: List<PhoneMapPoiSource>,
    actions: PhoneLibraryActions,
) {
    val model: PhoneLibraryViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val local by model.uiState.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PhoneLibraryFilter.ALL) }
    var selected by remember { mutableStateOf<PhoneLibraryItem?>(null) }
    val items =
        remember(routes.routes, local.items, poiSources) {
            phoneLibraryItems(routes.routes, local.items, poiSources)
        }
    val visible = remember(items, filter, query) { filterPhoneLibraryItems(items, filter, query) }
    LaunchedEffect(Unit) { model.refresh() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        libraryHeader(routes.isImporting, local.isLoading, actions) {
            model.refresh()
            actions.onRefresh()
        }
        librarySearch(query, { query = it })
        libraryFilters(filter) { filter = it }
        Text(
            stringResource(R.string.library_local_count, items.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (routes.message ?: local.message)?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            libraryContent(
                items = visible,
                loading = routes.isLoading || local.isLoading,
                enabled = !routes.isImporting && !local.isLoading,
                onSelect = { selected = it },
            )
        }
        if (filter == PhoneLibraryFilter.POIS || filter == PhoneLibraryFilter.OFFLINE) {
            OutlinedButton(
                onClick = {
                    actions.onOpenMapTools(if (filter == PhoneLibraryFilter.POIS) MapTool.POI else MapTool.MAPS)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.library_manage_on_map)) }
        }
    }
    selected?.let { item ->
        libraryItemDetails(
            item = item,
            actions = actions,
            onOpenOfflineMap = { map ->
                scope.launch { model.openOfflineMap(map) { actions.onOpenMapTools(MapTool.MAPS) } }
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun libraryHeader(
    importing: Boolean,
    loading: Boolean,
    actions: PhoneLibraryActions,
    onRefresh: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back_to_home_content_description))
        }
        Text(
            stringResource(R.string.library_title),
            Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
        )
        IconButton(onClick = onRefresh, enabled = !loading && !importing) {
            Icon(Icons.Filled.Refresh, stringResource(R.string.library_refresh))
        }
        libraryImportButton(importing, actions)
    }
}

@Composable
private fun libraryImportButton(
    importing: Boolean,
    actions: PhoneLibraryActions,
) {
    var expanded by remember { mutableStateOf(false) }
    var asRecording by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) actions.onImportGpx(uri, asRecording, null)
        }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !importing) {
            Icon(Icons.Filled.Add, stringResource(R.string.library_import_gpx))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            val importOptions =
                listOf(
                    false to R.string.library_import_route,
                    true to R.string.library_import_recording,
                )
            importOptions.forEach { (recorded, title) ->
                DropdownMenuItem(
                    text = { Text(stringResource(title)) },
                    onClick = {
                        asRecording = recorded
                        expanded = false
                        launcher.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }
}

@Composable
private fun librarySearch(
    query: String,
    onQuery: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.library_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.library_clear_search))
                }
            }
        },
        singleLine = true,
    )
}

@Composable
private fun libraryFilters(
    selected: PhoneLibraryFilter,
    onSelect: (PhoneLibraryFilter) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhoneLibraryFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filter.label)) },
            )
        }
    }
}

@Composable
private fun libraryContent(
    items: List<PhoneLibraryItem>,
    loading: Boolean,
    enabled: Boolean,
    onSelect: (PhoneLibraryItem) -> Unit,
) {
    if (items.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.library_empty_description), style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = PhoneLibraryItem::key) { item ->
                Card(onClick = { onSelect(item) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(item.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                item.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                libraryItemSummary(item),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.library_on_phone),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

private fun PhoneLibraryItem.icon(): ImageVector =
    when (this) {
        is PhoneLibraryItem.Gpx -> if (route.isRecording) Icons.Filled.Hiking else Icons.Filled.Route
        is PhoneLibraryItem.ReceivedGpx -> Icons.Filled.Description
        is PhoneLibraryItem.Pois -> Icons.Filled.Place
        is PhoneLibraryItem.OfflineMap -> Icons.Filled.Map
    }

@Composable
internal fun libraryItemSummary(item: PhoneLibraryItem): String =
    when (item) {
        is PhoneLibraryItem.Gpx -> {
            val kind =
                stringResource(
                    if (item.route.isRecording) R.string.library_item_recording else R.string.library_item_route,
                )
            stringResource(
                R.string.library_gpx_summary,
                kind,
                item.route.summary.distanceMeters / 1000.0,
                item.route.summary.elevationGainMeters
                    .roundToInt(),
            )
        }
        is PhoneLibraryItem.ReceivedGpx ->
            stringResource(
                R.string.library_received_summary,
                Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes),
            )
        is PhoneLibraryItem.Pois ->
            if (item.source.isReadable) {
                item.source.poiCount?.let { stringResource(R.string.library_poi_count, it) }
                    ?: stringResource(R.string.library_poi_collection)
            } else {
                stringResource(R.string.library_poi_unreadable)
            }
        is PhoneLibraryItem.OfflineMap ->
            stringResource(
                R.string.library_offline_summary,
                Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes),
            )
    }
