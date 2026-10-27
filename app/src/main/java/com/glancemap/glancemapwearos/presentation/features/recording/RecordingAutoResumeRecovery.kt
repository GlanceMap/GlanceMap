package com.glancemap.glancemapwearos.presentation.features.recording

internal data class RecordingAutoResumeFix<T>(
    val value: T,
    val point: RecordedTracePoint,
    val elapsedMillis: Long,
    val speedAccuracyMps: Float?,
    val source: String,
    val trustSpeedWithoutAccuracy: Boolean = false,
)

internal fun TraceRecordingUiState.canBufferAutoResumeFix(): Boolean {
    val automaticPause = active && paused && autoPaused
    return automaticPause && !saving && recordingAutoPauseEnabled
}

/** Holds only one uninterrupted candidate restart, never the pause connector. */
internal class RecordingAutoResumeRecoveryBuffer<T> {
    private val fixes = mutableListOf<RecordingAutoResumeFix<T>>()

    fun clear() = fixes.clear()

    fun offer(
        fix: RecordingAutoResumeFix<T>,
        eligible: Boolean,
    ) {
        if (!eligible) {
            clear()
        } else if (fix.isValid()) {
            val last = fixes.lastOrNull()
            val forward =
                last == null || (fix.elapsedMillis > last.elapsedMillis && fix.point.timeMillis > last.point.timeMillis)
            if (forward) {
                if (last != null && last.source != fix.source) clear()
                fixes.add(fix)
                while (fixes.size > MAX_FIXES || fix.elapsedMillis - fixes.first().elapsedMillis > MAX_AGE_MS) {
                    fixes.removeAt(0)
                }
            }
        }
    }

    private fun RecordingAutoResumeFix<T>.isValid(): Boolean {
        val coordinates = point.latLong
        val accuracy = point.effectiveAccuracyMeters
        val validTime = elapsedMillis > 0L && point.timeMillis > 0L
        val validLatitude = coordinates.latitude.isFinite() && coordinates.latitude in -90.0..90.0
        val validLongitude = coordinates.longitude.isFinite() && coordinates.longitude in -180.0..180.0
        val validAccuracy = accuracy != null && accuracy.isFinite() && accuracy >= 0f
        val validCoordinates = validLatitude && validLongitude
        return validTime && validCoordinates && validAccuracy
    }

    fun recover(activityProfile: String): List<RecordingAutoResumeFix<T>> {
        for (index in 0 until fixes.lastIndex) {
            val first = fixes[index]
            for (nextIndex in index + 1..fixes.lastIndex) {
                val next = fixes[nextIndex]
                if (isCredibleRestart(first, next, activityProfile)) return fixes.drop(index)
            }
        }
        return emptyList()
    }

    private fun isCredibleRestart(
        first: RecordingAutoResumeFix<T>,
        next: RecordingAutoResumeFix<T>,
        activityProfile: String,
    ): Boolean {
        val accuracy =
            maxOf(checkNotNull(first.point.effectiveAccuracyMeters), checkNotNull(next.point.effectiveAccuracyMeters))
        val quality = RecordingFixQualityGate()
        if (haversineMeters(first.point.latLong, next.point.latLong) <= accuracy * 2.0 ||
            !quality.evaluate(first.asQualitySample(), activityProfile).accepted ||
            !quality.evaluate(next.asQualitySample(), activityProfile).accepted
        ) {
            return false
        }
        return RecordingMovementConfidenceGate()
            .evaluate(
                previous = first.point,
                candidate =
                    RecordingMotionSample(
                        latLong = next.point.latLong,
                        elapsedRealtimeMillis = next.elapsedMillis,
                        accuracyMeters = next.point.effectiveAccuracyMeters,
                        speedMps = next.point.speedMps,
                        speedAccuracyMps = next.speedAccuracyMps,
                        stepCount = null,
                        cadenceSpm = null,
                        trustReportedSpeedWithoutAccuracy = next.trustSpeedWithoutAccuracy,
                    ),
                activityProfile = activityProfile,
                previousFilterAccuracyMeters = first.point.effectiveAccuracyMeters,
            ).accepted
    }

    private fun RecordingAutoResumeFix<T>.asQualitySample() =
        RecordingFixSample(
            latLong = point.latLong,
            timeMillis = point.timeMillis,
            elapsedRealtimeMillis = elapsedMillis,
            accuracyMeters = point.effectiveAccuracyMeters,
            speedMps = point.speedMps,
            speedAccuracyMps = speedAccuracyMps,
        )

    private companion object {
        const val MAX_FIXES = 24
        const val MAX_AGE_MS = 60_000L
    }
}
