@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
    "MatchingDeclarationName",
    "MaxLineLength",
)

package com.glancemap.glancemapcompanionapp.filepicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.SendToMobile
import androidx.compose.material.icons.filled.ContactMail
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.SpatialTracking
import androidx.compose.material.icons.filled.ViewComfyAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.glancemap.glancemapcompanionapp.CompanionAdaptiveSpec
import com.glancemap.glancemapcompanionapp.FileTransferUiState
import com.glancemap.glancemapcompanionapp.FileTransferViewModel
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.CompanionDiagnosticsEmailComposer
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCaptureState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class QuickGuideMode {
    GENERAL,
    TRANSFER,
    LIVE_TRACKING,
    MAP_LEGEND,
}

@Composable
internal fun DebugCaptureDialog(
    context: Context,
    viewModel: FileTransferViewModel,
    debugCaptureState: PhoneDebugCaptureState,
    onDismiss: () -> Unit,
) {
    val hasSavedPhoneRecording =
        remember(debugCaptureState.active, debugCaptureState.sessionId) {
            if (debugCaptureState.active) {
                false
            } else {
                CompanionDiagnosticsEmailComposer.hasSavedPhoneDiagnostics(context)
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (debugCaptureState.active) {
                    stringResource(R.string.home_debug_capture_active_title)
                } else {
                    stringResource(R.string.home_debug_capture_start_title)
                },
            )
        },
        text = {
            Text(
                if (debugCaptureState.active) {
                    "Recording is active on the phone. Stop when you are done and an email draft will open for Glancemap@protonmail.com.\n\nCaptured lines: ${debugCaptureState.bufferedLines}"
                } else {
                    buildString {
                        append("This records redacted companion app transfer and live tracking logs on the phone. Start it before reproducing the issue, then stop it to open an email draft to Glancemap@protonmail.com.")
                        if (hasSavedPhoneRecording) {
                            append("\n\nA saved phone recording is available and can be resent.")
                        }
                    }
                },
            )
        },
        confirmButton = {
            if (debugCaptureState.active) {
                TextButton(
                    onClick = {
                        onDismiss()
                        viewModel.stopPhoneDebugCaptureAndSend(context)
                    },
                ) {
                    Text(stringResource(R.string.home_debug_capture_stop_email_action))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasSavedPhoneRecording) {
                        TextButton(
                            onClick = {
                                onDismiss()
                                viewModel.sendLastPhoneDebugCapture(context)
                            },
                        ) {
                            Text(stringResource(R.string.home_debug_capture_send_last_action))
                        }
                    }
                    TextButton(
                        onClick = {
                            onDismiss()
                            viewModel.startPhoneDebugCapture(context)
                        },
                    ) {
                        Text(stringResource(R.string.home_debug_capture_start_action))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (debugCaptureState.active) {
                            R.string.home_debug_capture_keep_recording
                        } else {
                            R.string.common_action_cancel
                        },
                    ),
                )
            }
        },
    )
}

