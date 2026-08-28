package com.glancemap.glancemapcompanionapp.filepicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.CompanionAdaptiveSpec
import com.glancemap.glancemapcompanionapp.CompanionWindowClass
import com.glancemap.glancemapcompanionapp.FileTransferUiState
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCaptureState
import com.glancemap.glancemapcompanionapp.transfer.presentation.TransferTextFormatter

private const val MAPSFORGE_URL = "https://github.com/mapsforge/mapsforge"

@Composable
internal fun FilePickerDownloadSection(
    context: Context,
    adaptive: CompanionAdaptiveSpec,
    uiLocked: Boolean,
    hasNotificationPermission: Boolean,
    hasBluetoothConnectPermission: Boolean,
    canRefreshLastRefuges: Boolean,
    canRefreshLastRouting: Boolean,
    mapDownloadSources: List<ExternalDownloadSource>,
    showMapSourcesMenu: Boolean,
    onShowMapSourcesMenuChange: (Boolean) -> Unit,
    showRefugesMenu: Boolean,
    onShowRefugesMenuChange: (Boolean) -> Unit,
    showRoutingMenu: Boolean,
    onShowRoutingMenuChange: (Boolean) -> Unit,
    onRequestMissingPermissions: () -> Unit,
    onShowManagePhoneFiles: () -> Unit,
    onShowRefugesDialog: () -> Unit,
    onShowRoutingDialog: () -> Unit,
    onRefreshLastRefuges: () -> Unit,
    onRefreshLastRouting: () -> Unit,
) {
    val isCompactScreen = adaptive.isCompactScreen
    val downloadButtonHeight = adaptive.downloadButtonHeight
    val downloadSectionMinHeight = downloadButtonHeight + 60.dp
    val showNotificationNotice =
        !hasNotificationPermission &&
            Build.VERSION.SDK_INT >= 33 &&
            hasBluetoothConnectPermission
    var showGpxSourcesMenu by remember { mutableStateOf(false) }
    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { error ->
            Log.w("FilePickerScreen", "Unable to open URL: $url", error)
        }
    }

    SectionCard(
        title = stringResource(R.string.transfer_section_download_title),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = downloadSectionMinHeight),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showNotificationNotice) {
                Text(
                    stringResource(R.string.transfer_download_notifications_off),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                OutlinedButton(
                    onClick = onRequestMissingPermissions,
                    enabled = !uiLocked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.transfer_action_enable_notifications))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        if (isCompactScreen) 6.dp else 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                ) {
                    DownloadActionButton(
                        label = stringResource(R.string.transfer_download_poi_label),
                        buttonHeight = downloadButtonHeight,
                        iconSize = 22.dp,
                        onClick = { onShowRefugesMenuChange(true) },
                        enabled = !uiLocked,
                        modifier = Modifier.fillMaxWidth(),
                        icon = {
                            Icon(
                                Icons.Filled.Place,
                                contentDescription = stringResource(R.string.transfer_download_poi_content_description),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = showRefugesMenu,
                        onDismissRequest = { onShowRefugesMenuChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.transfer_import_poi_action)) },
                            onClick = {
                                onShowRefugesMenuChange(false)
                                onShowRefugesDialog()
                            },
                            enabled = !uiLocked,
                        )
                        if (canRefreshLastRefuges) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.transfer_refresh_last_import_action)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Update,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onShowRefugesMenuChange(false)
                                    onRefreshLastRefuges()
                                },
                                enabled = !uiLocked,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.weight(1f),
                ) {
                    DownloadActionButton(
                        label = stringResource(R.string.transfer_download_gpx_label),
                        buttonHeight = downloadButtonHeight,
                        iconSize = 22.dp,
                        onClick = { showGpxSourcesMenu = true },
                        enabled = !uiLocked,
                        modifier = Modifier.fillMaxWidth(),
                        icon = {
                            Icon(
                                Icons.Filled.Timeline,
                                contentDescription = stringResource(R.string.transfer_download_gpx_content_description),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = showGpxSourcesMenu,
                        onDismissRequest = { showGpxSourcesMenu = false },
                    ) {
                        Text(
                            stringResource(R.string.transfer_gpx_intro),
                            style = MaterialTheme.typography.bodySmall,
                            modifier =
                                Modifier
                                    .widthIn(max = 260.dp)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Text(
                            stringResource(R.string.transfer_gpx_sources_intro),
                            style = MaterialTheme.typography.bodySmall,
                            modifier =
                                Modifier
                                    .widthIn(max = 260.dp)
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 8.dp),
                        )
                        GpxSourceMenuItem(
                            label = "gpx.studio",
                            url = "https://gpx.studio/",
                            onOpen = { url ->
                                showGpxSourcesMenu = false
                                openUrl(url)
                            },
                        )
                        GpxSourceMenuItem(
                            label = "Trackbook",
                            url = "https://trackbook.com/",
                            onOpen = { url ->
                                showGpxSourcesMenu = false
                                openUrl(url)
                            },
                        )
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.transfer_gpx_route_planning_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier =
                                Modifier
                                    .widthIn(max = 260.dp)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier.weight(1f),
                ) {
                    DownloadActionButton(
                        label = stringResource(R.string.transfer_download_maps_label),
                        buttonHeight = downloadButtonHeight,
                        iconSize = 22.dp,
                        onClick = { onShowMapSourcesMenuChange(true) },
                        enabled = !uiLocked,
                        modifier = Modifier.fillMaxWidth(),
                        icon = {
                            Icon(
                                Icons.Filled.Map,
                                contentDescription = stringResource(R.string.transfer_map_sources_content_description),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = showMapSourcesMenu,
                        onDismissRequest = { onShowMapSourcesMenuChange(false) },
                    ) {
                        MapsDropdownIntro(
                            onOpenMapsforge = {
                                onShowMapSourcesMenuChange(false)
                                openUrl(MAPSFORGE_URL)
                            },
                        )
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.transfer_map_sources_title),
                            style = MaterialTheme.typography.labelMedium,
                            modifier =
                                Modifier
                                    .widthIn(max = 300.dp)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        val orderedCategories =
                            listOf(
                                stringResource(R.string.map_sources_category_topographic),
                                stringResource(R.string.map_sources_category_non_topographic),
                                stringResource(R.string.map_sources_category_other),
                            )

                        orderedCategories.forEachIndexed { index, category ->
                            val sources = mapDownloadSources.filter { it.category == category }
                            if (sources.isEmpty()) return@forEachIndexed
                            if (index > 0) {
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        category,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                onClick = {},
                                enabled = false,
                            )
                            sources.forEach { source ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                source.label,
                                                color = MaterialTheme.colorScheme.primary,
                                                textDecoration = TextDecoration.Underline,
                                            )
                                            source.guidance?.let { guidance ->
                                                Text(
                                                    guidance,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        onShowMapSourcesMenuChange(false)
                                        openUrl(source.url)
                                    },
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier.weight(1f),
                ) {
                    DownloadActionButton(
                        label = stringResource(R.string.transfer_download_routing_label),
                        buttonHeight = downloadButtonHeight,
                        iconSize = 22.dp,
                        onClick = { onShowRoutingMenuChange(true) },
                        enabled = !uiLocked,
                        modifier = Modifier.fillMaxWidth(),
                        icon = {
                            Icon(
                                Icons.Filled.Route,
                                contentDescription = stringResource(R.string.transfer_routing_content_description),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = showRoutingMenu,
                        onDismissRequest = { onShowRoutingMenuChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.transfer_download_routing_action)) },
                            onClick = {
                                onShowRoutingMenuChange(false)
                                onShowRoutingDialog()
                            },
                            enabled = !uiLocked,
                        )
                        if (canRefreshLastRouting) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.transfer_refresh_last_download_action)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Update,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onShowRoutingMenuChange(false)
                                    onRefreshLastRouting()
                                },
                                enabled = !uiLocked,
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onShowManagePhoneFiles,
                enabled = !uiLocked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.transfer_action_manage_downloads))
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun GpxSourceMenuItem(
    label: String,
    url: String,
    onOpen: (String) -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )
        },
        onClick = { onOpen(url) },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun MapsDropdownIntro(onOpenMapsforge: () -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
    val textStyle = MaterialTheme.typography.bodySmall.copy(color = textColor)
    val linkStyles =
        TextLinkStyles(
            style =
                SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
        )
    val mapsforgeName = "Mapsforge"
    val introTemplate = stringResource(R.string.map_sources_mapsforge_intro, mapsforgeName)
    val mapsforgeNameIndex = introTemplate.indexOf(mapsforgeName)
    val introText =
        buildAnnotatedString {
            if (mapsforgeNameIndex < 0) {
                append(introTemplate)
            } else {
                append(introTemplate.substring(0, mapsforgeNameIndex))
                withLink(
                    LinkAnnotation.Url(
                        url = MAPSFORGE_URL,
                        styles = linkStyles,
                        linkInteractionListener = LinkInteractionListener { onOpenMapsforge() },
                    ),
                ) {
                    append(mapsforgeName)
                }
                append(introTemplate.substring(mapsforgeNameIndex + mapsforgeName.length))
            }
        }

    Text(
        text = introText,
        style = textStyle,
        modifier =
            Modifier
                .widthIn(max = 300.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun FilePickerTransferSection(
    adaptive: CompanionAdaptiveSpec,
    uiState: FileTransferUiState,
    uiLocked: Boolean,
    isAllowedSelection: Boolean,
    transferSessionActive: Boolean,
    cancellingTransfer: Boolean,
    waitingForReconnect: Boolean,
    debugCaptureState: PhoneDebugCaptureState,
    onSend: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onCancelRequested: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.transfer_section_transfer_title),
        modifier =
            if (adaptive.useCompactPageLayout) {
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 190.dp)
            } else {
                Modifier.fillMaxWidth()
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSend,
                enabled =
                    uiState.selectedFileUris.isNotEmpty() &&
                        uiState.selectedWatch != null &&
                        !uiLocked &&
                        isAllowedSelection,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        !transferSessionActive -> "Send"
                        cancellingTransfer -> "Stopping..."
                        waitingForReconnect -> "Waiting..."
                        else -> "Sending..."
                    },
                )
            }

            if (transferSessionActive) {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )

                val rawProgressText =
                    when {
                        uiState.isPaused -> {
                            val pauseReason = uiState.pauseReason.trim()
                            val detail = uiState.progressText.trim()
                            when {
                                pauseReason.isNotBlank() &&
                                    detail.isNotBlank() &&
                                    !detail.equals(pauseReason, ignoreCase = true) ->
                                    "Paused • $pauseReason\n$detail"

                                pauseReason.isNotBlank() ->
                                    "Paused • $pauseReason"

                                detail.isNotBlank() ->
                                    "Paused\n$detail"

                                else ->
                                    "Paused"
                            }
                        }

                        else -> uiState.progressText
                    }
                val progressText =
                    TransferTextFormatter.formatCardText(
                        rawProgressText = rawProgressText,
                        statusMessage = uiState.statusMessage,
                        isPaused = uiState.isPaused,
                        canResume = uiState.canResume,
                        showTechnicalDetails = debugCaptureState.active,
                    )
                Text(
                    progressText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (uiState.isPaused && uiState.canResume) {
                        Button(
                            onClick = onResume,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.common_action_resume))
                        }
                    } else if (uiState.isPaused) {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.common_status_waiting))
                        }
                    } else if (waitingForReconnect) {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.common_status_waiting))
                        }
                    } else if (cancellingTransfer) {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.transfer_action_stopping))
                        }
                    } else if (uiState.isTransferring) {
                        OutlinedButton(
                            onClick = onPause,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.common_action_pause))
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Button(
                        onClick = onCancelRequested,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                }
            }
        }
    }
}

