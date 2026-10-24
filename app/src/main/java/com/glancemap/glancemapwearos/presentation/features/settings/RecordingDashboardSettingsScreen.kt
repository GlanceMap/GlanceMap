package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.RECORDING_DASHBOARD_MAX_PAGE_COUNT
import com.glancemap.glancemapwearos.data.repository.RECORDING_DASHBOARD_MIN_PAGE_COUNT
import com.glancemap.glancemapwearos.data.repository.RECORDING_DASHBOARD_PAGE_SLOT_COUNT
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.data.repository.defaultRecordingDashboardMetricSlotsForProfile
import com.glancemap.glancemapwearos.data.repository.newRecordingDashboardPageMetricSlotsForProfile
import com.glancemap.glancemapwearos.data.repository.normalizeRecordingDashboardMetricSlots
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.recordingMetricDefinitions
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.recordingMetricPickerOptionsForProfile
import com.glancemap.glancemapwearos.presentation.ui.WearHelpDialog

@Composable
fun RecordingDashboardSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenRecordingSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val listTokens = rememberSettingsListTokens()
    val dashboardMetricSlots by viewModel.recordingDashboardMetricSlots.collectAsState()
    val activityProfile by viewModel.activityProfile.collectAsState()
    val dashboardSlots =
        normalizeRecordingDashboardMetricSlots(
            metricSlots = dashboardMetricSlots,
            defaultMetricSlots = defaultRecordingDashboardMetricSlotsForProfile(activityProfile),
            newPageMetricSlots = newRecordingDashboardPageMetricSlotsForProfile(activityProfile),
        )
    val dashboardPageCount = dashboardSlots.size / RECORDING_DASHBOARD_PAGE_SLOT_COUNT
    var selectedDashboardPage by remember { mutableStateOf(0) }
    var pendingAddedPage by remember { mutableStateOf<Int?>(null) }
    var selectedDashboardSlot by remember { mutableStateOf<Int?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(dashboardPageCount) {
        pendingAddedPage?.let { requestedPage ->
            if (requestedPage < dashboardPageCount) {
                selectedDashboardPage = requestedPage
                pendingAddedPage = null
            }
        }
        selectedDashboardPage = selectedDashboardPage.coerceIn(0, dashboardPageCount - 1)
    }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            SettingsInfoButton(
                contentDescription = stringResource(R.string.settings_dashboard_info),
                onClick = { showInfoDialog = true },
            )
        }
        item {
            RecordingSettingsShortcutChip(
                onClick = onOpenRecordingSettings,
                applyTopPadding = false,
            )
        }
        item {
            RecordingDashboardPageSwitcher(
                pageIndex = selectedDashboardPage,
                pageCount = dashboardPageCount,
                dashboardLabel =
                    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                        stringResource(R.string.common_bike)
                    } else {
                        stringResource(R.string.common_hike)
                    },
                onClick = {
                    selectedDashboardPage = (selectedDashboardPage + 1) % dashboardPageCount
                },
            )
        }
        if (dashboardPageCount < RECORDING_DASHBOARD_MAX_PAGE_COUNT) {
            item {
                SettingsSectionChip(
                    label = stringResource(R.string.settings_add_dashboard_page),
                    secondaryLabel =
                        pluralStringResource(
                            R.plurals.settings_of_pages,
                            RECORDING_DASHBOARD_MAX_PAGE_COUNT,
                            dashboardPageCount,
                            RECORDING_DASHBOARD_MAX_PAGE_COUNT,
                        ),
                    iconImageVector = Icons.Filled.Add,
                    onClick = {
                        pendingAddedPage = dashboardPageCount
                        viewModel.addRecordingDashboardPage()
                    },
                )
            }
        }
        if (dashboardPageCount > RECORDING_DASHBOARD_MIN_PAGE_COUNT) {
            item {
                SettingsPickerChip(
                    label = stringResource(R.string.settings_delete_this_page),
                    secondaryLabel = recordingDashboardPageLabel(selectedDashboardPage, resources = resources),
                    iconImageVector = Icons.Filled.Delete,
                    onClick = {
                        val pageToDelete = selectedDashboardPage
                        selectedDashboardPage =
                            selectedDashboardPage.coerceAtMost(dashboardPageCount - 2)
                        viewModel.deleteRecordingDashboardPage(pageToDelete)
                    },
                )
            }
        }
        RECORDING_DASHBOARD_SLOT_LABELS.forEachIndexed { pageSlotIndex, label ->
            val absoluteSlotIndex = selectedDashboardPage * RECORDING_DASHBOARD_PAGE_SLOT_COUNT + pageSlotIndex
            item {
                RecordingMetricSlotSetting(
                    label = stringResource(label),
                    metricId = dashboardSlots[absoluteSlotIndex],
                    onClick = { selectedDashboardSlot = absoluteSlotIndex },
                )
            }
        }
    }
    RecordingDashboardInfoDialog(
        visible = showInfoDialog,
        activityProfile = activityProfile,
        onDismiss = { showInfoDialog = false },
    )
    selectedDashboardSlot?.let { slotIndex ->
        OptionPickerDialog(
            visible = true,
            title = stringResource(RECORDING_DASHBOARD_SLOT_LABELS[slotIndex % RECORDING_DASHBOARD_PAGE_SLOT_COUNT]),
            selectedValue = dashboardSlots[slotIndex],
            options = recordingMetricPickerOptionsForProfile(activityProfile, resources = resources),
            onDismiss = { selectedDashboardSlot = null },
            onSelect = { metricId ->
                viewModel.setRecordingDashboardMetricSlot(slotIndex, metricId)
                DebugTelemetry.log(
                    "TraceRecording",
                    "event=dashboard_metric_selected slot=$slotIndex metric=$metricId source=settings_dashboard",
                )
                selectedDashboardSlot = null
            },
        )
    }
}

@Suppress("FunctionNaming") // Compose functions use PascalCase by convention.
@Composable
private fun RecordingDashboardInfoDialog(
    visible: Boolean,
    activityProfile: String,
    onDismiss: () -> Unit,
) {
    WearHelpDialog(
        visible = visible,
        title =
            if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                stringResource(R.string.settings_bike_dashboard)
            } else {
                stringResource(R.string.settings_hike_dashboard)
            },
        onDismiss = onDismiss,
        lines =
            listOf(
                stringResource(R.string.settings_hike_and_bike_keep_separate_dashboards),
                stringResource(R.string.settings_in_the_rec_popup_long_press_any_metric_to_change_it),
            ),
    )
}

@Composable
private fun RecordingMetricSlotSetting(
    label: String,
    metricId: String,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    SettingsPickerChip(
        label = label,
        secondaryLabel = recordingMetricLabel(metricId, resources = resources),
        onClick = onClick,
    )
}

private val RECORDING_DASHBOARD_SLOT_LABELS =
    listOf(
        R.string.settings_top_measure,
        R.string.settings_left_measure,
        R.string.settings_right_measure,
        R.string.settings_bottom_measure,
    )

private fun recordingDashboardPageLabel(
    pageIndex: Int,
    resources: Resources,
): String = resources.getString(R.string.settings_page, pageIndex + 1)

private fun recordingMetricLabel(
    metricId: String,
    resources: Resources,
): String =
    resources.getString(
        recordingMetricDefinitions.firstOrNull { it.id == metricId }?.labelRes ?: R.string.common_distance,
    )