@Composable
internal fun ManagePhoneFilesDialog(
    context: Context,
    viewModel: FileTransferViewModel,
    uiState: FileTransferUiState,
    uiLocked: Boolean,
    isLoadingPhoneStoredFiles: Boolean,
    isClearingPhoneStoredFiles: Boolean,
    onIsClearingPhoneStoredFilesChange: (Boolean) -> Unit,
    phoneStoredFilesSummary: PhoneStoredFilesSummary,
    onRefreshRequested: () -> Unit,
    onDismiss: () -> Unit,
    coroutineScope: CoroutineScope,
) {
    AlertDialog(
        onDismissRequest = {
            if (!isClearingPhoneStoredFiles) onDismiss()
        },
        title = { Text(stringResource(R.string.transfer_action_manage_downloads)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.transfer_manage_downloads_message),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (isLoadingPhoneStoredFiles) {
                    Text(
                        stringResource(R.string.transfer_manage_loading_phone_files),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    PhoneStoredFilesSummaryRow(
                        label = stringResource(R.string.transfer_manage_imported_poi_label),
                        group = phoneStoredFilesSummary.poi,
                        context = context,
                    )
                    PhoneStoredFilesSummaryRow(
                        label = stringResource(R.string.transfer_manage_routing_packs_label),
                        group = phoneStoredFilesSummary.routing,
                        context = context,
                    )
                }
                if (isClearingPhoneStoredFiles) {
                    Text(
                        stringResource(R.string.transfer_manage_clearing_phone_files),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            onIsClearingPhoneStoredFilesChange(true)
                            val result =
                                withContext(Dispatchers.IO) {
                                    clearPhoneStoredFiles(
                                        context = context,
                                        clearPoi = true,
                                        clearRouting = false,
                                    )
                                }
                            removeClearedGeneratedFilesFromSelection(
                                context = context,
                                viewModel = viewModel,
                                uiState = uiState,
                                removedFileNames = result.removedFileNames,
                            )
                            onRefreshRequested()
                            onIsClearingPhoneStoredFilesChange(false)
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled =
                        !uiLocked &&
                            !isLoadingPhoneStoredFiles &&
                            !isClearingPhoneStoredFiles &&
                            phoneStoredFilesSummary.poi.fileCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.transfer_manage_clear_poi))
                }
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            onIsClearingPhoneStoredFilesChange(true)
                            val result =
                                withContext(Dispatchers.IO) {
                                    clearPhoneStoredFiles(
                                        context = context,
                                        clearPoi = false,
                                        clearRouting = true,
                                    )
                                }
                            removeClearedGeneratedFilesFromSelection(
                                context = context,
                                viewModel = viewModel,
                                uiState = uiState,
                                removedFileNames = result.removedFileNames,
                            )
                            onRefreshRequested()
                            onIsClearingPhoneStoredFilesChange(false)
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled =
                        !uiLocked &&
                            !isLoadingPhoneStoredFiles &&
                            !isClearingPhoneStoredFiles &&
                            phoneStoredFilesSummary.routing.fileCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.transfer_manage_clear_routing))
                }
                Button(
                    onClick = {
                        coroutineScope.launch {
                            onIsClearingPhoneStoredFilesChange(true)
                            val result =
                                withContext(Dispatchers.IO) {
                                    clearPhoneStoredFiles(
                                        context = context,
                                        clearPoi = true,
                                        clearRouting = true,
                                    )
                                }
                            removeClearedGeneratedFilesFromSelection(
                                context = context,
                                viewModel = viewModel,
                                uiState = uiState,
                                removedFileNames = result.removedFileNames,
                            )
                            onRefreshRequested()
                            onIsClearingPhoneStoredFilesChange(false)
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled =
                        !uiLocked &&
                            !isLoadingPhoneStoredFiles &&
                            !isClearingPhoneStoredFiles &&
                            (
                                phoneStoredFilesSummary.poi.fileCount > 0 ||
                                    phoneStoredFilesSummary.routing.fileCount > 0
                            ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.transfer_manage_clear_all))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isClearingPhoneStoredFiles,
            ) {
                Text(stringResource(R.string.common_action_close))
            }
        },
    )
}

