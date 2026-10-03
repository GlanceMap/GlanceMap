@file:Suppress(
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
)

package com.glancemap.glancemapwearos.presentation.features.download

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearFormDialog
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog

@Composable
internal fun AreaSearchDialog(
    visible: Boolean,
    initialQuery: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    if (!visible) return

    var draftQuery by remember(visible, initialQuery) { mutableStateOf(initialQuery) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(visible) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    WearFormDialog(
        visible = visible,
        title = "Search area",
        onDismiss = onDismiss,
    ) { formTokens ->
        BasicTextField(
            value = draftQuery,
            onValueChange = { draftQuery = it.take(32) },
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
                        text = "France, Alps...",
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
            onClick = { onApply(draftQuery) },
            modifier =
                formTokens.controlModifier
                    .heightIn(min = formTokens.buttonMinHeight),
        ) {
            Text("Apply")
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
            Text("Cancel")
        }
    }
}

@Composable
internal fun OamAttributionDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    WearInfoDialog(
        visible = visible,
        title = "Download",
        onDismiss = onDismiss,
    ) {
        item {
            Text(
                text = "Connect the watch to Wi-Fi and keep it on the charger.",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = "Thank you to OpenAndroMaps for providing the offline maps and POIs.",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = "Routing enables offline route calculation.",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = "Elevation adds altitude, slope, and terrain shading.",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Update,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("Use update button to refresh bundles.")
            }
        }
    }
}

@Composable
internal fun DownloadNetworkWarningDialog(
    message: String?,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    WearActionDialog(
        visible = true,
        title = "Wi-Fi recommended",
        message = message,
        confirmText = "Continue",
        onConfirm = onContinue,
        onDismissRequest = onDismiss,
        dismissText = "Cancel",
    )
}

@Composable
internal fun RefreshBundleDialog(
    check: OamBundleUpdateCheck?,
    selection: OamDownloadSelection,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (check == null) return

    val repairNeeded = check.status == OamBundleUpdateStatus.REPAIR_NEEDED
    val updateAvailable = check.status == OamBundleUpdateStatus.UPDATE_AVAILABLE
    val upToDate = check.status == OamBundleUpdateStatus.UP_TO_DATE
    val hasSelectedAdditions = check.hasSelectedAdditions(selection)
    WearActionDialog(
        visible = true,
        title =
            when {
                repairNeeded -> "Repair needed"
                updateAvailable -> "Update available"
                upToDate && hasSelectedAdditions -> "Components available"
                upToDate -> "Already up to date"
                check.checkedFileCount == 0 -> "Update info missing"
                else -> "Check incomplete"
            },
        message = refreshBundleDialogText(check, selection),
        confirmText = if (upToDate) "Add selected" else "Refresh",
        onConfirm = if (upToDate && !hasSelectedAdditions) onDismiss else onConfirm,
        onDismissRequest = onDismiss,
        dismissText = if (upToDate && !hasSelectedAdditions) null else "Cancel",
    )
}

@Composable
internal fun RefreshBundleSummaryDialog(
    summary: OamBundleRefreshSummary?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (summary == null) return

    val refreshCount = summary.bundlesToRefresh.size
    val hasRemoteRefresh = summary.updateAvailableCount > 0 || summary.repairNeededCount > 0
    val hasSelectedAdditions = summary.selectedAdditionCount > 0
    val hasRefreshWork = refreshCount > 0
    WearActionDialog(
        visible = true,
        title =
            when {
                summary.repairNeededCount > 0 -> "Repair bundles?"
                hasRemoteRefresh -> "Refresh bundles?"
                hasSelectedAdditions -> "Add selected components?"
                summary.unknownCount > 0 -> "Check incomplete"
                else -> "All up to date"
            },
        message = refreshSummaryDialogText(summary),
        confirmText =
            when {
                hasRemoteRefresh -> "Refresh $refreshCount"
                hasSelectedAdditions -> "Add $refreshCount"
                else -> "OK"
            },
        onConfirm = if (hasRefreshWork) onConfirm else onDismiss,
        onDismissRequest = onDismiss,
        dismissText = if (hasRefreshWork) "Cancel" else null,
    )
}

