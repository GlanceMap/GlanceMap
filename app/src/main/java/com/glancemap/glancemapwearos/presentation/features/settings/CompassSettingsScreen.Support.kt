package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.domain.sensors.CompassHeadingSourceMode
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassViewModel
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.HeadingSourceStatus
import com.glancemap.glancemapwearos.domain.sensors.NorthReferenceMode
import com.glancemap.glancemapwearos.domain.sensors.NorthReferenceStatus
import kotlinx.coroutines.delay
import kotlin.math.abs

internal data class CompatibilityTestUiState(
    val running: Boolean = false,
    val progressLabel: String = "",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val result: CompatibilityTestResult? = null,
    val errorMessage: String? = null,
)

internal data class CompatibilityTestResult(
    val recommendedMode: CompassHeadingSourceMode,
    val bestCandidate: CompatibilityCandidateScore,
    val candidates: List<CompatibilityCandidateScore>,
    val availability: HeadingSourceStatus,
)

internal data class CompatibilityCandidateScore(
    val mode: CompassHeadingSourceMode,
    val score: Float,
    val averageAccuracyRank: Float,
    val averageNoiseDeg: Float,
    val turnSpanDeg: Float,
    val sourceMatchRatio: Float,
    val magneticInterferenceRatio: Float,
    val available: Boolean,
    val unavailableReason: String? = null,
) {
    fun signalSummary(resources: Resources): String {
        if (!available) {
            return unavailableReason ?: resources.getString(R.string.common_unavailable)
        }
        val accuracyLabel = accuracyLabelFromAverageRank(averageAccuracyRank, resources = resources)
        val accuracy = "%.1f".format(averageAccuracyRank)
        val noise = "%.1f".format(averageNoiseDeg)
        val match = "%.0f".format(sourceMatchRatio * 100f)
        val response = turnResponseLabel(turnSpanDeg, resources = resources)
        return resources.getString(
            R.string.settings_accuracy_3_noise_deg_response_source_match,
            accuracyLabel,
            accuracy,
            noise,
            response,
            match,
        )
    }

    fun userSummary(
        isBest: Boolean,
        resources: Resources,
    ): String {
        val prefix = if (isBest) "* " else "- "
        if (!available) {
            return resources.getString(
                R.string.compass_source_unavailable_summary,
                prefix,
                headingSourceModeLabel(mode, resources = resources),
                unavailableReason ?: resources.getString(R.string.common_unavailable),
            )
        }
        val accuracyLabel = accuracyLabelFromAverageRank(averageAccuracyRank, resources = resources)
        val noise = "%.1f".format(averageNoiseDeg)
        val response = turnResponseLabel(turnSpanDeg, resources = resources)
        return resources.getString(
            R.string.compass_source_signal_summary,
            prefix,
            headingSourceModeLabel(mode, resources = resources),
            accuracyLabel,
            noise,
            response,
        )
    }
}

internal data class CompatibilitySampleSummary(
    val averageAccuracyRank: Float,
    val averageNoiseDeg: Float,
    val headingSpanDeg: Float,
    val sourceMatchRatio: Float,
    val magneticInterferenceRatio: Float,
)