@Composable
internal fun FilePickerHistorySection(
    adaptive: CompanionAdaptiveSpec,
    uiState: FileTransferUiState,
    historyListState: LazyListState,
    onClearHistory: () -> Unit,
) {
    val historyListHeight =
        if (adaptive.windowClass == CompanionWindowClass.EXPANDED) {
            280.dp
        } else {
            220.dp
        }

    SectionCard(
        title = stringResource(R.string.transfer_section_history_title),
        modifier =
            if (adaptive.useCompactPageLayout) {
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp)
            } else {
                Modifier.fillMaxWidth()
            },
        containerPadding = PaddingValues(start = 10.dp, top = 0.dp, end = 10.dp, bottom = 10.dp),
        titleContentSpacing = 2.dp,
        headerAction = {
            TextButton(
                onClick = onClearHistory,
                enabled = uiState.history.isNotEmpty(),
            ) {
                Text(stringResource(R.string.common_action_clear))
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Card(
                modifier =
                    if (adaptive.useCompactPageLayout) {
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(historyListHeight)
                    },
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                    ),
            ) {
                if (uiState.history.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.transfer_history_empty),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        LazyColumn(
                            state = historyListState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(uiState.history, key = { it.id }) { item ->
                                HistoryRow(item)
                            }
                        }
                        HistoryScrollbar(
                            listState = historyListState,
                            modifier =
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}
