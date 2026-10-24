@file:Suppress("LongMethod")

package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.BuildConfig
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassHeadingDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassHeadingReferenceBasis
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassHeadingReferenceDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.CrashDiagnosticsStore
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.DemDownloadDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsEmailHandoff
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsSettingsSnapshot
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.FieldMarkerDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.GnssDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.MapHotPathDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.RecordingScreenOffDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.ScreenStateDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.TelemetryFormatters
import com.glancemap.glancemapwearos.core.service.diagnostics.TerrainDiagnostics
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionTelemetry
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalSensorSimulation
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolBusySpinner
import com.glancemap.glancemapwearos.presentation.ui.WearHelpDialog
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import com.glancemap.shared.transfer.TransferDataLayerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.wear.compose.material.Text as WearText

private const val DEBUG_HELP_PREFS = "debug_settings_help_prefs"
private const val DEBUG_EXPORT_INFO_SHOWN_KEY = "debug_export_info_shown"
private val CLEAN_CAPTURE_DEFAULT_LABEL = R.string.debug_clear_all_captured_logs
private val CLEAN_CAPTURE_CLEARED_LABEL = R.string.debug_all_captured_logs_have_been_cleared
private const val CLEAN_CAPTURE_RESET_DELAY_MS = 3500L
private val DIAGNOSTICS_DEFAULT_STATUS = R.string.debug_export_to_support_email

private enum class DiagnosticsExportDialogMode {
    GENERATING,
    CHECK_PHONE,
    FAILED,
}