// Keep the existing flow and inputs; localization adds resources and wrapped calls.
@Suppress("LongMethod", "LongParameterList")
internal suspend fun evaluateCompatibilityCandidate(
    mode: CompassHeadingSourceMode,
    availability: HeadingSourceStatus,
    compassViewModel: CompassViewModel,
    readHeading: () -> Float,
    readAccuracy: () -> Int,
    readSource: () -> HeadingSource,
    readMagneticInterference: () -> Boolean,
    onPhaseStart: (Int, String) -> Unit,
    resources: Resources,
): CompatibilityCandidateScore {
    compassViewModel.setHeadingSourceMode(mode, forceRefresh = true)
    delay(COMPATIBILITY_SWITCH_SETTLE_MS)

    if (!sourceModeAvailable(mode, availability)) {
        return CompatibilityCandidateScore(
            mode = mode,
            score = Float.NEGATIVE_INFINITY,
            averageAccuracyRank = 0f,
            averageNoiseDeg = 180f,
            turnSpanDeg = 0f,
            sourceMatchRatio = 0f,
            magneticInterferenceRatio = 0f,
            available = false,
            unavailableReason = sourceModeUnavailableReason(mode, availability, resources = resources),
        )
    }

    onPhaseStart(
        1,
        resources.getString(R.string.settings_keep_watch_still, headingSourceModeLabel(mode, resources = resources)),
    )
    val stillSummary =
        collectCompatibilitySampleSummary(
            sampleCount = COMPATIBILITY_STILL_SAMPLE_COUNT,
            readHeading = readHeading,
            readAccuracy = readAccuracy,
            readSource = readSource,
            readMagneticInterference = readMagneticInterference,
            expectedMode = mode,
        )

    onPhaseStart(
        2,
        resources.getString(
            R.string.settings_turn_slowly_left_right,
            headingSourceModeLabel(mode, resources = resources),
        ),
    )
    delay(COMPATIBILITY_TURN_PREPARE_MS)
    val turnSummary =
        collectCompatibilitySampleSummary(
            sampleCount = COMPATIBILITY_TURN_SAMPLE_COUNT,
            readHeading = readHeading,
            readAccuracy = readAccuracy,
            readSource = readSource,
            readMagneticInterference = readMagneticInterference,
            expectedMode = mode,
        )

    val score =
        compatibilityScore(
            averageAccuracyRank = stillSummary.averageAccuracyRank,
            sourceMatchRatio = stillSummary.sourceMatchRatio,
            averageNoiseDeg = stillSummary.averageNoiseDeg,
            magneticInterferenceRatio = stillSummary.magneticInterferenceRatio,
            turnSpanDeg = turnSummary.headingSpanDeg,
        )
    return CompatibilityCandidateScore(
        mode = mode,
        score = score,
        averageAccuracyRank = stillSummary.averageAccuracyRank,
        averageNoiseDeg = stillSummary.averageNoiseDeg,
        turnSpanDeg = turnSummary.headingSpanDeg,
        sourceMatchRatio = stillSummary.sourceMatchRatio,
        magneticInterferenceRatio = stillSummary.magneticInterferenceRatio,
        available = true,
    )
}

internal fun compatibilityScore(
    averageAccuracyRank: Float,
    sourceMatchRatio: Float,
    averageNoiseDeg: Float,
    magneticInterferenceRatio: Float,
    turnSpanDeg: Float,
): Float {
    val noisePenalty =
        when {
            averageNoiseDeg >= 20f -> 3.5f
            averageNoiseDeg >= 12f -> 2.5f
            averageNoiseDeg >= 7f -> 1.5f
            averageNoiseDeg >= 4f -> 0.6f
            else -> 0f
        }
    return (averageAccuracyRank * 2.0f) +
        (sourceMatchRatio * 4.0f) -
        noisePenalty -
        (magneticInterferenceRatio * 2.0f) +
        turnResponseBonus(turnSpanDeg)
}

internal suspend fun collectCompatibilitySampleSummary(
    sampleCount: Int,
    readHeading: () -> Float,
    readAccuracy: () -> Int,
    readSource: () -> HeadingSource,
    readMagneticInterference: () -> Boolean,
    expectedMode: CompassHeadingSourceMode,
): CompatibilitySampleSummary {
    var measuredSamples = 0
    var accuracyRankTotal = 0f
    var sourceMatchCount = 0
    var interferenceCount = 0
    val headingSamples = mutableListOf<Float>()

    repeat(sampleCount) {
        val heading = readHeading()
        if (heading.isFinite()) {
            headingSamples += heading
        }
        accuracyRankTotal += accuracyRank(readAccuracy())
        if (sourceMatchesMode(readSource(), expectedMode)) {
            sourceMatchCount += 1
        }
        if (readMagneticInterference()) {
            interferenceCount += 1
        }
        measuredSamples += 1
        delay(COMPATIBILITY_SAMPLE_INTERVAL_MS)
    }

    return CompatibilitySampleSummary(
        averageAccuracyRank = if (measuredSamples > 0) accuracyRankTotal / measuredSamples else 0f,
        averageNoiseDeg = averageAngularNoiseDeg(headingSamples),
        headingSpanDeg = headingSpanDeg(headingSamples),
        sourceMatchRatio = if (measuredSamples > 0) sourceMatchCount.toFloat() / measuredSamples else 0f,
        magneticInterferenceRatio = if (measuredSamples > 0) interferenceCount.toFloat() / measuredSamples else 0f,
    )
}

internal fun averageAngularNoiseDeg(samples: List<Float>): Float {
    if (samples.size < 2) return 180f
    var sum = 0f
    var count = 0
    samples.zipWithNext().forEach { (a, b) ->
        sum += abs(shortestAngularDiffDeg(a, b))
        count += 1
    }
    return if (count == 0) 180f else (sum / count)
}

