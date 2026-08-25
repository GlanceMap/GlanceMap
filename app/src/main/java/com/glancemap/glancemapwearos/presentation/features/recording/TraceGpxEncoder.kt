package com.glancemap.glancemapwearos.presentation.features.recording

import java.io.StringWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun encodeRecordedTraceAsGpx(
    title: String,
    points: List<RecordedTracePoint>,
    summary: RecordedTraceSummary? = null,
): ByteArray {
    val writer = StringWriter()
    writer.append("""<?xml version="1.0" encoding="UTF-8"?>""")
    writer.append(
        """<gpx version="1.1" creator="GlanceMap" xmlns="http://www.topografix.com/GPX/1/1" """ +
            """xmlns:gmap="$GLANCEMAP_GPX_EXTENSION_NAMESPACE">""",
    )
    writer.textTag("metadata") {
        textTag("name", title)
        textTag("extensions") {
            textTag("gmap:activityType", "recording")
            summary?.let { writeRecordingSummaryExtensions(it) }
        }
    }
    writer.textTag("trk") {
        textTag("name", title)
        recordedTraceSegments(points).forEach { segment ->
            textTag("trkseg") {
                segment.forEach { point ->
                    writer.append("""<trkpt lat="${formatCoordinate(point.latLong.latitude)}" """)
                    writer.append("""lon="${formatCoordinate(point.latLong.longitude)}">""")
                    point.elevationMeters?.let { elevation ->
                        writer.textTag("ele", formatElevation(elevation))
                    }
                    writer.textTag("time", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(point.timeMillis)))
                    writer.writePointExtensions(point)
                    writer.append("</trkpt>")
                }
            }
        }
    }
    writer.append("</gpx>")
    return writer.toString().toByteArray(Charsets.UTF_8)
}

internal fun recordedTraceSegments(points: List<RecordedTracePoint>): List<List<RecordedTracePoint>> =
    buildList {
        var currentSegment = mutableListOf<RecordedTracePoint>()
        points.forEach { point ->
            if (point.startsNewSegment && currentSegment.isNotEmpty()) {
                add(currentSegment)
                currentSegment = mutableListOf()
            }
            currentSegment += point
        }
        if (currentSegment.isNotEmpty()) {
            add(currentSegment)
        }
    }

data class RecordedTraceSummary(
    val activityProfile: String?,
    val durationSeconds: Double,
    val totalDurationSeconds: Double,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val currentElevationMeters: Double?,
    val currentSpeedMps: Float?,
    val averageSpeedMps: Double?,
    val fastestSpeedMps: Double?,
    val gpsAccuracyMeters: Float?,
    val pointCount: Int,
    val gpsActiveDurationSeconds: Double,
    val recordingGapCount: Int,
    val recordingMaxGapSeconds: Double,
    val caloriesGrossKcal: Double,
    val caloriesActiveKcal: Double,
    val caloriesRestingKcal: Double,
    val calorieModel: String?,
    val cyclingMechanicalKj: Double,
    val cyclingPowerSampleSegments: Int,
    val cyclingPhysicsSegments: Int,
    val heartRateBpm: Int?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val stepCount: Int?,
    val cadenceSpm: Int?,
    val averageCadenceSpm: Int?,
    val maxCadenceSpm: Int?,
    val powerWatts: Int?,
    val averagePowerWatts: Int?,
    val maxPowerWatts: Int?,
    val barometricPressureHpa: Double?,
    val recordingTrackSmoothingMode: String? = null,
    val recordingTrackFilterVersion: Int? = null,
    val recordingElevationFilterVersion: Int? = null,
    val smartElevationPressurePointCount: Long? = null,
    val smartElevationDemAnchorPointCount: Long? = null,
    val smartElevationGpsFallbackPointCount: Long? = null,
)

private fun StringWriter.writeRecordingSummaryExtensions(summary: RecordedTraceSummary) {
    writeRecordingMotionSummary(summary)
    writeRecordingEnergySummary(summary)
    writeRecordingSensorSummary(summary)
}

