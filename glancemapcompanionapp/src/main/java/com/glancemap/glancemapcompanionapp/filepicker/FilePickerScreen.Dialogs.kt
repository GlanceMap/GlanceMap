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
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.pluralStringResource
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
import com.glancemap.shared.transfer.TransferDataLayerContract
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
    val hasCurrentCapture = !debugCaptureState.active && debugCaptureState.sessionId > 0L

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
                    pluralStringResource(
                        R.plurals.home_debug_capture_active_message,
                        debugCaptureState.bufferedLines,
                        debugCaptureState.bufferedLines,
                        TransferDataLayerContract.DIAGNOSTICS_SUPPORT_EMAIL,
                    )
                } else {
                    stringResource(
                        if (hasSavedPhoneRecording) {
                            R.string.home_debug_capture_start_message_saved
                        } else {
                            R.string.home_debug_capture_start_message
                        },
                        TransferDataLayerContract.DIAGNOSTICS_SUPPORT_EMAIL,
                    )
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
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Button(
                        onClick = {
                            onDismiss()
                            viewModel.startPhoneDebugCapture(context)
                        },
                    ) {
                        Text(
                            stringResource(
                                if (hasCurrentCapture) {
                                    R.string.home_debug_capture_start_new_action
                                } else {
                                    R.string.home_debug_capture_start_action
                                },
                            ),
                        )
                    }
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
                    if (hasCurrentCapture) {
                        TextButton(
                            onClick = {
                                onDismiss()
                                viewModel.sendCurrentPhoneDebugCapture(context)
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (debugCaptureState.interrupted) {
                                        R.string.home_debug_capture_send_recovered_action
                                    } else {
                                        R.string.home_debug_capture_send_current_action
                                    },
                                ),
                            )
                        }
                    }
                    if (debugCaptureState.hasPreviousCapture) {
                        TextButton(
                            onClick = {
                                onDismiss()
                                viewModel.sendPreviousPhoneDebugCapture(context)
                            },
                        ) {
                            Text(stringResource(R.string.home_debug_capture_send_previous_action))
                        }
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
            quickGuidePages(mode)
        }
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    var showLiveTrackingPrivacyDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveTrackingContactDialog by rememberSaveable { mutableStateOf(false) }
    val page = pages[pageIndex]
    val isWelcomePage = mode == QuickGuideMode.GENERAL
    val dialogTitle = stringResource(quickGuideDialogTitleResId(mode))
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
                        text = stringResource(page.titleResId),
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
                                stringResource(R.string.quick_guide_step, pageIndex + 1, pages.size),
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
                                text = stringResource(page.titleResId),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Start,
                            )
                        }
                        page.intro?.let { intro ->
                            when (intro) {
                                QuickGuideIntro.WelcomeDownload -> welcomeWatchDownloadIntroText()
                                is QuickGuideIntro.Text -> {
                                    Text(
                                        text = stringResource(intro.textResId),
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
    val (text, inlineContent) =
        quickGuideInlineTextContent(
            textResId = R.string.quick_guide_welcome_download_intro,
            inlineIcons =
                listOf(
                    QuickGuideInlineIcon(
                        id = GUIDE_DOWNLOAD_ICON_ID,
                        imageVector = Icons.Filled.Download,
                        contentDescriptionResId = R.string.quick_guide_download_icon_content_description,
                    ),
                ),
        )
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        inlineContent = inlineContent,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun quickGuideLineText(line: QuickGuideLine) {
    when (line) {
        QuickGuideLine.StayOpen -> stayOpenGuideLineText()
        QuickGuideLine.WelcomeSendToWatch -> welcomeSendToWatchLineText()
        QuickGuideLine.WelcomeLiveTracking -> welcomeLiveTrackingLineText()
        QuickGuideLine.WelcomeOffline -> welcomeOfflineLineText()
        QuickGuideLine.BookIcon -> quickGuideBookIconLineText()
        is QuickGuideLine.Text -> quickGuidePlainLineText(line.textResId)
    }
}

@Composable
private fun welcomeSendToWatchLineText() {
    val (text, inlineContent) =
        quickGuideInlineTextContent(
            textResId = R.string.quick_guide_welcome_send_to_watch,
            inlineIcons =
                listOf(
                    QuickGuideInlineIcon(
                        id = GUIDE_SEND_TO_WATCH_ICON_ID,
                        imageVector = Icons.AutoMirrored.Filled.SendToMobile,
                        contentDescriptionResId =
                            R.string.quick_guide_send_to_watch_icon_content_description,
                    ),
                ),
        )
    Text(
        text = text,
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun welcomeLiveTrackingLineText() {
    val (text, inlineContent) =
        quickGuideInlineTextContent(
            textResId = R.string.quick_guide_welcome_live_tracking,
            inlineIcons =
                listOf(
                    QuickGuideInlineIcon(
                        id = GUIDE_LIVE_TRACKING_ICON_ID,
                        imageVector = Icons.Filled.SpatialTracking,
                        contentDescriptionResId =
                            R.string.quick_guide_live_tracking_icon_content_description,
                    ),
                ),
        )
    Text(
        text = text,
        inlineContent = inlineContent,
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
private fun quickGuidePlainLineText(
    @StringRes textResId: Int,
) {
    Text(
        text = stringResource(R.string.quick_guide_bullet_line, stringResource(textResId)),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun quickGuideBookIconLineText() {
    val (text, inlineContent) =
        quickGuideInlineTextContent(
            textResId = R.string.quick_guide_welcome_book_icon,
            inlineIcons =
                listOf(
                    QuickGuideInlineIcon(
                        id = GUIDE_BOOK_ICON_ID,
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescriptionResId = R.string.quick_guide_book_icon_content_description,
                    ),
                ),
        )
    Text(
        text = text,
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodyMedium,
    )
}

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
    val (text, inlineContent) =
        quickGuideInlineTextContent(
            textResId = R.string.quick_guide_stay_open,
            inlineIcons =
                listOf(
                    QuickGuideInlineIcon(
                        id = GUIDE_TOOLS_ICON_ID,
                        imageVector = Icons.Filled.ViewComfyAlt,
                        contentDescriptionResId = R.string.quick_guide_tools_icon_content_description,
                    ),
                    QuickGuideInlineIcon(
                        id = GUIDE_STAY_ICON_ID,
                        imageVector = Icons.Filled.Visibility,
                        contentDescriptionResId = R.string.quick_guide_stay_icon_content_description,
                    ),
                ),
        )
    Text(
        text = text,
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun quickGuideInlineTextContent(
    @StringRes textResId: Int,
    inlineIcons: List<QuickGuideInlineIcon>,
): Pair<androidx.compose.ui.text.AnnotatedString, Map<String, InlineTextContent>> {
    val placeholders = inlineIcons.indices.map { "${GUIDE_INLINE_PLACEHOLDER_PREFIX}${it}__" }
    val text =
        when (placeholders.size) {
            1 -> stringResource(textResId, placeholders[0])
            2 -> stringResource(textResId, placeholders[0], placeholders[1])
            else -> error("Quick guide inline content requires one or two icons.")
        }
    val annotatedText =
        buildAnnotatedString {
            var textStart = 0
            guideInlinePlaceholderOccurrences(text, placeholders).forEach { occurrence ->
                val inlineIcon = inlineIcons[occurrence.inlineIconIndex]
                val placeholder = placeholders[occurrence.inlineIconIndex]
                append(text.substring(textStart, occurrence.startIndex))
                appendInlineContent(inlineIcon.id, placeholder)
                textStart = occurrence.startIndex + placeholder.length
            }
            append(text.substring(textStart))
        }
    val inlineContent =
        inlineIcons.associate { inlineIcon ->
            inlineIcon.id to
                guideInlineIcon(
                    imageVector = inlineIcon.imageVector,
                    contentDescription = stringResource(inlineIcon.contentDescriptionResId),
                )
        }
    return annotatedText to inlineContent
}

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
    @StringRes val titleResId: Int,
    val intro: QuickGuideIntro? = null,
    val lines: List<QuickGuideLine>,
)

private sealed interface QuickGuideIntro {
    data class Text(
        @StringRes val textResId: Int,
    ) : QuickGuideIntro

    data object WelcomeDownload : QuickGuideIntro
}

private sealed interface QuickGuideLine {
    data class Text(
        @StringRes val textResId: Int,
    ) : QuickGuideLine

    data object StayOpen : QuickGuideLine

    data object WelcomeSendToWatch : QuickGuideLine

    data object WelcomeLiveTracking : QuickGuideLine

    data object WelcomeOffline : QuickGuideLine

    data object BookIcon : QuickGuideLine
}

private data class QuickGuideInlineIcon(
    val id: String,
    val imageVector: ImageVector,
    @StringRes val contentDescriptionResId: Int,
)

internal data class GuideInlinePlaceholderOccurrence(
    val inlineIconIndex: Int,
    val startIndex: Int,
)

internal fun guideInlinePlaceholderOccurrences(
    text: String,
    placeholders: List<String>,
): List<GuideInlinePlaceholderOccurrence> =
    placeholders
        .mapIndexedNotNull { index, placeholder ->
            text
                .indexOf(placeholder)
                .takeIf { it >= 0 }
                ?.let { startIndex ->
                    GuideInlinePlaceholderOccurrence(
                        inlineIconIndex = index,
                        startIndex = startIndex,
                    )
                }
        }.sortedBy(GuideInlinePlaceholderOccurrence::startIndex)

private fun quickGuidePages(mode: QuickGuideMode): List<QuickGuidePage> =
    when (mode) {
        QuickGuideMode.GENERAL ->
            listOf(
                QuickGuidePage(
                    titleResId = R.string.quick_guide_general_title,
                    intro = QuickGuideIntro.WelcomeDownload,
                    lines =
                        listOf(
                            QuickGuideLine.WelcomeSendToWatch,
                            QuickGuideLine.WelcomeLiveTracking,
                            QuickGuideLine.WelcomeOffline,
                            QuickGuideLine.BookIcon,
                        ),
                ),
            )

        QuickGuideMode.TRANSFER ->
            listOf(
                QuickGuidePage(
                    titleResId = R.string.quick_guide_transfer_files_title,
                    lines =
                        listOf(
                            QuickGuideLine.Text(R.string.quick_guide_transfer_files_download),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_files_select),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_files_map),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_files_poi),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_files_gpx),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_files_routing),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_files_elevation),
                        ),
                ),
                QuickGuidePage(
                    titleResId = R.string.quick_guide_transfer_watch_title,
                    lines =
                        listOf(
                            QuickGuideLine.Text(R.string.quick_guide_transfer_watch_open),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_watch_large_files),
                            QuickGuideLine.StayOpen,
                            QuickGuideLine.Text(R.string.quick_guide_transfer_watch_bluetooth),
                        ),
                ),
                QuickGuidePage(
                    titleResId = R.string.quick_guide_transfer_areas_title,
                    lines =
                        listOf(
                            QuickGuideLine.Text(R.string.quick_guide_transfer_areas_choose),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_areas_poi),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_areas_routing),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_areas_map),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_areas_refresh),
                        ),
                ),
                QuickGuidePage(
                    titleResId = R.string.quick_guide_transfer_send_title,
                    lines =
                        listOf(
                            QuickGuideLine.Text(R.string.quick_guide_transfer_send_close),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_send_resume),
                            QuickGuideLine.Text(R.string.quick_guide_transfer_send_history),
                        ),
                ),
            )

        QuickGuideMode.LIVE_TRACKING ->
            listOf(
                QuickGuidePage(
                    titleResId = R.string.quick_guide_live_tracking_start_title,
                    intro = QuickGuideIntro.Text(R.string.quick_guide_live_tracking_start_intro),
                    lines =
                        listOf(
                            QuickGuideLine.Text(R.string.quick_guide_live_tracking_start_setup),
                            QuickGuideLine.Text(R.string.quick_guide_live_tracking_start_group),
                            QuickGuideLine.Text(R.string.quick_guide_live_tracking_start_options),
                        ),
                ),
                QuickGuidePage(
                    titleResId = R.string.quick_guide_live_tracking_updates_title,
                    lines =
                        listOf(
                            QuickGuideLine.Text(R.string.quick_guide_live_tracking_updates_plan),
                            QuickGuideLine.Text(R.string.quick_guide_live_tracking_updates_start),
                            QuickGuideLine.Text(R.string.quick_guide_live_tracking_updates_change),
                        ),
                ),
                QuickGuidePage(
                    titleResId = R.string.quick_guide_live_tracking_links_title,
                    lines =
                        listOf(
                            QuickGuideLine.Text(R.string.quick_guide_live_tracking_links_participant),
                            QuickGuideLine.Text(R.string.quick_guide_live_tracking_links_group),
                            QuickGuideLine.Text(R.string.quick_guide_live_tracking_links_share),
                        ),
                ),
            )

        QuickGuideMode.MAP_LEGEND ->
            listOf(
                QuickGuidePage(
                    titleResId = R.string.quick_guide_map_legend_title,
                    intro = QuickGuideIntro.Text(R.string.quick_guide_map_legend_intro),
                    lines =
                        listOf(
                            QuickGuideLine.Text(R.string.quick_guide_map_legend_select),
                            QuickGuideLine.Text(R.string.quick_guide_map_legend_reference),
                            QuickGuideLine.Text(R.string.quick_guide_map_legend_external),
                        ),
                ),
            )
    }