internal fun headingSpanDeg(samples: List<Float>): Float {
    if (samples.size < 2) return 0f
    var minOffset = 0f
    var maxOffset = 0f
    val anchor = samples.first()
    samples.forEach { sample ->
        val offset = shortestAngularDiffDeg(anchor, sample)
        minOffset = minOf(minOffset, offset)
        maxOffset = maxOf(maxOffset, offset)
    }
    return maxOffset - minOffset
}

internal fun turnResponseBonus(turnSpanDeg: Float): Float =
    when {
        turnSpanDeg >= 45f -> 1.2f
        turnSpanDeg >= 28f -> 0.7f
        turnSpanDeg >= 14f -> 0.2f
        else -> 0f
    }

internal fun turnResponseLabel(
    turnSpanDeg: Float,
    resources: Resources,
): String =
    when {
        turnSpanDeg >= 45f -> resources.getString(R.string.settings_good_2)
        turnSpanDeg >= 28f -> resources.getString(R.string.gpx_ok)
        turnSpanDeg >= 14f -> resources.getString(R.string.settings_limited)
        else -> resources.getString(R.string.settings_not_enough_movement)
    }

internal fun shortestAngularDiffDeg(
    from: Float,
    to: Float,
): Float {
    var diff = (to - from + 540f) % 360f - 180f
    if (abs(diff + 180f) < 1e-3f) {
        diff = if ((to - from) >= 0f) 180f else -180f
    }
    return diff
}

internal fun sourceMatchesMode(
    source: HeadingSource,
    mode: CompassHeadingSourceMode,
): Boolean =
    when (mode) {
        CompassHeadingSourceMode.AUTO -> source != HeadingSource.NONE
        CompassHeadingSourceMode.TYPE_HEADING -> source == HeadingSource.HEADING_SENSOR
        CompassHeadingSourceMode.ROTATION_VECTOR -> source == HeadingSource.ROTATION_VECTOR
        CompassHeadingSourceMode.MAGNETOMETER -> source == HeadingSource.MAG_ACCEL_FALLBACK
    }

internal fun accuracyRank(accuracy: Int): Float =
    when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 3f
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 2f
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 1f
        else -> 0f
    }

internal fun accuracyLabelFromAverageRank(
    rank: Float,
    resources: Resources,
): String =
    when {
        rank >= 2.7f -> resources.getString(R.string.settings_high)
        rank >= 2.2f -> resources.getString(R.string.settings_medium_high)
        rank >= 1.7f -> resources.getString(R.string.settings_medium)
        rank >= 1.2f -> resources.getString(R.string.settings_low_medium)
        rank > 0f -> resources.getString(R.string.settings_low)
        else -> resources.getString(R.string.settings_unreliable)
    }

internal fun recommendedModeFromCandidate(
    best: CompatibilityCandidateScore,
    availability: HeadingSourceStatus,
): CompassHeadingSourceMode {
    if (autoResolvesToMode(best.mode, availability)) {
        return CompassHeadingSourceMode.AUTO
    }
    return best.mode
}

internal fun CompatibilityTestResult.recommendedCompassModeLabel(resources: Resources): String =
    when (recommendedMode) {
        CompassHeadingSourceMode.AUTO -> resources.getString(R.string.settings_automatic_recommended)
        else -> resources.getString(R.string.settings_advanced)
    }

internal fun CompatibilityTestResult.recommendedHeadingSourceLine(resources: Resources): String {
    if (!bestCandidate.available) return ""
    return if (recommendedMode == CompassHeadingSourceMode.AUTO) {
        resources.getString(
            R.string.settings_automatic_will_use,
            headingSourceModeLabel(bestCandidate.mode, resources = resources),
        )
    } else {
        resources.getString(
            R.string.settings_heading_source_2,
            headingSourceModeLabel(recommendedMode, resources = resources),
        )
    }
}

internal fun CompatibilityTestResult.candidateSummaryLines(resources: Resources): String =
    candidates.joinToString("\n") { candidate ->
        candidate.userSummary(
            isBest = candidate.mode == bestCandidate.mode && candidate.available,
            resources = resources,
        )
    }