private fun StringWriter.writeRecordingMotionSummary(summary: RecordedTraceSummary) {
    summary.activityProfile?.takeIf { it.isNotBlank() }?.let {
        textTag("gmap:activityProfile", it)
    }
    summary.recordingTrackSmoothingMode?.takeIf { it.isNotBlank() }?.let {
        textTag("gmap:recordingTrackSmoothingMode", it)
    }
    summary.recordingTrackFilterVersion?.takeIf { it > 0 }?.let {
        textTag("gmap:recordingTrackFilterVersion", it.toString())
    }
    writeRecordingElevationSummary(summary)
    textTag("gmap:durationSeconds", formatDouble(summary.durationSeconds))
    textTag("gmap:totalDurationSeconds", formatDouble(summary.totalDurationSeconds))
    textTag("gmap:distanceMeters", formatDouble(summary.distanceMeters))
    textTag("gmap:elevationGainMeters", formatDouble(summary.elevationGainMeters))
    textTag("gmap:elevationLossMeters", formatDouble(summary.elevationLossMeters))
    summary.currentElevationMeters?.takeIf { it.isFinite() }?.let {
        textTag("gmap:currentElevationMeters", formatDouble(it))
    }
    summary.currentSpeedMps?.takeIf { it.isFinite() && it >= 0f }?.let {
        textTag("gmap:currentSpeedMps", formatFloat(it))
    }
    summary.averageSpeedMps?.takeIf { it.isFinite() && it >= 0.0 }?.let {
        textTag("gmap:averageSpeedMps", formatDouble(it))
    }
    summary.fastestSpeedMps?.takeIf { it.isFinite() && it >= 0.0 }?.let {
        textTag("gmap:fastestSpeedMps", formatDouble(it))
    }
    summary.gpsAccuracyMeters?.takeIf { it.isFinite() && it >= 0f }?.let {
        textTag("gmap:gpsAccuracyMeters", formatFloat(it))
    }
    textTag("gmap:pointCount", summary.pointCount.coerceAtLeast(0).toString())
    textTag("gmap:gpsActiveDurationSeconds", formatDouble(summary.gpsActiveDurationSeconds))
    textTag("gmap:recordingGapCount", summary.recordingGapCount.coerceAtLeast(0).toString())
    textTag("gmap:recordingMaxGapSeconds", formatDouble(summary.recordingMaxGapSeconds))
}

private fun StringWriter.writeRecordingElevationSummary(summary: RecordedTraceSummary) {
    summary.recordingElevationFilterVersion?.takeIf { it > 0 }?.let {
        textTag("gmap:recordingElevationFilterVersion", it.toString())
    }
    summary.smartElevationPressurePointCount?.takeIf { it >= 0L }?.let {
        textTag("gmap:smartElevationPressurePointCount", it.toString())
    }
    summary.smartElevationDemAnchorPointCount?.takeIf { it >= 0L }?.let {
        textTag("gmap:smartElevationDemAnchorPointCount", it.toString())
    }
    summary.smartElevationGpsFallbackPointCount?.takeIf { it >= 0L }?.let {
        textTag("gmap:smartElevationGpsFallbackPointCount", it.toString())
    }
}

private fun StringWriter.writeRecordingEnergySummary(summary: RecordedTraceSummary) {
    textTag("gmap:caloriesGrossKcal", formatDouble(summary.caloriesGrossKcal))
    textTag("gmap:caloriesActiveKcal", formatDouble(summary.caloriesActiveKcal))
    textTag("gmap:caloriesRestingKcal", formatDouble(summary.caloriesRestingKcal))
    summary.calorieModel?.takeIf { it.isNotBlank() }?.let {
        textTag("gmap:calorieModel", it)
    }
    summary.cyclingMechanicalKj.takeIf { it.isFinite() && it > 0.0 }?.let {
        textTag("gmap:cyclingMechanicalKj", formatDouble(it))
    }
    summary.cyclingPowerSampleSegments.takeIf { it > 0 }?.let {
        textTag("gmap:cyclingPowerSampleSegments", it.toString())
    }
    summary.cyclingPhysicsSegments.takeIf { it > 0 }?.let {
        textTag("gmap:cyclingPhysicsSegments", it.toString())
    }
}

private fun StringWriter.writeRecordingSensorSummary(summary: RecordedTraceSummary) {
    summary.heartRateBpm?.takeIf { it > 0 }?.let {
        textTag("gmap:heartRateBpm", it.toString())
    }
    summary.averageHeartRateBpm?.takeIf { it > 0 }?.let {
        textTag("gmap:averageHeartRateBpm", it.toString())
    }
    summary.maxHeartRateBpm?.takeIf { it > 0 }?.let {
        textTag("gmap:maxHeartRateBpm", it.toString())
    }
    summary.stepCount?.takeIf { it >= 0 }?.let {
        textTag("gmap:stepCount", it.toString())
    }
    summary.cadenceSpm?.takeIf { it > 0 }?.let {
        textTag("gmap:cadenceSpm", it.toString())
    }
    summary.averageCadenceSpm?.takeIf { it > 0 }?.let {
        textTag("gmap:averageCadenceSpm", it.toString())
    }
    summary.maxCadenceSpm?.takeIf { it > 0 }?.let {
        textTag("gmap:maxCadenceSpm", it.toString())
    }
    summary.powerWatts?.takeIf { it >= 0 }?.let {
        textTag("gmap:powerWatts", it.toString())
    }
    summary.averagePowerWatts?.takeIf { it >= 0 }?.let {
        textTag("gmap:averagePowerWatts", it.toString())
    }
    summary.maxPowerWatts?.takeIf { it >= 0 }?.let {
        textTag("gmap:maxPowerWatts", it.toString())
    }
    summary.barometricPressureHpa?.takeIf { it.isFinite() && it > 0.0 }?.let {
        textTag("gmap:pressureHpa", formatDouble(it))
    }
}