private fun refreshBundleDialogText(
    check: OamBundleUpdateCheck,
    selection: OamDownloadSelection,
): String =
    when (check.status) {
        OamBundleUpdateStatus.REPAIR_NEEDED ->
            buildString {
                append("Some files in ${check.bundle.areaLabel} are missing or damaged.")
                if (check.repairFileNames.isNotEmpty()) {
                    append("\n\nRepair: ")
                    append(check.repairFileNames.take(MAX_DIALOG_FILE_NAMES).joinToString(", "))
                    if (check.repairFileNames.size > MAX_DIALOG_FILE_NAMES) {
                        append(" +")
                        append(check.repairFileNames.size - MAX_DIALOG_FILE_NAMES)
                    }
                }
                append("\n\nRefresh will download only the affected parts.")
                appendSelectedAdditions(check, selection)
            }
        OamBundleUpdateStatus.UPDATE_AVAILABLE ->
            buildString {
                append(check.bundle.areaLabel)
                append(" has newer files available.")
                if (check.changedFileNames.isNotEmpty()) {
                    append("\n\nChanged: ")
                    append(check.changedFileNames.take(MAX_DIALOG_FILE_NAMES).joinToString(", "))
                    if (check.changedFileNames.size > MAX_DIALOG_FILE_NAMES) {
                        append(" +")
                        append(check.changedFileNames.size - MAX_DIALOG_FILE_NAMES)
                    }
                }
                append("\n\nExisting files will be replaced after the download completes.")
                appendSelectedAdditions(check, selection)
            }
        OamBundleUpdateStatus.UNKNOWN ->
            if (check.checkedFileCount == 0) {
                "${check.bundle.areaLabel} has no saved update info yet.\n\n" +
                    "Refresh once to enable future checks."
            } else {
                "Some files for ${check.bundle.areaLabel} could not be checked.\n\n" +
                    "Refresh anyway?"
            }
        OamBundleUpdateStatus.UP_TO_DATE ->
            if (check.hasSelectedAdditions(selection)) {
                "${check.bundle.areaLabel} is up to date, but selected settings include missing components: " +
                    "${check.bundle.missingComponentsFor(selection).label()}.\n\nAdd them without replacing " +
                    "healthy existing files?"
            } else {
                "${check.bundle.areaLabel} is already up to date."
            }
    }

private fun StringBuilder.appendSelectedAdditions(
    check: OamBundleUpdateCheck,
    selection: OamDownloadSelection,
) {
    if (check.hasSelectedAdditions(selection)) {
        append("\n\nSelected settings will also add: ")
        append(check.bundle.missingComponentsFor(selection).label())
        append(".")
    }
}

private fun refreshSummaryDialogText(summary: OamBundleRefreshSummary): String =
    when {
        summary.bundlesToRefresh.isNotEmpty() ->
            buildString {
                append("${summary.totalCount} checked")
                if (summary.repairNeededCount > 0) {
                    append("\n${summary.repairNeededCount} repair needed")
                }
                if (summary.updateAvailableCount > 0) {
                    append("\n${summary.updateAvailableCount} update available")
                }
                if (summary.unknownCount > 0) {
                    append("\n${summary.unknownCount} check incomplete")
                }
                if (summary.selectedAdditionCount > 0) {
                    append("\n${summary.selectedAdditionCount} can add selected components")
                }
                if (summary.upToDateCount > 0) {
                    append("\n${summary.upToDateCount} up to date")
                }
                append("\n\nRefresh ${summary.bundlesToRefresh.size} bundle(s)?")
            }
        summary.unknownCount > 0 ->
            buildString {
                append("${summary.totalCount} checked")
                append("\n${summary.unknownCount} check incomplete")
                if (summary.upToDateCount > 0) {
                    append("\n${summary.upToDateCount} up to date")
                }
                if (summary.selectedAdditionCount > 0) {
                    append("\n${summary.selectedAdditionCount} can add selected components")
                }
                append("\n\nNo confirmed updates were found.")
            }
        summary.selectedAdditionCount > 0 ->
            "${summary.totalCount} selected bundle(s) are up to date.\n\n" +
                "Add selected components to ${summary.selectedAdditionCount} bundle(s)?"
        else -> "${summary.totalCount} selected bundle(s) are up to date."
    }

private const val MAX_DIALOG_FILE_NAMES = 3
