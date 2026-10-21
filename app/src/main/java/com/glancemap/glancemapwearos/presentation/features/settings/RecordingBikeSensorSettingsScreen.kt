package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.ui.WearFormDialog
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RecordingBikeSensorSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenRecordingSourceSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val listTokens = rememberSettingsListTokens()
    val wheelCircumferenceMeters by viewModel.cyclingWheelCircumferenceMeters.collectAsState()
    val linkedRunPodAddress by viewModel.recordingExternalRunPodAddress.collectAsState()
    val linkedRunPodName by viewModel.recordingExternalRunPodName.collectAsState()
    val selectedWheelCircumferenceMm = (wheelCircumferenceMeters * 1000f).roundToInt()
    var wheelPickerVisible by remember { mutableStateOf(false) }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            AdaptiveSettingsShortcutChip(
                standardLabel = stringResource(R.string.settings_recording_sources),
                compactLabel = stringResource(R.string.settings_sources),
                standardSecondaryLabel = stringResource(R.string.settings_back_to_sensor_sources),
                compactSecondaryLabel = stringResource(R.string.settings_sensor_sources),
                iconImageVector = Icons.Filled.Folder,
                applyTopPadding = true,
                compactRoundWidthFraction = 0.82f,
                onClick = onOpenRecordingSourceSettings,
            )
        }
        item {
            SettingsPickerChip(
                label = stringResource(R.string.settings_wheel_size),
                secondaryLabel = formatWheelCircumference(wheelCircumferenceMeters, resources = resources),
                iconImageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                onClick = { wheelPickerVisible = true },
            )
        }
        item {
            SettingsPickerChip(
                label = stringResource(R.string.settings_linked_bike_sensor),
                secondaryLabel = linkedBikeSensorLabel(linkedRunPodName, linkedRunPodAddress, resources = resources),
                iconImageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                onClick = onOpenRecordingSourceSettings,
            )
        }
    }

    WheelCircumferencePickerDialog(
        visible = wheelPickerVisible,
        valueMm = selectedWheelCircumferenceMm,
        onDismiss = { wheelPickerVisible = false },
        onValueChange = { wheelMm ->
            viewModel.setCyclingWheelCircumferenceMeters(wheelMm / 1000f)
        },
    )
}

@Composable
private fun WheelCircumferencePickerDialog(
    visible: Boolean,
    valueMm: Int,
    onDismiss: () -> Unit,
    onValueChange: (Int) -> Unit,
) {
    if (!visible) return

    val focusRequester = remember { FocusRequester() }
    val minMm = (SettingsRepository.MIN_CYCLING_WHEEL_CIRCUMFERENCE_METERS * 1000f).roundToInt()
    val maxMm = (SettingsRepository.MAX_CYCLING_WHEEL_CIRCUMFERENCE_METERS * 1000f).roundToInt()
    var localValueMm by remember(valueMm) {
        mutableIntStateOf(valueMm.coerceIn(minMm, maxMm))
    }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }

    fun applyDelta(deltaMm: Int) {
        if (deltaMm == 0) return
        val nextValue = (localValueMm + deltaMm).coerceIn(minMm, maxMm)
        if (nextValue == localValueMm) return
        localValueMm = nextValue
        onValueChange(nextValue)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    WearFormDialog(
        visible = true,
        title = stringResource(R.string.settings_wheel_size),
        onDismiss = onDismiss,
        backgroundColor = Color.Black.copy(alpha = 0.96f),
    ) {
        Column(
            modifier =
                it.controlModifier
                    .onPreRotaryScrollEvent { event ->
                        val delta = event.verticalScrollPixels
                        if (!delta.isFinite() || delta == 0f) return@onPreRotaryScrollEvent false
                        rotaryAccumulator += delta
                        when {
                            rotaryAccumulator >= WHEEL_PICKER_ROTARY_THRESHOLD_PX -> {
                                applyDelta(WHEEL_PICKER_STEP_MM)
                                rotaryAccumulator = 0f
                                true
                            }
                            rotaryAccumulator <= -WHEEL_PICKER_ROTARY_THRESHOLD_PX -> {
                                applyDelta(-WHEEL_PICKER_STEP_MM)
                                rotaryAccumulator = 0f
                                true
                            }
                            else -> true
                        }
                    }.focusRequester(focusRequester)
                    .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_wheel_circumference),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.settings_mm, localValueMm),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.settings_m_2, localValueMm / 1000f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WheelPickerButton(
                    text = "-",
                    onClick = { applyDelta(-WHEEL_PICKER_STEP_MM) },
                    onLongClick = { applyDelta(-WHEEL_PICKER_LONG_PRESS_STEP_MM) },
                )
                WheelPickerButton(
                    text = "+",
                    onClick = { applyDelta(WHEEL_PICKER_STEP_MM) },
                    onLongClick = { applyDelta(WHEEL_PICKER_LONG_PRESS_STEP_MM) },
                )
            }
            Text(
                text = stringResource(R.string.settings_tap_1_mm_long_press_10),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onDismiss,
                modifier =
                    it.controlModifier
                        .height(it.buttonMinHeight),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(stringResource(R.string.common_done))
            }
        }
    }
}

@Composable
private fun WheelPickerButton(
    text: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(WHEEL_PICKER_ACTION_BUTTON_SIZE)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .pointerInput(onClick, onLongClick) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongClick() },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatWheelCircumference(
    meters: Float,
    resources: Resources,
): String =
    resources.getString(
        R.string.settings_mm_m,
        (meters * 1000f).roundToInt(),
        String.format(Locale.getDefault(), "%.3f", meters),
    )

private fun linkedBikeSensorLabel(
    name: String?,
    address: String?,
    resources: Resources,
): String {
    val cleanAddress =
        address?.takeIf(String::isNotBlank) ?: return resources.getString(R.string.settings_no_bike_sensor_linked)
    val cleanName = name?.takeIf(String::isNotBlank) ?: resources.getString(R.string.settings_bike_sensor)
    return resources.getString(R.string.settings_sensor_name_address, cleanAddress.takeLast(5), cleanName)
}

private const val WHEEL_PICKER_STEP_MM = 1
private const val WHEEL_PICKER_LONG_PRESS_STEP_MM = 10
private const val WHEEL_PICKER_ROTARY_THRESHOLD_PX = 42
private val WHEEL_PICKER_ACTION_BUTTON_SIZE = 58.dp