private fun StringWriter.writePointExtensions(point: RecordedTracePoint) {
    val accuracyMeters = point.accuracyMeters?.takeIf { it.isFinite() && it >= 0f }
    val speedMps = point.speedMps?.takeIf { it.isFinite() && it >= 0f }
    val elevationSource = point.elevationSource?.takeIf { it.isNotBlank() }
    val heartRateBpm = point.heartRateBpm?.takeIf { it > 0 }
    val stepCount = point.stepCount?.takeIf { it >= 0 }
    val cadenceSpm = point.cadenceSpm?.takeIf { it > 0 }
    val powerWatts = point.powerWatts?.takeIf { it >= 0 }
    val pressureHpa = point.barometricPressureHpa?.takeIf { it.isFinite() && it > 0.0 }
    val segmentStartReason =
        point.segmentStartReason
            ?.takeIf { point.startsNewSegment && it.isNotBlank() }
    val extensionValues =
        listOf(
            accuracyMeters,
            speedMps,
            elevationSource,
            heartRateBpm,
            stepCount,
            cadenceSpm,
            powerWatts,
            pressureHpa,
            segmentStartReason,
        )
    if (extensionValues.all { it == null }) {
        return
    }

    textTag("extensions") {
        accuracyMeters?.let {
            textTag("gmap:accuracyMeters", formatFloat(it))
        }
        speedMps?.let {
            textTag("gmap:speedMps", formatFloat(it))
        }
        elevationSource?.let {
            textTag("gmap:elevationSource", it)
        }
        heartRateBpm?.let {
            textTag("gmap:heartRateBpm", it.toString())
        }
        stepCount?.let {
            textTag("gmap:stepCount", it.toString())
        }
        cadenceSpm?.let {
            textTag("gmap:cadenceSpm", it.toString())
        }
        powerWatts?.let {
            textTag("gmap:powerWatts", it.toString())
        }
        pressureHpa?.let {
            textTag("gmap:pressureHpa", formatDouble(it))
        }
        segmentStartReason?.let {
            textTag("gmap:segmentStartReason", it)
        }
    }
}

private fun StringWriter.textTag(
    tagName: String,
    value: String,
) {
    append("<")
    append(tagName)
    append(">")
    append(escapeXmlText(value))
    append("</")
    append(tagName)
    append(">")
}

private fun StringWriter.textTag(
    tagName: String,
    content: StringWriter.() -> Unit,
) {
    append("<")
    append(tagName)
    append(">")
    content()
    append("</")
    append(tagName)
    append(">")
}

internal fun buildRecordingFileName(
    startedAtMillis: Long,
    endedAtMillis: Long,
): String = buildRecordingFileNameFromTitle(buildRecordingTitle(startedAtMillis, endedAtMillis))

internal fun buildRecordingFileNameFromTitle(title: String): String = "${sanitizeRecordingFileStem(title)}.gpx"

internal fun buildRecordingTitle(
    startedAtMillis: Long,
    endedAtMillis: Long,
): String =
    "${RECORDING_TITLE_DATE_FORMAT.format(Instant.ofEpochMilli(startedAtMillis))} " +
        "${RECORDING_TITLE_TIME_FORMAT.format(Instant.ofEpochMilli(startedAtMillis))} " +
        RECORDING_TITLE_TIME_FORMAT.format(Instant.ofEpochMilli(endedAtMillis))

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.8f", value)

private fun formatElevation(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatFloat(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun formatDouble(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun escapeXmlText(value: String): String =
    buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }

private fun sanitizeRecordingFileStem(input: String): String =
    input
        .replace(Regex("\\.gpx$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { "Recording" }

private val RECORDING_TITLE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter
        .ofPattern("HH:mm")
        .withZone(java.time.ZoneId.systemDefault())

private val RECORDING_TITLE_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter
        .ofPattern("yyyy-MM-dd")
        .withZone(java.time.ZoneId.systemDefault())

private const val GLANCEMAP_GPX_EXTENSION_NAMESPACE = "https://glancemap.app/gpx/extensions/1"
