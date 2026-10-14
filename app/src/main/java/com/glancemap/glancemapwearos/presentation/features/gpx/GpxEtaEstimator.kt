package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.TrailGuidanceEtaConfig
import com.glancemap.trailcore.guidance.TrailGuidanceEtaProjection
import com.glancemap.trailcore.guidance.buildTrailGuidanceEtaProjection
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.guidance.applyBikePhysicsSpeedAdjustment as trailApplyBikePhysicsSpeedAdjustment
import com.glancemap.trailcore.guidance.applyStaminaFlatSpeedAdjustment as trailApplyStaminaFlatSpeedAdjustment

internal data class GpxEtaModelConfig(
    val flatSpeedMps: Double,
    val advancedVerticalRateEnabled: Boolean = false,
    val staminaAdjustmentEnabled: Boolean = false,
    val activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    val userWeightKg: Double = SettingsRepository.DEFAULT_USER_WEIGHT_KG.toDouble(),
    val backpackWeightKg: Double = SettingsRepository.DEFAULT_BACKPACK_WEIGHT_KG.toDouble(),
    val bikeWeightKg: Double = SettingsRepository.DEFAULT_BIKE_WEIGHT_KG.toDouble(),
    val uphillVerticalMetersPerHour: Double = 0.0,
    val downhillVerticalMetersPerHour: Double = 0.0,
)

internal data class GpxEtaProjection(
    val cumulativeSeconds: DoubleArray,
) {
    private val trailProjection get() = TrailGuidanceEtaProjection(cumulativeSeconds)
    val totalSeconds: Double? get() = trailProjection.totalSeconds

    fun secondsAtPointIndex(index: Int): Double? = trailProjection.secondsAtPointIndex(index)

    fun secondsAtTrackPosition(
        position: TrackPosition,
    ): Double? = trailProjection.secondsAtRoutePosition(position.segmentIndex, position.t)

    fun remainingSecondsAtDistance(
        cumulativeDistancesMeters: List<Double>,
        distanceFromStartMeters: Double?,
    ): Long? = trailProjection.remainingSecondsAtDistance(cumulativeDistancesMeters, distanceFromStartMeters)
}

internal fun applyStaminaFlatSpeedAdjustment(
    flatSpeedMps: Double,
    totalDistanceMeters: Double,
    enabled: Boolean,
): Double = trailApplyStaminaFlatSpeedAdjustment(flatSpeedMps, totalDistanceMeters, enabled)

internal fun applyBikePhysicsSpeedAdjustment(
    flatSpeedMps: Double,
    grade: Double,
    config: GpxEtaModelConfig,
): Double = trailApplyBikePhysicsSpeedAdjustment(flatSpeedMps, grade, config.toTrailConfig())

internal fun buildEtaProjection(
    profile: TrackProfile,
    flatSpeedMps: Double,
): GpxEtaProjection? =
    buildEtaProjection(
        profile = profile,
        config = GpxEtaModelConfig(flatSpeedMps = flatSpeedMps),
    )

internal fun buildEtaProjection(
    profile: TrackProfile,
    config: GpxEtaModelConfig,
): GpxEtaProjection? =
    buildTrailGuidanceEtaProjection(
        points = profile.points.map(TrackPoint::toTrailPoint),
        cumulativeDistancesMeters = profile.cumDist.toList(),
        config = config.toTrailConfig(),
    )?.let { GpxEtaProjection(it.cumulativeSeconds) }

internal fun estimateRemainingRouteSeconds(
    trackPoints: List<TrackPoint>,
    distanceFromStartMeters: Double?,
    config: GpxEtaModelConfig,
): Long? =
    buildRouteEtaProjection(trackPoints, config)
        ?.remainingSecondsAtDistance(distanceFromStartMeters)

internal data class GpxRouteEtaProjection(
    val cumulativeDistancesMeters: DoubleArray,
    val etaProjection: GpxEtaProjection,
) {
    fun remainingSecondsAtDistance(
        distanceFromStartMeters: Double?,
    ): Long? = etaProjection.remainingSecondsAtDistance(cumulativeDistancesMeters.toList(), distanceFromStartMeters)
}

internal fun buildRouteEtaProjection(
    trackPoints: List<TrackPoint>,
    config: GpxEtaModelConfig,
): GpxRouteEtaProjection? {
    if (trackPoints.size < 2) return null
    val profile =
        buildProfile(
            sig = FileSig(lastModified = 0L, length = trackPoints.size.toLong()),
            pts = trackPoints,
        )
    val projection = buildEtaProjection(profile, config) ?: return null
    return GpxRouteEtaProjection(
        cumulativeDistancesMeters = profile.cumDist,
        etaProjection = projection,
    )
}

private fun GpxEtaModelConfig.toTrailConfig() =
    TrailGuidanceEtaConfig(
        flatSpeedMps = flatSpeedMps,
        advancedVerticalRateEnabled = advancedVerticalRateEnabled,
        staminaAdjustmentEnabled = staminaAdjustmentEnabled,
        activityProfile = activityProfile,
        userWeightKg = userWeightKg,
        backpackWeightKg = backpackWeightKg,
        bikeWeightKg = bikeWeightKg,
        uphillVerticalMetersPerHour = uphillVerticalMetersPerHour,
        downhillVerticalMetersPerHour = downhillVerticalMetersPerHour,
    )

private fun TrackPoint.toTrailPoint() =
    TrailPoint(
        location = GeoPoint(latLong.latitude, latLong.longitude),
        elevationMeters = elevation,
        startsNewSegment = startsNewSegment,
    )
