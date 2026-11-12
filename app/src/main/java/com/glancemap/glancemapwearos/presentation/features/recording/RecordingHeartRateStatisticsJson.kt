package com.glancemap.glancemapwearos.presentation.features.recording

import org.json.JSONArray
import org.json.JSONObject

internal fun encodeRecordingHeartRateStatistics(state: RecordingHeartRateStatisticsState): String {
    require(state.isValidCheckpoint())
    return JSONObject()
        .put("weightedBpmMillis", state.weightedBpmMillis)
        .put("coveredMillis", state.coveredMillis)
        .put("maxBpm", state.maxBpm ?: JSONObject.NULL)
        .put("committedBeforeMillis", state.committedBeforeMillis)
        .put(
            "recentObservations",
            JSONArray().also { array ->
                state.recentObservations.forEach { observation ->
                    array.put(
                        JSONObject()
                            .put("atMillis", observation.atMillis)
                            .put("bpm", observation.bpm)
                            .put("source", observation.source),
                    )
                }
            },
        ).put(
            "recentWindows",
            JSONArray().also { array ->
                state.recentWindows.forEach { window ->
                    array.put(
                        JSONObject()
                            .put("startMillis", window.startMillis)
                            .put("endMillis", window.endMillis)
                            .put("source", window.source),
                    )
                }
            },
        ).toString()
}

internal fun decodeRecordingHeartRateStatistics(json: String?): RecordingHeartRateStatisticsState? {
    if (json == null) return null
    return runCatching {
        val value = JSONObject(json)
        RecordingHeartRateStatisticsState(
            weightedBpmMillis = value.number("weightedBpmMillis").toDouble(),
            coveredMillis = value.long("coveredMillis"),
            maxBpm = value.nullableDouble("maxBpm"),
            committedBeforeMillis = value.long("committedBeforeMillis"),
            recentObservations =
                value.objects("recentObservations").map { observation ->
                    RecordingHeartRateObservation(
                        atMillis = observation.long("atMillis"),
                        bpm = observation.number("bpm").toDouble(),
                        source = observation.source(),
                    )
                },
            recentWindows =
                value.objects("recentWindows").map { window ->
                    RecordingHeartRateWindow(
                        startMillis = window.long("startMillis"),
                        endMillis = window.long("endMillis"),
                        source = window.source(),
                    )
                },
        ).takeIf { it.isValidCheckpoint() }
    }.getOrNull()
}

private fun RecordingHeartRateStatisticsState.isValidCheckpoint(): Boolean =
    hasValidPrefix() &&
        recentObservations.all { it.atMillis >= 0L && it.bpm.isFinite() && it.bpm > 0.0 && it.source.isNotBlank() } &&
        recentWindows.all { it.startMillis >= 0L && it.endMillis > it.startMillis && it.source.isNotBlank() } &&
        recentWindows.zipWithNext().all { (before, after) -> before.endMillis <= after.startMillis } &&
        recentObservations.distinctBy { it.source to it.atMillis }.size == recentObservations.size

private fun RecordingHeartRateStatisticsState.hasValidPrefix(): Boolean {
    val integralValid =
        weightedBpmMillis.isFinite() &&
            weightedBpmMillis >= 0.0 &&
            coveredMillis >= 0L &&
            (coveredMillis > 0L || weightedBpmMillis == 0.0)
    val maximumValid = maxBpm?.let { it.isFinite() && it > 0.0 } ?: true
    val watermarkValid = committedBeforeMillis >= 0L || committedBeforeMillis == Long.MIN_VALUE
    return integralValid && maximumValid && watermarkValid && (coveredMillis == 0L || maxBpm != null)
}

private fun JSONObject.number(key: String): Number = checkNotNull(get(key) as? Number)

private fun JSONObject.long(key: String): Long = number(key).toString().toBigDecimal().longValueExact()

private fun JSONObject.nullableDouble(key: String): Double? {
    val value = get(key)
    return if (value == JSONObject.NULL) null else number(key).toDouble()
}

private fun JSONObject.source(): String = checkNotNull(get("source") as? String)

private fun JSONObject.objects(key: String): List<JSONObject> {
    val array = getJSONArray(key)
    return List(array.length()) { array.getJSONObject(it) }
}