@Composable
internal fun FilePickerQuickGuideDialog(
    adaptive: CompanionAdaptiveSpec,
    mode: QuickGuideMode,
    onDismiss: () -> Unit,
) {
    val pages =
        remember(mode) {
            when (mode) {
                QuickGuideMode.GENERAL ->
                    listOf(
                        QuickGuidePage(
                            title = "Welcome to GlanceMap Companion",
                            intro = WELCOME_WATCH_DOWNLOAD_INTRO,
                            lines =
                                listOf(
                                    WELCOME_SEND_TO_WATCH_LINE,
                                    WELCOME_LIVE_TRACKING_LINE,
                                    WELCOME_OFFLINE_LINE,
                                    QUICK_GUIDE_BOOK_ICON_LINE,
                                ),
                        ),
                    )

                QuickGuideMode.TRANSFER ->
                    listOf(
                        QuickGuidePage(
                            title = "Get files ready",
                            lines =
                                listOf(
                                    "Use 1. Download to get Mapsforge OSM .map, POI, GPX, or routing files.",
                                    "Tap 2. Select file(s) to add files from the phone:",
                                    ".map = offline map",
                                    ".poi = points of interest",
                                    ".gpx = route/track",
                                    ".rd5 = offline routing tile",
                                    ".hgt / .hgt.zip / .hgt.gz = elevation data for hill shading / slope",
                                ),
                        ),
                        QuickGuidePage(
                            title = "Prepare the watch",
                            lines =
                                listOf(
                                    "Open GlanceMap on the watch and keep it near the phone.",
                                    "For large transfers, charge the watch and use the same Wi-Fi or phone hotspot.",
                                    STAY_OPEN_GUIDE_LINE,
                                    "Without Wi-Fi, Bluetooth can send files up to 50 MB.",
                                ),
                        ),
                        QuickGuidePage(
                            title = "POI & routing areas",
                            lines =
                                listOf(
                                    "For POI and routing downloads, first choose the area you need.",
                                    "POI downloads OSM points of interest.",
                                    "Routing downloads data for creating GPX routes offline on the watch.",
                                    "You can choose an area on the map, pick a region.",
                                    "Refresh last import repeats the previous area.",
                                ),
                        ),
                        QuickGuidePage(
                            title = "Send",
                            lines =
                                listOf(
                                    "Tap Send and keep phone and watch close until it finishes.",
                                    "If it stops, send the same file again; it usually resumes " +
                                        "from the partial file already on the watch.",
                                    "History shows each transfer status.",
                                ),
                        ),
                    )

                QuickGuideMode.LIVE_TRACKING ->
                    listOf(
                        QuickGuidePage(
                            title = "Get started",
                            intro =
                                "Live Tracking is provided by Arkluz. GlanceMap acts as the phone interface to configure it.",
                            lines =
                                listOf(
                                    "Tap Set up live tracking.",
                                    "Connect to an existing private group or create one.",
                                    "Add your name, GPS update frequency, and safety alerts.",
                                ),
                        ),
                        QuickGuidePage(
                            title = "Share updates",
                            lines =
                                listOf(
                                    "Optionally upload a GPX route or add a comment to send to your contacts.",
                                    "Start tracking.",
                                    "While tracking, you can modify GPX and share a new comment by clicking on \"Send update\".",
                                ),
                        ),
                        QuickGuidePage(
                            title = "Track links",
                            lines =
                                listOf(
                                    "Participant link opens your own live track.",
                                    "Group link opens the shared group map.",
                                    "Use View & Share to open or share your live tracking links.",
                                ),
                        ),
                    )

                QuickGuideMode.MAP_LEGEND ->
                    listOf(
                        QuickGuidePage(
                            title = "Map Legend",
                            intro = "Use this area to open reference material for the map themes used by GlanceMap.",
                            lines =
                                listOf(
                                    "Select the theme you use on the watch.",
                                    "Open the legend PDF or reference page to understand symbols, colors, and paths.",
                                    "These links are external references and may open in your browser.",
                                ),
                        ),
                    )
            }
        }
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    var showLiveTrackingPrivacyDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveTrackingContactDialog by rememberSaveable { mutableStateOf(false) }
    val page = pages[pageIndex]
    val isWelcomePage = mode == QuickGuideMode.GENERAL
    val dialogTitle = quickGuideDialogTitle(mode)
    val showBodyTitle = !isWelcomePage && pages.size > 1
    val titleHeight =
        when {
            isWelcomePage -> 88.dp
            mode == QuickGuideMode.LIVE_TRACKING -> 88.dp
            pages.size > 1 -> 72.dp
            else -> 48.dp
        }
    val bodyMaxHeight =
        if (isWelcomePage) {
            adaptive.quickGuideDialogMaxHeight.coerceAtMost(360.dp)
        } else {
            adaptive.quickGuideDialogMaxHeight
        }
    val bodyScrollState = rememberScrollState()
    val density = LocalDensity.current
    val pageSwipeThresholdPx = with(density) { QUICK_GUIDE_PAGE_SWIPE_THRESHOLD_DP.dp.toPx() }
    val pageSwipeModifier =
        if (pages.size > 1) {
            Modifier.pointerInput(pages.size, pageIndex) {
                var horizontalDragTotal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDragTotal = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        horizontalDragTotal += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        when {
                            horizontalDragTotal < -pageSwipeThresholdPx && pageIndex < pages.lastIndex -> {
                                pageIndex += 1
                            }

                            horizontalDragTotal > pageSwipeThresholdPx && pageIndex > 0 -> {
                                pageIndex -= 1
                            }
                        }
                    },
                    onDragCancel = { horizontalDragTotal = 0f },
                )
            }
        } else {
            Modifier
        }
    LaunchedEffect(mode, pageIndex) {
        bodyScrollState.scrollTo(0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = titleHeight),
                contentAlignment =
                    if (isWelcomePage) {
                        Alignment.Center
                    } else {
                        Alignment.TopStart
                    },
            ) {
                if (isWelcomePage) {
                    Text(
                        text = page.title,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = dialogTitle,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                            )
                            if (mode == QuickGuideMode.LIVE_TRACKING) {
                                IconButton(onClick = { showLiveTrackingPrivacyDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.Gavel,
                                        contentDescription = stringResource(R.string.settings_privacy_policy_title),
                                    )
                                }
                                IconButton(onClick = { showLiveTrackingContactDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.ContactMail,
                                        contentDescription =
                                            stringResource(
                                                R.string.settings_contact_contributions_title,
                                            ),
                                    )
                                }
                            }
                        }
                        if (pages.size > 1 && mode != QuickGuideMode.LIVE_TRACKING) {
                            Text(
                                "Step ${pageIndex + 1} of ${pages.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .then(pageSwipeModifier)
                        .fillMaxWidth()
                        .heightIn(max = bodyMaxHeight),
                verticalArrangement = Arrangement.spacedBy(if (isWelcomePage) 8.dp else 12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(end = 10.dp)
                                .verticalScroll(bodyScrollState),
                        verticalArrangement = Arrangement.spacedBy(if (isWelcomePage) 10.dp else 12.dp),
                    ) {
                        if (showBodyTitle) {
                            Text(
                                text = page.title,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Start,
                            )
                        }
                        page.intro?.let { intro ->
                            if (intro == WELCOME_WATCH_DOWNLOAD_INTRO) {
                                welcomeWatchDownloadIntroText()
                            } else {
                                Text(
                                    text = intro,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign =
                                        if (isWelcomePage) {
                                            TextAlign.Center
                                        } else {
                                            TextAlign.Start
                                        },
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            page.lines.forEach { line ->
                                quickGuideLineText(line = line)
                            }
                        }
                    }
                    PageScrollbar(
                        scrollState = bodyScrollState,
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight(),
                    )
                }
                if (pages.size > 1) {
                    quickGuidePageIndicator(
                        pageCount = pages.size,
                        selectedPage = pageIndex,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_action_close))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pages.size > 1) {
                    TextButton(
                        onClick = { pageIndex -= 1 },
                        enabled = pageIndex > 0,
                    ) {
                        Text(stringResource(R.string.common_action_back))
                    }
                }
                Button(
                    onClick = {
                        if (pageIndex == pages.lastIndex) {
                            onDismiss()
                        } else {
                            pageIndex += 1
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (pageIndex == pages.lastIndex) {
                                R.string.common_action_done
                            } else {
                                R.string.common_action_next
                            },
                        ),
                    )
                }
            }
        },
    )

    if (showLiveTrackingPrivacyDialog) {
        LiveTrackingPrivacyPolicyDialog(
            onDismiss = { showLiveTrackingPrivacyDialog = false },
        )
    }
    if (showLiveTrackingContactDialog) {
        LiveTrackingContactDialog(
            onDismiss = { showLiveTrackingContactDialog = false },
        )
    }
}