@Composable
@Suppress("FunctionNaming")
private fun DiagnosticsSettingsSectionTitle() {
    Text(
        text = stringResource(R.string.common_settings),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
fun DebuggingSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val screenSize = rememberWearScreenSize()
    val listTokens = rememberSettingsListTokens()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val compassDeepTraceState by CompassDeepTraceDiagnostics.state.collectAsState()
    val compassHeadingReferenceTestActive by CompassHeadingReferenceDiagnostics.active.collectAsState()
    val compassHeadingReferenceBasis by CompassHeadingReferenceDiagnostics.referenceBasis.collectAsState()

    val gpsIntervalMs by viewModel.gpsInterval.collectAsState()
    val isWatchGpsOnly by viewModel.watchGpsOnly.collectAsState()
    val keepAppOpen by viewModel.keepAppOpen.collectAsState()
    val gpsInAmbientMode by viewModel.gpsInAmbientMode.collectAsState()
    val gpsDebugTelemetry by viewModel.gpsDebugTelemetry.collectAsState()
    val diagnosticsCaptureMode by viewModel.diagnosticsCaptureMode.collectAsState()
    val gpsPassiveLocationExperiment by viewModel.gpsPassiveLocationExperiment.collectAsState()
    val backButtonExitsNavigation by viewModel.backButtonExitsNavigation.collectAsState()
    val backButtonClosesApp by viewModel.backButtonClosesApp.collectAsState()
    val gpsDebugTelemetryPopupEnabled by
        viewModel.gpsDebugTelemetryPopupEnabled.collectAsState(
            initial = SettingsRepository.DEFAULT_GPS_DEBUG_TELEMETRY_POPUP_ENABLED,
        )
    val turnByTurnGuidanceSource by viewModel.turnByTurnGuidanceSource.collectAsState()
    val turnByTurnHapticsEnabled by viewModel.turnByTurnHapticsEnabled.collectAsState()
    val turnByTurnVoiceGuidanceEnabled by viewModel.turnByTurnVoiceGuidanceEnabled.collectAsState()
    val turnByTurnTurnAlertsMode by viewModel.turnByTurnTurnAlertsMode.collectAsState()
    val turnByTurnOffRouteAlertsEnabled by viewModel.turnByTurnOffRouteAlertsEnabled.collectAsState()
    val turnByTurnCompactPopupEnabled by viewModel.turnByTurnCompactPopupEnabled.collectAsState()
    val turnByTurnOffRouteThresholdMeters by viewModel.turnByTurnOffRouteAlertThresholdMeters.collectAsState()
    val turnByTurnOffRouteRepeatSeconds by viewModel.turnByTurnOffRouteRepeatSeconds.collectAsState()
    val turnByTurnGpsInAmbientMode by viewModel.turnByTurnGpsInAmbientMode.collectAsState()
    val turnByTurnScreenOffBatchingEnabled by viewModel.turnByTurnScreenOffBatchingEnabled.collectAsState()
    val turnByTurnGpsIntervalSeconds by viewModel.turnByTurnGpsIntervalSeconds.collectAsState()
    val turnByTurnScreenOffGpsIntervalSeconds by viewModel.turnByTurnScreenOffGpsIntervalSeconds.collectAsState()
    val turnByTurnBrouterGuideBackEnabled by viewModel.turnByTurnBrouterGuideBackEnabled.collectAsState()
    val turnByTurnRouteStartBehavior by viewModel.turnByTurnRouteStartBehavior.collectAsState()
    val turnByTurnReverseSuggestionMode by viewModel.turnByTurnReverseSuggestionMode.collectAsState()
    val recordingSampleIntervalSeconds by viewModel.recordingSampleIntervalSeconds.collectAsState()
    val recordingScreenOffSampleIntervalSeconds by viewModel.recordingScreenOffSampleIntervalSeconds.collectAsState()
    val recordingAutoPauseMode by viewModel.recordingAutoPauseMode.collectAsState()
    val recordingTrackSmoothingMode by viewModel.recordingTrackSmoothingMode.collectAsState()
    val recordingElevationSource by viewModel.recordingElevationSource.collectAsState()
    val recordingHeartRateSource by viewModel.recordingHeartRateSource.collectAsState()
    val recordingCadenceSource by viewModel.recordingCadenceSource.collectAsState()
    val recordingSpeedSource by viewModel.recordingSpeedSource.collectAsState()
    val recordingDistanceSource by viewModel.recordingDistanceSource.collectAsState()
    val recordingStepsSource by viewModel.recordingStepsSource.collectAsState()
    val recordingShowSavedGpxOnMap by viewModel.recordingShowSavedGpxOnMap.collectAsState()
    val recordingStartWithTurnByTurn by viewModel.recordingStartWithTurnByTurn.collectAsState()
    val recordingExternalHeartRateAddress by viewModel.recordingExternalHeartRateAddress.collectAsState()
    val recordingExternalHeartRateName by viewModel.recordingExternalHeartRateName.collectAsState()
    val recordingExternalRunPodAddress by viewModel.recordingExternalRunPodAddress.collectAsState()
    val recordingExternalRunPodName by viewModel.recordingExternalRunPodName.collectAsState()
    val externalSensorSimulationEnabled by ExternalSensorSimulation.enabled.collectAsState()
    val activityProfile by viewModel.activityProfile.collectAsState()
    val userWeightKg by viewModel.userWeightKg.collectAsState()
    val backpackWeightKg by viewModel.backpackWeightKg.collectAsState()
    val bikeWeightKg by viewModel.bikeWeightKg.collectAsState()
    var diagnosticsExportStatus by remember {
        mutableStateOf(resources.getString(DIAGNOSTICS_DEFAULT_STATUS))
    }
    var cleanCaptureStatus by remember {
        mutableStateOf(resources.getString(CLEAN_CAPTURE_DEFAULT_LABEL))
    }
    var cleanCaptureResetToken by remember { mutableStateOf(0L) }
    var exportInProgress by remember { mutableStateOf(false) }
    var showExportInfoDialog by remember { mutableStateOf(false) }
    var exportedDiagnosticsCount by remember { mutableStateOf(0) }
    var exportDialogMode by remember { mutableStateOf<DiagnosticsExportDialogMode?>(null) }
    var exportDialogMessage by remember { mutableStateOf("") }
    var energySummaryRevision by remember { mutableStateOf(0L) }
    val helpPrefs =
        remember(context) {
            context.getSharedPreferences(DEBUG_HELP_PREFS, Context.MODE_PRIVATE)
        }
    val hasExportedDiagnostics = exportedDiagnosticsCount > 0
    val lastBatteryUse =
        remember(energySummaryRevision, gpsDebugTelemetry) {
            EnergyDiagnostics.summary().batteryUse
        }
    val batteryBenchmarkValidity = EnergyDiagnostics.batteryBenchmarkValidity()

    val infoButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 24.dp
            WearScreenSize.MEDIUM -> 22.dp
            WearScreenSize.SMALL -> 20.dp
        }
    val infoIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 14.dp
            WearScreenSize.MEDIUM -> 13.dp
            WearScreenSize.SMALL -> 12.dp
        }

    LaunchedEffect(gpsDebugTelemetry, diagnosticsCaptureMode) {
        if (gpsDebugTelemetry) {
            val fullDiagnostics =
                diagnosticsCaptureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_FULL
            EnergyDiagnostics.configure(
                captureActive = true,
                fullDiagnostics = fullDiagnostics,
            )
            ScreenStateDiagnostics.configure(captureActive = true)
            CompassHeadingDiagnostics.reset()
            DebugTelemetry.log(
                "DiagnosticsFlow",
                "capture enabled mode=$diagnosticsCaptureMode",
            )
            EnergyDiagnostics.recordSample(
                context = context,
                reason = "capture_toggle_on",
                detail = "source=debug_screen captureMode=$diagnosticsCaptureMode",
            )
        } else {
            EnergyDiagnostics.recordEvent(
                reason = "capture_toggle_off",
                detail = "source=debug_screen",
            )
            CompassHeadingDiagnostics.flush(reason = "capture_toggle_off")
            CompassHeadingDiagnostics.reset()
            EnergyDiagnostics.configure(captureActive = false, fullDiagnostics = false)
            ScreenStateDiagnostics.configure(captureActive = false)
        }
    }

    LaunchedEffect(helpPrefs) {
        val alreadyShown = helpPrefs.getBoolean(DEBUG_EXPORT_INFO_SHOWN_KEY, false)
        if (!alreadyShown) {
            showExportInfoDialog = true
        }
    }

    LaunchedEffect(context) {
        val existingExportCount =
            withContext(Dispatchers.IO) {
                DiagnosticsExporter.exportedFileCount(context)
            }
        exportedDiagnosticsCount = existingExportCount
        if (existingExportCount > 0 && diagnosticsExportStatus == resources.getString(DIAGNOSTICS_DEFAULT_STATUS)) {
            diagnosticsExportStatus = buildRetryReadyLabel(existingExportCount, resources = resources)
        }
    }

    LaunchedEffect(cleanCaptureResetToken) {
        if (cleanCaptureResetToken <= 0L) return@LaunchedEffect
        delay(CLEAN_CAPTURE_RESET_DELAY_MS)
        if (cleanCaptureStatus == resources.getString(CLEAN_CAPTURE_CLEARED_LABEL)) {
            cleanCaptureStatus = resources.getString(CLEAN_CAPTURE_DEFAULT_LABEL)
        }
    }

    fun dismissExportInfoDialog() {
        showExportInfoDialog = false
        helpPrefs.edit().putBoolean(DEBUG_EXPORT_INFO_SHOWN_KEY, true).apply()
    }

    DiagnosticsExportInfoDialog(
        visible = showExportInfoDialog,
        onDismiss = { dismissExportInfoDialog() },
    )
    DiagnosticsExportStatusDialog(
        mode = exportDialogMode,
        message = exportDialogMessage,
        onDismiss = { exportDialogMode = null },
    )

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = { showExportInfoDialog = true },
                    modifier =
                        Modifier
                            .size(infoButtonSize)
                            .wrapContentSize(align = Alignment.Center),
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.7f),
                            contentColor = Color.White,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.settings_diagnostics_export_info),
                        modifier = Modifier.size(infoIconSize),
                    )
                }
            }
        }

        item {
            GeneralSettingsShortcutChip(
                onClick = onOpenGeneralSettings,
                applyTopPadding = false,
            )
        }

        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = {
                    WearText(
                        text = stringResource(R.string.settings_1_clean_previous_logs),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                secondaryLabel =
                    {
                        WearText(
                            text =
                                if (exportInProgress) {
                                    stringResource(R.string.settings_export_in_progress)
                                } else {
                                    cleanCaptureStatus
                                },
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                onClick = {
                    if (exportInProgress) return@Chip
                    CompassHeadingReferenceDiagnostics.stop()
                    DebugTelemetry.clear()
                    CompassHeadingDiagnostics.reset()
                    CompassDeepTraceDiagnostics.clear()
                    MarkerMotionTelemetry.clear()
                    EnergyDiagnostics.clear()
                    RecordingScreenOffDiagnostics.clear()
                    ScreenStateDiagnostics.clear()
                    energySummaryRevision += 1L
                    DemDownloadDiagnostics.clear()
                    FieldMarkerDiagnostics.clear()
                    GnssDiagnostics.clear()
                    MapHotPathDiagnostics.clear()
                    TerrainDiagnostics.clear()
                    CrashDiagnosticsStore.clear(context)
                    DiagnosticsExporter.clearExportedFiles(context)
                    exportedDiagnosticsCount = 0
                    cleanCaptureStatus = resources.getString(CLEAN_CAPTURE_CLEARED_LABEL)
                    cleanCaptureResetToken += 1L
                    diagnosticsExportStatus = resources.getString(R.string.settings_cleared_start_capture)
                },
            )
        }

        item {
            SettingsToggleChip(
                checked = gpsDebugTelemetry,
                onCheckedChanged = {
                    if (exportInProgress) return@SettingsToggleChip
                    if (!it) {
                        EnergyDiagnostics.recordSample(
                            context = context,
                            reason = "capture_toggle_off",
                            detail = "source=capture_toggle",
                        )
                        energySummaryRevision += 1L
                    }
                    viewModel.setGpsDebugTelemetry(it)
                    FieldMarkerDiagnostics.recordMarker(
                        type = if (it) "capture_start" else "capture_stop",
                        note = "source=capture_toggle",
                    )
                    diagnosticsExportStatus =
                        if (it) {
                            resources.getString(R.string.settings_capturing)
                        } else if (hasExportedDiagnostics) {
                            buildRetryReadyLabel(exportedDiagnosticsCount, resources = resources)
                        } else if (DebugTelemetry.size() > 0 || EnergyDiagnostics.snapshotLines().isNotEmpty()) {
                            resources.getString(R.string.settings_send_to_phone)
                        } else {
                            resources.getString(R.string.settings_capture_off)
                        }
                },
                label = stringResource(R.string.settings_2_start_capturing),
                secondaryLabel =
                    if (gpsDebugTelemetry) {
                        if (diagnosticsCaptureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_BATTERY) {
                            if (!batteryBenchmarkValidity.valid) {
                                stringResource(R.string.settings_battery_benchmark_invalid_deep_trace_used)
                            } else {
                                stringResource(R.string.settings_battery_benchmark_running)
                            }
                        } else {
                            stringResource(R.string.settings_general_diagnostics_running)
                        }
                    } else if (exportInProgress) {
                        stringResource(R.string.settings_export_in_progress)
                    } else if (
                        diagnosticsCaptureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_BATTERY &&
                        !batteryBenchmarkValidity.valid
                    ) {
                        stringResource(R.string.settings_last_run_invalid_deep_trace_used)
                    } else if (
                        diagnosticsCaptureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_BATTERY &&
                        lastBatteryUse != null
                    ) {
                        lastBatteryUse.consumedMah?.let {
                            stringResource(R.string.settings_last_run_mah, TelemetryFormatters.decimal(it, 2))
                        } ?: stringResource(R.string.settings_last_run_insufficient_data)
                    } else {
                        stringResource(R.string.settings_off_tap_to_start)
                    },
            )
        }

        if (BuildConfig.DEBUG) {
            item {
                SettingsToggleChip(
                    checked = externalSensorSimulationEnabled,
                    onCheckedChanged = { enabled ->
                        ExternalSensorSimulation.setEnabled(enabled)
                        if (!enabled) {
                            if (recordingExternalHeartRateAddress == ExternalSensorSimulation.HEART_RATE_ADDRESS) {
                                viewModel.setRecordingExternalHeartRateDevice(null, null)
                            }
                            if (recordingExternalRunPodAddress == ExternalSensorSimulation.RUN_POD_ADDRESS) {
                                viewModel.setRecordingExternalRunPodDevice(null, null)
                            }
                        }
                        DebugTelemetry.log(
                            "ExternalSensors",
                            "event=simulation_toggle enabled=$enabled",
                        )
                    },
                    label = stringResource(R.string.settings_simulated_ble_sensors),
                    secondaryLabel =
                        if (externalSensorSimulationEnabled) {
                            stringResource(R.string.settings_hr_strap_and_run_pod_available)
                        } else {
                            stringResource(R.string.settings_debug_testing_only)
                        },
                )
            }
        }

        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label =
                    {
                        WearText(
                            text =
                                if (hasExportedDiagnostics && !gpsDebugTelemetry) {
                                    stringResource(R.string.settings_3_resend_diagnostic)
                                } else {
                                    stringResource(R.string.settings_3_export_diagnostic)
                                },
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                secondaryLabel =
                    {
                        WearText(
                            text =
                                if (exportInProgress) {
                                    stringResource(R.string.settings_exporting)
                                } else if (gpsDebugTelemetry) {
                                    stringResource(R.string.settings_stop_export)
                                } else {
                                    diagnosticsExportStatus
                                },
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                onClick = {
                    if (exportInProgress) return@Chip
                    coroutineScope.launch {
                        exportInProgress = true
                        exportDialogMode = DiagnosticsExportDialogMode.GENERATING
                        exportDialogMessage = resources.getString(R.string.settings_generating_diagnostics_file)
                        val captureWasEnabled = gpsDebugTelemetry
                        var captureFrozenForExport = false
                        try {
                            CompassHeadingReferenceDiagnostics.stop()
                            CompassDeepTraceDiagnostics.stop(reason = "export")
                            val hasBufferedLogs =
                                DebugTelemetry.size() > 0 ||
                                    EnergyDiagnostics.snapshotLines().isNotEmpty() ||
                                    CompassDeepTraceDiagnostics.snapshot().lines.isNotEmpty()
                            val hasExistingExport =
                                withContext(Dispatchers.IO) {
                                    DiagnosticsExporter.latestExportFile(context) != null
                                }
                            val canExport = captureWasEnabled || hasBufferedLogs || hasExistingExport
                            if (!canExport) {
                                exportDialogMode = null
                                diagnosticsExportStatus =
                                    resources.getString(R.string.settings_no_logs_to_export_start_capturing_first)
                                return@launch
                            }

                            diagnosticsExportStatus = resources.getString(R.string.settings_preparing_file)

                            // Freeze capture state immediately to avoid session churn while exporting.
                            if (captureWasEnabled) {
                                EnergyDiagnostics.recordSample(
                                    context = context,
                                    reason = "capture_toggle_off",
                                    detail = "source=export",
                                )
                                CompassHeadingDiagnostics.flush(reason = "capture_export")
                                viewModel.setGpsDebugTelemetry(false)
                                DebugTelemetry.freezeForExport()
                                captureFrozenForExport = true
                                CompassHeadingDiagnostics.reset()
                                EnergyDiagnostics.configure(captureActive = false, fullDiagnostics = false)
                                ScreenStateDiagnostics.configure(captureActive = false)
                            }

                            val diagnosticsFile =
                                withContext(Dispatchers.IO) {
                                    DiagnosticsExporter.export(
                                        context = context,
                                        settings =
                                            DiagnosticsSettingsSnapshot(
                                                gpsIntervalMs = gpsIntervalMs,
                                                watchGpsOnly = isWatchGpsOnly,
                                                keepAppOpen = keepAppOpen,
                                                gpsInAmbientMode = gpsInAmbientMode,
                                                gpsDebugTelemetry = captureWasEnabled,
                                                diagnosticsCaptureMode = diagnosticsCaptureMode,
                                                gpsPassiveLocationExperiment = gpsPassiveLocationExperiment,
                                                backButtonExitsNavigation = backButtonExitsNavigation,
                                                backButtonClosesApp = backButtonClosesApp,
                                                recordingSampleIntervalSeconds = recordingSampleIntervalSeconds,
                                                recordingScreenOffSampleIntervalSeconds =
                                                recordingScreenOffSampleIntervalSeconds,
                                                recordingAutoPauseMode = recordingAutoPauseMode,
                                                recordingTrackSmoothingMode = recordingTrackSmoothingMode,
                                                recordingElevationSource = recordingElevationSource,
                                                recordingHeartRateSource = recordingHeartRateSource,
                                                recordingCadenceSource = recordingCadenceSource,
                                                recordingSpeedSource = recordingSpeedSource,
                                                recordingDistanceSource = recordingDistanceSource,
                                                recordingStepsSource = recordingStepsSource,
                                                recordingShowSavedGpxOnMap = recordingShowSavedGpxOnMap,
                                                recordingStartWithTurnByTurn = recordingStartWithTurnByTurn,
                                                recordingExternalHeartRateLinked =
                                                    !recordingExternalHeartRateAddress.isNullOrBlank(),
                                                recordingExternalHeartRateName = recordingExternalHeartRateName,
                                                recordingExternalHeartRateAddressSuffix =
                                                    recordingExternalHeartRateAddress?.takeLast(5),
                                                recordingExternalRunPodLinked =
                                                    !recordingExternalRunPodAddress.isNullOrBlank(),
                                                recordingExternalRunPodName = recordingExternalRunPodName,
                                                recordingExternalRunPodAddressSuffix =
                                                    recordingExternalRunPodAddress?.takeLast(5),
                                                activityProfile = activityProfile,
                                                userWeightKg = userWeightKg,
                                                backpackWeightKg = backpackWeightKg,
                                                bikeWeightKg = bikeWeightKg,
                                                turnByTurnGuidanceSource = turnByTurnGuidanceSource,
                                                turnByTurnGpsIntervalSeconds = turnByTurnGpsIntervalSeconds,
                                                turnByTurnScreenOffGpsIntervalSeconds =
                                                turnByTurnScreenOffGpsIntervalSeconds,
                                                turnByTurnHapticsEnabled = turnByTurnHapticsEnabled,
                                                turnByTurnVoiceGuidanceEnabled = turnByTurnVoiceGuidanceEnabled,
                                                turnByTurnTurnAlertsMode = turnByTurnTurnAlertsMode,
                                                turnByTurnOffRouteAlertsEnabled = turnByTurnOffRouteAlertsEnabled,
                                                turnByTurnCompactPopupEnabled = turnByTurnCompactPopupEnabled,
                                                turnByTurnOffRouteAlertThresholdMeters =
                                                turnByTurnOffRouteThresholdMeters,
                                                turnByTurnOffRouteRepeatSeconds = turnByTurnOffRouteRepeatSeconds,
                                                turnByTurnGpsInAmbientMode = turnByTurnGpsInAmbientMode,
                                                turnByTurnScreenOffBatchingEnabled =
                                                turnByTurnScreenOffBatchingEnabled,
                                                turnByTurnBrouterGuideBackEnabled =
                                                turnByTurnBrouterGuideBackEnabled,
                                                turnByTurnRouteStartBehavior = turnByTurnRouteStartBehavior,
                                                turnByTurnReverseSuggestionMode = turnByTurnReverseSuggestionMode,
                                            ),
                                        reuseLatestIfAvailable = !captureWasEnabled,
                                    )
                                }
                            exportedDiagnosticsCount =
                                withContext(Dispatchers.IO) {
                                    DiagnosticsExporter.exportedFileCount(context)
                                }
                            exportDialogMessage =
                                resources.getString(R.string.settings_sending_diagnostics_to_your_phone)

                            val subject =
                                resources.getString(
                                    R.string.debug_status_detail,
                                    TransferDataLayerContract.DIAGNOSTICS_SUBJECT_PREFIX,
                                    diagnosticsFile.nameWithoutExtension,
                                )

                            // Prefer phone handoff first because many watches have no mail/share app.
                            val handedOff =
                                withContext(Dispatchers.IO) {
                                    DiagnosticsEmailHandoff.sendToPhone(
                                        context = context,
                                        diagnosticsFile = diagnosticsFile,
                                        subject = subject,
                                    )
                                }
                            if (handedOff) {
                                diagnosticsExportStatus =
                                    resources.getString(R.string.settings_check_phone_use_button_3_to_resend)
                                exportDialogMode = DiagnosticsExportDialogMode.CHECK_PHONE
                                exportDialogMessage =
                                    resources.getString(
                                        R.string.settings_if_you_closed_the_phone_prompt_tap_button_3_again_to_resend,
                                    )
                                return@launch
                            }
                            exportDialogMode = null

                            val uri =
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    diagnosticsFile,
                                )

                            val shareIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_EMAIL,
                                        arrayOf(TransferDataLayerContract.DIAGNOSTICS_SUPPORT_EMAIL),
                                    )
                                    putExtra(Intent.EXTRA_SUBJECT, subject)
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        resources.getString(R.string.debug_diagnostics_export_attached_from_watch),
                                    )
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }

                            val hasWatchTargets =
                                context.packageManager.queryIntentActivities(shareIntent, 0).isNotEmpty()
                            if (!hasWatchTargets) {
                                diagnosticsExportStatus =
                                    buildRetryReadyLabel(exportedDiagnosticsCount, resources = resources)
                                exportDialogMode = DiagnosticsExportDialogMode.FAILED
                                exportDialogMessage =
                                    resources.getString(
                                        R.string.settings_diagnostics_phone_send_failed,
                                    )
                                return@launch
                            }

                            val chooser =
                                Intent
                                    .createChooser(
                                        shareIntent,
                                        resources.getString(R.string.settings_send_diagnostics),
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                            runCatching { context.startActivity(chooser) }
                                .onSuccess {
                                    diagnosticsExportStatus =
                                        if (captureWasEnabled) {
                                            resources.getString(R.string.settings_confirm_send_on_phone_capture_off)
                                        } else {
                                            resources.getString(R.string.settings_confirm_send_on_phone)
                                        }
                                }.onFailure {
                                    diagnosticsExportStatus =
                                        buildRetryReadyLabel(exportedDiagnosticsCount, resources = resources)
                                    exportDialogMode = DiagnosticsExportDialogMode.FAILED
                                    exportDialogMessage =
                                        resources.getString(
                                            R.string.settings_export_failed_tap_resend_diagnostic_to_try_again,
                                        )
                                }
                        } catch (_: ActivityNotFoundException) {
                            diagnosticsExportStatus =
                                buildRetryReadyLabel(exportedDiagnosticsCount, resources = resources)
                            exportDialogMode = DiagnosticsExportDialogMode.FAILED
                            exportDialogMessage =
                                resources.getString(R.string.settings_export_failed_tap_resend_diagnostic_to_try_again)
                        } catch (_: Exception) {
                            diagnosticsExportStatus =
                                buildRetryReadyLabel(exportedDiagnosticsCount, resources = resources)
                            exportDialogMode = DiagnosticsExportDialogMode.FAILED
                            exportDialogMessage =
                                resources.getString(R.string.settings_export_failed_tap_resend_diagnostic_to_try_again)
                        } finally {
                            viewModel.setGpsDebugTelemetry(false)
                            if (captureWasEnabled && !captureFrozenForExport) {
                                DebugTelemetry.freezeForExport()
                            }
                            EnergyDiagnostics.configure(captureActive = false, fullDiagnostics = false)
                            ScreenStateDiagnostics.configure(captureActive = false)
                            if (exportDialogMode == DiagnosticsExportDialogMode.GENERATING) {
                                exportDialogMode = null
                            }
                            exportInProgress = false
                        }
                    }
                },
            )
        }

        item {
            DiagnosticsSettingsSectionTitle()
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_capture_mode),
                selectedValue = diagnosticsCaptureMode,
                options =
                    listOf(
                        SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_FULL to
                            stringResource(R.string.settings_full_diagnostics),
                        SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_BATTERY to
                            stringResource(R.string.settings_battery_benchmark),
                    ),
                secondaryLabel =
                    if (diagnosticsCaptureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_BATTERY) {
                        stringResource(R.string.settings_energy_only_low_overhead)
                    } else {
                        stringResource(R.string.settings_system_gps_and_recording_logs)
                    },
                onSelect = { mode ->
                    if (gpsDebugTelemetry) {
                        CompassHeadingReferenceDiagnostics.stop()
                        CompassDeepTraceDiagnostics.stop(reason = "capture_mode_change")
                        EnergyDiagnostics.recordSample(
                            context = context,
                            reason = "capture_toggle_off",
                            detail = "source=capture_mode_change",
                        )
                        energySummaryRevision += 1L
                        viewModel.setGpsDebugTelemetry(false)
                        diagnosticsExportStatus = resources.getString(R.string.settings_capture_stopped_start_a_new_run)
                    }
                    viewModel.setDiagnosticsCaptureMode(mode)
                },
            )
        }
        item {
            val fullDiagnostics =
                diagnosticsCaptureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_FULL
            SettingsToggleChip(
                checked = fullDiagnostics && gpsDebugTelemetryPopupEnabled,
                enabled = fullDiagnostics,
                onCheckedChanged = {
                    if (exportInProgress || !fullDiagnostics) return@SettingsToggleChip
                    viewModel.setGpsDebugTelemetryPopupEnabled(it)
                },
                label = stringResource(R.string.settings_debug_popup),
                secondaryLabel =
                    when {
                        !fullDiagnostics -> stringResource(R.string.settings_disabled_in_battery_benchmark)
                        gpsDebugTelemetryPopupEnabled -> stringResource(R.string.settings_on_during_full_diagnostics)
                        else -> stringResource(R.string.settings_off_during_full_diagnostics)
                    },
            )
        }
        item {
            val batteryBenchmark =
                diagnosticsCaptureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_BATTERY
            SettingsToggleChip(
                checked = compassDeepTraceState.active,
                enabled = !exportInProgress,
                onCheckedChanged = { enabled ->
                    if (enabled) {
                        CompassDeepTraceDiagnostics.start(
                            context = context,
                            batteryBenchmarkSelected = batteryBenchmark,
                        )
                    } else {
                        CompassHeadingReferenceDiagnostics.stop()
                        CompassDeepTraceDiagnostics.stop(reason = "manual")
                    }
                },
                label = stringResource(R.string.settings_compass_deep_trace),
                secondaryLabel =
                    when {
                        compassDeepTraceState.active && batteryBenchmark ->
                            stringResource(R.string.settings_active_battery_benchmark_invalid)
                        compassDeepTraceState.active ->
                            stringResource(R.string.settings_active_stop_manually_when_finished)
                        batteryBenchmark && !batteryBenchmarkValidity.valid ->
                            stringResource(R.string.settings_start_compass_trace_invalidates_benchmark)
                        batteryBenchmark ->
                            stringResource(R.string.settings_manual_stop_invalidates_benchmark)
                        else -> stringResource(R.string.settings_compass_only_capture_manual_stop)
                    },
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                enabled =
                    compassDeepTraceState.active &&
                        !exportInProgress &&
                        !compassHeadingReferenceTestActive,
                label = {
                    WearText(
                        text =
                            stringResource(
                                R.string.settings_reference_north,
                                resources.getString(
                                    when (compassHeadingReferenceBasis) {
                                        CompassHeadingReferenceBasis.MAGNETIC_NORTH -> R.string.settings_magnetic_north
                                        CompassHeadingReferenceBasis.TRUE_NORTH -> R.string.settings_true_north
                                        else -> R.string.map_unknown
                                    },
                                ),
                            ),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                secondaryLabel = {
                    WearText(
                        text =
                            when (compassHeadingReferenceBasis) {
                                CompassHeadingReferenceBasis.MAGNETIC_NORTH ->
                                    stringResource(R.string.settings_use_for_a_physical_handheld_compass)
                                CompassHeadingReferenceBasis.TRUE_NORTH ->
                                    stringResource(R.string.settings_use_for_a_declination_adjusted_reference)
                                CompassHeadingReferenceBasis.UNKNOWN ->
                                    stringResource(R.string.settings_select_before_starting_the_test)
                            },
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                onClick = {
                    CompassHeadingReferenceDiagnostics.selectReferenceBasis(
                        compassHeadingReferenceBasis.next(),
                    )
                },
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                enabled = compassDeepTraceState.active && !exportInProgress,
                label = {
                    WearText(
                        text =
                            if (compassHeadingReferenceTestActive) {
                                stringResource(R.string.settings_stop_heading_reference_test)
                            } else {
                                stringResource(R.string.settings_start_heading_reference_test)
                            },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                secondaryLabel = {
                    WearText(
                        text =
                            if (compassHeadingReferenceTestActive) {
                                stringResource(R.string.settings_open_navigate_shortcuts_to_mark_n_e_s_and_w)
                            } else {
                                stringResource(R.string.settings_deep_trace_active_measures_absolute_heading_error)
                            },
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                onClick = { CompassHeadingReferenceDiagnostics.toggle() },
            )
        }

        if (BuildConfig.DEBUG) {
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        WearText(
                            text = stringResource(R.string.settings_force_close_app),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        WearText(
                            text = stringResource(R.string.settings_debug_crash_test),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                    onClick = {
                        DebugTelemetry.log("DiagnosticsFlow", "manual_force_close_requested")
                        error("Manual force close from debugging settings")
                    },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsExportStatusDialog(
    mode: DiagnosticsExportDialogMode?,
    message: String,
    onDismiss: () -> Unit,
) {
    if (mode == null) return

    val dismissible = mode != DiagnosticsExportDialogMode.GENERATING
    val title =
        if (mode == DiagnosticsExportDialogMode.GENERATING) {
            stringResource(R.string.settings_preparing_diagnostics)
        } else if (mode == DiagnosticsExportDialogMode.FAILED) {
            stringResource(R.string.settings_export_failed)
        } else {
            stringResource(R.string.settings_diagnostic_ready_check_your_phone)
        }

    WearInfoDialog(
        visible = true,
        title = title,
        onDismiss = onDismiss,
        dismissible = dismissible,
    ) {
        if (mode == DiagnosticsExportDialogMode.GENERATING) {
            item {
                RouteToolBusySpinner(size = 30.dp)
            }
        }
        item {
            Text(
                text = message,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (dismissible) {
            item {
                Button(
                    onClick = onDismiss,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsExportInfoDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    WearHelpDialog(
        visible = visible,
        title = stringResource(R.string.settings_diagnostics_export),
        onDismiss = onDismiss,
        lines =
            listOf(
                stringResource(
                    R.string.settings_after_export_check_your_phone_it_opens_the_email_draft_with_diagnostic,
                ),
            ),
    )
}

private fun buildRetryReadyLabel(
    count: Int,
    resources: Resources,
): String {
    val safeCount = count.coerceAtLeast(1)
    return resources.getQuantityString(R.plurals.debug_retry_ready, safeCount, safeCount)
}