internal fun autoResolvesToMode(
    mode: CompassHeadingSourceMode,
    availability: HeadingSourceStatus,
): Boolean =
    when (mode) {
        CompassHeadingSourceMode.TYPE_HEADING -> availability.headingSensorAvailable
        CompassHeadingSourceMode.ROTATION_VECTOR ->
            !availability.headingSensorAvailable && availability.rotationVectorAvailable
        CompassHeadingSourceMode.MAGNETOMETER ->
            !availability.headingSensorAvailable &&
                !availability.rotationVectorAvailable &&
                availability.magAccelFallbackAvailable
        CompassHeadingSourceMode.AUTO -> true
    }

internal fun sourceModeAvailable(
    mode: CompassHeadingSourceMode,
    availability: HeadingSourceStatus,
): Boolean =
    when (mode) {
        CompassHeadingSourceMode.AUTO -> true
        CompassHeadingSourceMode.TYPE_HEADING -> availability.headingSensorAvailable
        CompassHeadingSourceMode.ROTATION_VECTOR -> availability.rotationVectorAvailable
        CompassHeadingSourceMode.MAGNETOMETER -> availability.magAccelFallbackAvailable
    }

internal fun sourceModeUnavailableReason(
    mode: CompassHeadingSourceMode,
    availability: HeadingSourceStatus,
    resources: Resources,
): String =
    when (mode) {
        CompassHeadingSourceMode.TYPE_HEADING -> {
            if (availability.headingSensorAvailable) {
                resources.getString(R.string.common_unavailable)
            } else {
                resources.getString(R.string.settings_not_exposed_by_this_watch)
            }
        }
        CompassHeadingSourceMode.ROTATION_VECTOR -> {
            if (availability.rotationVectorAvailable) {
                resources.getString(R.string.common_unavailable)
            } else {
                resources.getString(R.string.settings_not_exposed_by_this_watch)
            }
        }
        CompassHeadingSourceMode.MAGNETOMETER -> {
            if (availability.magAccelFallbackAvailable) {
                resources.getString(R.string.common_unavailable)
            } else {
                resources.getString(R.string.settings_magnetometer_fallback_unavailable)
            }
        }
        CompassHeadingSourceMode.AUTO -> resources.getString(R.string.common_unavailable)
    }

internal fun northReferenceModeFromSetting(mode: String): NorthReferenceMode =
    when (mode) {
        SettingsRepository.NORTH_REFERENCE_MAGNETIC -> NorthReferenceMode.MAGNETIC
        else -> NorthReferenceMode.TRUE
    }

internal fun headingSourceModeFromSetting(mode: String): CompassHeadingSourceMode =
    when (mode) {
        SettingsRepository.COMPASS_HEADING_SOURCE_TYPE_HEADING -> CompassHeadingSourceMode.TYPE_HEADING
        SettingsRepository.COMPASS_HEADING_SOURCE_ROTATION_VECTOR -> CompassHeadingSourceMode.ROTATION_VECTOR
        SettingsRepository.COMPASS_HEADING_SOURCE_MAGNETOMETER -> CompassHeadingSourceMode.MAGNETOMETER
        else -> CompassHeadingSourceMode.AUTO
    }

internal fun headingSourceSettingFromMode(mode: CompassHeadingSourceMode): String =
    when (mode) {
        CompassHeadingSourceMode.TYPE_HEADING ->
            SettingsRepository.COMPASS_HEADING_SOURCE_TYPE_HEADING
        CompassHeadingSourceMode.ROTATION_VECTOR ->
            SettingsRepository.COMPASS_HEADING_SOURCE_ROTATION_VECTOR
        CompassHeadingSourceMode.MAGNETOMETER ->
            SettingsRepository.COMPASS_HEADING_SOURCE_MAGNETOMETER
        CompassHeadingSourceMode.AUTO ->
            SettingsRepository.COMPASS_HEADING_SOURCE_AUTO
    }

internal fun compassSettingsModeLabel(
    mode: String,
    resources: Resources,
): String =
    when (mode) {
        SettingsRepository.COMPASS_SETTINGS_MODE_ADVANCED -> resources.getString(R.string.settings_advanced)
        else -> resources.getString(R.string.settings_automatic_recommended)
    }

internal fun compassProviderTypeFromSetting(mode: String): CompassProviderType =
    when (mode) {
        SettingsRepository.COMPASS_PROVIDER_SENSOR_MANAGER -> CompassProviderType.SENSOR_MANAGER
        else -> CompassProviderType.GOOGLE_FUSED
    }

