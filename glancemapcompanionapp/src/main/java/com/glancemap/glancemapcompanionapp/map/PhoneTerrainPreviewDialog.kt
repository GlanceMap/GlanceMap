package com.glancemap.glancemapcompanionapp.map

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun phoneTerrainPreviewDialog(
    data: PhoneTerrainPreviewData,
    onDismiss: () -> Unit,
) {
    val payload by produceState<String?>(null, data) {
        value = withContext(Dispatchers.Default) { data.toPreviewJson() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.terrain_preview_title),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, stringResource(R.string.common_action_close))
                    }
                }
                Text(
                    stringResource(R.string.terrain_preview_summary),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                val readyPayload = payload
                if (readyPayload == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    terrainPreviewContent(data, readyPayload)
                }
            }
        }
    }
}

@Composable
private fun terrainPreviewContent(
    data: PhoneTerrainPreviewData,
    payload: String,
) {
    var status by remember { mutableStateOf(PhoneTerrainPreviewStatus.LOADING) }
    var attempt by remember { mutableStateOf(0) }
    var basemap by remember { mutableStateOf(data.initialBasemap) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(attempt, status) {
        if (status == PhoneTerrainPreviewStatus.LOADING) {
            delay(60_000L)
            status = PhoneTerrainPreviewStatus.ERROR
        }
    }
    Column(Modifier.fillMaxSize()) {
        terrainPreviewControls(
            basemap = basemap,
            onBasemap = { selected ->
                if (selected != basemap || status.isFailure) {
                    val restart = status.isFailure
                    basemap = selected
                    status = PhoneTerrainPreviewStatus.LOADING
                    if (restart) {
                        attempt += 1
                    } else {
                        webView?.evaluateJavascript("window.GlanceMapTerrain.setBasemap('${selected.name}')", null)
                    }
                }
            },
            webView = webView.takeIf { status == PhoneTerrainPreviewStatus.READY },
            hasTracks = data.tracks.any { !it.isGap && it.points.size >= 2 },
            canSelectBasemap = status != PhoneTerrainPreviewStatus.LOADING,
        )
        if (status == PhoneTerrainPreviewStatus.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (status.isFailure) {
                terrainPreviewFailureRow(status) {
                    status = PhoneTerrainPreviewStatus.LOADING
                    attempt += 1
                }
            } else {
                key(attempt) {
                    phoneTerrainPreviewWebView(
                        payload,
                        basemap,
                        { webView = it },
                        { status = it },
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (basemap != PhoneTerrainPreviewBasemap.SATELLITE || status != PhoneTerrainPreviewStatus.READY) {
            phoneMaptoolkitAttribution()
        }
    }
}

@Composable
private fun terrainPreviewControls(
    basemap: PhoneTerrainPreviewBasemap,
    onBasemap: (PhoneTerrainPreviewBasemap) -> Unit,
    webView: WebView?,
    hasTracks: Boolean,
    canSelectBasemap: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, enabled = canSelectBasemap) {
                Text(stringResource(basemap.labelResource()), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                PhoneTerrainPreviewBasemap.entries.forEach { source ->
                    DropdownMenuItem(text = { Text(stringResource(source.labelResource())) }, onClick = {
                        expanded = false
                        onBasemap(source)
                    })
                }
            }
        }
        IconButton(
            onClick = { webView?.evaluateJavascript("window.GlanceMapTerrain.fitTracks()", null) },
            enabled = webView != null && hasTracks,
        ) {
            Icon(Icons.Filled.Route, stringResource(R.string.terrain_preview_fit_tracks))
        }
        IconButton(
            onClick = { webView?.evaluateJavascript("window.GlanceMapTerrain.resetCamera()", null) },
            enabled = webView != null,
        ) {
            Icon(Icons.Filled.MyLocation, stringResource(R.string.terrain_preview_reset))
        }
    }
}

private fun PhoneTerrainPreviewBasemap.labelResource(): Int =
    when (this) {
        PhoneTerrainPreviewBasemap.HIKING -> R.string.terrain_preview_hiking
        PhoneTerrainPreviewBasemap.CYCLING -> R.string.terrain_preview_cycling
        PhoneTerrainPreviewBasemap.SATELLITE -> R.string.terrain_preview_satellite
    }

@Composable
internal fun terrainPreviewLaunchButton(onPreview: (() -> Unit)?) {
    OutlinedButton(
        onClick = { onPreview?.invoke() },
        enabled = onPreview != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.terrain_preview_open))
    }
    if (onPreview == null) {
        Text(stringResource(R.string.terrain_preview_offline), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun terrainPreviewFailureRow(
    status: PhoneTerrainPreviewStatus,
    onRetry: () -> Unit,
) {
    val webViewVersion = remember { runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val message =
                if (status == PhoneTerrainPreviewStatus.UNSUPPORTED) {
                    R.string.terrain_preview_unsupported
                } else {
                    R.string.terrain_preview_error
                }
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            webViewVersion?.let {
                Text(
                    stringResource(R.string.terrain_preview_webview_version, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        TextButton(onClick = onRetry) { Text(stringResource(R.string.terrain_preview_retry)) }
    }
}
