@file:Suppress(
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
)

package com.glancemap.glancemapwearos.presentation.features.download

import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
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
        title = stringResource(R.string.download_search_area),
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
                        text = stringResource(R.string.download_france_alps),
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
            Text(stringResource(R.string.download_apply))
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
            Text(stringResource(R.string.common_cancel))
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
        title = stringResource(R.string.common_download),
        onDismiss = onDismiss,
    ) {
        item {
            Text(
                text = stringResource(R.string.download_connect_the_watch_to_wi_fi_and_keep_it_on_the_charger),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text =
                    stringResource(
                        R.string.download_thank_you_to_openandromaps_for_providing_the_offline_maps_and_pois,
                    ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = stringResource(R.string.download_routing_enables_offline_route_calculation),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = stringResource(R.string.download_elevation_adds_altitude_slope_and_terrain_shading),
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
                Text(stringResource(R.string.download_use_update_button_to_refresh_bundles))
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
        title = stringResource(R.string.download_wi_fi_recommended),
        message = message,
        confirmText = stringResource(R.string.common_continue),
        onConfirm = onContinue,
        onDismissRequest = onDismiss,
        dismissText = stringResource(R.string.common_cancel),
    )
}

@Composable
internal fun RefreshBundleDialog(
    check: OamBundleUpdateCheck?,
    selection: OamDownloadSelection,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    if (check == null) return

    val repairNeeded = check.status == OamBundleUpdateStatus.REPAIR_NEEDED
    val updateAvailable = check.status == OamBundleUpdateStatus.UPDATE_AVAILABLE
    val upToDate = check.status == OamBundleUpdateStatus.UP_TO_DATE
    val hasSelectedAdditions = check.hasSelectedAdditions(selection)
    WearActionDialog(
        visible = true,
        title =
            when {
                repairNeeded -> stringResource(R.string.download_repair_needed)
                updateAvailable -> stringResource(R.string.download_update_available)
                upToDate && hasSelectedAdditions -> stringResource(R.string.download_components_available)
                upToDate -> stringResource(R.string.download_already_up_to_date)
                check.checkedFileCount == 0 -> stringResource(R.string.download_update_info_missing)
                else -> stringResource(R.string.download_check_incomplete)
            },
        message = refreshBundleDialogText(check, selection, resources = resources),
        confirmText =
            if (upToDate) {
                stringResource(
                    R.string.download_add_selected,
                )
            } else {
                stringResource(R.string.download_refresh)
            },
        onConfirm = if (upToDate && !hasSelectedAdditions) onDismiss else onConfirm,
        onDismissRequest = onDismiss,
        dismissText = if (upToDate && !hasSelectedAdditions) null else stringResource(R.string.common_cancel),
    )
}

@Composable
internal fun RefreshBundleSummaryDialog(
    summary: OamBundleRefreshSummary?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    if (summary == null) return

    val refreshCount = summary.bundlesToRefresh.size
    val hasRemoteRefresh = summary.updateAvailableCount > 0 || summary.repairNeededCount > 0
    val hasSelectedAdditions = summary.selectedAdditionCount > 0
    val hasRefreshWork = refreshCount > 0
    WearActionDialog(
        visible = true,
        title =
            when {
                summary.repairNeededCount > 0 -> stringResource(R.string.download_repair_bundles)
                hasRemoteRefresh -> stringResource(R.string.download_refresh_bundles)
                hasSelectedAdditions -> stringResource(R.string.download_add_selected_components_title)
                summary.unknownCount > 0 -> stringResource(R.string.download_check_incomplete)
                else -> stringResource(R.string.download_all_up_to_date)
            },
        message = refreshSummaryDialogText(summary, resources = resources),
        confirmText =
            when {
                hasRemoteRefresh -> stringResource(R.string.download_refresh_2, refreshCount)
                hasSelectedAdditions -> stringResource(R.string.download_add, refreshCount)
                else -> stringResource(R.string.common_ok)
            },
        onConfirm = if (hasRefreshWork) onConfirm else onDismiss,
        onDismissRequest = onDismiss,
        dismissText = if (hasRefreshWork) stringResource(R.string.common_cancel) else null,
    )
}

private fun refreshBundleDialogText(
    check: OamBundleUpdateCheck,
    selection: OamDownloadSelection,
    resources: Resources,
): String =
    when (check.status) {
        OamBundleUpdateStatus.REPAIR_NEEDED ->
            buildString {
                append(
                    resources.getString(R.string.download_some_files_in_are_missing_or_damaged, check.bundle.areaLabel),
                )
                if (check.repairFileNames.isNotEmpty()) {
                    append(
                        resources.getString(
                            R.string.download_repair_files,
                            dialogFileNames(check.repairFileNames, resources),
                        ),
                    )
                }
                append(resources.getString(R.string.download_refresh_will_download_only_the_affected_parts))
                appendSelectedAdditions(check, selection, resources = resources)
            }
        OamBundleUpdateStatus.UPDATE_AVAILABLE ->
            buildString {
                append(resources.getString(R.string.download_newer_files_available, check.bundle.areaLabel))
                if (check.changedFileNames.isNotEmpty()) {
                    append(
                        resources.getString(
                            R.string.download_changed_files,
                            dialogFileNames(check.changedFileNames, resources),
                        ),
                    )
                }
                append(
                    resources.getString(R.string.download_existing_files_will_be_replaced_after_the_download_completes),
                )
                appendSelectedAdditions(check, selection, resources = resources)
            }
        OamBundleUpdateStatus.UNKNOWN ->
            if (check.checkedFileCount == 0) {
                resources.getString(
                    R.string.download_has_no_saved_update_info_yet_refresh_once_to_enable_future_checks,
                    check.bundle.areaLabel,
                )
            } else {
                resources.getString(
                    R.string.download_some_files_for_could_not_be_checked_refresh_anyway,
                    check.bundle.areaLabel,
                )
            }
        OamBundleUpdateStatus.UP_TO_DATE ->
            if (check.hasSelectedAdditions(selection)) {
                resources.getString(
                    R.string.download_is_up_to_date_but_selected_settings_include_missing_components_add_the,
                    check.bundle.areaLabel,
                    check.bundle.missingComponentsFor(selection).label(resources = resources),
                )
            } else {
                resources.getString(R.string.download_is_already_up_to_date, check.bundle.areaLabel)
            }
    }

private fun StringBuilder.appendSelectedAdditions(
    check: OamBundleUpdateCheck,
    selection: OamDownloadSelection,
    resources: Resources,
) {
    if (check.hasSelectedAdditions(selection)) {
        append(
            resources.getString(
                R.string.download_selected_additions,
                check.bundle.missingComponentsFor(selection).label(resources = resources),
            ),
        )
    }
}

private fun refreshSummaryDialogText(
    summary: OamBundleRefreshSummary,
    resources: Resources,
): String =
    when {
        summary.bundlesToRefresh.isNotEmpty() ->
            buildString {
                append(resources.getQuantityString(R.plurals.download_checked, summary.totalCount, summary.totalCount))
                if (summary.repairNeededCount > 0) {
                    append(
                        resources.getQuantityString(
                            R.plurals.download_repair_needed_3,
                            summary.repairNeededCount,
                            summary.repairNeededCount,
                        ),
                    )
                }
                if (summary.updateAvailableCount > 0) {
                    append(
                        resources.getQuantityString(
                            R.plurals.download_update_available_2,
                            summary.updateAvailableCount,
                            summary.updateAvailableCount,
                        ),
                    )
                }
                if (summary.unknownCount > 0) {
                    append(
                        resources.getQuantityString(
                            R.plurals.download_check_incomplete_2,
                            summary.unknownCount,
                            summary.unknownCount,
                        ),
                    )
                }
                if (summary.selectedAdditionCount > 0) {
                    append(
                        resources.getQuantityString(
                            R.plurals.download_can_add_selected_components,
                            summary.selectedAdditionCount,
                            summary.selectedAdditionCount,
                        ),
                    )
                }
                if (summary.upToDateCount > 0) {
                    append(
                        resources.getQuantityString(
                            R.plurals.download_up_to_date,
                            summary.upToDateCount,
                            summary.upToDateCount,
                        ),
                    )
                }
                append(
                    resources.getQuantityString(
                        R.plurals.download_refresh_bundle_s,
                        summary.bundlesToRefresh.size,
                        summary.bundlesToRefresh.size,
                    ),
                )
            }
        summary.unknownCount > 0 ->
            buildString {
                append(resources.getQuantityString(R.plurals.download_checked, summary.totalCount, summary.totalCount))
                append(
                    resources.getQuantityString(
                        R.plurals.download_check_incomplete_2,
                        summary.unknownCount,
                        summary.unknownCount,
                    ),
                )
                if (summary.upToDateCount > 0) {
                    append(
                        resources.getQuantityString(
                            R.plurals.download_up_to_date,
                            summary.upToDateCount,
                            summary.upToDateCount,
                        ),
                    )
                }
                if (summary.selectedAdditionCount > 0) {
                    append(
                        resources.getQuantityString(
                            R.plurals.download_can_add_selected_components,
                            summary.selectedAdditionCount,
                            summary.selectedAdditionCount,
                        ),
                    )
                }
                append(resources.getString(R.string.download_no_confirmed_updates_were_found))
            }
        summary.selectedAdditionCount > 0 ->
            resources.getString(
                R.string.download_combined_summary,
                resources.getQuantityString(
                    R.plurals.download_selected_bundles_up_to_date,
                    summary.totalCount,
                    summary.totalCount,
                ),
                resources.getQuantityString(
                    R.plurals.download_add_selected_components,
                    summary.selectedAdditionCount,
                    summary.selectedAdditionCount,
                ),
            )
        else ->
            resources.getQuantityString(
                R.plurals.download_selected_bundles_are_up_to_date_2,
                summary.totalCount,
                summary.totalCount,
            )
    }

private const val MAX_DIALOG_FILE_NAMES = 3

private fun dialogFileNames(
    names: List<String>,
    resources: Resources,
): String {
    val visible = names.take(MAX_DIALOG_FILE_NAMES).joinToString(", ")
    val extra = names.size - MAX_DIALOG_FILE_NAMES
    return if (extra > 0) resources.getString(R.string.common_list_with_more_count, visible, extra) else visible
}
