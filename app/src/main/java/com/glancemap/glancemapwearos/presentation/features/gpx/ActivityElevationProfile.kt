package com.glancemap.glancemapwearos.presentation.features.gpx

internal data class ActivityElevationProfile(
    val samples: List<ElevationSample>,
    val totalDistance: Double,
)

internal fun buildElevationProfileSamples(
    profile: TrackProfile,
    etaProjection: GpxEtaProjection?,
): List<ElevationSample> {
    val samples = mutableListOf<ElevationSample>()
    var gapBefore = false
    profile.points.forEachIndexed { index, point ->
        gapBefore = gapBefore || point.startsNewSegment
        val elevation = point.elevation?.takeIf { it.isFinite() }
        if (elevation == null) {
            gapBefore = true
        } else {
            samples +=
                ElevationSample(
                    distance = profile.cumDist[index],
                    elevation = elevation,
                    cumulativeAscent = profile.cumAscent.getOrElse(index) { profile.cumAscent.lastOrNull() ?: 0.0 },
                    cumulativeDescent =
                        profile.cumDescent.getOrElse(index) { profile.cumDescent.lastOrNull() ?: 0.0 },
                    cumulativeDurationSec = etaProjection?.secondsAtPointIndex(index),
                    gapBefore = gapBefore && samples.isNotEmpty(),
                )
            gapBefore = false
        }
    }
    return samples
}

internal fun buildActivityElevationProfile(profile: TrackProfile): ActivityElevationProfile =
    ActivityElevationProfile(
        samples = downsampleActivityElevationSamples(buildElevationProfileSamples(profile, etaProjection = null)),
        totalDistance = profile.totalDistance,
    )

/** Keeps the popup's uniform sampling while retaining both ends of every gap for grey rendering. */
internal fun downsampleElevationSamples(
    samples: List<ElevationSample>,
    maxSamples: Int = 120,
): List<ElevationSample> {
    if (samples.size <= maxSamples || maxSamples <= 1) return samples

    val lastIndex = samples.lastIndex
    val step = lastIndex.toDouble() / (maxSamples - 1).toDouble()
    val keep =
        (0 until maxSamples)
            .map { sampleIndex -> (sampleIndex * step).toInt().coerceIn(0, lastIndex) }
            .toMutableSet()
    samples.indices.filter { samples[it].gapBefore }.forEach { index ->
        keep += index
        if (index > 0) keep += index - 1
    }
    return keep.sorted().map(samples::get)
}

/** Gap endpoints take priority over the target size so downsampling never hides a discontinuity. */
internal fun downsampleActivityElevationSamples(
    samples: List<ElevationSample>,
    maxSamples: Int = 120,
): List<ElevationSample> {
    if (samples.size <= maxSamples || maxSamples < 4) return samples
    val keep =
        mutableSetOf(
            0,
            samples.lastIndex,
            samples.indices.minBy { samples[it].elevation },
            samples.indices.maxBy { samples[it].elevation },
        )
    samples.indices.filter { samples[it].gapBefore }.forEach { index ->
        keep += index
        if (index > 0) keep += index - 1
    }
    val bucketCount = (maxSamples - keep.size).coerceAtLeast(0) / 2
    repeat(bucketCount) { bucket ->
        val start = bucket * samples.size / bucketCount
        val end = (bucket + 1) * samples.size / bucketCount
        val indices = start until end
        keep += indices.minBy { samples[it].elevation }
        keep += indices.maxBy { samples[it].elevation }
    }
    return keep.sorted().map(samples::get)
}
