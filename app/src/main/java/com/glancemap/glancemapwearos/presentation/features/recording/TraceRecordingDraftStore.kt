package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.LatLong
import java.io.File

data class TraceRecordingDraftPersistStats(
    val jsonBytesWritten: Int,
    val gpxBytesWritten: Int,
    val pointCount: Int,
)

class TraceRecordingDraftStore(
    context: Context,
) {
    private val draftDir: File = context.getDir("recording_drafts", Context.MODE_PRIVATE)
    private val metadataFile = File(draftDir, "current.json")
    private val metadataTempFile = File(draftDir, "current.json.tmp")
    private val legacyGpxFile = File(draftDir, "current.gpx")
    private val legacyGpxTempFile = File(draftDir, "current.gpx.tmp")

    suspend fun load(): TraceRecordingDraft? =
        withContext(Dispatchers.IO) {
            runCatching(::readDraft).getOrNull().also { draft ->
                if (draft != null) deleteLegacyGpxArtifacts()
            }
        }

    private fun readDraft(): TraceRecordingDraft? =
        metadataFile
            .takeIf(File::exists)
            ?.readText()
            ?.let(::JSONObject)
            ?.let { json ->
                with(TraceRecordingDraftJson) {
                    json.toTraceRecordingDraft()
                }
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
                .put("distanceSource", state.distanceSource)
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
        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        metadataTempFile.writeBytes(jsonBytes)
        metadataTempFile.renameAtomicallyTo(metadataFile)
        deleteLegacyGpxArtifacts()
        TraceRecordingDraftPersistStats(
            jsonBytesWritten = jsonBytes.size,
            gpxBytesWritten = 0,
            pointCount = state.points.size,
        )
    }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            metadataFile.delete()
            metadataTempFile.delete()
            deleteLegacyGpxArtifacts()
        }

    fun draftPath(): String = metadataFile.absolutePath

    private fun deleteLegacyGpxArtifacts() {
        legacyGpxFile.delete()
        legacyGpxTempFile.delete()
    }
}

private object TraceRecordingDraftJson {
    fun JSONObject.toTraceRecordingDraft(): TraceRecordingDraft =
        TraceRecordingDraft(
            active = optBoolean("active", true),
            paused = optBoolean("paused", false),
            autoPaused = optBoolean("autoPaused", false),
            activityProfile = optionalString("activityProfile"),
            trackSmoothingMode = optionalString("trackSmoothingMode").toRecordingTrackSmoothingMode(),
            distanceSource = optionalString("distanceSource"),
            startedAtMillis = optLong("startedAtMillis", 0L).takeIf { it > 0L },
            pausedAtMillis = optLong("pausedAtMillis", 0L).takeIf { it > 0L },
            accumulatedPausedMillis = optLong("accumulatedPausedMillis", 0L).coerceAtLeast(0L),
            distanceMeters = optDouble("distanceMeters", 0.0).takeIf { it.isFinite() } ?: 0.0,
            gpsActiveDurationMillis = optLong("gpsActiveDurationMillis", 0L).coerceAtLeast(0L),
            recordingGapCount = optInt("recordingGapCount", 0).coerceAtLeast(0),
            recordingMaxGapMillis = optLong("recordingMaxGapMillis", 0L).coerceAtLeast(0L),
            externalRawDistanceUnits = optionalLong("externalRawDistanceUnits"),
            externalDistanceMeters = optionalDouble("externalDistanceMeters"),
            externalIntegratedDistanceMeters = optionalDouble("externalIntegratedDistanceMeters"),
            stepCount = optionalInt("stepCount"),
            lastUiAction = optionalString("lastUiAction"),
            points = (optJSONArray("points") ?: JSONArray()).toRecordedTracePoints(),
        )

    private fun JSONArray.toRecordedTracePoints(): List<RecordedTracePoint> =
        buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.toRecordedTracePoint()?.let(::add)
            }
        }

    private fun JSONObject.toRecordedTracePoint(): RecordedTracePoint? {
        val latLong = LatLong(getDouble("lat"), getDouble("lon"))
        val timeMillis = optLong("timeMillis", 0L).takeIf { it > 0L } ?: return null
        val rawAccuracyMeters = optionalFloat("accuracyMeters")
        val accuracyProvenance =
            restoreRecordingAccuracyProvenance(
                rawAccuracyMeters = rawAccuracyMeters,
                effectiveAccuracyMeters = optionalFloat("effectiveAccuracyMeters"),
                accuracyInterpretation = optionalString("accuracyInterpretation"),
            )
        return RecordedTracePoint(
            latLong = latLong,
            elevationMeters = optionalDouble("elevationMeters"),
            timeMillis = timeMillis,
            accuracyMeters = rawAccuracyMeters,
            speedMps = optionalFloat("speedMps"),
            effectiveAccuracyMeters = accuracyProvenance.effectiveAccuracyMeters,
            accuracyInterpretation = accuracyProvenance.interpretation,
            elevationSource = optionalString("elevationSource"),
            heartRateBpm = optionalInt("heartRateBpm"),
            stepCount = optionalInt("stepCount"),
            cadenceSpm = optionalInt("cadenceSpm"),
            powerWatts = optionalInt("powerWatts"),
            barometricPressureHpa = optionalDouble("barometricPressureHpa"),
            startsNewSegment = optBoolean("startsNewSegment", false),
            segmentStartReason = optionalString("segmentStartReason"),
            trajectoryFinalized = optBoolean("trajectoryFinalized", false),
        )
    }
}

internal fun restoreRecordingAccuracyProvenance(
    rawAccuracyMeters: Float?,
    effectiveAccuracyMeters: Float?,
    accuracyInterpretation: String?,
): RecordingAccuracyProvenance =
    RecordingAccuracyProvenance(
        effectiveAccuracyMeters = effectiveAccuracyMeters ?: rawAccuracyMeters,
        interpretation = accuracyInterpretation?.takeIf { it.isNotBlank() } ?: RECORDING_ACCURACY_INTERPRETATION_RAW,
    )

data class TraceRecordingDraft(
    val active: Boolean,
    val paused: Boolean,
    val autoPaused: Boolean,
    val activityProfile: String?,
    val trackSmoothingMode: String,
    val distanceSource: String?,
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
        .put("effectiveAccuracyMeters", effectiveAccuracyMeters ?: JSONObject.NULL)
        .put("accuracyInterpretation", accuracyInterpretation)
        .put("elevationSource", elevationSource ?: JSONObject.NULL)
        .put("heartRateBpm", heartRateBpm ?: JSONObject.NULL)
        .put("stepCount", stepCount ?: JSONObject.NULL)
        .put("cadenceSpm", cadenceSpm ?: JSONObject.NULL)
        .put("powerWatts", powerWatts ?: JSONObject.NULL)
        .put("barometricPressureHpa", barometricPressureHpa ?: JSONObject.NULL)
        .put("startsNewSegment", startsNewSegment)
        .put("segmentStartReason", segmentStartReason ?: JSONObject.NULL)
        .put("trajectoryFinalized", trajectoryFinalized)

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
