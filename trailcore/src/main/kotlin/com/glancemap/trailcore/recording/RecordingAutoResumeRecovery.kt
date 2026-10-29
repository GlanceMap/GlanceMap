package com.glancemap.trailcore.recording

data class RecordingAutoResumeFix<T>(
    val value: T,
    val point: RecordingPoint,
    val elapsedMillis: Long,
    val speedAccuracyMps: Float?,
    val source: String,
)

/** Holds only one bounded candidate restart, never the connector across a pause. */
class RecordingAutoResumeRecoveryBuffer<T> {
    private val fixes = mutableListOf<RecordingAutoResumeFix<T>>()

    fun clear() = fixes.clear()

    @Suppress("ReturnCount")
    fun offer(
        fix: RecordingAutoResumeFix<T>,
        eligible: Boolean,
    ) {
        if (!eligible) {
            clear()
            return
        }
        if (!fix.isValid()) return
        val previous = fixes.lastOrNull()
        if (previous != null &&
            (fix.elapsedMillis <= previous.elapsedMillis || fix.point.timeMillis <= previous.point.timeMillis)
        ) {
            return
        }
        if (previous != null && previous.source != fix.source) clear()
        fixes.add(fix)
        while (fixes.size > 24 || fix.elapsedMillis - fixes.first().elapsedMillis > 60_000L) fixes.removeAt(0)
    }

    private fun RecordingAutoResumeFix<T>.isValid(): Boolean {
        val accuracy = point.effectiveAccuracyMeters
        val validTime = elapsedMillis > 0L && point.timeMillis > 0L
        val validLatitude = point.location.latitude.isFinite() && point.location.latitude in -90.0..90.0
        val validLongitude = point.location.longitude.isFinite() && point.location.longitude in -180.0..180.0
        val validAccuracy = accuracy != null && accuracy.isFinite() && accuracy >= 0f
        val validCoordinates = validLatitude && validLongitude
        return validTime && validCoordinates && validAccuracy
    }

    fun recover(
        profile: String,
        earliestElapsedMillis: Long = Long.MIN_VALUE,
        latestElapsedMillis: Long = Long.MAX_VALUE,
    ): List<RecordingAutoResumeFix<T>> {
        val candidates = fixes.filter { it.elapsedMillis in earliestElapsedMillis..latestElapsedMillis }
        for (index in 0 until candidates.lastIndex) {
            for (next in index + 1..candidates.lastIndex) {
                if (credible(candidates[index], candidates[next], profile)) return candidates.drop(index)
            }
        }
        return emptyList()
    }

    private fun credible(
        first: RecordingAutoResumeFix<T>,
        next: RecordingAutoResumeFix<T>,
        profile: String,
    ): Boolean {
        val accuracy =
            maxOf(checkNotNull(first.point.effectiveAccuracyMeters), checkNotNull(next.point.effectiveAccuracyMeters))
        val quality = RecordingFixQualityGate()

        fun RecordingAutoResumeFix<T>.qualitySample() =
            RecordingFixSample(
                point.location,
                point.timeMillis,
                elapsedMillis,
                point.effectiveAccuracyMeters,
                point.speedMps,
                speedAccuracyMps,
            )
        if (recordingHaversineMeters(first.point.location, next.point.location) <= accuracy * 2.0 ||
            !quality.evaluate(first.qualitySample(), profile).accepted ||
            !quality.evaluate(next.qualitySample(), profile).accepted
        ) {
            return false
        }
        return RecordingMovementConfidenceGate()
            .evaluate(
                previous = first.point,
                candidate =
                    RecordingMotionSample(
                        location = next.point.location,
                        elapsedRealtimeMillis = next.elapsedMillis,
                        accuracyMeters = next.point.effectiveAccuracyMeters,
                        speedMps = next.point.speedMps,
                        speedAccuracyMps = next.speedAccuracyMps,
                        stepCount = null,
                        cadenceSpm = null,
                    ),
                activityProfile = profile,
                previousFilterAccuracyMeters = first.point.effectiveAccuracyMeters,
            ).accepted
    }
}