@StringRes
private fun quickGuideDialogTitleResId(mode: QuickGuideMode): Int =
    when (mode) {
        QuickGuideMode.GENERAL -> R.string.quick_guide_dialog_title_general
        QuickGuideMode.TRANSFER -> R.string.quick_guide_dialog_title_transfer
        QuickGuideMode.LIVE_TRACKING -> R.string.quick_guide_dialog_title_live_tracking
        QuickGuideMode.MAP_LEGEND -> R.string.quick_guide_dialog_title_map_legend
    }

private const val GUIDE_TOOLS_ICON_ID = "guide_tools_icon"
private const val GUIDE_STAY_ICON_ID = "guide_stay_icon"
private const val GUIDE_BOOK_ICON_ID = "guide_book_icon"
private const val GUIDE_DOWNLOAD_ICON_ID = "guide_download_icon"
private const val GUIDE_SEND_TO_WATCH_ICON_ID = "guide_send_to_watch_icon"
private const val GUIDE_LIVE_TRACKING_ICON_ID = "guide_live_tracking_icon"
private const val GUIDE_INLINE_PLACEHOLDER_PREFIX = "__guide_inline_placeholder_"
private const val QUICK_GUIDE_PAGE_SWIPE_THRESHOLD_DP = 64
private const val ARKLUZ_CONTACT_URL = "https://arkluz.com/trk?contact"
private const val ARKLUZ_WEBSITE_URL = "https://arkluz.com/trk?api"

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
