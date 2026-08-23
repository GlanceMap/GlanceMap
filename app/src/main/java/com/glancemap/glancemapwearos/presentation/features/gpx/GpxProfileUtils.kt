package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterConfig
import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterDefaults
import org.mapsforge.core.model.LatLong
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import kotlin.math.abs
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
    val distanceMeters: Double?,
    val elevationGainMeters: Double?,
    val elevationLossMeters: Double?,
    val currentElevationMeters: Double?,
    val currentSpeedMps: Float?,
    val averageSpeedMps: Double?,
    val fastestSpeedMps: Double?,
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
    var currentSpeedMps: Float? = null
    var currentHeartRateBpm: Int? = null
    var currentStepCount: Int? = null
    var currentCadenceSpm: Int? = null
    var currentPowerWatts: Int? = null
    var currentPressureHpa: Double? = null
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
                                currentSpeedMps = null
                                currentHeartRateBpm = null
                                currentStepCount = null
                                currentCadenceSpm = null
                                currentPowerWatts = null
                                currentPressureHpa = null
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
                            "speedMps" -> {
                                if (inGeometryPoint) {
                                    currentSpeedMps = parser.nextText()?.trim()?.toFloatOrNull()
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
                                                hasTimestamp = currentHasTimestamp,
                                                timeMillis = currentTimestampMillis,
                                                accuracyMeters = currentAccuracyMeters,
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
                                currentSpeedMps = null
                                currentHeartRateBpm = null
                                currentStepCount = null
                                currentCadenceSpm = null
                                currentPowerWatts = null
                                currentPressureHpa = null
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

private data class EffectiveElevationFilter(
    val smoothingDistanceMeters: Double,
    val neutralDiffThresholdMeters: Double,
    val trendActivationThresholdMeters: Double,
    val minimumGradePercent: Double,
)

private fun buildCanonicalElevationCumulative(
    points: List<TrackPoint>,
    segmentLengths: DoubleArray,
    cumulativeDistances: DoubleArray,
    elevationFilterConfig: GpxElevationFilterConfig,
): Pair<DoubleArray, DoubleArray> {
    val count = points.size
    val cumAsc = DoubleArray(count)
    val cumDesc = DoubleArray(count)
    if (count <= 1) return cumAsc to cumDesc

    val normalizedElevations =
        normalizeElevations(
            points = points,
            cumulativeDistances = cumulativeDistances,
        ) ?: return cumAsc to cumDesc

    val effectiveFilter =
        resolveEffectiveElevationFilter(
            points = points,
            normalizedElevations = normalizedElevations,
            segmentLengths = segmentLengths,
            cumulativeDistances = cumulativeDistances,
            baseConfig = elevationFilterConfig,
        )

    val smoothedElevations =
        smoothElevationsByDistance(
            points = points,
            elevations = normalizedElevations,
            segmentLengths = segmentLengths,
            smoothingDistanceMeters = effectiveFilter.smoothingDistanceMeters,
        )

    val neutralThresholdMeters = effectiveFilter.neutralDiffThresholdMeters
    val trendActivationThresholdMeters = effectiveFilter.trendActivationThresholdMeters
    val directionActivationThresholdMeters =
        maxOf(neutralThresholdMeters, trendActivationThresholdMeters)

    var ascent = 0.0
    var descent = 0.0
    var pendingAscent = 0.0
    var pendingDescent = 0.0
    var ascentActive = false
    var descentActive = false

    for (index in 1 until count) {
        if (points[index].startsNewSegment) {
            pendingAscent = 0.0
            pendingDescent = 0.0
            ascentActive = false
            descentActive = false
            cumAsc[index] = ascent
            cumDesc[index] = descent
            continue
        }
        val stepMeters = segmentLengths.getOrElse(index - 1) { 0.0 }.coerceAtLeast(0.0)
        val diff =
            applyMinimumGradeGate(
                diffMeters = smoothedElevations[index] - smoothedElevations[index - 1],
                stepMeters = stepMeters,
                minimumGradePercent = effectiveFilter.minimumGradePercent,
            )
        when {
            diff > 0.0 -> {
                if (descentActive) {
                    pendingAscent += diff
                    if (pendingAscent >= directionActivationThresholdMeters) {
                        ascent += pendingAscent
                        pendingAscent = 0.0
                        pendingDescent = 0.0
                        descentActive = false
                        ascentActive = true
                    }
                } else {
                    val recoveredDescent = minOf(diff, pendingDescent)
                    pendingDescent -= recoveredDescent
                    val netRise = diff - recoveredDescent
                    if (ascentActive) {
                        ascent += netRise
                    } else {
                        pendingAscent += netRise
                        if (pendingAscent >= directionActivationThresholdMeters) {
                            ascent += pendingAscent
                            pendingAscent = 0.0
                            ascentActive = true
                        }
                    }
                }
            }

            diff < 0.0 -> {
                val loss = -diff
                if (ascentActive) {
                    pendingDescent += loss
                    if (pendingDescent >= directionActivationThresholdMeters) {
                        descent += pendingDescent
                        pendingAscent = 0.0
                        pendingDescent = 0.0
                        ascentActive = false
                        descentActive = true
                    }
                } else {
                    val recoveredAscent = minOf(loss, pendingAscent)
                    pendingAscent -= recoveredAscent
                    val netLoss = loss - recoveredAscent
                    if (descentActive) {
                        descent += netLoss
                    } else {
                        pendingDescent += netLoss
                        if (pendingDescent >= directionActivationThresholdMeters) {
                            descent += pendingDescent
                            pendingDescent = 0.0
                            descentActive = true
                        }
                    }
                }
            }
        }

        cumAsc[index] = ascent
        cumDesc[index] = descent
    }

    return cumAsc to cumDesc
}

private fun resolveEffectiveElevationFilter(
    points: List<TrackPoint>,
    normalizedElevations: DoubleArray,
    segmentLengths: DoubleArray,
    cumulativeDistances: DoubleArray,
    baseConfig: GpxElevationFilterConfig,
): EffectiveElevationFilter {
    if (!baseConfig.autoAdjustPerGpx) {
        return EffectiveElevationFilter(
            smoothingDistanceMeters = baseConfig.smoothingDistanceMeters.toDouble(),
            neutralDiffThresholdMeters = baseConfig.neutralDiffThresholdMeters.toDouble(),
            trendActivationThresholdMeters = baseConfig.trendActivationThresholdMeters.toDouble(),
            minimumGradePercent = 0.0,
        )
    }
    val totalDistanceMeters = cumulativeDistances.lastOrNull() ?: 0.0
    val totalDistanceKm = totalDistanceMeters / 1000.0
    val reliefMeters =
        (
            (normalizedElevations.maxOrNull() ?: 0.0) -
                (normalizedElevations.minOrNull() ?: 0.0)
        ).coerceAtLeast(0.0)
    val reliefPerKm = if (totalDistanceKm > 0.0) reliefMeters / totalDistanceKm else 0.0
    val coarseHighReliefFactor = resolveCoarseHighReliefFactor(points, reliefPerKm)
    val lowReliefPerKmFactor =
        (
            (LOW_RELIEF_REFERENCE_METERS_PER_KM - reliefPerKm) / LOW_RELIEF_BLEND_RANGE_METERS_PER_KM
        ).coerceIn(0.0, 1.0)
    // Avoid classifying very long mountain traverses as "low relief" just because the route is long.
    val lowReliefFactor = lowReliefPerKmFactor * resolveLowReliefAbsoluteReliefFactor(reliefMeters)
    val recordedLowReliefFactor = resolveRecordedLowReliefFactor(points, lowReliefFactor)
    val editedLowReliefDenseTrackFactor = resolveEditedLowReliefDensityFactor(segmentLengths)
    val editedLowReliefDensityFactor =
        if (recordedLowReliefFactor > 0.0) {
            0.0
        } else {
            lowReliefFactor * editedLowReliefDenseTrackFactor
        }

    return EffectiveElevationFilter(
        smoothingDistanceMeters =
            (
                baseConfig.smoothingDistanceMeters.toDouble() -
                    (editedLowReliefDensityFactor * LOW_RELIEF_SMOOTHING_REDUCTION_METERS) -
                    (recordedLowReliefFactor * RECORDED_LOW_RELIEF_SMOOTHING_REDUCTION_METERS) +
                    (coarseHighReliefFactor * COARSE_HIGH_RELIEF_SMOOTHING_BOOST_METERS)
            ).coerceAtLeast(GpxElevationFilterDefaults.MIN_SMOOTHING_DISTANCE_METERS.toDouble()),
        neutralDiffThresholdMeters = baseConfig.neutralDiffThresholdMeters.toDouble(),
        trendActivationThresholdMeters =
            (
                baseConfig.trendActivationThresholdMeters.toDouble() -
                    (editedLowReliefDensityFactor * LOW_RELIEF_TREND_REDUCTION_METERS) -
                    (recordedLowReliefFactor * RECORDED_LOW_RELIEF_TREND_REDUCTION_METERS) +
                    (coarseHighReliefFactor * COARSE_HIGH_RELIEF_TREND_BOOST_METERS)
            ).coerceAtLeast(
                GpxElevationFilterDefaults.MIN_TREND_ACTIVATION_THRESHOLD_METERS.toDouble(),
            ),
        minimumGradePercent =
            if (recordedLowReliefFactor > 0.0) {
                0.0
            } else {
                lowReliefFactor * (
                    LOW_RELIEF_SPARSE_MIN_GRADE_PERCENT +
                        (
                            editedLowReliefDenseTrackFactor *
                                LOW_RELIEF_DENSE_MIN_GRADE_BOOST_PERCENT
                        )
                )
            },
    )
}

private fun resolveRecordedLowReliefFactor(
    points: List<TrackPoint>,
    lowReliefFactor: Double,
): Double {
    val timestampFraction =
        points.count(TrackPoint::hasTimestamp).toDouble() /
            points.size.coerceAtLeast(1).toDouble()
    return if (timestampFraction >= RECORDED_LOW_RELIEF_MIN_TIMESTAMP_FRACTION) {
        lowReliefFactor
    } else {
        0.0
    }
}

private fun resolveLowReliefAbsoluteReliefFactor(reliefMeters: Double): Double =
    (
        (LOW_RELIEF_ZERO_ABSOLUTE_RELIEF_METERS - reliefMeters) /
            (LOW_RELIEF_ZERO_ABSOLUTE_RELIEF_METERS - LOW_RELIEF_FULL_ABSOLUTE_RELIEF_METERS)
    ).coerceIn(0.0, 1.0)

private fun resolveEditedLowReliefDensityFactor(segmentLengths: DoubleArray): Double {
    if (segmentLengths.isEmpty()) return 0.0
    val sorted = segmentLengths.copyOf().apply { sort() }
    val middleIndex = sorted.size / 2
    val medianSegmentMeters =
        if (sorted.size % 2 == 0) {
            (sorted[middleIndex - 1] + sorted[middleIndex]) / 2.0
        } else {
            sorted[middleIndex]
        }
    return (
        (LOW_RELIEF_SPARSE_TRACK_MEDIAN_SEGMENT_METERS - medianSegmentMeters) /
            (LOW_RELIEF_SPARSE_TRACK_MEDIAN_SEGMENT_METERS - LOW_RELIEF_DENSE_TRACK_MEDIAN_SEGMENT_METERS)
    ).coerceIn(0.0, 1.0)
}

private fun resolveCoarseHighReliefFactor(
    points: List<TrackPoint>,
    reliefPerKm: Double,
): Double {
    val knownElevations = points.mapNotNull(TrackPoint::elevation)
    if (knownElevations.isEmpty()) return 0.0
    val integerLikeFraction =
        knownElevations
            .count { elevation ->
                abs(elevation - kotlin.math.round(elevation)) <= COARSE_ELEVATION_INTEGER_TOLERANCE_METERS
            }.toDouble() / knownElevations.size.toDouble()
    if (integerLikeFraction < COARSE_ELEVATION_MIN_INTEGER_FRACTION) return 0.0
    return (
        (reliefPerKm - COARSE_HIGH_RELIEF_REFERENCE_METERS_PER_KM) /
            COARSE_HIGH_RELIEF_BLEND_RANGE_METERS_PER_KM
    ).coerceIn(0.0, 1.0)
}

private fun applyMinimumGradeGate(
    diffMeters: Double,
    stepMeters: Double,
    minimumGradePercent: Double,
): Double {
    if (diffMeters == 0.0 || stepMeters <= 0.0 || minimumGradePercent <= 0.0) {
        return diffMeters
    }
    val gradePercent = (abs(diffMeters) / stepMeters) * 100.0
    return if (gradePercent < minimumGradePercent) 0.0 else diffMeters
}

private fun normalizeElevations(
    points: List<TrackPoint>,
    cumulativeDistances: DoubleArray,
): DoubleArray? {
    val knownIndices = points.indices.filter { points[it].elevation != null }
    if (knownIndices.isEmpty()) return null

    val normalized = DoubleArray(points.size)
    val firstKnownIndex = knownIndices.first()
    val firstKnownElevation = points[firstKnownIndex].elevation ?: return null
    for (index in 0..firstKnownIndex) {
        normalized[index] = firstKnownElevation
    }

    for (knownIndexPosition in 0 until knownIndices.lastIndex) {
        val startIndex = knownIndices[knownIndexPosition]
        val endIndex = knownIndices[knownIndexPosition + 1]
        val startElevation = points[startIndex].elevation ?: continue
        val endElevation = points[endIndex].elevation ?: continue
        val startDistance = cumulativeDistances.getOrElse(startIndex) { 0.0 }
        val endDistance = cumulativeDistances.getOrElse(endIndex) { startDistance }

        normalized[startIndex] = startElevation
        for (index in startIndex + 1 until endIndex) {
            val currentDistance = cumulativeDistances.getOrElse(index) { startDistance }
            val t =
                if (endDistance > startDistance) {
                    ((currentDistance - startDistance) / (endDistance - startDistance)).coerceIn(0.0, 1.0)
                } else {
                    (index - startIndex).toDouble() / (endIndex - startIndex).toDouble()
                }
            normalized[index] = startElevation + t * (endElevation - startElevation)
        }
        normalized[endIndex] = endElevation
    }

    val lastKnownIndex = knownIndices.last()
    val lastKnownElevation = points[lastKnownIndex].elevation ?: return normalized
    for (index in lastKnownIndex until points.size) {
        normalized[index] = lastKnownElevation
    }

    return normalized
}

private fun smoothElevationsByDistance(
    points: List<TrackPoint>,
    elevations: DoubleArray,
    segmentLengths: DoubleArray,
    smoothingDistanceMeters: Double,
): DoubleArray {
    if (elevations.isEmpty()) return elevations
    val smoothed = DoubleArray(elevations.size)
    smoothed[0] = elevations[0]
    for (index in 1 until elevations.size) {
        if (points.getOrNull(index)?.startsNewSegment == true) {
            smoothed[index] = elevations[index]
            continue
        }
        val stepMeters = segmentLengths.getOrElse(index - 1) { 0.0 }.coerceAtLeast(0.0)
        val alpha =
            (stepMeters / (smoothingDistanceMeters + stepMeters))
                .coerceIn(MIN_ELEVATION_SMOOTHING_ALPHA, 1.0)
        smoothed[index] = smoothed[index - 1] + alpha * (elevations[index] - smoothed[index - 1])
    }
    return smoothed
}

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

private const val MIN_ELEVATION_SMOOTHING_ALPHA = 0.18
private const val LOW_RELIEF_REFERENCE_METERS_PER_KM = 10.0
private const val LOW_RELIEF_BLEND_RANGE_METERS_PER_KM = 4.0
private const val LOW_RELIEF_FULL_ABSOLUTE_RELIEF_METERS = 150.0
private const val LOW_RELIEF_ZERO_ABSOLUTE_RELIEF_METERS = 400.0
private const val LOW_RELIEF_SMOOTHING_REDUCTION_METERS = 1.0
private const val LOW_RELIEF_TREND_REDUCTION_METERS = 0.5
private const val LOW_RELIEF_SPARSE_MIN_GRADE_PERCENT = 1.4
private const val LOW_RELIEF_DENSE_MIN_GRADE_BOOST_PERCENT = 0.8
private const val LOW_RELIEF_DENSE_TRACK_MEDIAN_SEGMENT_METERS = 40.0
private const val LOW_RELIEF_SPARSE_TRACK_MEDIAN_SEGMENT_METERS = 80.0
private const val RECORDED_LOW_RELIEF_MIN_TIMESTAMP_FRACTION = 0.95
private const val RECORDED_LOW_RELIEF_SMOOTHING_REDUCTION_METERS = 1.0
private const val RECORDED_LOW_RELIEF_TREND_REDUCTION_METERS = 2.5
private const val COARSE_ELEVATION_INTEGER_TOLERANCE_METERS = 0.01
private const val COARSE_ELEVATION_MIN_INTEGER_FRACTION = 0.95
private const val COARSE_HIGH_RELIEF_REFERENCE_METERS_PER_KM = 35.0
private const val COARSE_HIGH_RELIEF_BLEND_RANGE_METERS_PER_KM = 15.0
private const val COARSE_HIGH_RELIEF_SMOOTHING_BOOST_METERS = 5.0
private const val COARSE_HIGH_RELIEF_TREND_BOOST_METERS = 0.2