internal fun compassProviderModeLabel(
    mode: String,
    resources: Resources,
): String =
    when (compassProviderTypeFromSetting(mode)) {
        CompassProviderType.SENSOR_MANAGER -> resources.getString(R.string.settings_custom_sensors)
        CompassProviderType.GOOGLE_FUSED -> resources.getString(R.string.settings_google_fused_default)
    }

internal fun usesAutomaticGoogleNorthReference(
    providerType: CompassProviderType,
): Boolean = providerType == CompassProviderType.GOOGLE_FUSED

internal fun automaticNorthReferenceStatusLabel(
    activeProviderType: CompassProviderType,
    resources: Resources,
): String =
    when (activeProviderType) {
        CompassProviderType.GOOGLE_FUSED -> resources.getString(R.string.settings_automatic_google)
        CompassProviderType.SENSOR_MANAGER -> resources.getString(R.string.settings_true_north_sensor_fallback)
    }

internal fun compassProviderStatusLabel(
    requestedMode: String,
    activeProviderType: CompassProviderType,
    resources: Resources,
): String {
    val requestedType = compassProviderTypeFromSetting(requestedMode)
    if (requestedType == activeProviderType) {
        return compassProviderModeLabel(requestedMode, resources = resources)
    }
    return when {
        requestedType == CompassProviderType.GOOGLE_FUSED &&
            activeProviderType == CompassProviderType.SENSOR_MANAGER ->
            resources.getString(R.string.settings_google_fused_custom_backup)
        requestedType == CompassProviderType.SENSOR_MANAGER &&
            activeProviderType == CompassProviderType.GOOGLE_FUSED ->
            resources.getString(R.string.settings_custom_sensors_google_fused)
        else -> compassProviderModeLabel(requestedMode, resources = resources)
    }
}

internal fun northReferenceModeLabel(
    mode: String,
    resources: Resources,
): String =
    when (mode) {
        SettingsRepository.NORTH_REFERENCE_MAGNETIC -> resources.getString(R.string.settings_magnetic_north)
        else -> resources.getString(R.string.settings_true_north)
    }

internal fun northReferenceStatusSecondaryLabel(
    requestedMode: String,
    status: NorthReferenceStatus,
    resources: Resources,
): String {
    val requestedLabel = northReferenceModeLabel(requestedMode, resources = resources)
    return when {
        status.automaticByProvider -> resources.getString(R.string.settings_automatic_google)
        status.waitingForDeclination && status.requestedMode == NorthReferenceMode.TRUE ->
            resources.getString(R.string.settings_waiting_for_location, requestedLabel)
        status.waitingForDeclination && status.requestedMode == NorthReferenceMode.MAGNETIC ->
            resources.getString(R.string.settings_needs_declination, requestedLabel)
        else -> resources.getString(R.string.settings_ready, requestedLabel)
    }
}

internal fun headingSourceModeLabel(
    mode: String,
    resources: Resources,
): String = headingSourceModeLabel(headingSourceModeFromSetting(mode), resources = resources)

internal fun headingSourceModeLabel(
    mode: CompassHeadingSourceMode,
    resources: Resources,
): String =
    when (mode) {
        CompassHeadingSourceMode.TYPE_HEADING -> "TYPE_HEADING"
        CompassHeadingSourceMode.ROTATION_VECTOR -> resources.getString(R.string.settings_rotation_vector_2)
        CompassHeadingSourceMode.MAGNETOMETER -> resources.getString(R.string.settings_magnetometer_2)
        CompassHeadingSourceMode.AUTO -> resources.getString(R.string.settings_auto_recommended)
    }

internal val COMPATIBILITY_CANDIDATES =
    listOf(
        CompassHeadingSourceMode.TYPE_HEADING,
        CompassHeadingSourceMode.ROTATION_VECTOR,
        CompassHeadingSourceMode.MAGNETOMETER,
    )

private val SensorStatusChipBackground = Color(0xFF33465F)
private val SensorStatusChipContent = Color(0xFFF4F7FB)
private val SensorStatusChipSecondary = Color(0xFFD0DEEE)

