package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterConfig
import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterDefaults
import com.glancemap.trailcore.recording.RecordingElevationProfileConfig
import com.glancemap.trailcore.recording.RecordingElevationProfilePoint
import com.glancemap.trailcore.recording.buildRecordingElevationProfileCumulative
import org.mapsforge.core.model.LatLong
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class FileSig(
    val lastModified: Long,
    val length: Long,
)

internal data class TrackProfile(
    val sig: FileSig,
    val elevationFilterConfig: GpxElevationFilterConfig,
    val points: List<TrackPoint>,
    val segLen: DoubleArray,
    val cumDist: DoubleArray,
    val cumAscent: DoubleArray,
    val cumDescent: DoubleArray,
)

internal data class ParsedGpxData(
    val title: String?,
    val points: List<TrackPoint>,
    val totalDistance: Double,
    val isActivity: Boolean = false,
    val activityDurationSec: Double? = null,
    val activitySummary: GpxActivitySummary? = null,
)

internal data class GpxActivitySummary(
    val activityProfile: String?,
    val durationSeconds: Double?,
    val totalDurationSeconds: Double?,
    // Original recording distance; independent of the exported GPX geometry.
    val distanceMeters: Double?,
    val elevationGainMeters: Double?,
    val elevationLossMeters: Double?,
    val currentElevationMeters: Double?,
    val currentSpeedMps: Float?,
    val averageSpeedMps: Double?,
    val fastestSpeedMps: Double?,
    val fastestSpeedMethod: String?,
    val gpsAccuracyMeters: Float?,
    val pointCount: Int?,
    val gpsActiveDurationSeconds: Double?,
    val recordingGapCount: Int?,
    val recordingMaxGapSeconds: Double?,
    val caloriesGrossKcal: Double?,
    val caloriesActiveKcal: Double?,
    val caloriesRestingKcal: Double?,
    val calorieModel: String?,
    val cyclingMechanicalKj: Double?,
    val cyclingPowerSampleSegments: Int?,
    val cyclingPhysicsSegments: Int?,
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
)

internal fun resolveGpxDisplayDistance(
    profileDistance: Double,
    parsedDistance: Double?,
    activityDistance: Double?,
): Double =
    profileDistance.takeIf { it.isFinite() && it > 0.0 }
        ?: parsedDistance?.takeIf { it.isFinite() && it > 0.0 }
        ?: activityDistance?.takeIf { it.isFinite() && it > 0.0 }
        ?: 0.0

private val B_ROUTER_DISPLAY_REGEX = Regex("brouter", RegexOption.IGNORE_CASE)

internal fun sigOf(file: File): FileSig =
    FileSig(
        lastModified = file.lastModified(),
        length = file.length(),
    )

internal fun <K, V> LinkedHashMap<K, V>.trimTo(max: Int) {
    while (size > max) {
        val it = entries.iterator()
        if (!it.hasNext()) break
        it.next()
        it.remove()
    }
}

internal fun buildProfile(
    sig: FileSig,
    pts: List<TrackPoint>,
    elevationFilterConfig: GpxElevationFilterConfig = GpxElevationFilterDefaults.defaultConfig(),
): TrackProfile {
    val n = pts.size
    val segLen = DoubleArray((n - 1).coerceAtLeast(0))
    val cumDist = DoubleArray(n)

    if (n <= 1) {
        val cumAsc = DoubleArray(n)
        val cumDesc = DoubleArray(n)
        return TrackProfile(sig, elevationFilterConfig, pts, segLen, cumDist, cumAsc, cumDesc)
    }

    var dist = 0.0

    cumDist[0] = 0.0

    for (i in 0 until n - 1) {
        val a = pts[i]
        val b = pts[i + 1]

        val d =
            if (b.startsNewSegment) {
                0.0
            } else {
                haversine(
                    a.latLong.latitude,
                    a.latLong.longitude,
                    b.latLong.latitude,
                    b.latLong.longitude,
                )
            }

        segLen[i] = d
        dist += d
        cumDist[i + 1] = dist
    }

    val (cumAsc, cumDesc) =
        buildCanonicalElevationCumulative(
            points = pts,
            segmentLengths = segLen,
            cumulativeDistances = cumDist,
            elevationFilterConfig = elevationFilterConfig,
        )

    return TrackProfile(sig, elevationFilterConfig, pts, segLen, cumDist, cumAsc, cumDesc)
}

