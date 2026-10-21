package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.domain.sensors.CompassHeadingSourceMode
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassViewModel
import com.glancemap.glancemapwearos.presentation.ui.WearActionButtonRole
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialogButton
import com.glancemap.glancemapwearos.presentation.ui.WearHelpDialog
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.wear.compose.material.Text as WearText

@Composable
fun CompassSettingsScreen(
    viewModel: SettingsViewModel,
    compassViewModel: CompassViewModel,
    onOpenGeneralSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val listTokens = rememberSettingsListTokens()
    val adaptive = rememberWearAdaptiveSpec()
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var showCompatibilityDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showAdvancedSection by remember { mutableStateOf(false) }
    var compatibilityRunToken by remember { mutableIntStateOf(0) }
    var compatibilityState by remember { mutableStateOf(CompatibilityTestUiState()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isScreenResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val promptForCalibration by viewModel.promptForCalibration.collectAsState(initial = false)
    val compassSettingsMode by viewModel.compassSettingsMode.collectAsState(
        initial = SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC,
    )
    val navigationMarkerStyle by viewModel.navigationMarkerStyle.collectAsState()
    val compassConeAccuracyColorsEnabled by viewModel.compassConeAccuracyColorsEnabled.collectAsState(
        initial = true,
    )
    val compassProviderModeSetting by viewModel.compassProviderMode.collectAsState(
        initial = SettingsRepository.COMPASS_PROVIDER_GOOGLE_FUSED,
    )
    val northReferenceMode by viewModel.northReferenceMode.collectAsState(
        initial = SettingsRepository.NORTH_REFERENCE_TRUE,
    )
    val headingSourceModeSetting by viewModel.compassHeadingSourceMode.collectAsState(
        initial = SettingsRepository.COMPASS_HEADING_SOURCE_AUTO,
    )
    val compassRenderState by compassViewModel.renderState.collectAsState()
    val activeProviderType = compassRenderState.providerType
    val heading = compassRenderState.headingDeg
    val headingAccuracy = compassRenderState.accuracy
    val activeHeadingSource = compassRenderState.headingSource
    val headingSourceStatus = compassRenderState.headingSourceStatus
    val northReferenceStatus = compassRenderState.northReferenceStatus
    val magneticInterference = compassRenderState.magneticInterference

    val latestHeading by rememberUpdatedState(heading)
    val latestHeadingAccuracy by rememberUpdatedState(headingAccuracy)
    val latestActiveHeadingSource by rememberUpdatedState(activeHeadingSource)
    val latestMagneticInterference by rememberUpdatedState(magneticInterference)
    val latestNorthReferenceMode by rememberUpdatedState(northReferenceMode)
    val latestHeadingSourceSetting by rememberUpdatedState(headingSourceModeSetting)
    val latestHeadingSourceStatus by rememberUpdatedState(headingSourceStatus)
    val latestShowCalibrationDialog by rememberUpdatedState(showCalibrationDialog)
    val effectiveCompassProviderModeSetting = compassProviderModeSetting
    val effectiveProviderType = compassProviderTypeFromSetting(effectiveCompassProviderModeSetting)
    val effectiveHeadingSourceModeSetting =
        if (
            effectiveProviderType == CompassProviderType.SENSOR_MANAGER
        ) {
            headingSourceModeSetting
        } else {
            SettingsRepository.COMPASS_HEADING_SOURCE_AUTO
        }
    val showSensorControls = effectiveProviderType == CompassProviderType.SENSOR_MANAGER
    val showCompassConeAccuracyColorsSetting =
        showSensorControls &&
            navigationMarkerStyle == SettingsRepository.MARKER_STYLE_DOT

    val compassModeOptions =
        remember {
            listOf(
                SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC to
                    resources.getString(R.string.settings_automatic_recommended),
                SettingsRepository.COMPASS_SETTINGS_MODE_ADVANCED to resources.getString(R.string.settings_advanced),
            )
        }
    val compassProviderOptions =
        remember {
            listOf(
                SettingsRepository.COMPASS_PROVIDER_GOOGLE_FUSED to
                    resources.getString(R.string.settings_google_fused_default),
                SettingsRepository.COMPASS_PROVIDER_SENSOR_MANAGER to
                    resources.getString(R.string.settings_custom_sensors),
            )
        }
    val northReferenceOptions =
        remember {
            listOf(
                SettingsRepository.NORTH_REFERENCE_TRUE to resources.getString(R.string.settings_true_north),
                SettingsRepository.NORTH_REFERENCE_MAGNETIC to resources.getString(R.string.settings_magnetic_north),
            )
        }
    val headingSourceOptions =
        remember {
            listOf(
                SettingsRepository.COMPASS_HEADING_SOURCE_AUTO to
                    resources.getString(R.string.settings_auto_recommended),
                SettingsRepository.COMPASS_HEADING_SOURCE_TYPE_HEADING to "TYPE_HEADING",
                SettingsRepository.COMPASS_HEADING_SOURCE_ROTATION_VECTOR to
                    resources.getString(R.string.settings_rotation_vector_2),
                SettingsRepository.COMPASS_HEADING_SOURCE_MAGNETOMETER to
                    resources.getString(R.string.settings_magnetometer_2),
            )
        }
    val calibrationAlertsLabel =
        if (adaptive.isCompact) {
            stringResource(
                R.string.settings_custom_alerts,
            )
        } else {
            stringResource(R.string.settings_custom_compass_alerts)
        }
    val calibrationAlertsSecondary =
        if (adaptive.isCompact) {
            stringResource(
                R.string.settings_if_custom_drifts,
            )
        } else {
            stringResource(R.string.settings_if_custom_compass_drifts)
        }
    val isCompatibilityRunning = compatibilityState.running

    LaunchedEffect(compassSettingsMode, headingSourceModeSetting) {
        if (
            compassSettingsMode == SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC &&
            headingSourceModeSetting != SettingsRepository.COMPASS_HEADING_SOURCE_AUTO
        ) {
            viewModel.setCompassHeadingSourceMode(SettingsRepository.COMPASS_HEADING_SOURCE_AUTO)
        }
    }

    LaunchedEffect(effectiveCompassProviderModeSetting) {
        compassViewModel.setProviderType(
            type = effectiveProviderType,
            forceRefresh = false,
        )
    }

    DisposableEffect(lifecycleOwner, compassViewModel) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> isScreenResumed = true
                    Lifecycle.Event.ON_PAUSE -> {
                        isScreenResumed = false
                        if (!latestShowCalibrationDialog) {
                            compassViewModel.stop()
                        }
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            compassViewModel.stop()
        }
    }

    LaunchedEffect(isCompatibilityRunning, isScreenResumed, showCalibrationDialog) {
        if (isScreenResumed || showCalibrationDialog) {
            compassViewModel.start(lowPower = !isCompatibilityRunning && !showCalibrationDialog)
        } else {
            compassViewModel.stop()
        }
    }

    LaunchedEffect(showSensorControls) {
        if (showSensorControls) return@LaunchedEffect
        showCalibrationDialog = false
        showCompatibilityDialog = false
        showAdvancedSection = false
    }

    LaunchedEffect(
        northReferenceMode,
        headingSourceModeSetting,
        isCompatibilityRunning,
        effectiveHeadingSourceModeSetting,
    ) {
        if (isCompatibilityRunning) return@LaunchedEffect
        compassViewModel.setNorthReferenceMode(
            mode = northReferenceModeFromSetting(northReferenceMode),
            forceRefresh = true,
        )
        compassViewModel.setHeadingSourceMode(
            mode = headingSourceModeFromSetting(effectiveHeadingSourceModeSetting),
            forceRefresh = true,
        )
    }

    LaunchedEffect(compatibilityRunToken, showCompatibilityDialog) {
        if (!showCompatibilityDialog || compatibilityRunToken == 0) return@LaunchedEffect
        compatibilityState =
            CompatibilityTestUiState(
                running = true,
                progressLabel = resources.getString(R.string.settings_preparing_sensors),
                stepIndex = 0,
                totalSteps = COMPATIBILITY_CANDIDATES.size * COMPATIBILITY_PHASE_COUNT,
            )
        val persistedNorthMode = northReferenceModeFromSetting(latestNorthReferenceMode)
        val persistedHeadingSource = headingSourceModeFromSetting(latestHeadingSourceSetting)
        try {
            compassViewModel.setNorthReferenceMode(persistedNorthMode, forceRefresh = true)
            delay(COMPATIBILITY_PREPARE_DELAY_MS)

            val candidateScores = mutableListOf<CompatibilityCandidateScore>()
            COMPATIBILITY_CANDIDATES.forEachIndexed { index, candidate ->
                compatibilityState =
                    compatibilityState.copy(
                        progressLabel =
                            resources.getString(
                                R.string.settings_testing,
                                headingSourceModeLabel(candidate, resources = resources),
                            ),
                        stepIndex = index * COMPATIBILITY_PHASE_COUNT,
                    )
                val candidateScore =
                    evaluateCompatibilityCandidate(
                        mode = candidate,
                        availability = latestHeadingSourceStatus,
                        compassViewModel = compassViewModel,
                        readHeading = { latestHeading },
                        readAccuracy = { latestHeadingAccuracy },
                        readSource = { latestActiveHeadingSource },
                        readMagneticInterference = { latestMagneticInterference },
                        onPhaseStart = { phaseIndex, label ->
                            compatibilityState =
                                compatibilityState.copy(
                                    progressLabel = label,
                                    stepIndex = (index * COMPATIBILITY_PHASE_COUNT) + phaseIndex,
                                )
                        },
                        resources = resources,
                    )
                candidateScores += candidateScore
            }

            val best = candidateScores.filter { it.available }.maxByOrNull { it.score }
            if (best == null) {
                compatibilityState =
                    CompatibilityTestUiState(
                        running = false,
                        progressLabel = resources.getString(R.string.settings_test_unavailable),
                        result = null,
                        errorMessage = resources.getString(R.string.settings_no_compatible_heading_source_found),
                    )
            } else {
                compatibilityState =
                    CompatibilityTestUiState(
                        running = false,
                        progressLabel = resources.getString(R.string.settings_test_complete),
                        result =
                            CompatibilityTestResult(
                                recommendedMode =
                                    recommendedModeFromCandidate(
                                        best = best,
                                        availability = latestHeadingSourceStatus,
                                    ),
                                bestCandidate = best,
                                candidates = candidateScores,
                                availability = latestHeadingSourceStatus,
                            ),
                        errorMessage = null,
                    )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            compatibilityState =
                CompatibilityTestUiState(
                    running = false,
                    progressLabel = resources.getString(R.string.settings_test_failed),
                    result = null,
                    errorMessage = resources.getString(R.string.settings_could_not_finish_compatibility_test),
                )
        } finally {
            // Restore persisted runtime preference after probing candidates.
            compassViewModel.setNorthReferenceMode(
                mode = persistedNorthMode,
                forceRefresh = true,
            )
            compassViewModel.setHeadingSourceMode(
                mode = persistedHeadingSource,
                forceRefresh = true,
            )
        }
    }

    if (showCalibrationDialog) {
        CompassRecalibrationDialog(
            compassViewModel = compassViewModel,
            onApplyRecalibration = { compassViewModel.recalibrate() },
        ) { _ ->
            showCalibrationDialog = false
        }
    }

    WearSettingsListScreen(listTokens = listTokens) {
        item { GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings) }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_orientation_provider),
                dialogTitle = stringResource(R.string.settings_orientation_provider_2),
                selectedValue = compassProviderModeSetting,
                options = compassProviderOptions,
                secondaryLabel =
                    compassProviderStatusLabel(
                        requestedMode = compassProviderModeSetting,
                        activeProviderType = activeProviderType,
                        resources = resources,
                    ),
                onSelect = viewModel::setCompassProviderMode,
            )
        }
        item {
            if (usesAutomaticGoogleNorthReference(effectiveProviderType)) {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        WearText(
                            text = stringResource(R.string.settings_north_reference),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        WearText(
                            text = automaticNorthReferenceStatusLabel(activeProviderType, resources = resources),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                        )
                    },
                    onClick = { showInfoDialog = true },
                )
            } else {
                SettingsOptionPickerRow(
                    label = stringResource(R.string.settings_north_mode),
                    selectedValue = northReferenceMode,
                    options = northReferenceOptions,
                    secondaryLabel =
                        northReferenceStatusSecondaryLabel(
                            requestedMode = northReferenceMode,
                            status = northReferenceStatus,
                            resources = resources,
                        ),
                    onSelect = viewModel::setNorthReferenceMode,
                )
            }
        }
        if (showSensorControls) {
            item {
                Text(
                    stringResource(R.string.settings_setup),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                SettingsOptionPickerRow(
                    label = stringResource(R.string.settings_compass_mode),
                    selectedValue = compassSettingsMode,
                    options = compassModeOptions,
                    secondaryLabel = compassSettingsModeLabel(compassSettingsMode, resources = resources),
                    onSelect = { selected ->
                        viewModel.setCompassSettingsMode(selected)
                        if (selected == SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC) {
                            viewModel.setCompassHeadingSourceMode(SettingsRepository.COMPASS_HEADING_SOURCE_AUTO)
                        }
                    },
                )
            }
            if (showCompassConeAccuracyColorsSetting) {
                item {
                    SettingsToggleChip(
                        checked = compassConeAccuracyColorsEnabled,
                        onCheckedChanged = { viewModel.setCompassConeAccuracyColorsEnabled(it) },
                        label = stringResource(R.string.settings_accuracy_colors),
                        secondaryLabel = stringResource(R.string.settings_if_off_cone_stays_green),
                    )
                }
            }
            item {
                Text(stringResource(R.string.settings_custom_compass), style = MaterialTheme.typography.titleMedium)
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        WearText(
                            text = stringResource(R.string.settings_recalibrate_compass),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                        )
                    },
                    onClick = { showCalibrationDialog = true },
                )
            }
            item {
                SettingsToggleChip(
                    checked = promptForCalibration,
                    onCheckedChanged = { viewModel.setPromptForCalibration(it) },
                    label = calibrationAlertsLabel,
                    secondaryLabel = calibrationAlertsSecondary,
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        WearText(
                            text = stringResource(R.string.settings_run_source_test),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        WearText(
                            text = stringResource(R.string.settings_checks_still_turn_response),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                    onClick = {
                        compatibilityState = CompatibilityTestUiState()
                        compatibilityRunToken += 1
                        showCompatibilityDialog = true
                    },
                )
            }
            item {
                Text(stringResource(R.string.gps_advanced), style = MaterialTheme.typography.titleMedium)
            }
            item {
                SettingsSectionChip(
                    label = stringResource(R.string.settings_advanced_options),
                    onClick = { showAdvancedSection = !showAdvancedSection },
                )
            }
            if (showAdvancedSection) {
                item {
                    SensorStatusPanel(
                        status = headingSourceStatus,
                        northReferenceStatus = northReferenceStatus,
                    )
                }
                item {
                    SettingsOptionPickerRow(
                        label = stringResource(R.string.settings_heading_source),
                        selectedValue = headingSourceModeSetting,
                        options = headingSourceOptions,
                        secondaryLabel = headingSourceModeLabel(headingSourceModeSetting, resources = resources),
                        onSelect = { selected ->
                            viewModel.setCompassSettingsMode(SettingsRepository.COMPASS_SETTINGS_MODE_ADVANCED)
                            viewModel.setCompassHeadingSourceMode(selected)
                        },
                    )
                }
            }
        }
        item {
            Text(stringResource(R.string.common_help), style = MaterialTheme.typography.titleMedium)
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = {
                    WearText(
                        text = stringResource(R.string.settings_compass_help),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                secondaryLabel = {
                    WearText(
                        text = stringResource(R.string.settings_how_to_test_compass),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                icon = { Icon(imageVector = Icons.Filled.Info, contentDescription = null) },
                onClick = { showInfoDialog = true },
            )
        }
    }

    if (showCompatibilityDialog) {
        val result = compatibilityState.result
        val error = compatibilityState.errorMessage
        val compatibilityMessage =
            when {
                isCompatibilityRunning ->
                    stringResource(
                        R.string.settings_step_follow_the_on_screen_step_for_best_result,
                        compatibilityState.progressLabel,
                        compatibilityState.stepIndex,
                        compatibilityState.totalSteps,
                    )
                error != null -> error
                result != null ->
                    stringResource(
                        R.string.compass_compatibility_results,
                        result.recommendedCompassModeLabel(resources = resources),
                        result.recommendedHeadingSourceLine(resources = resources),
                        result.candidateSummaryLines(resources = resources),
                    )
                else -> stringResource(R.string.settings_preparing_test)
            }
        val closeCompatibilityDialog = {
            showCompatibilityDialog = false
            compatibilityState = CompatibilityTestUiState()
        }
        WearActionDialog(
            visible = true,
            title = stringResource(R.string.settings_custom_sensor_test),
            onDismissRequest = {
                if (!isCompatibilityRunning) {
                    closeCompatibilityDialog()
                }
            },
            buttons =
                buildList {
                    if (!isCompatibilityRunning && result != null) {
                        add(
                            WearActionDialogButton(
                                text = stringResource(R.string.download_apply),
                                onClick = {
                                    when (result.recommendedMode) {
                                        CompassHeadingSourceMode.AUTO -> {
                                            viewModel.setCompassSettingsMode(
                                                SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC,
                                            )
                                            viewModel.setCompassHeadingSourceMode(
                                                SettingsRepository.COMPASS_HEADING_SOURCE_AUTO,
                                            )
                                        }
                                        else -> {
                                            viewModel.setCompassSettingsMode(
                                                SettingsRepository.COMPASS_SETTINGS_MODE_ADVANCED,
                                            )
                                            viewModel.setCompassHeadingSourceMode(
                                                headingSourceSettingFromMode(result.recommendedMode),
                                            )
                                        }
                                    }
                                    closeCompatibilityDialog()
                                },
                            ),
                        )
                    }
                    add(
                        WearActionDialogButton(
                            text =
                                if (isCompatibilityRunning) {
                                    stringResource(
                                        R.string.common_cancel,
                                    )
                                } else {
                                    stringResource(R.string.common_close)
                                },
                            onClick = closeCompatibilityDialog,
                            role = WearActionButtonRole.Secondary,
                        ),
                    )
                },
        ) {
            Text(
                text = compatibilityMessage,
                textAlign = TextAlign.Center,
            )
        }
    }

    WearHelpDialog(
        visible = showInfoDialog,
        title = stringResource(R.string.settings_compass_help),
        onDismiss = { showInfoDialog = false },
        lines =
            listOf(
                stringResource(
                    R.string.settings_recommended_setup_google_fused_quick_test_stand_still_face_a_clear_lan,
                ),
            ),
    )
}
