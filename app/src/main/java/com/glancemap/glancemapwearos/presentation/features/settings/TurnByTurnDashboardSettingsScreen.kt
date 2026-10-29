package com.glancemap.glancemapwearos.presentation.features.settings

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
import com.glancemap.glancemapwearos.data.repository.TURN_BY_TURN_DASHBOARD_MAX_PAGE_COUNT
import com.glancemap.glancemapwearos.data.repository.TURN_BY_TURN_DASHBOARD_MIN_PAGE_COUNT
import com.glancemap.glancemapwearos.data.repository.TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT
import com.glancemap.glancemapwearos.data.repository.normalizeTurnByTurnDashboardMetricSlots
import com.glancemap.glancemapwearos.presentation.features.navigate.turnByTurnMetricLabel
import com.glancemap.glancemapwearos.presentation.features.navigate.turnByTurnMetricPickerOptions
import com.glancemap.glancemapwearos.presentation.ui.WearHelpDialog

@Composable
fun TurnByTurnDashboardSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenTurnByTurnSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val metricSlots by viewModel.turnByTurnDashboardMetricSlots.collectAsState()
    val slots = normalizeTurnByTurnDashboardMetricSlots(metricSlots)
    val pageCount = slots.size / TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT
    var selectedPage by remember { mutableStateOf(0) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var pendingAddedPage by remember { mutableStateOf<Int?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    LaunchedEffect(pageCount) {
        pendingAddedPage?.takeIf { it < pageCount }?.let {
            selectedPage = it
            pendingAddedPage = null
        }
        selectedPage = selectedPage.coerceIn(0, pageCount - 1)
    }

    WearSettingsListScreen(
        listTokens = rememberSettingsListTokens(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            SettingsInfoButton(
                contentDescription = stringResource(R.string.settings_turn_by_turn_dashboard_info),
                onClick = { showInfo = true },
            )
        }
        item {
            TurnByTurnSettingsShortcutChip(
                onClick = onOpenTurnByTurnSettings,
                applyTopPadding = false,
            )
        }
        item {
            RecordingDashboardPageSwitcher(
                pageIndex = selectedPage,
                pageCount = pageCount,
                onClick = { selectedPage = (selectedPage + 1) % pageCount },
            )
        }
        if (pageCount < TURN_BY_TURN_DASHBOARD_MAX_PAGE_COUNT) {
            item {
                SettingsSectionChip(
                    label = stringResource(R.string.settings_add_dashboard_page),
                    secondaryLabel =
                        pluralStringResource(
                            R.plurals.settings_of_pages,
                            TURN_BY_TURN_DASHBOARD_MAX_PAGE_COUNT,
                            pageCount,
                            TURN_BY_TURN_DASHBOARD_MAX_PAGE_COUNT,
                        ),
                    iconImageVector = Icons.Filled.Add,
                    onClick = {
                        pendingAddedPage = pageCount
                        viewModel.addTurnByTurnDashboardPage()
                    },
                )
            }
        }
        if (pageCount > TURN_BY_TURN_DASHBOARD_MIN_PAGE_COUNT) {
            item {
                SettingsPickerChip(
                    label = stringResource(R.string.settings_delete_this_page),
                    secondaryLabel = stringResource(R.string.settings_page, selectedPage + 1),
                    iconImageVector = Icons.Filled.Delete,
                    onClick = {
                        val pageToDelete = selectedPage
                        selectedPage = selectedPage.coerceAtMost(pageCount - 2)
                        viewModel.deleteTurnByTurnDashboardPage(pageToDelete)
                    },
                )
            }
        }
        DASHBOARD_SLOT_LABELS.forEachIndexed { pageSlot, label ->
            val slotIndex = selectedPage * TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT + pageSlot
            item {
                SettingsPickerChip(
                    label = stringResource(label),
                    secondaryLabel = turnByTurnMetricLabel(slots[slotIndex], resources = resources),
                    onClick = { selectedSlot = slotIndex },
                )
            }
        }
    }

    WearHelpDialog(
        visible = showInfo,
        title = stringResource(R.string.settings_tbt_dashboard),
        lines =
            listOf(
                stringResource(R.string.settings_the_first_full_screen_page_keeps_turn_guidance),
                stringResource(R.string.settings_use_the_crown_or_swipe_vertically_for_route_metric_pages),
                stringResource(R.string.settings_long_press_a_metric_in_the_tbt_popup_to_change_it),
            ),
        onDismiss = { showInfo = false },
    )
    selectedSlot?.let { slotIndex ->
        OptionPickerDialog(
            visible = true,
            title = stringResource(DASHBOARD_SLOT_LABELS[slotIndex % TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT]),
            selectedValue = slots[slotIndex],
            options =
                turnByTurnMetricPickerOptions
                    .map { (value, labelRes) ->
                        value to stringResource(labelRes)
                    }.sortedBy { it.second.lowercase() },
            onDismiss = { selectedSlot = null },
            onSelect = { metricId ->
                viewModel.setTurnByTurnDashboardMetricSlot(slotIndex, metricId)
                selectedSlot = null
            },
        )
    }
}

private val DASHBOARD_SLOT_LABELS =
    listOf(
        R.string.settings_top_measure,
        R.string.settings_left_measure,
        R.string.settings_right_measure,
        R.string.settings_bottom_measure,
    )