internal fun reverseTrackPointsForProfile(points: List<TrackPoint>): List<TrackPoint> =
    points.asReversed().mapIndexed { reversedIndex, point ->
        val originalIndex = points.lastIndex - reversedIndex
        point.copy(
            startsNewSegment =
                reversedIndex == 0 ||
                    points.getOrNull(originalIndex + 1)?.startsNewSegment == true,
        )
    }

internal fun readBestGpxTitle(file: File): String? = parseGpxData(file).title

internal fun parseGpxPoints(file: File): List<TrackPoint> = parseGpxData(file).points

internal fun normalizeUserFacingGpxText(value: String?): String? =
    value
        ?.takeIf { it.isNotBlank() }
        ?.replace(B_ROUTER_DISPLAY_REGEX, "BRouter")

internal fun parseGpxData(file: File): ParsedGpxData {
    var trkName: String? = null
    var routeName: String? = null
    var metaName: String? = null
    val trackPoints = mutableListOf<TrackPoint>()
    val routePoints = mutableListOf<TrackPoint>()
    var trackDistance = 0.0
    var routeDistance = 0.0
    var lastTrackPoint: LatLong? = null
    var lastRoutePoint: LatLong? = null

    var inTrk = false
    var inRoute = false
    var inMetadata = false
    var inMetadataExtensions = false
    var trkDepth = -1
    var routeDepth = -1
    var metadataDepth = -1
    var metadataExtensionsDepth = -1
    var isActivity = false
    var summaryActivityProfile: String? = null
    var firstTimestampMillis: Long? = null
    var lastTimestampMillis: Long? = null
    var summaryDurationSeconds: Double? = null
    var summaryTotalDurationSeconds: Double? = null
    var summaryDistanceMeters: Double? = null
    var summaryElevationGainMeters: Double? = null
    var summaryElevationLossMeters: Double? = null
    var summaryCurrentElevationMeters: Double? = null
    var summaryCurrentSpeedMps: Float? = null
    var summaryAverageSpeedMps: Double? = null
    var summaryFastestSpeedMps: Double? = null
    var summaryFastestSpeedMethod: String? = null
    var summaryGpsAccuracyMeters: Float? = null
    var summaryPointCount: Int? = null
    var summaryGpsActiveDurationSeconds: Double? = null
    var summaryRecordingGapCount: Int? = null
    var summaryRecordingMaxGapSeconds: Double? = null
    var summaryCaloriesGrossKcal: Double? = null
    var summaryCaloriesActiveKcal: Double? = null
    var summaryCaloriesRestingKcal: Double? = null
    var summaryCalorieModel: String? = null
    var summaryCyclingMechanicalKj: Double? = null
    var summaryCyclingPowerSampleSegments: Int? = null
    var summaryCyclingPhysicsSegments: Int? = null
    var summaryHeartRateBpm: Int? = null
    var summaryAverageHeartRateBpm: Int? = null
    var summaryMaxHeartRateBpm: Int? = null
    var summaryStepCount: Int? = null
    var summaryCadenceSpm: Int? = null
    var summaryAverageCadenceSpm: Int? = null
    var summaryMaxCadenceSpm: Int? = null
    var summaryPowerWatts: Int? = null
    var summaryAveragePowerWatts: Int? = null
    var summaryMaxPowerWatts: Int? = null
    var summaryPressureHpa: Double? = null

    var inGeometryPoint = false
    var currentPointIsTrack = false
    var currentLat: Double? = null
    var currentLon: Double? = null
    var currentElevation: Double? = null
    var currentHasTimestamp = false
    var currentTimestampMillis: Long? = null
    var currentAccuracyMeters: Float? = null
    var currentEffectiveAccuracyMeters: Float? = null
    var currentAccuracyInterpretation: String? = null
    var currentSpeedMps: Float? = null
    var currentHeartRateBpm: Int? = null
    var currentStepCount: Int? = null
    var currentCadenceSpm: Int? = null
    var currentPowerWatts: Int? = null
    var currentPressureHpa: Double? = null
    var currentSegmentStartReason: String? = null
    var currentDesc: String? = null
    var currentSym: String? = null
    var currentBrouterVoiceHint: String? = null
    var currentStartsNewSegment = false
    var nextTrackPointStartsNewSegment = false
    var nextRoutePointStartsNewSegment = false

    return try {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        FileInputStream(file).use { input ->
            parser.setInput(input, "UTF-8")

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name
                        when (tagName.localXmlName()) {
                            "trk" -> {
                                if (trackPoints.isNotEmpty()) nextTrackPointStartsNewSegment = true
                                inTrk = true
                                trkDepth = parser.depth
                            }
                            "trkseg" -> {
                                if (inTrk && trackPoints.isNotEmpty()) nextTrackPointStartsNewSegment = true
                            }
                            "rte" -> {
                                if (routePoints.isNotEmpty()) nextRoutePointStartsNewSegment = true
                                inRoute = true
                                routeDepth = parser.depth
                            }
                            "metadata" -> {
                                inMetadata = true
                                metadataDepth = parser.depth
                            }
                            "extensions" -> {
                                if (inMetadata && parser.depth == metadataDepth + 1) {
                                    inMetadataExtensions = true
                                    metadataExtensionsDepth = parser.depth
                                }
                            }
                            "activityType" -> {
                                if (inMetadataExtensions) {
                                    isActivity =
                                        parser
                                            .nextText()
                                            ?.trim()
                                            ?.equals("recording", ignoreCase = true) == true
                                }
                            }
                            "activityProfile" -> {
                                if (inMetadataExtensions) {
                                    summaryActivityProfile = parser.nextText()?.trim()?.takeIf { it.isNotBlank() }
                                }
                            }
                            "durationSeconds" -> {
                                if (inMetadataExtensions) summaryDurationSeconds = parser.nextTextDouble()
                            }
                            "totalDurationSeconds" -> {
                                if (inMetadataExtensions) summaryTotalDurationSeconds = parser.nextTextDouble()
                            }
                            "distanceMeters" -> {
                                if (inMetadataExtensions) summaryDistanceMeters = parser.nextTextDouble()
                            }
                            "elevationGainMeters" -> {
                                if (inMetadataExtensions) summaryElevationGainMeters = parser.nextTextDouble()
                            }
                            "elevationLossMeters" -> {
                                if (inMetadataExtensions) summaryElevationLossMeters = parser.nextTextDouble()
                            }
                            "currentElevationMeters" -> {
                                if (inMetadataExtensions) summaryCurrentElevationMeters = parser.nextTextDouble()
                            }
                            "currentSpeedMps" -> {
                                if (inMetadataExtensions) summaryCurrentSpeedMps = parser.nextTextFloat()
                            }
                            "averageSpeedMps" -> {
                                if (inMetadataExtensions) summaryAverageSpeedMps = parser.nextTextDouble()
                            }
                            "fastestSpeedMps" -> {
                                if (inMetadataExtensions) summaryFastestSpeedMps = parser.nextTextDouble()
                            }
                            "fastestSpeedMethod" -> {
                                if (inMetadataExtensions) {
                                    summaryFastestSpeedMethod = parser.nextText()?.trim()?.takeIf { it.isNotBlank() }
                                }
                            }
                            "gpsAccuracyMeters" -> {
                                if (inMetadataExtensions) summaryGpsAccuracyMeters = parser.nextTextFloat()
                            }
                            "pointCount" -> {
                                if (inMetadataExtensions) summaryPointCount = parser.nextTextInt()
                            }
                            "gpsActiveDurationSeconds" -> {
                                if (inMetadataExtensions) summaryGpsActiveDurationSeconds = parser.nextTextDouble()
                            }
                            "recordingGapCount" -> {
                                if (inMetadataExtensions) summaryRecordingGapCount = parser.nextTextInt()
                            }
                            "recordingMaxGapSeconds" -> {
                                if (inMetadataExtensions) summaryRecordingMaxGapSeconds = parser.nextTextDouble()
                            }
                            "caloriesGrossKcal" -> {
                                if (inMetadataExtensions) summaryCaloriesGrossKcal = parser.nextTextDouble()
                            }
                            "caloriesActiveKcal" -> {
                                if (inMetadataExtensions) summaryCaloriesActiveKcal = parser.nextTextDouble()
                            }
                            "caloriesRestingKcal" -> {
                                if (inMetadataExtensions) summaryCaloriesRestingKcal = parser.nextTextDouble()
                            }
                            "calorieModel" -> {
                                if (inMetadataExtensions) {
                                    summaryCalorieModel = parser.nextText()?.trim()?.takeIf { it.isNotBlank() }
                                }
                            }
                            "cyclingMechanicalKj" -> {
                                if (inMetadataExtensions) summaryCyclingMechanicalKj = parser.nextTextDouble()
                            }
                            "cyclingPowerSampleSegments" -> {
                                if (inMetadataExtensions) summaryCyclingPowerSampleSegments = parser.nextTextInt()
                            }
                            "cyclingPhysicsSegments" -> {
                                if (inMetadataExtensions) summaryCyclingPhysicsSegments = parser.nextTextInt()
                            }
                            "name" -> {
                                val depth = parser.depth
                                val text = parser.nextText()?.trim()?.takeIf { it.isNotBlank() }
                                if (text != null) {
                                    if (inTrk && trkName == null && depth == trkDepth + 1) {
                                        trkName = text
                                    }
                                    if (inRoute && routeName == null && depth == routeDepth + 1) {
                                        routeName = text
                                    }
                                    if (inMetadata && metaName == null && depth == metadataDepth + 1) {
                                        metaName = text
                                    }
                                }
                            }
                            "trkpt", "rtept" -> {
                                currentPointIsTrack = tagName.localXmlName() == "trkpt"
                                inGeometryPoint = true
                                currentStartsNewSegment =
                                    if (currentPointIsTrack) {
                                        nextTrackPointStartsNewSegment.also {
                                            nextTrackPointStartsNewSegment = false
                                        }
                                    } else {
                                        nextRoutePointStartsNewSegment.also {
                                            nextRoutePointStartsNewSegment = false
                                        }
                                    }
                                currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                currentElevation = null
                                currentHasTimestamp = false
                                currentTimestampMillis = null
                                currentAccuracyMeters = null
                                currentEffectiveAccuracyMeters = null
                                currentAccuracyInterpretation = null
                                currentSpeedMps = null
                                currentHeartRateBpm = null
                                currentStepCount = null
                                currentCadenceSpm = null
                                currentPowerWatts = null
                                currentPressureHpa = null
                                currentSegmentStartReason = null
                                currentDesc = null
                                currentSym = null
                                currentBrouterVoiceHint = null
                            }
                            "ele" -> {
                                if (inGeometryPoint) {
                                    currentElevation = parser.nextText()?.trim()?.toDoubleOrNull()
                                }
                            }
                            "desc" -> {
                                if (inGeometryPoint) {
                                    currentDesc = parser.nextText()?.trim()?.takeIf { it.isNotBlank() }
                                }
                            }
                            "sym" -> {
                                if (inGeometryPoint) {
                                    currentSym = parser.nextText()?.trim()?.takeIf { it.isNotBlank() }
                                }
                            }
                            "voicehint" -> {
                                val isBrouterVoiceHint =
                                    parser.namespace?.contains("brouter", ignoreCase = true) == true ||
                                        parser.prefix?.equals("brouter", ignoreCase = true) == true ||
                                        tagName?.startsWith("brouter:", ignoreCase = true) == true
                                if (inGeometryPoint && isBrouterVoiceHint) {
                                    currentBrouterVoiceHint =
                                        parser.nextText()?.trim()?.takeIf { it.isNotBlank() }
                                }
                            }
                            "time" -> {
                                if (inGeometryPoint) {
                                    val timeText = parser.nextText()?.trim()
                                    currentHasTimestamp = !timeText.isNullOrBlank()
                                    currentTimestampMillis =
                                        timeText
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { value ->
                                                runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                                            }
                                }
                            }
                            "accuracyMeters" -> {
                                if (inGeometryPoint) {
                                    currentAccuracyMeters = parser.nextText()?.trim()?.toFloatOrNull()
                                }
                            }
                            "effectiveAccuracyMeters" -> {
                                if (inGeometryPoint) {
                                    currentEffectiveAccuracyMeters = parser.nextText()?.trim()?.toFloatOrNull()
                                }
                            }
                            "accuracyInterpretation" -> {
                                if (inGeometryPoint) {
                                    currentAccuracyInterpretation =
                                        parser.nextText()?.trim()?.takeIf { it.isNotBlank() }
                                }
                            }
                            "speedMps" -> {
                                if (inGeometryPoint) {
                                    currentSpeedMps = parser.nextText()?.trim()?.toFloatOrNull()
                                }
                            }
                            "segmentStartReason" -> {
                                if (inGeometryPoint) {
                                    currentSegmentStartReason = parser.nextText()?.trim()?.takeIf { it.isNotBlank() }
                                }
                            }
                            "heartRateBpm" -> {
                                if (inMetadataExtensions) {
                                    summaryHeartRateBpm = parser.nextTextInt()
                                } else if (inGeometryPoint) {
                                    currentHeartRateBpm = parser.nextText()?.trim()?.toIntOrNull()
                                }
                            }
                            "averageHeartRateBpm" -> {
                                if (inMetadataExtensions) summaryAverageHeartRateBpm = parser.nextTextInt()
                            }
                            "maxHeartRateBpm" -> {
                                if (inMetadataExtensions) summaryMaxHeartRateBpm = parser.nextTextInt()
                            }
                            "stepCount" -> {
                                if (inMetadataExtensions) {
                                    summaryStepCount = parser.nextTextInt()
                                } else if (inGeometryPoint) {
                                    currentStepCount = parser.nextText()?.trim()?.toIntOrNull()
                                }
                            }
                            "cadenceSpm" -> {
                                if (inMetadataExtensions) {
                                    summaryCadenceSpm = parser.nextTextInt()
                                } else if (inGeometryPoint) {
                                    currentCadenceSpm = parser.nextText()?.trim()?.toIntOrNull()
                                }
                            }
                            "averageCadenceSpm" -> {
                                if (inMetadataExtensions) summaryAverageCadenceSpm = parser.nextTextInt()
                            }
                            "maxCadenceSpm" -> {
                                if (inMetadataExtensions) summaryMaxCadenceSpm = parser.nextTextInt()
                            }
                            "powerWatts" -> {
                                if (inMetadataExtensions) {
                                    summaryPowerWatts = parser.nextTextInt()
                                } else if (inGeometryPoint) {
                                    currentPowerWatts = parser.nextText()?.trim()?.toIntOrNull()
                                }
                            }
                            "averagePowerWatts" -> {
                                if (inMetadataExtensions) summaryAveragePowerWatts = parser.nextTextInt()
                            }
                            "maxPowerWatts" -> {
                                if (inMetadataExtensions) summaryMaxPowerWatts = parser.nextTextInt()
                            }
                            "pressureHpa" -> {
                                if (inMetadataExtensions) {
                                    summaryPressureHpa = parser.nextTextDouble()
                                } else if (inGeometryPoint) {
                                    currentPressureHpa = parser.nextText()?.trim()?.toDoubleOrNull()
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name.localXmlName()) {
                            "trk" -> inTrk = false
                            "rte" -> inRoute = false
                            "metadata" -> inMetadata = false
                            "extensions" -> {
                                if (inMetadataExtensions && parser.depth == metadataExtensionsDepth) {
                                    inMetadataExtensions = false
                                }
                            }
                            "trkpt", "rtept" -> {
                                val closingTrackPoint = parser.name.localXmlName() == "trkpt"
                                if (inGeometryPoint && currentPointIsTrack == closingTrackPoint) {
                                    val lat = currentLat
                                    val lon = currentLon
                                    if (lat != null && lon != null) {
                                        val latLong = LatLong(lat, lon)
                                        val point =
                                            TrackPoint(
                                                latLong = latLong,
                                                elevation = currentElevation,
                                                startsNewSegment = currentStartsNewSegment,
                                                segmentStartReason = currentSegmentStartReason,
                                                hasTimestamp = currentHasTimestamp,
                                                timeMillis = currentTimestampMillis,
                                                accuracyMeters = currentAccuracyMeters,
                                                effectiveAccuracyMeters = currentEffectiveAccuracyMeters,
                                                accuracyInterpretation = currentAccuracyInterpretation,
                                                speedMps = currentSpeedMps,
                                                heartRateBpm = currentHeartRateBpm,
                                                stepCount = currentStepCount,
                                                cadenceSpm = currentCadenceSpm,
                                                powerWatts = currentPowerWatts,
                                                barometricPressureHpa = currentPressureHpa,
                                                guidanceHint =
                                                    parseGpxGuidanceHint(
                                                        desc = currentDesc,
                                                        sym = currentSym,
                                                        brouterVoiceHint = currentBrouterVoiceHint,
                                                    ),
                                            )

                                        if (currentPointIsTrack) {
                                            trackPoints += point
                                            lastTrackPoint?.takeUnless { currentStartsNewSegment }?.let { previous ->
                                                trackDistance +=
                                                    haversine(
                                                        previous.latitude,
                                                        previous.longitude,
                                                        latLong.latitude,
                                                        latLong.longitude,
                                                    )
                                            }
                                            lastTrackPoint = latLong
                                            currentTimestampMillis?.let { timestampMillis ->
                                                if (firstTimestampMillis == null) {
                                                    firstTimestampMillis = timestampMillis
                                                }
                                                lastTimestampMillis = timestampMillis
                                            }
                                        } else {
                                            routePoints += point
                                            lastRoutePoint?.takeUnless { currentStartsNewSegment }?.let { previous ->
                                                routeDistance +=
                                                    haversine(
                                                        previous.latitude,
                                                        previous.longitude,
                                                        latLong.latitude,
                                                        latLong.longitude,
                                                    )
                                            }
                                            lastRoutePoint = latLong
                                        }
                                    }
                                }

                                inGeometryPoint = false
                                currentPointIsTrack = false
                                currentLat = null
                                currentLon = null
                                currentElevation = null
                                currentHasTimestamp = false
                                currentTimestampMillis = null
                                currentAccuracyMeters = null
                                currentEffectiveAccuracyMeters = null
                                currentAccuracyInterpretation = null
                                currentSpeedMps = null
                                currentHeartRateBpm = null
                                currentStepCount = null
                                currentCadenceSpm = null
                                currentPowerWatts = null
                                currentPressureHpa = null
                                currentSegmentStartReason = null
                                currentDesc = null
                                currentSym = null
                                currentBrouterVoiceHint = null
                                currentStartsNewSegment = false
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }

        val selectedPoints = if (trackPoints.isNotEmpty()) trackPoints else routePoints
        val selectedDistance = if (trackPoints.isNotEmpty()) trackDistance else routeDistance

        ParsedGpxData(
            title = normalizeUserFacingGpxText(trkName ?: routeName ?: metaName),
            points = selectedPoints,
            totalDistance = selectedDistance,
            isActivity = isActivity || file.name.startsWith("Recording-", ignoreCase = true),
            activityDurationSec =
                firstTimestampMillis?.let { first ->
                    lastTimestampMillis
                        ?.let { last -> ((last - first).coerceAtLeast(0L) / 1000.0) }
                        ?.takeIf { it > 0.0 }
                },
            activitySummary =
                buildGpxActivitySummary(
                    activityProfile = summaryActivityProfile,
                    durationSeconds = summaryDurationSeconds,
                    totalDurationSeconds = summaryTotalDurationSeconds,
                    distanceMeters = summaryDistanceMeters,
                    elevationGainMeters = summaryElevationGainMeters,
                    elevationLossMeters = summaryElevationLossMeters,
                    currentElevationMeters = summaryCurrentElevationMeters,
                    currentSpeedMps = summaryCurrentSpeedMps,
                    averageSpeedMps = summaryAverageSpeedMps,
                    fastestSpeedMps = summaryFastestSpeedMps,
                    fastestSpeedMethod = summaryFastestSpeedMethod,
                    gpsAccuracyMeters = summaryGpsAccuracyMeters,
                    pointCount = summaryPointCount,
                    gpsActiveDurationSeconds = summaryGpsActiveDurationSeconds,
                    recordingGapCount = summaryRecordingGapCount,
                    recordingMaxGapSeconds = summaryRecordingMaxGapSeconds,
                    caloriesGrossKcal = summaryCaloriesGrossKcal,
                    caloriesActiveKcal = summaryCaloriesActiveKcal,
                    caloriesRestingKcal = summaryCaloriesRestingKcal,
                    calorieModel = summaryCalorieModel,
                    cyclingMechanicalKj = summaryCyclingMechanicalKj,
                    cyclingPowerSampleSegments = summaryCyclingPowerSampleSegments,
                    cyclingPhysicsSegments = summaryCyclingPhysicsSegments,
                    heartRateBpm = summaryHeartRateBpm,
                    averageHeartRateBpm = summaryAverageHeartRateBpm,
                    maxHeartRateBpm = summaryMaxHeartRateBpm,
                    stepCount = summaryStepCount,
                    cadenceSpm = summaryCadenceSpm,
                    averageCadenceSpm = summaryAverageCadenceSpm,
                    maxCadenceSpm = summaryMaxCadenceSpm,
                    powerWatts = summaryPowerWatts,
                    averagePowerWatts = summaryAveragePowerWatts,
                    maxPowerWatts = summaryMaxPowerWatts,
                    barometricPressureHpa = summaryPressureHpa,
                ),
        )
    } catch (_: Exception) {
        ParsedGpxData(
            title = null,
            points = emptyList(),
            totalDistance = 0.0,
            isActivity = file.name.startsWith("Recording-", ignoreCase = true),
            activityDurationSec = null,
            activitySummary = null,
        )
    }
}

@Suppress("LongParameterList") // Parameters map one-to-one to optional GPX extension fields parsed above.
private fun buildGpxActivitySummary(
    activityProfile: String?,
    durationSeconds: Double?,
    totalDurationSeconds: Double?,
    distanceMeters: Double?,
    elevationGainMeters: Double?,
    elevationLossMeters: Double?,
    currentElevationMeters: Double?,
    currentSpeedMps: Float?,
    averageSpeedMps: Double?,
    fastestSpeedMps: Double?,
    fastestSpeedMethod: String?,
    gpsAccuracyMeters: Float?,
    pointCount: Int?,
    gpsActiveDurationSeconds: Double?,
    recordingGapCount: Int?,
    recordingMaxGapSeconds: Double?,
    caloriesGrossKcal: Double?,
    caloriesActiveKcal: Double?,
    caloriesRestingKcal: Double?,
    calorieModel: String?,
    cyclingMechanicalKj: Double?,
    cyclingPowerSampleSegments: Int?,
    cyclingPhysicsSegments: Int?,
    heartRateBpm: Int?,
    averageHeartRateBpm: Int?,
    maxHeartRateBpm: Int?,
    stepCount: Int?,
    cadenceSpm: Int?,
    averageCadenceSpm: Int?,
    maxCadenceSpm: Int?,
    powerWatts: Int?,
    averagePowerWatts: Int?,
    maxPowerWatts: Int?,
    barometricPressureHpa: Double?,
): GpxActivitySummary? {
    if (
        durationSeconds == null &&
        distanceMeters == null &&
        elevationGainMeters == null &&
        elevationLossMeters == null &&
        caloriesGrossKcal == null
    ) {
        return null
    }
    return GpxActivitySummary(
        activityProfile = activityProfile,
        durationSeconds = durationSeconds,
        totalDurationSeconds = totalDurationSeconds,
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        currentElevationMeters = currentElevationMeters,
        currentSpeedMps = currentSpeedMps,
        averageSpeedMps = averageSpeedMps,
        fastestSpeedMps = fastestSpeedMps,
        fastestSpeedMethod = fastestSpeedMethod,
        gpsAccuracyMeters = gpsAccuracyMeters,
        pointCount = pointCount,
        gpsActiveDurationSeconds = gpsActiveDurationSeconds,
        recordingGapCount = recordingGapCount,
        recordingMaxGapSeconds = recordingMaxGapSeconds,
        caloriesGrossKcal = caloriesGrossKcal,
        caloriesActiveKcal = caloriesActiveKcal,
        caloriesRestingKcal = caloriesRestingKcal,
        calorieModel = calorieModel,
        cyclingMechanicalKj = cyclingMechanicalKj,
        cyclingPowerSampleSegments = cyclingPowerSampleSegments,
        cyclingPhysicsSegments = cyclingPhysicsSegments,
        heartRateBpm = heartRateBpm,
        averageHeartRateBpm = averageHeartRateBpm,
        maxHeartRateBpm = maxHeartRateBpm,
        stepCount = stepCount,
        cadenceSpm = cadenceSpm,
        averageCadenceSpm = averageCadenceSpm,
        maxCadenceSpm = maxCadenceSpm,
        powerWatts = powerWatts,
        averagePowerWatts = averagePowerWatts,
        maxPowerWatts = maxPowerWatts,
        barometricPressureHpa = barometricPressureHpa,
    )
}

private fun XmlPullParser.nextTextDouble(): Double? =
    nextText()
        ?.trim()
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }

private fun XmlPullParser.nextTextFloat(): Float? =
    nextText()
        ?.trim()
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() }

private fun XmlPullParser.nextTextInt(): Int? = nextText()?.trim()?.toIntOrNull()

private fun String?.localXmlName(): String = this?.substringAfter(':').orEmpty()

private fun parseGpxGuidanceHint(
    desc: String?,
    sym: String?,
    brouterVoiceHint: String?,
): GpxGuidanceHint? {
    val brouterCommand = brouterVoiceHint?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
    if (brouterCommand != null) {
        return GpxGuidanceHint(
            commandCode = brouterCommand,
            message = desc?.guidanceHintMessage(),
            source = GpxGuidanceHintSource.BROUTER,
        )
    }

    val symbolCommand =
        sym
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it.equals("pass_place", ignoreCase = true) }
    if (symbolCommand != null) {
        return GpxGuidanceHint(
            commandCode = symbolCommand,
            message = desc?.guidanceHintMessage(),
            source = GpxGuidanceHintSource.GPX_SYMBOL,
        )
    }
    return null
}

private fun String.guidanceHintMessage(): String? =
    trim()
        .takeIf { it.isNotBlank() }
        ?.takeUnless { it.equals("start", ignoreCase = true) || it.equals("end", ignoreCase = true) }

internal val TrackProfile.totalDistance: Double
    get() = cumDist.lastOrNull() ?: 0.0

internal val TrackProfile.totalAscent: Double
    get() = cumAscent.lastOrNull() ?: 0.0

internal val TrackProfile.totalDescent: Double
    get() = cumDescent.lastOrNull() ?: 0.0

private fun buildCanonicalElevationCumulative(
    points: List<TrackPoint>,
    segmentLengths: DoubleArray,
    cumulativeDistances: DoubleArray,
    elevationFilterConfig: GpxElevationFilterConfig,
): Pair<DoubleArray, DoubleArray> =
    buildRecordingElevationProfileCumulative(
        points = points.map { RecordingElevationProfilePoint(it.elevation, it.startsNewSegment, it.hasTimestamp) },
        segmentLengths = segmentLengths,
        cumulativeDistances = cumulativeDistances,
        elevationFilterConfig =
            RecordingElevationProfileConfig(
                smoothingDistanceMeters = elevationFilterConfig.smoothingDistanceMeters,
                neutralDiffThresholdMeters = elevationFilterConfig.neutralDiffThresholdMeters,
                trendActivationThresholdMeters = elevationFilterConfig.trendActivationThresholdMeters,
                autoAdjustPerGpx = elevationFilterConfig.autoAdjustPerGpx,
            ),
    )

internal fun haversine(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val radiusMeters = 6371e3
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)

    val a =
        sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)

    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return radiusMeters * c
}