@Composable
internal fun SensorStatusPanel(
    status: HeadingSourceStatus,
    northReferenceStatus: NorthReferenceStatus,
) {
    val resources = LocalResources.current
    val requested = sensorModeLabel(status.requestedMode, resources = resources)
    val active = sensorActiveLabel(status.activeSource, resources = resources)
    val headingAvailability =
        stringResource(
            R.string.settings_heading_sensor,
            status.headingSensorAvailable.yesNoLabel(resources = resources),
        )
    val rotationVectorAvailability =
        stringResource(
            R.string.settings_rotation_vector,
            status.rotationVectorAvailable.yesNoLabel(resources = resources),
        )
    val magnetometerAvailability =
        stringResource(
            R.string.settings_magnetometer,
            status.magAccelFallbackAvailable.yesNoLabel(resources = resources),
        )
    val northStatus =
        stringResource(
            R.string.settings_north,
            northReferenceStatus.requestedMode.userLabel(resources = resources),
            northReferenceStatus.effectiveMode.userLabel(resources = resources),
        )
    val declinationStatus =
        when {
            northReferenceStatus.declinationAvailable -> stringResource(R.string.settings_declination_ready)
            northReferenceStatus.waitingForDeclination -> stringResource(R.string.settings_declination_waiting)
            else -> stringResource(R.string.settings_declination_not_needed)
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 98.dp)
                .background(SensorStatusChipBackground, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_sensor_status),
                style = MaterialTheme.typography.titleMedium,
                color = SensorStatusChipContent,
            )
            Text(
                text = stringResource(R.string.settings_requested, requested),
                style = MaterialTheme.typography.bodySmall,
                color = SensorStatusChipSecondary,
            )
            Text(
                text = stringResource(R.string.settings_active, active),
                style = MaterialTheme.typography.bodySmall,
                color = SensorStatusChipSecondary,
            )
            Text(
                text = headingAvailability,
                style = MaterialTheme.typography.bodySmall,
                color = SensorStatusChipSecondary,
            )
            Text(
                text = rotationVectorAvailability,
                style = MaterialTheme.typography.bodySmall,
                color = SensorStatusChipSecondary,
            )
            Text(
                text = magnetometerAvailability,
                style = MaterialTheme.typography.bodySmall,
                color = SensorStatusChipSecondary,
            )
            Text(
                text = northStatus,
                style = MaterialTheme.typography.bodySmall,
                color = SensorStatusChipSecondary,
            )
            Text(
                text = declinationStatus,
                style = MaterialTheme.typography.bodySmall,
                color = SensorStatusChipSecondary,
            )
        }
    }
}

private fun sensorModeLabel(
    mode: CompassHeadingSourceMode,
    resources: Resources,
): String =
    when (mode) {
        CompassHeadingSourceMode.AUTO -> resources.getString(R.string.settings_auto)
        CompassHeadingSourceMode.TYPE_HEADING -> resources.getString(R.string.settings_type_heading)
        CompassHeadingSourceMode.ROTATION_VECTOR -> resources.getString(R.string.settings_rotation_vector_2)
        CompassHeadingSourceMode.MAGNETOMETER -> resources.getString(R.string.settings_magnetometer_2)
    }

private fun sensorActiveLabel(
    source: HeadingSource,
    resources: Resources,
): String =
    when (source) {
        HeadingSource.NONE -> resources.getString(R.string.common_unavailable)
        HeadingSource.FUSED_ORIENTATION -> resources.getString(R.string.settings_google_fused)
        HeadingSource.HEADING_SENSOR -> resources.getString(R.string.settings_type_heading)
        HeadingSource.ROTATION_VECTOR -> resources.getString(R.string.settings_rotation_vector_2)
        HeadingSource.MAG_ACCEL_FALLBACK -> resources.getString(R.string.settings_mag_fallback)
    }

private fun NorthReferenceMode.userLabel(resources: Resources): String =
    when (this) {
        NorthReferenceMode.TRUE -> resources.getString(R.string.settings_true)
        NorthReferenceMode.MAGNETIC -> resources.getString(R.string.settings_magnetic)
    }

private fun Boolean.yesNoLabel(
    resources: Resources,
): String = if (this) resources.getString(R.string.common_yes) else resources.getString(R.string.common_no)

internal const val COMPATIBILITY_PREPARE_DELAY_MS = 350L
internal const val COMPATIBILITY_SWITCH_SETTLE_MS = 1_700L
internal const val COMPATIBILITY_PHASE_COUNT = 2
internal const val COMPATIBILITY_STILL_SAMPLE_COUNT = 10
internal const val COMPATIBILITY_TURN_SAMPLE_COUNT = 12
internal const val COMPATIBILITY_TURN_PREPARE_MS = 900L
internal const val COMPATIBILITY_SAMPLE_INTERVAL_MS = 100L
