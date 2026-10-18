package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.routes.RecordedActivityStatistics
import java.util.Locale

internal data class PhoneActivityMetric(
    val label: Int,
    val value: String,
)

internal fun phoneActivityMetrics(
    context: Context,
    statistics: RecordedActivityStatistics,
    isMetric: Boolean,
): List<PhoneActivityMetric> {
    val format = ActivityMetricFormatter(context, isMetric)
    val metrics =
        with(statistics) {
            listOf(
                PhoneActivityMetric(R.string.activity_distance, format.distance(distanceMeters)),
                PhoneActivityMetric(R.string.activity_steps, stepCount?.takeIf { it >= 0 }?.toString() ?: "—"),
                PhoneActivityMetric(R.string.activity_total_time, format.duration(totalDurationSeconds)),
                PhoneActivityMetric(R.string.activity_active_time, format.duration(durationSeconds)),
                PhoneActivityMetric(R.string.activity_ascent, format.elevation(elevationGainMeters)),
                PhoneActivityMetric(R.string.activity_descent, format.elevation(elevationLossMeters)),
                PhoneActivityMetric(R.string.activity_average_speed, format.speed(averageSpeedMps)),
                PhoneActivityMetric(R.string.activity_max_speed, format.speed(fastestSpeedMps)),
                PhoneActivityMetric(R.string.activity_average_pace, format.pace(averageSpeedMps)),
                PhoneActivityMetric(R.string.activity_best_pace, format.pace(fastestSpeedMps)),
                PhoneActivityMetric(R.string.activity_average_hr, format.heartRate(averageHeartRateBpm)),
                PhoneActivityMetric(R.string.activity_max_hr, format.heartRate(maxHeartRateBpm)),
                PhoneActivityMetric(R.string.activity_average_power, format.power(averagePowerWatts)),
                PhoneActivityMetric(R.string.activity_max_power, format.power(maxPowerWatts)),
                PhoneActivityMetric(R.string.activity_average_cadence, format.cadence(averageCadenceSpm)),
                PhoneActivityMetric(R.string.activity_max_cadence, format.cadence(maxCadenceSpm)),
                PhoneActivityMetric(R.string.activity_total_calories, format.calories(caloriesGrossKcal)),
                PhoneActivityMetric(R.string.activity_active_calories, format.calories(caloriesActiveKcal)),
                PhoneActivityMetric(R.string.activity_resting_calories, format.calories(caloriesRestingKcal)),
            )
        }
    if (statistics.activityProfile != PhoneActivityProfile.BIKE.storageValue) return metrics
    val bikeOrder =
        listOf(
            R.string.activity_distance,
            R.string.activity_total_calories,
            R.string.activity_active_calories,
            R.string.activity_resting_calories,
            R.string.activity_active_time,
            R.string.activity_total_time,
            R.string.activity_ascent,
            R.string.activity_descent,
            R.string.activity_average_hr,
            R.string.activity_max_hr,
            R.string.activity_average_speed,
            R.string.activity_max_speed,
            R.string.activity_average_power,
            R.string.activity_max_power,
        )
    return metrics.sortedBy { bikeOrder.indexOf(it.label).takeIf { index -> index >= 0 } ?: bikeOrder.size }
}

private class ActivityMetricFormatter(
    private val context: Context,
    private val isMetric: Boolean,
) {
    fun distance(value: Double?): String {
        val distance = value.validMeasurement() ?: return "—"
        return formatRecordingDistance(context, distance, isMetric)
    }

    fun elevation(value: Double?): String {
        val elevation = value.validMeasurement() ?: return "—"
        return formatPhoneMapElevation(elevation, isMetric)
    }

    fun duration(seconds: Double?): String {
        val duration = seconds.validMeasurement() ?: return "—"
        return formatRecordingDuration(context, (duration * 1_000).toLong())
    }

    fun calories(value: Double?): String {
        val calories = value.validMeasurement() ?: return "—"
        return context.getString(R.string.activity_unit_kcal, calories)
    }

    fun heartRate(value: Int?): String = count(value?.takeIf { it > 0 }, R.string.activity_unit_bpm)

    fun power(value: Int?): String = count(value, R.string.activity_unit_watts)

    fun cadence(value: Int?): String = count(value?.takeIf { it > 0 }, R.string.activity_unit_spm)

    fun speed(value: Double?): String =
        value.validMeasurement()?.let {
            context.getString(
                if (isMetric) R.string.activity_unit_kmh else R.string.activity_unit_mph,
                if (isMetric) it * 3.6 else it / 0.44704,
            )
        } ?: "—"

    fun pace(value: Double?): String {
        val seconds =
            value.validMeasurement()?.takeIf { it > 0.0 }?.let {
                ((if (isMetric) 1_000.0 else 1_609.344) / it).takeIf(Double::isFinite)?.toLong()
            } ?: return "—"
        val clock = String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
        return context.getString(
            if (isMetric) R.string.activity_unit_pace_km else R.string.activity_unit_pace_mile,
            clock,
        )
    }

    private fun count(
        value: Int?,
        unit: Int,
    ): String = value?.takeIf { it >= 0 }?.let { context.getString(unit, it) } ?: "—"
}

private fun Double?.validMeasurement(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }
