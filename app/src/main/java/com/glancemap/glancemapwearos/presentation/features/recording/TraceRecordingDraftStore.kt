package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.LatLong
import java.io.File

class TraceRecordingDraftStore(
    context: Context,
) {
    private val draftDir: File = context.getDir("recording_drafts", Context.MODE_PRIVATE)
    private val metadataFile = File(draftDir, "current.json")
    private val metadataTempFile = File(draftDir, "current.json.tmp")
    private val gpxFile = File(draftDir, "current.gpx")
    private val gpxTempFile = File(draftDir, "current.gpx.tmp")

    suspend fun load(): TraceRecordingDraft? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!metadataFile.exists()) return@withContext null
                val json = JSONObject(metadataFile.readText())
                val pointsJson = json.optJSONArray("points") ?: JSONArray()
                val points =
                    buildList {
                        for (index in 0 until pointsJson.length()) {
                            val pointJson = pointsJson.optJSONObject(index) ?: continue
                            add(
                                RecordedTracePoint(
                                    latLong =
                                        LatLong(
                                            pointJson.getDouble("lat"),
                                            pointJson.getDouble("lon"),
                                        ),
                                    elevationMeters = pointJson.optionalDouble("elevationMeters"),
                                    timeMillis =
                                        pointJson.optLong("timeMillis", 0L).takeIf { it > 0L }
                                            ?: continue,
                                    accuracyMeters = pointJson.optionalFloat("accuracyMeters"),
                                    speedMps = pointJson.optionalFloat("speedMps"),
                                    elevationSource = pointJson.optionalString("elevationSource"),
                                    heartRateBpm = pointJson.optionalInt("heartRateBpm"),
                                    stepCount = pointJson.optionalInt("stepCount"),
                                    cadenceSpm = pointJson.optionalInt("cadenceSpm"),
                                    powerWatts = pointJson.optionalInt("powerWatts"),
                                    barometricPressureHpa = pointJson.optionalDouble("barometricPressureHpa"),
                                    startsNewSegment = pointJson.optBoolean("startsNewSegment", false),
                                    segmentStartReason = pointJson.optionalString("segmentStartReason"),
                                ),
                            )
                        }
                    }
                TraceRecordingDraft(
                    active = json.optBoolean("active", true),
                    paused = json.optBoolean("paused", false),
                    autoPaused = json.optBoolean("autoPaused", false),
                    activityProfile = json.optionalString("activityProfile"),
                    trackSmoothingMode =
                        json
                            .optionalString("trackSmoothingMode")
                            .toRecordingTrackSmoothingMode(),
                    startedAtMillis = json.optLong("startedAtMillis", 0L).takeIf { it > 0L },
                    pausedAtMillis = json.optLong("pausedAtMillis", 0L).takeIf { it > 0L },
                    accumulatedPausedMillis = json.optLong("accumulatedPausedMillis", 0L).coerceAtLeast(0L),
                    distanceMeters = json.optDouble("distanceMeters", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    gpsActiveDurationMillis = json.optLong("gpsActiveDurationMillis", 0L).coerceAtLeast(0L),
                    recordingGapCount = json.optInt("recordingGapCount", 0).coerceAtLeast(0),
                    recordingMaxGapMillis = json.optLong("recordingMaxGapMillis", 0L).coerceAtLeast(0L),
                    externalRawDistanceUnits = json.optionalLong("externalRawDistanceUnits"),
                    externalDistanceMeters = json.optionalDouble("externalDistanceMeters"),
                    externalIntegratedDistanceMeters = json.optionalDouble("externalIntegratedDistanceMeters"),
                    stepCount = json.optionalInt("stepCount"),
                    lastUiAction = json.optionalString("lastUiAction"),
                    points = points,
                )
            }.getOrNull()
        }

    suspend fun save(
        state: TraceRecordingUiState,
        lastUiAction: String?,
    ) = withContext(Dispatchers.IO) {
        if (!draftDir.exists()) {
            draftDir.mkdirs()
        }
        val json =
            JSONObject()
                .put("active", state.active)
                .put("paused", state.paused)
                .put("autoPaused", state.autoPaused)
                .put("activityProfile", state.activityProfile)
                .put("trackSmoothingMode", state.trackSmoothingMode)
                .put("startedAtMillis", state.startedAtMillis ?: 0L)
                .put("pausedAtMillis", state.pausedAtMillis ?: 0L)
                .put("accumulatedPausedMillis", state.accumulatedPausedMillis)
                .put("distanceMeters", state.distanceMeters)
                .put("gpsActiveDurationMillis", state.gpsActiveDurationMillis)
                .put("recordingGapCount", state.recordingGapCount)
                .put("recordingMaxGapMillis", state.recordingMaxGapMillis)
                .put("externalRawDistanceUnits", state.externalRawDistanceUnits ?: JSONObject.NULL)
                .put("externalDistanceMeters", state.externalDistanceMeters ?: JSONObject.NULL)
                .put("externalIntegratedDistanceMeters", state.externalIntegratedDistanceMeters ?: JSONObject.NULL)
                .put("stepCount", state.stepCount ?: JSONObject.NULL)
                .put("lastUiAction", lastUiAction ?: JSONObject.NULL)
                .put(
                    "points",
                    JSONArray().also { array ->
                        state.points.forEach { point ->
                            array.put(point.toJson())
                        }
                    },
                )
        metadataTempFile.writeText(json.toString())
        metadataTempFile.renameAtomicallyTo(metadataFile)

        val nowMillis = System.currentTimeMillis()
        val title =
            buildRecordingTitle(
                startedAtMillis = state.startedAtMillis ?: nowMillis,
                endedAtMillis = state.points.lastOrNull()?.timeMillis ?: nowMillis,
            )
        gpxTempFile.writeBytes(encodeRecordedTraceAsGpx(title = title, points = state.points))
        gpxTempFile.renameAtomicallyTo(gpxFile)
    }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            metadataFile.delete()
            metadataTempFile.delete()
            gpxFile.delete()
            gpxTempFile.delete()
        }

    fun draftPath(): String = gpxFile.absolutePath
}

data class TraceRecordingDraft(
    val active: Boolean,
    val paused: Boolean,
    val autoPaused: Boolean,
    val activityProfile: String?,
    val trackSmoothingMode: String,
    val startedAtMillis: Long?,
    val pausedAtMillis: Long?,
    val accumulatedPausedMillis: Long,
    val distanceMeters: Double,
    val gpsActiveDurationMillis: Long,
    val recordingGapCount: Int,
    val recordingMaxGapMillis: Long,
    val externalRawDistanceUnits: Long?,
    val externalDistanceMeters: Double?,
    val externalIntegratedDistanceMeters: Double?,
    val stepCount: Int?,
    val lastUiAction: String?,
    val points: List<RecordedTracePoint>,
)

private fun RecordedTracePoint.toJson(): JSONObject =
    JSONObject()
        .put("lat", latLong.latitude)
        .put("lon", latLong.longitude)
        .put("elevationMeters", elevationMeters ?: JSONObject.NULL)
        .put("timeMillis", timeMillis)
        .put("accuracyMeters", accuracyMeters ?: JSONObject.NULL)
        .put("speedMps", speedMps ?: JSONObject.NULL)
        .put("elevationSource", elevationSource ?: JSONObject.NULL)
        .put("heartRateBpm", heartRateBpm ?: JSONObject.NULL)
        .put("stepCount", stepCount ?: JSONObject.NULL)
        .put("cadenceSpm", cadenceSpm ?: JSONObject.NULL)
        .put("powerWatts", powerWatts ?: JSONObject.NULL)
        .put("barometricPressureHpa", barometricPressureHpa ?: JSONObject.NULL)
        .put("startsNewSegment", startsNewSegment)
        .put("segmentStartReason", segmentStartReason ?: JSONObject.NULL)

private fun JSONObject.optionalDouble(key: String): Double? =
    if (isNull(key)) {
        null
    } else {
        optDouble(key).takeIf { it.isFinite() }
    }

private fun JSONObject.optionalFloat(key: String): Float? = optionalDouble(key)?.toFloat()

private fun JSONObject.optionalInt(key: String): Int? =
    if (isNull(key)) {
        null
    } else {
        optInt(key).takeIf { it >= 0 }
    }

private fun JSONObject.optionalLong(key: String): Long? =
    if (isNull(key)) {
        null
    } else {
        optLong(key).takeIf { it >= 0L }
    }

internal fun String?.toRecordingTrackSmoothingMode(): String =
    when (this) {
        SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF -> this
        SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG -> this
        else -> SettingsRepository.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE
    }

private fun JSONObject.optionalString(key: String): String? =
    if (isNull(key)) {
        null
    } else {
        optString(key).takeIf { it.isNotBlank() }
    }

private fun File.renameAtomicallyTo(target: File) {
    if (target.exists()) {
        target.delete()
    }
    if (!renameTo(target)) {
        copyTo(target, overwrite = true)
        delete()
    }
}