@Composable
private fun LiveTrackingPrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_privacy_policy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.live_tracking_privacy_intro))
                Text(stringResource(R.string.live_tracking_privacy_tracking_data))
                Text(stringResource(R.string.live_tracking_privacy_email_data))
                Text(stringResource(R.string.live_tracking_privacy_offline_data))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_action_close))
            }
        },
    )
}

@Composable
private fun LiveTrackingContactDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_contact_contributions_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.live_tracking_contact_action),
                    modifier = Modifier.clickable { openQuickGuideUrl(context, ARKLUZ_CONTACT_URL) },
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.live_tracking_arkluz_website_action),
                    modifier = Modifier.clickable { openQuickGuideUrl(context, ARKLUZ_WEBSITE_URL) },
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.live_tracking_contributions))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_action_close))
            }
        },
    )
}

@Composable
private fun welcomeWatchDownloadIntroText() {
    Text(
        text =
            buildAnnotatedString {
                append("Start on the watch and use ")
                appendInlineContent(GUIDE_DOWNLOAD_ICON_ID, "[download]")
                append(" to download offline maps.")
            },
        modifier = Modifier.fillMaxWidth(),
        inlineContent = welcomeGuideIntroInlineContent(),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

private fun welcomeGuideIntroInlineContent(): Map<String, InlineTextContent> =
    mapOf(
        GUIDE_DOWNLOAD_ICON_ID to guideInlineIcon(Icons.Filled.Download, "Download"),
    )

private fun welcomeGuideActionInlineContent(): Map<String, InlineTextContent> =
    mapOf(
        GUIDE_SEND_TO_WATCH_ICON_ID to guideInlineIcon(Icons.AutoMirrored.Filled.SendToMobile, "Send to watch"),
        GUIDE_LIVE_TRACKING_ICON_ID to guideInlineIcon(Icons.Filled.SpatialTracking, "Live Tracking"),
    )

@Composable
private fun quickGuideLineText(line: String) {
    when (line) {
        STAY_OPEN_GUIDE_LINE -> stayOpenGuideLineText()
        WELCOME_SEND_TO_WATCH_LINE -> welcomeSendToWatchLineText()
        WELCOME_LIVE_TRACKING_LINE -> welcomeLiveTrackingLineText()
        WELCOME_OFFLINE_LINE -> welcomeOfflineLineText()
        QUICK_GUIDE_BOOK_ICON_LINE -> quickGuideBookIconLineText()
        else -> quickGuidePlainLineText(line = line)
    }
}

@Composable
private fun welcomeSendToWatchLineText() {
    Text(
        text =
            buildAnnotatedString {
                appendInlineContent(GUIDE_SEND_TO_WATCH_ICON_ID, "[send]")
                append(" Use the phone companion to send GPX routes to your watch. You can also send maps, POI, routing data and elevation files.")
            },
        inlineContent = welcomeGuideActionInlineContent(),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun welcomeLiveTrackingLineText() {
    Text(
        text =
            buildAnnotatedString {
                appendInlineContent(GUIDE_LIVE_TRACKING_ICON_ID, "[live]")
                append(" Start Live Tracking from the phone to share your GPS position to friends & family.")
            },
        inlineContent = welcomeGuideActionInlineContent(),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun welcomeOfflineLineText() {
    Text(
        text = stringResource(R.string.transfer_quick_guide_offline_hint),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun quickGuidePlainLineText(line: String) {
    val context = LocalContext.current
    val url = QUICK_GUIDE_URL_PATTERN.find(line)?.value

    Text(
        text = "• $line",
        modifier =
            if (url == null) {
                Modifier
            } else {
                Modifier.clickable { openQuickGuideUrl(context, url) }
            },
        style =
            if (url == null) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline)
            },
        color =
            if (url == null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.primary
            },
    )
}

@Composable
private fun quickGuideBookIconLineText() {
    Text(
        text =
            buildAnnotatedString {
                append("Tap the ")
                appendInlineContent(GUIDE_BOOK_ICON_ID, "[book]")
                append(" book icon in the top-right corner of each area to open its quick guide again.")
            },
        inlineContent = bookGuideInlineContent(),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private val QUICK_GUIDE_URL_PATTERN = Regex("""https?://\S+""")

private fun openQuickGuideUrl(
    context: Context,
    url: String,
) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@Composable
private fun stayOpenGuideLineText() {
    Text(
        text =
            buildAnnotatedString {
                append("• Tap ")
                appendInlineContent(GUIDE_TOOLS_ICON_ID, "[tools]")
                append(" tools, then ")
                appendInlineContent(GUIDE_STAY_ICON_ID, "[stay]")
                append(" Stay. You can also enable Always-on display.")
            },
        inlineContent = stayOpenGuideInlineContent(),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun stayOpenGuideInlineContent(): Map<String, InlineTextContent> =
    mapOf(
        GUIDE_TOOLS_ICON_ID to guideInlineIcon(Icons.Filled.ViewComfyAlt, "Tools"),
        GUIDE_STAY_ICON_ID to guideInlineIcon(Icons.Filled.Visibility, "Stay"),
    )

private fun bookGuideInlineContent(): Map<String, InlineTextContent> =
    mapOf(
        GUIDE_BOOK_ICON_ID to guideInlineIcon(Icons.AutoMirrored.Filled.MenuBook, "Quick Guide"),
    )

private fun guideInlineIcon(
    imageVector: ImageVector,
    contentDescription: String,
): InlineTextContent =
    InlineTextContent(
        Placeholder(
            width = 1.1.em,
            height = 1.1.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

@Composable
private fun quickGuidePageIndicator(
    pageCount: Int,
    selectedPage: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(pageCount) { index ->
            Text(
                text = "•",
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (index == selectedPage) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
            )
        }
    }
}

private data class QuickGuidePage(
    val title: String,
    val intro: String? = null,
    val lines: List<String>,
)

private fun quickGuideDialogTitle(mode: QuickGuideMode): String =
    when (mode) {
        QuickGuideMode.GENERAL -> "Quick guide"
        QuickGuideMode.TRANSFER -> "Send to Watch Guide"
        QuickGuideMode.LIVE_TRACKING -> "Live Tracking"
        QuickGuideMode.MAP_LEGEND -> "Map Legend Guide"
    }

private const val GUIDE_TOOLS_ICON_ID = "guide_tools_icon"
private const val GUIDE_STAY_ICON_ID = "guide_stay_icon"
private const val GUIDE_BOOK_ICON_ID = "guide_book_icon"
private const val GUIDE_DOWNLOAD_ICON_ID = "guide_download_icon"
private const val GUIDE_SEND_TO_WATCH_ICON_ID = "guide_send_to_watch_icon"
private const val GUIDE_LIVE_TRACKING_ICON_ID = "guide_live_tracking_icon"
private const val QUICK_GUIDE_PAGE_SWIPE_THRESHOLD_DP = 64
private const val ARKLUZ_CONTACT_URL = "https://arkluz.com/trk?contact"
private const val ARKLUZ_WEBSITE_URL = "https://arkluz.com/trk?api"
private const val WELCOME_WATCH_DOWNLOAD_INTRO =
    "Start on the watch and use the download icon to download offline maps."
private const val STAY_OPEN_GUIDE_LINE = "__stay_open_guide_line__"
private const val WELCOME_SEND_TO_WATCH_LINE = "__welcome_send_to_watch_line__"
private const val WELCOME_LIVE_TRACKING_LINE = "__welcome_live_tracking_line__"
private const val WELCOME_OFFLINE_LINE = "__welcome_offline_line__"
private const val QUICK_GUIDE_BOOK_ICON_LINE = "__quick_guide_book_icon_line__"

@Composable
internal fun CancelTransferDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_action_cancel_transfer)) },
        text = { Text(stringResource(R.string.transfer_cancel_transfer_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.transfer_action_confirm_stop),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.transfer_action_continue_sending))
            }
        },
    )
}
