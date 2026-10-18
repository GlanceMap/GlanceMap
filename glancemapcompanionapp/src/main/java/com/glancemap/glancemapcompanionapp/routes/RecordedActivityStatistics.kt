package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import com.glancemap.trailcore.recording.RecordedTraceSummary
import org.w3c.dom.Element

/** Recorded measurements stay separate from the library's route-planning estimates. */
data class RecordedActivityStatistics(
    val activityProfile: String? = null,
    val pointCount: Int = 0,
    val distanceMeters: Double? = null,
    val durationSeconds: Double? = null,
    val totalDurationSeconds: Double? = null,
    val elevationGainMeters: Double? = null,
    val elevationLossMeters: Double? = null,
    val averageSpeedMps: Double? = null,
    val fastestSpeedMps: Double? = null,
    val averageHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val averagePowerWatts: Int? = null,
    val maxPowerWatts: Int? = null,
    val averageCadenceSpm: Int? = null,
    val maxCadenceSpm: Int? = null,
    val stepCount: Int? = null,
    val caloriesGrossKcal: Double? = null,
    val caloriesActiveKcal: Double? = null,
    val caloriesRestingKcal: Double? = null,
) {
    val isShortRecording: Boolean
        get() =
            pointCount < 2 || distanceMeters?.let { it < 20.0 } == true || durationSeconds?.let { it < 10.0 } == true
}

internal fun RecordedTraceSummary.activityStatistics() =
    RecordedActivityStatistics(
        activityProfile = activityProfile,
        pointCount = pointCount,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        totalDurationSeconds = totalDurationSeconds,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        averageSpeedMps = averageSpeedMps,
        fastestSpeedMps = fastestSpeedMps,
        averageHeartRateBpm = averageHeartRateBpm ?: heartRateBpm,
        maxHeartRateBpm = maxHeartRateBpm ?: heartRateBpm,
        averagePowerWatts = averagePowerWatts ?: powerWatts,
        maxPowerWatts = maxPowerWatts ?: powerWatts,
        averageCadenceSpm = averageCadenceSpm ?: cadenceSpm,
        maxCadenceSpm = maxCadenceSpm ?: cadenceSpm,
        stepCount = stepCount,
        caloriesGrossKcal = caloriesGrossKcal,
        caloriesActiveKcal = caloriesActiveKcal,
        caloriesRestingKcal = caloriesRestingKcal,
    )

internal fun recordedActivityGeometry(points: List<TrailPoint>): RecordedActivityStatistics {
    val profile = buildTrailRouteProfile(points)
    val hasElevation = points.any { it.elevationMeters != null }
    return RecordedActivityStatistics(
        pointCount = points.size,
        distanceMeters = profile.totalDistanceMeters,
        elevationGainMeters = profile.totalAscentMeters.takeIf { hasElevation },
        elevationLossMeters = profile.totalDescentMeters.takeIf { hasElevation },
    )
}

internal fun recordedActivityStatistics(
    root: Element,
    points: List<TrailPoint>,
): RecordedActivityStatistics? {
    val metadata = root.childElements().firstOrNull { it.localTag() == "metadata" }
    val extensions = metadata?.childElements()?.firstOrNull { it.localTag() == "extensions" }
    val values =
        extensions
            ?.childElements()
            .orEmpty()
            .filter { it.isRecordingExtension() }
            .associate { it.localTag() to it.textContent.trim() }
    val isRecording = values["activityType"] == "recording" || "durationSeconds" in values || "distanceMeters" in values
    if (!isRecording) return null
    val geometry =
        if (listOf("distanceMeters", "elevationGainMeters", "elevationLossMeters")
                .any { values.measurement(it) == null }
        ) {
            recordedActivityGeometry(points)
        } else {
            RecordedActivityStatistics(pointCount = points.size)
        }
    val duration = values.measurement("durationSeconds")
    val distance = values.measurement("distanceMeters") ?: geometry.distanceMeters
    return geometry
        .copy(
            activityProfile = values["activityProfile"],
            pointCount = values.count("pointCount") ?: points.size,
            distanceMeters = distance,
            durationSeconds = duration,
            totalDurationSeconds = values.measurement("totalDurationSeconds"),
            elevationGainMeters = values.measurement("elevationGainMeters") ?: geometry.elevationGainMeters,
            elevationLossMeters = values.measurement("elevationLossMeters") ?: geometry.elevationLossMeters,
            averageSpeedMps =
                values.measurement("averageSpeedMps") ?: duration?.takeIf { it > 0.0 }?.let { distance?.div(it) },
            fastestSpeedMps = values.measurement("fastestSpeedMps"),
            caloriesGrossKcal = values.measurement("caloriesGrossKcal"),
            caloriesActiveKcal = values.measurement("caloriesActiveKcal"),
            caloriesRestingKcal = values.measurement("caloriesRestingKcal"),
        ).withRecordedSensors(values)
}

private fun RecordedActivityStatistics.withRecordedSensors(values: Map<String, String>): RecordedActivityStatistics =
    copy(
        averageHeartRateBpm = values.count("averageHeartRateBpm") ?: values.count("heartRateBpm"),
        maxHeartRateBpm = values.count("maxHeartRateBpm") ?: values.count("heartRateBpm"),
        averagePowerWatts = values.count("averagePowerWatts") ?: values.count("powerWatts"),
        maxPowerWatts = values.count("maxPowerWatts") ?: values.count("powerWatts"),
        averageCadenceSpm = values.count("averageCadenceSpm") ?: values.count("cadenceSpm"),
        maxCadenceSpm = values.count("maxCadenceSpm") ?: values.count("cadenceSpm"),
        stepCount = values.count("stepCount"),
    )

private fun Map<String, String>.measurement(name: String): Double? {
    val value = get(name)?.toDoubleOrNull()
    return value?.takeIf { it.isFinite() && it >= 0.0 }
}

private fun Map<String, String>.count(name: String): Int? = get(name)?.toIntOrNull()?.takeIf { it >= 0 }

private fun Element.childElements(): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

private fun Element.localTag(): String = nodeName.substringAfter(':')

// Resolve the extension namespace while preserving the parser's support for legacy GPX prefixes.
private fun Element.isRecordingExtension(): Boolean {
    val prefix = nodeName.substringBefore(':', "")
    val declaration = if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
    val namespace =
        generateSequence(this) { it.parentNode as? Element }
            .firstOrNull { it.hasAttribute(declaration) }
            ?.getAttribute(declaration)
    return namespace == "https://glancemap.app/gpx/extensions/1"
}
