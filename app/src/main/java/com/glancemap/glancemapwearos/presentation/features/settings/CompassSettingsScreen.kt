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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
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
                SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC to "Automatic (recommended)",
                SettingsRepository.COMPASS_SETTINGS_MODE_ADVANCED to "Advanced...",
            )
        }
    val compassProviderOptions =
        remember {
            listOf(
                SettingsRepository.COMPASS_PROVIDER_GOOGLE_FUSED to "Google Fused (default)",
                SettingsRepository.COMPASS_PROVIDER_SENSOR_MANAGER to "Custom sensors",
            )
        }
    val northReferenceOptions =
        remember {
            listOf(
                SettingsRepository.NORTH_REFERENCE_TRUE to "True north",
                SettingsRepository.NORTH_REFERENCE_MAGNETIC to "Magnetic north",
            )
        }
    val headingSourceOptions =
        remember {
            listOf(
                SettingsRepository.COMPASS_HEADING_SOURCE_AUTO to "Auto (recommended)",
                SettingsRepository.COMPASS_HEADING_SOURCE_TYPE_HEADING to "TYPE_HEADING",
                SettingsRepository.COMPASS_HEADING_SOURCE_ROTATION_VECTOR to "Rotation vector",
                SettingsRepository.COMPASS_HEADING_SOURCE_MAGNETOMETER to "Magnetometer",
            )
        }
    val calibrationAlertsLabel = if (adaptive.isCompact) "Custom alerts" else "Custom compass alerts"
    val calibrationAlertsSecondary = if (adaptive.isCompact) "If custom drifts" else "If custom compass drifts"
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
                progressLabel = "Preparing sensors...",
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
                        progressLabel = "Testing ${headingSourceModeLabel(candidate)}",
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
                    )
                candidateScores += candidateScore
            }

            val best = candidateScores.filter { it.available }.maxByOrNull { it.score }
            if (best == null) {
                compatibilityState =
                    CompatibilityTestUiState(
                        running = false,
                        progressLabel = "Test unavailable",
                        result = null,
                        errorMessage = "No compatible heading source found.",
                    )
            } else {
                compatibilityState =
                    CompatibilityTestUiState(
                        running = false,
                        progressLabel = "Test complete",
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
                    progressLabel = "Test failed",
                    result = null,
                    errorMessage = "Could not finish compatibility test.",
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
                label = "Orientation provider",
                dialogTitle = "Orientation\nprovider",
                selectedValue = compassProviderModeSetting,
                options = compassProviderOptions,
                secondaryLabel =
                    compassProviderStatusLabel(
                        requestedMode = compassProviderModeSetting,
                        activeProviderType = activeProviderType,
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
                            text = "North reference",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        WearText(
                            text = automaticNorthReferenceStatusLabel(activeProviderType),
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
                    label = "North mode",
                    selectedValue = northReferenceMode,
                    options = northReferenceOptions,
                    secondaryLabel =
                        northReferenceStatusSecondaryLabel(
                            requestedMode = northReferenceMode,
                            status = northReferenceStatus,
                        ),
                    onSelect = viewModel::setNorthReferenceMode,
                )
            }
        }
        if (showSensorControls) {
            item {
                Text(
                    "Setup",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                SettingsOptionPickerRow(
                    label = "Compass mode",
                    selectedValue = compassSettingsMode,
                    options = compassModeOptions,
                    secondaryLabel = compassSettingsModeLabel(compassSettingsMode),
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
                        label = "▽ Accuracy colors",
                        secondaryLabel = "If off, cone stays green",
                    )
                }
            }
            item {
                Text("Custom compass", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        WearText(
                            text = "Recalibrate Compass",
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
                            text = "Run source test",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        WearText(
                            text = "Checks still + turn response",
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
                Text("Advanced", style = MaterialTheme.typography.titleMedium)
            }
            item {
                SettingsSectionChip(
                    label = "Advanced options",
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
                        label = "Heading source",
                        selectedValue = headingSourceModeSetting,
                        options = headingSourceOptions,
                        secondaryLabel = headingSourceModeLabel(headingSourceModeSetting),
                        onSelect = { selected ->
                            viewModel.setCompassSettingsMode(SettingsRepository.COMPASS_SETTINGS_MODE_ADVANCED)
                            viewModel.setCompassHeadingSourceMode(selected)
                        },
                    )
                }
            }
        }
        item {
            Text("Help", style = MaterialTheme.typography.titleMedium)
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = {
                    WearText(
                        text = "Compass help",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                secondaryLabel = {
                    WearText(
                        text = "How to test compass",
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
                    "${compatibilityState.progressLabel}\n" +
                        "Step ${compatibilityState.stepIndex}/${compatibilityState.totalSteps}\n" +
                        "Follow the on-screen step for best result."
                error != null -> error
                result != null ->
                    "Recommended custom settings:\n" +
                        "Compass mode: ${result.recommendedCompassModeLabel()}\n" +
                        result.recommendedHeadingSourceLine() +
                        "North mode: Keep True north for maps\n" +
                        "Magnetic north only if you want to match a magnetic compass.\n\n" +
                        "This test checks source availability, still-watch stability,\n" +
                        "and slow turn response.\n" +
                        "It does not detect true vs magnetic north.\n\n" +
                        "Results:\n" +
                        result.candidateSummaryLines()
                else -> "Preparing test..."
            }
        val closeCompatibilityDialog = {
            showCompatibilityDialog = false
            compatibilityState = CompatibilityTestUiState()
        }
        WearActionDialog(
            visible = true,
            title = "Custom sensor test",
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
                                text = "Apply",
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
                            text = if (isCompatibilityRunning) "Cancel" else "Close",
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
        title = "Compass help",
        onDismiss = { showInfoDialog = false },
        lines =
            listOf(
                "Recommended setup: Google Fused.\n\n" +
                    "Quick test: stand still, face a clear landmark, then switch North-up / Compass.\n" +
                    "The map should keep the same direction and should not jump after open or wake.\n\n" +
                    "North reference:\n" +
                    "Google Fused chooses it automatically.\n" +
                    "With Custom sensors, use True north for maps,\n" +
                    "or Magnetic north to match a magnetic compass.\n\n" +
                    "If Google Fused still feels wrong on your watch,\n" +
                    "switch Orientation provider to Custom sensors.\n" +
                    "Custom mode prefers Rotation vector when available,\n" +
                    "and gives you Recalibrate Compass, source test,\n" +
                    "heading source selection, and accuracy-color options.",
            ),
    )
}
