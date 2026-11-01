package com.glancemap.glancemapcompanionapp.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
@Suppress("FunctionNaming") // Compose details entry point.
internal fun PublishedTrailDetails(
    trail: PublishedTrail,
    save: DiscoveredTrailSaveState,
    actions: PublishedTrailDetailActions,
    detail: OnlineTrailDetailState = OnlineTrailDetailState(),
) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = actions.onBack) { Text(stringResource(R.string.discovery_back_results)) }
            Text(trail.title, style = MaterialTheme.typography.titleLarge)
            Text(trail.activity)
            if (trail.description.isNotBlank()) Text(trail.description)
            trail.difficulty?.let { Text(it) }
            trail.distanceMeters?.let { Text(stringResource(R.string.discovery_distance, it / 1000.0)) }
            PublishedTrailMetrics(trail)
            PublishedTrailGeometryStatus(trail, detail, actions.onRetry)
            if (trail.geometry.any { it.startsNewSegment }) Text(stringResource(R.string.discovery_segments))
            PublishedTrailDownloadControls(trail, save, detail, actions.onSave)
        }
        items(trail.sources) { source ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.provider.label, style = MaterialTheme.typography.titleSmall)
                source.publisherName?.let { Text(it) }
                Text(source.attribution)
                Text(source.license, style = MaterialTheme.typography.bodySmall)
                source.updatedAt?.let { Text(stringResource(R.string.discovery_updated, it)) }
                Text(source.providerTrailId, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { uriHandler.openUri(source.sourceUrl) }) {
                    Text(stringResource(R.string.discovery_original))
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose helper for the download action and status.
private fun PublishedTrailDownloadControls(
    trail: PublishedTrail,
    save: DiscoveredTrailSaveState,
    detail: OnlineTrailDetailState,
    onSave: (PublishedTrail) -> Unit,
) {
    Button(
        onClick = { onSave(trail) },
        enabled =
            trail.sources.isNotEmpty() &&
                !save.saving &&
                !detail.loading &&
                !detail.failed &&
                trail.id !in save.savedTrailIds,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val label =
            if (trail.id in save.savedTrailIds) {
                R.string.discovery_available_offline
            } else {
                R.string.discovery_download
            }
        Text(stringResource(label))
    }
    if (!detail.loading && !detail.failed) {
        val scope =
            if (trail.canSave) {
                R.string.discovery_download_route_scope
            } else {
                R.string.discovery_download_info_scope
            }
        Text(stringResource(scope))
    }
    if (save.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    save.message?.let { Text(it) }
}

@Composable
@Suppress("FunctionNaming") // Compose helper for asynchronous detail status.
private fun PublishedTrailGeometryStatus(trail: PublishedTrail, detail: OnlineTrailDetailState, onRetry: () -> Unit) {
    when {
        detail.loading -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.discovery_detail_loading))
        }
        detail.failed -> {
            Text(stringResource(R.string.discovery_detail_failed))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.discovery_detail_retry)) }
        }
        else -> Text(stringResource(if (trail.canSave) R.string.discovery_preview else R.string.discovery_no_geometry))
    }
}

@Composable
@Suppress("FunctionNaming") // Compose helper follows the panel naming convention.
private fun PublishedTrailMetrics(trail: PublishedTrail) {
    trail.profile?.let { profile ->
        Text(stringResource(R.string.discovery_duration, profile.estimatedDurationSeconds / 3600.0))
        if (trail.geometry.all { it.elevationMeters != null }) {
            Text(stringResource(R.string.discovery_elevation, profile.totalAscentMeters, profile.totalDescentMeters))
        }
    }
}
