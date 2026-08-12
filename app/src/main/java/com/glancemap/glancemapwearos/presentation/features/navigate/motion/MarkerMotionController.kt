package com.glancemap.glancemapwearos.presentation.features.navigate.motion

import com.glancemap.glancemapwearos.core.service.location.config.WATCH_GPS_ACCURACY_FLOOR_M
import com.glancemap.glancemapwearos.core.service.location.config.WATCH_GPS_ACCURACY_FLOOR_TOLERANCE_M
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.moveLatLong
import org.mapsforge.core.model.LatLong
import kotlin.math.abs
import kotlin.math.max

internal data class MarkerMotionReading(
    val fixElapsedMs: Long,
    val accuracyM: Float,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val speedAccuracyMps: Float? = null,
    val bearingAccuracyDeg: Float? = null,
)

internal data class MarkerMotionSeed(
    val latLong: LatLong,
    val reading: MarkerMotionReading,
    val sourceMode: LocationSourceMode = LocationSourceMode.AUTO_FUSED,
    val origin: MarkerMotionAnchorOrigin = MarkerMotionAnchorOrigin.CACHED_LOCATION,
)

internal enum class MarkerMotionAnchorOrigin(
    val telemetryLabel: String,
) {
    CACHED_LOCATION("cached_location"),
    RETAINED_VISUAL("retained_visual"),
}

internal data class MarkerMotionGpsFix(
    val latLong: LatLong,
    val nowElapsedMs: Long,
    val reading: MarkerMotionReading,
    val allowLargeCorrection: Boolean = false,
    val sourceMode: LocationSourceMode = LocationSourceMode.AUTO_FUSED,
    /** Whether the map can render this correction for the user. */
    val isMarkerVisible: Boolean = true,
)

internal data class MarkerMotionUpdate(
    val displayedLatLong: LatLong,
    val fixAccepted: Boolean,
    val resolvedSpeedMps: Float = 0f,
    val resolvedBearingDeg: Float? = null,
)

internal data class MarkerVisualMotionStatus(
    val isMoving: Boolean,
    val isFresh: Boolean,
)

internal class MarkerMotionController(
    predictionFreshnessMaxAgeMs: Long,
    maxAcceptedFixAgeMs: Long,
    private val maxPredictionAccuracyM: Float = DEFAULT_MAX_PREDICTION_ACCURACY_M,
    private val minPredictionSpeedMps: Float = DEFAULT_MIN_PREDICTION_SPEED_MPS,
    private val correctionBlendDurationMs: Long = DEFAULT_CORRECTION_BLEND_DURATION_MS,
    expectedGpsIntervalMs: Long =
        (predictionFreshnessMaxAgeMs * 2L / 3L).coerceAtLeast(MIN_EXPECTED_GPS_INTERVAL_MS),
) {
    private var predictionFreshnessMaxAgeMs = predictionFreshnessMaxAgeMs
    private val state = MarkerMotionState()
    private val predictionCadence = MarkerPredictionCadence(expectedGpsIntervalMs)
    private val predictionWindowFor: (Long) -> MarkerPredictionWindow = { serviceFreshnessMaxAgeMs ->
        predictionCadence.predictionWindow(
            configuredFreshnessMaxAgeMs = predictionFreshnessMaxAgeMs,
            serviceFreshnessMaxAgeMs = serviceFreshnessMaxAgeMs,
            startDelayMs = PREDICTION_START_DELAY_MS,
        )
    }
    private val fixProcessor =
        MarkerMotionGpsFixProcessor(
            state = state,
            settings =
                MarkerMotionGpsFixProcessorSettings(
                    maxAcceptedFixAgeMs = maxAcceptedFixAgeMs,
                    maxVisualCorrectionAccuracyM = maxPredictionAccuracyM,
                    minPredictionSpeedMps = minPredictionSpeedMps,
                    correctionBlendDurationMs = correctionBlendDurationMs,
                ),
            predictionWindowProvider = { predictionWindowFor(Long.MAX_VALUE) },
        )

    fun updateTiming(
        predictionFreshnessMaxAgeMs: Long,
        maxAcceptedFixAgeMs: Long,
        expectedGpsIntervalMs: Long? = null,
    ) {
        this.predictionFreshnessMaxAgeMs = predictionFreshnessMaxAgeMs
        fixProcessor.updateMaxAcceptedFixAgeMs(maxAcceptedFixAgeMs)
        expectedGpsIntervalMs?.let(predictionCadence::updateConfiguredInterval)
    }

    fun updateActivityProfile(isBikeActivityProfile: Boolean) {
        fixProcessor.updateActivityProfile(isBikeActivityProfile)
    }

    fun reset(
        reason: String = "reset",
        nowElapsedMs: Long = state.lastAcceptedFix?.fixElapsedMs ?: 0L,
    ) {
        state.lastAcceptedFix = null
        state.displayedLatLong = null
        state.visualTrajectory.reset(nowElapsedMs).recordTelemetryInterruption()
        state.predictionRequiresFreshFix = true
        state.clampedCorrectionStreak = 0
        MarkerMotionTelemetry.recordIdle(
            nowElapsedMs = nowElapsedMs,
            reason = reason,
        )
    }

    val retainedAnchorSeed: MarkerMotionSeed?
        get() =
            state.lastAcceptedFix?.let { anchor ->
                MarkerMotionSeed(
                    latLong = state.displayedLatLong ?: anchor.latLong,
                    reading =
                        MarkerMotionReading(
                            fixElapsedMs = anchor.fixElapsedMs,
                            accuracyM = anchor.accuracyM,
                            speedMps = 0f,
                            bearingDeg = null,
                        ),
                    sourceMode = anchor.sourceMode,
                    origin = MarkerMotionAnchorOrigin.RETAINED_VISUAL,
                )
            }

    fun seedAnchor(
        seed: MarkerMotionSeed,
        nowElapsedMs: Long = seed.reading.fixElapsedMs,
        allowPredictionUntilFreshFix: Boolean = false,
    ) {
        val sanitizedSpeed = sanitizeSpeed(seed.reading.speedMps)
        val motionAccuracyM = effectiveMotionAccuracy(seed.reading.accuracyM, seed.sourceMode)
        val fixElapsedMs = seed.reading.fixElapsedMs.coerceAtLeast(0L)
        val motionFix =
            MotionFix(
                latLong = seed.latLong,
                fixElapsedMs = fixElapsedMs,
                accuracyM = motionAccuracyM,
                speedMps = sanitizedSpeed,
                bearingDeg = seed.reading.bearingDeg?.let(::normalize360),
                sourceMode = seed.sourceMode,
            )
        state.lastAcceptedFix = motionFix
        state.displayedLatLong = seed.latLong
        state.visualTrajectory.seed(motionFix.toVisualAnchor(seed.latLong)).recordTelemetryInterruption()
        state.predictionRequiresFreshFix = !allowPredictionUntilFreshFix
        state.clampedCorrectionStreak = 0
        MarkerMotionTelemetry.recordSeedAnchor(
            nowElapsedMs = nowElapsedMs,
            fixAgeMs = (nowElapsedMs - fixElapsedMs).coerceAtLeast(0L),
            accuracyM = motionAccuracyM,
            speedMps = sanitizedSpeed,
            bearingDeg = seed.reading.bearingDeg?.let(::normalize360),
            origin = seed.origin.telemetryLabel,
        )
    }

    fun requireFreshFixForPrediction(reason: String = "await_fresh_fix") {
        state.predictionRequiresFreshFix = true
        state.clampedCorrectionStreak = 0
        MarkerMotionTelemetry.recordPredictionBlocked(
            reason = reason,
            nowElapsedMs = state.lastAcceptedFix?.fixElapsedMs ?: 0L,
            fixAgeMs = null,
            accuracyM = state.lastAcceptedFix?.accuracyM,
            speedMps = state.lastAcceptedFix?.speedMps,
            bearingDeg = state.lastAcceptedFix?.bearingDeg,
        )
    }

    fun hasPendingVisualCorrection(nowElapsedMs: Long): Boolean =
        state.visualTrajectory.hasActiveCorrection(
            nowElapsedMs = nowElapsedMs,
            predictionWindow = predictionWindowFor(Long.MAX_VALUE),
        )

    fun visualMotionStatus(
        nowElapsedMs: Long,
        serviceFreshnessMaxAgeMs: Long,
    ): MarkerVisualMotionStatus =
        state.visualTrajectory.currentAnchor()?.let { anchor ->
            MarkerVisualMotionStatus(
                isMoving = anchor.speedMps >= minPredictionSpeedMps && anchor.bearingDeg != null,
                isFresh =
                    (nowElapsedMs - anchor.fixElapsedMs).coerceAtLeast(0L) <=
                        predictionWindowFor(serviceFreshnessMaxAgeMs).stopAtMs,
            )
        } ?: MarkerVisualMotionStatus(isMoving = false, isFresh = false)

    fun canProduceVisualSample(
        nowElapsedMs: Long,
        serviceFreshnessMaxAgeMs: Long,
        watchGpsDegraded: Boolean,
    ): Boolean =
        state.visualTrajectory.currentAnchor()?.let { anchor ->
            val window = predictionWindowFor(serviceFreshnessMaxAgeMs)
            val fixAgeMs = (nowElapsedMs - anchor.fixElapsedMs).coerceAtLeast(0L)
            val hasActiveCorrection =
                state.visualTrajectory.hasActiveCorrection(
                    nowElapsedMs = nowElapsedMs,
                    predictionWindow = window,
                )
            !watchGpsDegraded &&
                !state.predictionRequiresFreshFix &&
                fixAgeMs <= window.stopAtMs &&
                (
                    hasActiveCorrection ||
                        (
                            anchor.accuracyM <= maxPredictionAccuracyM &&
                                anchor.speedMps >= minPredictionSpeedMps &&
                                anchor.bearingDeg != null
                        )
                )
        } ?: false

    fun onGpsFix(fix: MarkerMotionGpsFix): MarkerMotionUpdate {
        val previousAcceptedFix = state.lastAcceptedFix
        val displayedLatLong = fixProcessor.onGpsFix(fix)
        val acceptedFix = state.lastAcceptedFix
        return MarkerMotionUpdate(
            displayedLatLong = displayedLatLong,
            fixAccepted = acceptedFix !== previousAcceptedFix,
            resolvedSpeedMps = acceptedFix?.speedMps ?: 0f,
            resolvedBearingDeg = acceptedFix?.bearingDeg,
        )
    }

    fun predict(
        nowElapsedMs: Long,
        serviceFreshnessMaxAgeMs: Long,
        watchGpsDegraded: Boolean,
    ): LatLong? {
        val currentDisplayed = state.displayedLatLong ?: state.lastAcceptedFix?.latLong ?: return null
        val anchor = state.visualTrajectory.currentAnchor() ?: return currentDisplayed

        if (watchGpsDegraded || state.predictionRequiresFreshFix) {
            MarkerMotionTelemetry.recordPredictionBlocked(
                reason =
                    when {
                        watchGpsDegraded -> "degraded_gps"
                        else -> "await_fresh_fix"
                    },
                nowElapsedMs = nowElapsedMs,
                fixAgeMs = (nowElapsedMs - anchor.fixElapsedMs).coerceAtLeast(0L),
                accuracyM = anchor.accuracyM,
                speedMps = anchor.speedMps,
                bearingDeg = anchor.bearingDeg,
            )
            return currentDisplayed
        }

        val window = predictionWindowFor(serviceFreshnessMaxAgeMs)
        val fixAgeMs = (nowElapsedMs - anchor.fixElapsedMs).coerceAtLeast(0L)
        if (fixAgeMs > window.stopAtMs) {
            MarkerMotionTelemetry.recordPredictionBlocked(
                reason = "stale",
                nowElapsedMs = nowElapsedMs,
                fixAgeMs = fixAgeMs,
                accuracyM = anchor.accuracyM,
                speedMps = anchor.speedMps,
                bearingDeg = anchor.bearingDeg,
            )
            return currentDisplayed
        }
        val hasPendingCorrection =
            state.visualTrajectory.hasActiveCorrection(
                nowElapsedMs = nowElapsedMs,
                predictionWindow = window,
            )
        if (!hasPendingCorrection && anchor.accuracyM > maxPredictionAccuracyM) {
            MarkerMotionTelemetry.recordPredictionBlocked(
                reason = "bad_accuracy",
                nowElapsedMs = nowElapsedMs,
                fixAgeMs = fixAgeMs,
                accuracyM = anchor.accuracyM,
                speedMps = anchor.speedMps,
                bearingDeg = anchor.bearingDeg,
            )
            return currentDisplayed
        }
        if (!hasPendingCorrection && anchor.speedMps < minPredictionSpeedMps) {
            MarkerMotionTelemetry.recordPredictionBlocked(
                reason = "slow",
                nowElapsedMs = nowElapsedMs,
                fixAgeMs = fixAgeMs,
                accuracyM = anchor.accuracyM,
                speedMps = anchor.speedMps,
                bearingDeg = anchor.bearingDeg,
            )
            return currentDisplayed
        }
        if (!hasPendingCorrection && anchor.bearingDeg == null) {
            MarkerMotionTelemetry.recordPredictionBlocked(
                reason = "no_bearing",
                nowElapsedMs = nowElapsedMs,
                fixAgeMs = fixAgeMs,
                accuracyM = anchor.accuracyM,
                speedMps = anchor.speedMps,
                bearingDeg = null,
            )
            return currentDisplayed
        }

        val sample = state.visualTrajectory.sample(nowElapsedMs, window) ?: return currentDisplayed
        if (sample.correctionActive) {
            MarkerMotionTelemetry.recordBlendState(
                nowElapsedMs = nowElapsedMs,
                fixAgeMs = fixAgeMs,
                anchor = anchor,
                sample = sample,
            )
        }
        sample.settledCorrectionDurationMs?.let { settledDurationMs ->
            MarkerMotionTelemetry.recordCorrectionSettled(
                reason = sample.correctionReason ?: "gps_correction",
                durationMs = settledDurationMs,
            )
        }
        if (!sample.correctionActive && sample.predictedDistanceM > 0f && anchor.bearingDeg != null) {
            MarkerMotionTelemetry.recordPredictionDisplayed(
                nowElapsedMs = nowElapsedMs,
                fixAgeMs = fixAgeMs,
                accuracyM = anchor.accuracyM,
                speedMps = anchor.speedMps,
                bearingDeg = anchor.bearingDeg,
                predictedDistanceM = sample.predictedDistanceM,
            )
        } else if (!sample.correctionActive && fixAgeMs <= PREDICTION_START_DELAY_MS) {
            MarkerMotionTelemetry.recordPredictionBlocked(
                reason = "prediction_delay",
                nowElapsedMs = nowElapsedMs,
                fixAgeMs = fixAgeMs,
                accuracyM = anchor.accuracyM,
                speedMps = anchor.speedMps,
                bearingDeg = anchor.bearingDeg,
            )
        }
        state.displayedLatLong = sample.latLong
        return sample.latLong
    }
}

@Suppress("LargeClass", "TooManyFunctions")
private class MarkerMotionGpsFixProcessor(
    private val state: MarkerMotionState,
    private val settings: MarkerMotionGpsFixProcessorSettings,
    private val predictionWindowProvider: () -> MarkerPredictionWindow,
) {
    fun updateMaxAcceptedFixAgeMs(maxAcceptedFixAgeMs: Long) {
        settings.maxAcceptedFixAgeMs = maxAcceptedFixAgeMs
    }

    fun updateActivityProfile(isBikeActivityProfile: Boolean) {
        settings.isBikeActivityProfile = isBikeActivityProfile
    }

    fun onGpsFix(fix: MarkerMotionGpsFix): LatLong {
        val context = buildGpsFixContext(fix)
        rejectGpsFix(context)?.let { return it }
        return acceptGpsFix(context)
    }

    private fun buildGpsFixContext(fix: MarkerMotionGpsFix): GpsFixContext {
        val reliableFixElapsedMs =
            fix.reading.fixElapsedMs
                .takeIf { it > 0L }
                ?.coerceAtMost(fix.nowElapsedMs)
                ?: fix.nowElapsedMs
        return GpsFixContext(
            fix = fix,
            timing =
                GpsFixTiming(
                    reliableFixElapsedMs = reliableFixElapsedMs,
                    fixAgeMs = (fix.nowElapsedMs - reliableFixElapsedMs).coerceAtLeast(0L),
                ),
            accuracyM = effectiveMotionAccuracy(fix.reading.accuracyM, fix.sourceMode),
            currentDisplayed = state.displayedLatLong,
            previousFix = state.lastAcceptedFix,
            previousVisualBearingDeg = state.visualTrajectory.currentAnchor()?.bearingDeg,
        )
    }

    private fun rejectGpsFix(context: GpsFixContext): LatLong? =
        when {
            isStaleGpsFix(context) -> rejectBlockedGpsFix(context, "stale_fix", context.currentDisplayed)
            else ->
                rejectNonForwardGpsFix(context)
                    ?: rejectDuplicateGpsFix(context)
                    ?: rejectOutlierGpsFix(context)
        }

    private fun rejectNonForwardGpsFix(context: GpsFixContext): LatLong? {
        val previousFix = context.previousFix ?: return null
        val sameSource = previousFix.sourceMode == context.fix.sourceMode
        val timestampDidNotAdvance = context.timing.reliableFixElapsedMs <= previousFix.fixElapsedMs
        return if (sameSource && timestampDidNotAdvance) {
            rejectBlockedGpsFix(
                context = context,
                reason = "non_forward_fix",
                displayLatLong = context.currentDisplayed ?: previousFix.latLong,
            )
        } else {
            null
        }
    }

    private fun isStaleGpsFix(context: GpsFixContext): Boolean {
        val hasDisplayedMarker = context.currentDisplayed != null
        val fixIsTooOld = context.timing.fixAgeMs > settings.maxAcceptedFixAgeMs
        return hasDisplayedMarker && fixIsTooOld
    }

    private fun rejectBlockedGpsFix(
        context: GpsFixContext,
        reason: String,
        displayLatLong: LatLong?,
    ): LatLong? {
        recordBlockedGpsFix(context, reason)
        return displayLatLong
    }

    private fun rejectDuplicateGpsFix(context: GpsFixContext): LatLong? {
        val previousFix = context.previousFix ?: return null
        val isDuplicate =
            isDuplicateMotionFix(
                previousFix = previousFix,
                candidate = context.fix.latLong,
                candidateFixElapsedMs = context.timing.reliableFixElapsedMs,
                candidateAccuracyM = context.accuracyM,
            )
        return if (isDuplicate) {
            rejectBlockedGpsFix(
                context = context,
                reason = "duplicate_fix",
                displayLatLong = context.currentDisplayed ?: previousFix.latLong,
            )
        } else {
            null
        }
    }

    private fun rejectOutlierGpsFix(context: GpsFixContext): LatLong? {
        val previousFix = context.previousFix
        if (previousFix != null && isSourceModeTransition(context)) {
            return null
        }
        val outlierDecision =
            previousFix?.detectOutlier(
                candidate = context.fix.latLong,
                candidateAccuracyM = context.accuracyM,
                candidateFixElapsedMs = context.timing.reliableFixElapsedMs,
            )
        return if (previousFix != null && outlierDecision != null) {
            state.clampedCorrectionStreak = 0
            MarkerMotionTelemetry.recordOutlierDropped(
                nowElapsedMs = context.fix.nowElapsedMs,
                fixAgeMs = context.timing.fixAgeMs,
                accuracyM = context.accuracyM,
                jumpMeters = outlierDecision.jumpMeters,
                impliedSpeedMps = outlierDecision.impliedSpeedMps,
                dtSec = outlierDecision.dtSec,
            )
            context.currentDisplayed ?: previousFix.latLong
        } else {
            null
        }
    }

    private fun recordBlockedGpsFix(
        context: GpsFixContext,
        reason: String,
    ) {
        MarkerMotionTelemetry.recordPredictionBlocked(
            reason = reason,
            nowElapsedMs = context.fix.nowElapsedMs,
            fixAgeMs = context.timing.fixAgeMs,
            accuracyM = context.accuracyM,
            speedMps =
                context.fix.reading.speedMps
                    ?.takeIf { it.isFinite() },
            bearingDeg =
                context.fix.reading.bearingDeg
                    ?.takeIf { it.isFinite() },
        )
    }

    private fun acceptGpsFix(context: GpsFixContext): LatLong {
        val motion = resolveAcceptedMotion(context)
        state.lastAcceptedFix =
            MotionFix(
                latLong = context.fix.latLong,
                fixElapsedMs = context.timing.reliableFixElapsedMs,
                accuracyM = context.accuracyM,
                speedMps = motion.speedMps,
                bearingDeg = motion.bearingDeg,
                sourceMode = context.fix.sourceMode,
            )
        state.predictionRequiresFreshFix = false
        return applyAcceptedGpsFix(context, motion)
    }

    private fun resolveAcceptedMotion(context: GpsFixContext): ResolvedMotion {
        val derivedMotion =
            context.previousFix?.deriveMotionTo(
                target = context.fix.latLong,
                targetFixElapsedMs = context.timing.reliableFixElapsedMs,
            )
        val resolvedSpeedMps =
            resolveMotionSpeedMps(
                rawSpeedMps = context.fix.reading.speedMps,
                derivedSpeedMps = derivedMotion?.speedMps,
                accuracyM = context.accuracyM,
                speedAccuracyMps = context.fix.reading.speedAccuracyMps,
                sourceMode = context.fix.sourceMode,
            )
        val confirmedStop =
            isConfirmedStop(
                rawSpeedMps = context.fix.reading.speedMps,
                speedAccuracyMps = context.fix.reading.speedAccuracyMps,
                derivedSpeedMps = derivedMotion?.speedMps,
                accuracyM = context.accuracyM,
            )
        val outputSpeedMps = if (confirmedStop) 0f else resolvedSpeedMps
        return ResolvedMotion(
            speedMps = outputSpeedMps,
            bearingDeg =
                resolveMotionBearingDeg(
                    input =
                        MotionBearingInput(
                            rawBearingDeg = context.fix.reading.bearingDeg,
                            rawSpeedMps = context.fix.reading.speedMps,
                            bearingAccuracyDeg = context.fix.reading.bearingAccuracyDeg,
                            derivedMotion = derivedMotion,
                            fallbackBearingDeg = context.previousFix?.bearingDeg,
                            resolvedSpeedMps = outputSpeedMps,
                            confirmedStop = confirmedStop,
                        ),
                ),
        )
    }

    private fun applyAcceptedGpsFix(
        context: GpsFixContext,
        motion: ResolvedMotion,
    ): LatLong {
        if (shouldUpdateScreenOffAnchor(context)) {
            return acceptScreenOffAnchor(context, motion)
        }
        return context.currentDisplayed
            ?.let { currentDisplayed ->
                val correction =
                    CorrectionContext(
                        currentDisplayed = currentDisplayed,
                        correctionDistanceM =
                            MarkerMotionGeometry.distanceMeters(currentDisplayed, context.fix.latLong),
                    )
                acceptCorrection(context, motion, correction)
            }
            ?: acceptInitialFix(context, motion)
    }

    private fun shouldUpdateScreenOffAnchor(context: GpsFixContext): Boolean =
        !context.fix.isMarkerVisible &&
            context.accuracyM <= settings.maxVisualCorrectionAccuracyM

    private fun acceptScreenOffAnchor(
        context: GpsFixContext,
        motion: ResolvedMotion,
    ): LatLong {
        state.displayedLatLong = context.fix.latLong
        state.visualTrajectory
            .seed(
                visualAnchor(
                    context = context,
                    motion = motion,
                    latLong = context.fix.latLong,
                ),
            ).recordTelemetryInterruption()
        state.clampedCorrectionStreak = 0
        recordFixAccepted(
            context = context,
            motion = motion,
            event =
                FixAcceptedTelemetry(
                    mode = MarkerMotionMode.FIXED,
                    reason = "screen_off_anchor",
                    correctionDistanceM =
                        context.currentDisplayed?.let { displayed ->
                            MarkerMotionGeometry.distanceMeters(displayed, context.fix.latLong)
                        },
                    blendDurationMs = null,
                ),
        )
        return context.fix.latLong
    }

    private fun acceptInitialFix(
        context: GpsFixContext,
        motion: ResolvedMotion,
    ): LatLong {
        state.displayedLatLong = context.fix.latLong
        state.visualTrajectory
            .seed(
                visualAnchor(
                    context = context,
                    motion = motion,
                    latLong = context.fix.latLong,
                ),
            ).recordTelemetryInterruption()
        state.clampedCorrectionStreak = 0
        recordFixAccepted(
            context = context,
            motion = motion,
            event =
                FixAcceptedTelemetry(
                    mode = MarkerMotionMode.FIXED,
                    reason = "initial_fix",
                    correctionDistanceM = null,
                    blendDurationMs = null,
                ),
        )
        return context.fix.latLong
    }

    private fun acceptCorrection(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correction: CorrectionContext,
    ): LatLong =
        when {
            shouldHoldWeakNonWatchCorrection(context) ->
                acceptWeakNonWatchCorrection(context, motion, correction)
            shouldFreezeStationaryJitter(correction.correctionDistanceM, context.accuracyM, motion.speedMps) ->
                acceptStationaryJitter(context, motion, correction)
            correction.correctionDistanceM <= correctionDeadbandMeters(context.accuracyM, motion.speedMps) ->
                acceptDeadbandSnap(context, motion, correction)
            else -> startCorrectionBlend(context, motion, correction)
        }

    private fun shouldHoldWeakNonWatchCorrection(context: GpsFixContext): Boolean =
        context.fix.sourceMode != LocationSourceMode.WATCH_GPS &&
            context.accuracyM > settings.maxVisualCorrectionAccuracyM

    private fun acceptWeakNonWatchCorrection(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correction: CorrectionContext,
    ): LatLong {
        refreshHeldVisualMotionIfUsable(context, motion, correction.currentDisplayed)
        state.displayedLatLong = correction.currentDisplayed
        state.clampedCorrectionStreak = 0
        recordFixAccepted(
            context = context,
            motion = motion,
            event =
                FixAcceptedTelemetry(
                    mode = MarkerMotionMode.FIXED,
                    reason = "weak_accuracy_hold",
                    correctionDistanceM = correction.correctionDistanceM,
                    blendDurationMs = null,
                ),
        )
        return correction.currentDisplayed
    }

    private fun refreshHeldVisualMotionIfUsable(
        context: GpsFixContext,
        motion: ResolvedMotion,
        displayedLatLong: LatLong,
    ) {
        val previousVisualAnchor = state.visualTrajectory.currentAnchor()
        val canContinueTrustedMotion =
            previousVisualAnchor != null &&
                previousVisualAnchor.accuracyM <= settings.maxVisualCorrectionAccuracyM &&
                motion.speedMps >= settings.minPredictionSpeedMps &&
                motion.bearingDeg != null
        if (!canContinueTrustedMotion) return
        state.visualTrajectory
            .seed(
                MarkerVisualAnchor(
                    latLong = displayedLatLong,
                    fixElapsedMs = context.timing.reliableFixElapsedMs,
                    accuracyM = previousVisualAnchor.accuracyM,
                    speedMps = motion.speedMps,
                    bearingDeg = motion.bearingDeg,
                ),
            ).recordTelemetryInterruption()
    }

    private fun acceptStationaryJitter(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correction: CorrectionContext,
    ): LatLong {
        state.visualTrajectory
            .seed(
                visualAnchor(
                    context = context,
                    motion = motion.copy(speedMps = 0f, bearingDeg = null),
                    latLong = correction.currentDisplayed,
                ),
            ).recordTelemetryInterruption()
        state.displayedLatLong = correction.currentDisplayed
        state.clampedCorrectionStreak = 0
        recordFixAccepted(
            context = context,
            motion = motion,
            event =
                FixAcceptedTelemetry(
                    mode = MarkerMotionMode.FIXED,
                    reason = "stationary_jitter",
                    correctionDistanceM = correction.correctionDistanceM,
                    blendDurationMs = null,
                ),
        )
        return correction.currentDisplayed
    }

    private fun acceptDeadbandSnap(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correction: CorrectionContext,
    ): LatLong {
        rebaseVisualTrajectory(
            context = context,
            motion = motion,
            request =
                VisualTrajectoryRebaseRequest(
                    targetLatLong = context.fix.latLong,
                    displayedLatLong = correction.currentDisplayed,
                    correctionPlan =
                        MarkerVisualCorrectionPlan(
                            durationMs = SMALL_CORRECTION_DURATION_MS,
                            reason = "deadband_snap",
                        ),
                ),
        )
        state.clampedCorrectionStreak = 0
        recordFixAccepted(
            context = context,
            motion = motion,
            event =
                FixAcceptedTelemetry(
                    mode = MarkerMotionMode.FIXED,
                    reason = "deadband_snap",
                    correctionDistanceM = correction.correctionDistanceM,
                    blendDurationMs = null,
                ),
        )
        return correction.currentDisplayed
    }

    private fun startCorrectionBlend(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correction: CorrectionContext,
    ): LatLong {
        val sustainedLagCatchUpReason = sustainedLagCatchUpReason(context, motion, correction)
        val correctionTarget =
            resolveCorrectionTarget(
                request = correctionTargetRequest(context, motion, correction, sustainedLagCatchUpReason),
            )
        updateClampTelemetry(context, motion, correction, correctionTarget)
        return if (shouldApplyCorrectionImmediately(context, sustainedLagCatchUpReason)) {
            applyImmediateCorrection(context, motion, correctionTarget, sustainedLagCatchUpReason)
        } else {
            beginCorrectionBlend(context, motion, correction, correctionTarget, sustainedLagCatchUpReason)
        }
    }

    private fun correctionTargetRequest(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correction: CorrectionContext,
        sustainedLagCatchUpReason: String?,
    ): CorrectionTargetRequest =
        CorrectionTargetRequest(
            currentDisplayed = correction.currentDisplayed,
            targetLatLong = context.fix.latLong,
            correctionDistanceM = correction.correctionDistanceM,
            accuracyM = context.accuracyM,
            speedMps = motion.speedMps,
            allowLargeCorrection =
                context.fix.allowLargeCorrection ||
                    sustainedLagCatchUpReason != null ||
                    isSourceModeTransition(context),
        )

    private fun applyImmediateCorrection(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correctionTarget: CorrectionTargetDecision,
        sustainedLagCatchUpReason: String?,
    ): LatLong {
        val displayedLatLong = state.displayedLatLong ?: correctionTarget.targetLatLong
        val correctionReason = correctionReason(context, sustainedLagCatchUpReason, correctionTarget)
        val correctionDurationMs =
            fastReanchorDurationMs(correctionTarget.visibleCorrectionDistanceM)
        rebaseVisualTrajectory(
            context = context,
            motion = motion,
            request =
                VisualTrajectoryRebaseRequest(
                    targetLatLong = correctionTarget.targetLatLong,
                    displayedLatLong = displayedLatLong,
                    correctionPlan =
                        MarkerVisualCorrectionPlan(
                            durationMs = correctionDurationMs,
                            reason = correctionReason,
                            bypassRemovalRateLimit =
                                context.fix.allowLargeCorrection ||
                                    sustainedLagCatchUpReason != null,
                        ),
                ),
        )
        recordFixAccepted(
            context = context,
            motion = motion,
            event =
                FixAcceptedTelemetry(
                    mode = MarkerMotionMode.BLEND,
                    reason = correctionReason,
                    correctionDistanceM = correctionTarget.visibleCorrectionDistanceM,
                    blendDurationMs = correctionDurationMs,
                ),
        )
        return displayedLatLong
    }

    private fun beginCorrectionBlend(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correction: CorrectionContext,
        correctionTarget: CorrectionTargetDecision,
        sustainedLagCatchUpReason: String?,
    ): LatLong {
        val durationMs = correctionDurationMs(context, correction)
        val correctionReason = correctionReason(context, sustainedLagCatchUpReason, correctionTarget)
        rebaseVisualTrajectory(
            context = context,
            motion = motion,
            request =
                VisualTrajectoryRebaseRequest(
                    targetLatLong = correctionTarget.targetLatLong,
                    displayedLatLong = correction.currentDisplayed,
                    correctionPlan =
                        MarkerVisualCorrectionPlan(
                            durationMs = durationMs,
                            reason = correctionReason,
                        ),
                ),
        )
        recordFixAccepted(
            context = context,
            motion = motion,
            event =
                FixAcceptedTelemetry(
                    mode = MarkerMotionMode.BLEND,
                    reason = correctionReason,
                    correctionDistanceM = correctionTarget.visibleCorrectionDistanceM,
                    blendDurationMs = durationMs,
                ),
        )
        return correction.currentDisplayed
    }

    private fun correctionDurationMs(
        context: GpsFixContext,
        correction: CorrectionContext,
    ): Long {
        val fixGapMs =
            context.previousFix
                ?.let { (context.timing.reliableFixElapsedMs - it.fixElapsedMs).coerceAtLeast(0L) }
                ?: DEFAULT_EXPECTED_GPS_INTERVAL_MS
        val cadenceDurationMs = (fixGapMs * CORRECTION_CADENCE_FRACTION).toLong()
        val distanceDurationMs =
            (correction.correctionDistanceM * CORRECTION_DURATION_PER_METER_MS).toLong()
        return maxOf(settings.correctionBlendDurationMs, cadenceDurationMs, distanceDurationMs)
            .coerceIn(MIN_CONTINUOUS_CORRECTION_DURATION_MS, MAX_CONTINUOUS_CORRECTION_DURATION_MS)
    }

    private fun rebaseVisualTrajectory(
        context: GpsFixContext,
        motion: ResolvedMotion,
        request: VisualTrajectoryRebaseRequest,
    ) {
        state.visualTrajectory
            .rebase(
                anchor = visualAnchor(context, motion, request.targetLatLong),
                displayedAtRebase = request.displayedLatLong,
                nowElapsedMs = context.fix.nowElapsedMs,
                predictionWindow = predictionWindowProvider(),
                correctionPlan = request.correctionPlan,
            ).recordTelemetryInterruption()
        state.displayedLatLong = request.displayedLatLong
    }

    private fun visualAnchor(
        context: GpsFixContext,
        motion: ResolvedMotion,
        latLong: LatLong,
    ): MarkerVisualAnchor =
        MarkerVisualAnchor(
            latLong = latLong,
            fixElapsedMs = context.timing.reliableFixElapsedMs,
            accuracyM = context.accuracyM,
            speedMps = motion.speedMps,
            bearingDeg = motion.bearingDeg,
        )

    private fun updateClampTelemetry(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correction: CorrectionContext,
        correctionTarget: CorrectionTargetDecision,
    ) {
        if (correctionTarget.wasClamped) {
            state.clampedCorrectionStreak += 1
            MarkerMotionTelemetry.recordCorrectionClamped(
                event =
                    CorrectionClampTelemetryEvent(
                        nowElapsedMs = context.fix.nowElapsedMs,
                        actualCorrectionDistanceM = correction.correctionDistanceM,
                        visibleCorrectionDistanceM = correctionTarget.visibleCorrectionDistanceM,
                        accuracyM = context.accuracyM,
                        speedMps = motion.speedMps,
                        bearingDeg = motion.bearingDeg,
                    ),
            )
        } else {
            state.clampedCorrectionStreak = 0
        }
    }

    private fun shouldApplyCorrectionImmediately(
        context: GpsFixContext,
        sustainedLagCatchUpReason: String?,
    ): Boolean =
        context.fix.sourceMode == LocationSourceMode.WATCH_GPS ||
            context.fix.allowLargeCorrection ||
            sustainedLagCatchUpReason != null ||
            isSourceModeTransition(context)

    private fun isSourceModeTransition(context: GpsFixContext): Boolean =
        context.previousFix?.sourceMode != null &&
            context.previousFix.sourceMode != context.fix.sourceMode

    private fun correctionReason(
        context: GpsFixContext,
        sustainedLagCatchUpReason: String?,
        correctionTarget: CorrectionTargetDecision,
    ): String =
        when {
            sustainedLagCatchUpReason != null -> sustainedLagCatchUpReason
            isSourceModeTransition(context) -> "source_switch"
            correctionTarget.wasClamped -> "correction_clamped"
            else -> "gps_correction"
        }

    private fun recordFixAccepted(
        context: GpsFixContext,
        motion: ResolvedMotion,
        event: FixAcceptedTelemetry,
    ) {
        context.currentDisplayed?.let { displayed ->
            val residualDistanceM = MarkerMotionGeometry.distanceMeters(displayed, context.fix.latLong)
            val components =
                context.previousVisualBearingDeg?.let { bearingDeg ->
                    MarkerMotionGeometry.correctionComponents(
                        from = displayed,
                        to = context.fix.latLong,
                        travelBearingDeg = bearingDeg,
                    )
                }
            MarkerMotionTelemetry.recordNextFixPredictionResidual(
                residualDistanceM = residualDistanceM,
                alongTrackErrorM = components?.alongTrackM,
                crossTrackErrorM = components?.crossTrackM,
                isMarkerVisible = context.fix.isMarkerVisible,
            )
        }
        MarkerMotionTelemetry.recordFixAccepted(
            mode = event.mode,
            reason = event.reason,
            nowElapsedMs = context.fix.nowElapsedMs,
            fixAgeMs = context.timing.fixAgeMs,
            accuracyM = context.accuracyM,
            speedMps = motion.speedMps,
            bearingDeg = motion.bearingDeg,
            correctionDistanceM = event.correctionDistanceM,
            blendDurationMs = event.blendDurationMs,
            innovationDistanceM =
                context.currentDisplayed?.let { displayed ->
                    MarkerMotionGeometry.distanceMeters(displayed, context.fix.latLong)
                },
            fixGapMs =
                context.previousFix?.let { previous ->
                    (context.timing.reliableFixElapsedMs - previous.fixElapsedMs).coerceAtLeast(0L)
                },
            rawSpeedMps = context.fix.reading.speedMps,
            speedAccuracyMps = context.fix.reading.speedAccuracyMps,
            sourceMode = context.fix.sourceMode.telemetryValue,
        )
    }

    private fun MotionFix.detectOutlier(
        candidate: LatLong,
        candidateAccuracyM: Float,
        candidateFixElapsedMs: Long,
    ): OutlierDecision? {
        val dtMs = (candidateFixElapsedMs - fixElapsedMs).coerceAtLeast(0L)
        if (dtMs < OUTLIER_MIN_WINDOW_MS) return null
        val dtSec = dtMs / 1000f

        val jumpMeters = MarkerMotionGeometry.distanceMeters(latLong, candidate)
        val impliedSpeedMps = jumpMeters / dtSec
        val allowedJumpMeters =
            max(
                MIN_OUTLIER_JUMP_M,
                accuracyM + candidateAccuracyM + OUTLIER_JUMP_MARGIN_M,
            )
        val allowedSpeedMps =
            max(
                MAX_OUTLIER_SPEED_MPS,
                speedMps * OUTLIER_SPEED_MULTIPLIER + OUTLIER_SPEED_MARGIN_M,
            )
        return if (jumpMeters > allowedJumpMeters && impliedSpeedMps > allowedSpeedMps) {
            OutlierDecision(
                jumpMeters = jumpMeters,
                impliedSpeedMps = impliedSpeedMps,
                dtSec = dtSec,
            )
        } else {
            null
        }
    }

    private fun shouldFreezeStationaryJitter(
        correctionDistanceM: Float,
        accuracyM: Float,
        speedMps: Float,
    ): Boolean {
        if (speedMps > STATIONARY_JITTER_MAX_SPEED_MPS) return false
        return correctionDistanceM <= accuracyM.coerceIn(STATIONARY_JITTER_MIN_RADIUS_M, STATIONARY_JITTER_MAX_RADIUS_M)
    }

    private fun correctionDeadbandMeters(
        accuracyM: Float,
        speedMps: Float,
    ): Float =
        when {
            speedMps < 0.6f -> accuracyM.coerceIn(2.5f, 6f)
            else -> MOVING_CORRECTION_DEADBAND_M
        }

    private fun resolveCorrectionTarget(request: CorrectionTargetRequest): CorrectionTargetDecision {
        val canClamp =
            !request.allowLargeCorrection &&
                request.correctionDistanceM >= LARGE_CORRECTION_MIN_DISTANCE_M &&
                (
                    request.accuracyM >= LARGE_CORRECTION_MIN_ACCURACY_M ||
                        request.correctionDistanceM >= LARGE_CORRECTION_FORCE_CLAMP_DISTANCE_M
                )
        val maxVisibleCorrectionM =
            if (canClamp) {
                (
                    LARGE_CORRECTION_BASE_VISIBLE_M +
                        request.accuracyM * LARGE_CORRECTION_ACCURACY_SCALE +
                        request.speedMps * LARGE_CORRECTION_SPEED_SCALE
                ).coerceAtLeast(LARGE_CORRECTION_BASE_VISIBLE_M)
            } else {
                Float.POSITIVE_INFINITY
            }
        val visibleCorrectionDistanceM = minOf(request.correctionDistanceM, maxVisibleCorrectionM)
        val wasClamped = canClamp && maxVisibleCorrectionM < request.correctionDistanceM
        val targetLatLong =
            if (wasClamped) {
                moveLatLong(
                    start = request.currentDisplayed,
                    bearing =
                        MarkerMotionGeometry.bearingBetweenDegrees(
                            request.currentDisplayed,
                            request.targetLatLong,
                        ),
                    distanceMeters = visibleCorrectionDistanceM,
                )
            } else {
                request.targetLatLong
            }
        return CorrectionTargetDecision(
            targetLatLong = targetLatLong,
            visibleCorrectionDistanceM = visibleCorrectionDistanceM,
            wasClamped = wasClamped,
        )
    }

    private fun sustainedLagCatchUpReason(
        context: GpsFixContext,
        motion: ResolvedMotion,
        correction: CorrectionContext,
    ): String? {
        if (state.clampedCorrectionStreak < SUSTAINED_LAG_CATCH_UP_CLAMP_STREAK) {
            return null
        }
        return when (context.fix.sourceMode) {
            LocationSourceMode.WATCH_GPS ->
                "watch_gps_catch_up".takeIf {
                    context.accuracyM <= WATCH_GPS_CATCH_UP_MAX_ACCURACY_M &&
                        motion.speedMps >= WATCH_GPS_CATCH_UP_MIN_SPEED_MPS &&
                        correction.correctionDistanceM >= WATCH_GPS_CATCH_UP_MIN_LAG_M
                }
            LocationSourceMode.AUTO_FUSED,
            LocationSourceMode.PASSIVE_EXTERNAL,
            ->
                when {
                    context.accuracyM <= AUTO_FUSED_CATCH_UP_MAX_ACCURACY_M &&
                        motion.speedMps >= AUTO_FUSED_CATCH_UP_MIN_SPEED_MPS &&
                        correction.correctionDistanceM >= AUTO_FUSED_CATCH_UP_MIN_LAG_M ->
                        "auto_fused_catch_up"
                    context.accuracyM <= AUTO_FUSED_HIGH_SPEED_CATCH_UP_MAX_ACCURACY_M &&
                        motion.speedMps >= AUTO_FUSED_HIGH_SPEED_CATCH_UP_MIN_SPEED_MPS &&
                        isConfirmedHighSpeedMotion(context, motion) &&
                        correction.correctionDistanceM >=
                        maxOf(
                            AUTO_FUSED_HIGH_SPEED_CATCH_UP_MIN_LAG_M,
                            context.accuracyM * AUTO_FUSED_HIGH_SPEED_CATCH_UP_ACCURACY_MULTIPLIER,
                        ) ->
                        "auto_fused_high_speed_catch_up"
                    else -> null
                }
        }
    }

    private fun isConfirmedHighSpeedMotion(
        context: GpsFixContext,
        motion: ResolvedMotion,
    ): Boolean {
        val previousFix = context.previousFix
        val derivedMotion =
            previousFix
                ?.takeIf { it.sourceMode == context.fix.sourceMode }
                ?.deriveMotionTo(
                    target = context.fix.latLong,
                    targetFixElapsedMs = context.timing.reliableFixElapsedMs,
                )
        val motionBearingDeg = motion.bearingDeg
        val derivedBearingDeg = derivedMotion?.bearingDeg
        if (derivedMotion == null || motionBearingDeg == null || derivedBearingDeg == null) return false
        val speedDifferenceMps = abs(derivedMotion.speedMps - motion.speedMps)
        val maximumSpeedDifferenceMps =
            maxOf(
                AUTO_FUSED_HIGH_SPEED_CATCH_UP_MIN_SPEED_DIFFERENCE_MPS,
                motion.speedMps * AUTO_FUSED_HIGH_SPEED_CATCH_UP_SPEED_DIFFERENCE_RATIO,
            )
        return derivedMotion.speedMps >= AUTO_FUSED_HIGH_SPEED_CATCH_UP_MIN_SPEED_MPS &&
            speedDifferenceMps <= maximumSpeedDifferenceMps &&
            angularDistanceDegrees(motionBearingDeg, derivedBearingDeg) <=
            AUTO_FUSED_HIGH_SPEED_CATCH_UP_MAX_BEARING_DIFFERENCE_DEG
    }

    private fun fastReanchorDurationMs(correctionDistanceM: Float): Long =
        (
            FAST_REANCHOR_MIN_DURATION_MS +
                correctionDistanceM.coerceAtLeast(0f) * FAST_REANCHOR_DURATION_PER_METER_MS
        ).toLong()
            .coerceIn(FAST_REANCHOR_MIN_DURATION_MS, FAST_REANCHOR_MAX_DURATION_MS)

    private fun resolveMotionSpeedMps(
        rawSpeedMps: Float?,
        derivedSpeedMps: Float?,
        accuracyM: Float,
        speedAccuracyMps: Float?,
        sourceMode: LocationSourceMode,
    ): Float {
        val trustedRawSpeed =
            rawSpeedMps
                ?.takeIf { it.isFinite() }
                ?.coerceAtLeast(0f)
        val trustedDerivedSpeed =
            derivedSpeedMps
                ?.takeIf { it.isFinite() }
                ?.coerceAtLeast(0f)
        val trustedWalkingDerivedSpeed =
            trustedDerivedSpeed
                ?.takeIf { accuracyM <= DERIVED_WALKING_SPEED_MAX_ACCURACY_M }
                ?.takeIf { it in DERIVED_WALKING_SPEED_MIN_MPS..DERIVED_WALKING_SPEED_MAX_MPS }
                ?.coerceAtMost(DERIVED_WALKING_SPEED_CAP_MPS)
        val trustedBikeDerivedSpeed =
            trustedDerivedSpeed
                ?.takeIf { settings.isBikeActivityProfile }
                ?.takeIf { accuracyM <= DERIVED_BIKE_SPEED_MAX_ACCURACY_M }
                ?.takeIf { it in DERIVED_BIKE_SPEED_MIN_MPS..DERIVED_BIKE_SPEED_MAX_MPS }
                ?.coerceAtMost(DERIVED_BIKE_SPEED_CAP_MPS)
        val profileDerivedSpeed = trustedBikeDerivedSpeed ?: trustedWalkingDerivedSpeed
        val rawSpeedAccuracyIsPoor =
            sourceMode != LocationSourceMode.WATCH_GPS &&
                (
                    speedAccuracyMps
                        ?.takeIf { it.isFinite() }
                        ?.let { accuracy ->
                            accuracy >
                                maxOf(
                                    MAX_TRUSTED_SPEED_ACCURACY_MPS,
                                    (trustedRawSpeed ?: 0f) * MAX_SPEED_ACCURACY_RATIO,
                                )
                        } ?: false
                )
        val usableRawSpeed = trustedRawSpeed.takeUnless { rawSpeedAccuracyIsPoor }

        return when {
            usableRawSpeed != null -> usableRawSpeed
            profileDerivedSpeed != null -> profileDerivedSpeed
            else -> 0f
        }
    }

    private fun isConfirmedStop(
        rawSpeedMps: Float?,
        speedAccuracyMps: Float?,
        derivedSpeedMps: Float?,
        accuracyM: Float,
    ): Boolean {
        val rawSpeed = rawSpeedMps?.takeIf { it.isFinite() }?.coerceAtLeast(0f)
        val reliableSpeedReading =
            speedAccuracyMps
                ?.takeIf { it.isFinite() }
                ?.let { it <= CONFIRMED_STOP_MAX_SPEED_ACCURACY_MPS }
                ?: false
        val positionAlsoStopped =
            accuracyM <= CONFIRMED_STOP_MAX_POSITION_ACCURACY_M &&
                derivedSpeedMps?.takeIf { it.isFinite() }?.let { it <= CONFIRMED_STOP_MAX_DERIVED_SPEED_MPS } == true
        return rawSpeed != null &&
            rawSpeed <= CONFIRMED_STOP_MAX_SPEED_MPS &&
            (reliableSpeedReading || positionAlsoStopped)
    }

    private fun resolveMotionBearingDeg(input: MotionBearingInput): Float? {
        if (input.confirmedStop) return null
        val rawBearingAccuracyIsUsable =
            input.bearingAccuracyDeg
                ?.takeIf { it.isFinite() }
                ?.let { it <= MAX_TRUSTED_BEARING_ACCURACY_DEG }
                ?: true
        val rawBearingIsUsable =
            input.rawBearingDeg != null &&
                input.rawBearingDeg.isFinite() &&
                rawBearingAccuracyIsUsable &&
                max(sanitizeSpeed(input.rawSpeedMps), input.resolvedSpeedMps) >= GPS_BEARING_MIN_SPEED_MPS
        val derivedBearingIsUsable =
            input.derivedMotion?.bearingDeg != null &&
                max(input.derivedMotion.speedMps, input.resolvedSpeedMps) >= settings.minPredictionSpeedMps
        return when {
            rawBearingIsUsable -> normalize360(input.rawBearingDeg)
            derivedBearingIsUsable -> input.derivedMotion.bearingDeg
            else -> input.fallbackBearingDeg?.let(::normalize360)
        }
    }
}

private class MarkerMotionState {
    var lastAcceptedFix: MotionFix? = null
    var displayedLatLong: LatLong? = null
    val visualTrajectory = MarkerVisualTrajectory()
    var predictionRequiresFreshFix: Boolean = true
    var clampedCorrectionStreak: Int = 0
}

private data class MarkerMotionGpsFixProcessorSettings(
    var maxAcceptedFixAgeMs: Long,
    val maxVisualCorrectionAccuracyM: Float,
    val minPredictionSpeedMps: Float,
    val correctionBlendDurationMs: Long,
    var isBikeActivityProfile: Boolean = false,
)

private data class MotionFix(
    val latLong: LatLong,
    val fixElapsedMs: Long,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float?,
    val sourceMode: LocationSourceMode,
)

private data class GpsFixTiming(
    val reliableFixElapsedMs: Long,
    val fixAgeMs: Long,
)

private data class GpsFixContext(
    val fix: MarkerMotionGpsFix,
    val timing: GpsFixTiming,
    val accuracyM: Float,
    val currentDisplayed: LatLong?,
    val previousFix: MotionFix?,
    val previousVisualBearingDeg: Float?,
)

private data class ResolvedMotion(
    val speedMps: Float,
    val bearingDeg: Float?,
)

private data class CorrectionContext(
    val currentDisplayed: LatLong,
    val correctionDistanceM: Float,
)

private data class VisualTrajectoryRebaseRequest(
    val targetLatLong: LatLong,
    val displayedLatLong: LatLong,
    val correctionPlan: MarkerVisualCorrectionPlan,
)

private data class FixAcceptedTelemetry(
    val mode: MarkerMotionMode,
    val reason: String,
    val correctionDistanceM: Float?,
    val blendDurationMs: Long?,
)

private data class DerivedMotion(
    val speedMps: Float,
    val bearingDeg: Float?,
)

private data class MotionBearingInput(
    val rawBearingDeg: Float?,
    val rawSpeedMps: Float?,
    val bearingAccuracyDeg: Float?,
    val derivedMotion: DerivedMotion?,
    val fallbackBearingDeg: Float?,
    val resolvedSpeedMps: Float,
    val confirmedStop: Boolean,
)

private data class OutlierDecision(
    val jumpMeters: Float,
    val impliedSpeedMps: Float,
    val dtSec: Float,
)

private data class CorrectionTargetDecision(
    val targetLatLong: LatLong,
    val visibleCorrectionDistanceM: Float,
    val wasClamped: Boolean,
)

private data class CorrectionTargetRequest(
    val currentDisplayed: LatLong,
    val targetLatLong: LatLong,
    val correctionDistanceM: Float,
    val accuracyM: Float,
    val speedMps: Float,
    val allowLargeCorrection: Boolean,
)

private fun MotionFix.toVisualAnchor(latLong: LatLong = this.latLong): MarkerVisualAnchor =
    MarkerVisualAnchor(
        latLong = latLong,
        fixElapsedMs = fixElapsedMs,
        accuracyM = accuracyM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
    )

private fun MotionFix.deriveMotionTo(
    target: LatLong,
    targetFixElapsedMs: Long,
): DerivedMotion? {
    val dtMs = (targetFixElapsedMs - fixElapsedMs).coerceAtLeast(0L)
    if (dtMs < DERIVED_MOTION_MIN_WINDOW_MS) return null
    val dtSec = dtMs / 1000f
    if (dtSec <= 0f) return null
    val distanceM = MarkerMotionGeometry.distanceMeters(latLong, target)
    val speedMps = distanceM / dtSec
    return DerivedMotion(
        speedMps = speedMps,
        bearingDeg = MarkerMotionGeometry.bearingBetweenDegrees(latLong, target),
    )
}

private fun sanitizeAccuracy(accuracyM: Float): Float {
    if (!accuracyM.isFinite()) return DEFAULT_UNKNOWN_ACCURACY_M
    return accuracyM.coerceAtLeast(0f)
}

private fun effectiveMotionAccuracy(
    accuracyM: Float,
    sourceMode: LocationSourceMode,
): Float {
    val sanitizedAccuracy = sanitizeAccuracy(accuracyM)
    if (
        sourceMode == LocationSourceMode.WATCH_GPS &&
        isKnownWatchGpsAccuracyFloor(sanitizedAccuracy)
    ) {
        return WATCH_GPS_FLOOR_MOTION_ACCURACY_M
    }
    return sanitizedAccuracy
}

private fun isKnownWatchGpsAccuracyFloor(
    accuracyM: Float,
): Boolean {
    if (!accuracyM.isFinite()) return false
    return abs(accuracyM - WATCH_GPS_ACCURACY_FLOOR_M) <= WATCH_GPS_ACCURACY_FLOOR_TOLERANCE_M
}

private fun isDuplicateMotionFix(
    previousFix: MotionFix,
    candidate: LatLong,
    candidateFixElapsedMs: Long,
    candidateAccuracyM: Float,
): Boolean {
    val fixTimeDeltaMs = candidateFixElapsedMs - previousFix.fixElapsedMs
    val isSameTime = fixTimeDeltaMs <= DUPLICATE_FIX_TIME_EPSILON_MS
    val isSameAccuracy = abs(previousFix.accuracyM - candidateAccuracyM) <= DUPLICATE_FIX_ACCURACY_EPSILON_M
    val isSamePosition =
        MarkerMotionGeometry.distanceMeters(previousFix.latLong, candidate) <= DUPLICATE_FIX_DISTANCE_EPSILON_M
    return isSameTime && isSameAccuracy && isSamePosition
}

private fun sanitizeSpeed(speedMps: Float?): Float {
    if (speedMps == null || !speedMps.isFinite()) return 0f
    return speedMps.coerceAtLeast(0f)
}

private fun normalize360(angleDeg: Float): Float {
    var normalized = angleDeg % 360f
    if (normalized < 0f) normalized += 360f
    return normalized
}

private fun angularDistanceDegrees(
    firstDeg: Float,
    secondDeg: Float,
): Float = abs((((firstDeg - secondDeg) + 540f) % 360f) - 180f)

private fun MarkerVisualCorrectionEnd?.recordTelemetryInterruption() {
    this?.let { interruption ->
        MarkerMotionTelemetry.recordCorrectionInterrupted(
            reason = interruption.reason,
            durationMs = interruption.durationMs,
        )
    }
}

private const val DEFAULT_UNKNOWN_ACCURACY_M = 99f
private const val DEFAULT_MAX_PREDICTION_ACCURACY_M = 35f
private const val WATCH_GPS_FLOOR_MOTION_ACCURACY_M = 18f
private const val DEFAULT_MIN_PREDICTION_SPEED_MPS = 0.35f
private const val DEFAULT_CORRECTION_BLEND_DURATION_MS = 1_200L
private const val DEFAULT_EXPECTED_GPS_INTERVAL_MS = 3_000L
private const val MIN_EXPECTED_GPS_INTERVAL_MS = 1_000L
private const val PREDICTION_START_DELAY_MS = 50L
private const val DUPLICATE_FIX_TIME_EPSILON_MS = 250L
private const val DUPLICATE_FIX_DISTANCE_EPSILON_M = 0.25f
private const val DUPLICATE_FIX_ACCURACY_EPSILON_M = 0.1f
private const val SUSTAINED_LAG_CATCH_UP_CLAMP_STREAK = 2
private const val WATCH_GPS_CATCH_UP_MIN_LAG_M = 60f
private const val WATCH_GPS_CATCH_UP_MIN_SPEED_MPS = 2.0f
private const val WATCH_GPS_CATCH_UP_MAX_ACCURACY_M = 25f
private const val AUTO_FUSED_CATCH_UP_MIN_LAG_M = 35f
private const val AUTO_FUSED_CATCH_UP_MIN_SPEED_MPS = 0.8f
private const val AUTO_FUSED_CATCH_UP_MAX_ACCURACY_M = 12f
private const val AUTO_FUSED_HIGH_SPEED_CATCH_UP_MIN_LAG_M = 60f
private const val AUTO_FUSED_HIGH_SPEED_CATCH_UP_MIN_SPEED_MPS = 5f
private const val AUTO_FUSED_HIGH_SPEED_CATCH_UP_MAX_ACCURACY_M = 25f
private const val AUTO_FUSED_HIGH_SPEED_CATCH_UP_ACCURACY_MULTIPLIER = 3f
private const val AUTO_FUSED_HIGH_SPEED_CATCH_UP_MIN_SPEED_DIFFERENCE_MPS = 5f
private const val AUTO_FUSED_HIGH_SPEED_CATCH_UP_SPEED_DIFFERENCE_RATIO = 0.5f
private const val AUTO_FUSED_HIGH_SPEED_CATCH_UP_MAX_BEARING_DIFFERENCE_DEG = 45f
private const val GPS_BEARING_MIN_SPEED_MPS = 0.45f
private const val MAX_TRUSTED_SPEED_ACCURACY_MPS = 1.5f
private const val MAX_SPEED_ACCURACY_RATIO = 0.75f
private const val MAX_TRUSTED_BEARING_ACCURACY_DEG = 45f
private const val CONFIRMED_STOP_MAX_SPEED_MPS = 0.25f
private const val CONFIRMED_STOP_MAX_SPEED_ACCURACY_MPS = 0.8f
private const val CONFIRMED_STOP_MAX_DERIVED_SPEED_MPS = 0.35f
private const val CONFIRMED_STOP_MAX_POSITION_ACCURACY_M = 20f
private const val DERIVED_MOTION_MIN_WINDOW_MS = 900L
private const val DERIVED_WALKING_SPEED_MAX_ACCURACY_M = 35f
private const val DERIVED_WALKING_SPEED_MIN_MPS = 0.25f
private const val DERIVED_WALKING_SPEED_MAX_MPS = 2.4f
private const val DERIVED_WALKING_SPEED_CAP_MPS = 1.8f
private const val DERIVED_BIKE_SPEED_MAX_ACCURACY_M = 25f
private const val DERIVED_BIKE_SPEED_MIN_MPS = 0.5f
private const val DERIVED_BIKE_SPEED_MAX_MPS = 20f
private const val DERIVED_BIKE_SPEED_CAP_MPS = 15f
private const val OUTLIER_MIN_WINDOW_MS = 1_000L
private const val MIN_OUTLIER_JUMP_M = 24f
private const val OUTLIER_JUMP_MARGIN_M = 10f
private const val MAX_OUTLIER_SPEED_MPS = 14f
private const val OUTLIER_SPEED_MULTIPLIER = 2.5f
private const val OUTLIER_SPEED_MARGIN_M = 5f
private const val STATIONARY_JITTER_MAX_SPEED_MPS = 0.35f
private const val STATIONARY_JITTER_MIN_RADIUS_M = 3f
private const val STATIONARY_JITTER_MAX_RADIUS_M = 10f
private const val MOVING_CORRECTION_DEADBAND_M = 1.2f
private const val SMALL_CORRECTION_DURATION_MS = 500L
private const val FAST_REANCHOR_MIN_DURATION_MS = 600L
private const val FAST_REANCHOR_MAX_DURATION_MS = 1_200L
private const val FAST_REANCHOR_DURATION_PER_METER_MS = 2f
private const val MIN_CONTINUOUS_CORRECTION_DURATION_MS = 600L
private const val MAX_CONTINUOUS_CORRECTION_DURATION_MS = 2_400L

// Spread ordinary GPS residuals across most of the selected cadence, keeping the base trajectory
// visibly forward-moving instead of front-loading every correction into a short deceleration.
private const val CORRECTION_CADENCE_FRACTION = 0.75
private const val CORRECTION_DURATION_PER_METER_MS = 100f
private const val LARGE_CORRECTION_MIN_DISTANCE_M = 18f
private const val LARGE_CORRECTION_MIN_ACCURACY_M = 14f
private const val LARGE_CORRECTION_FORCE_CLAMP_DISTANCE_M = 26f
private const val LARGE_CORRECTION_BASE_VISIBLE_M = 8f
private const val LARGE_CORRECTION_ACCURACY_SCALE = 0.35f
private const val LARGE_CORRECTION_SPEED_SCALE = 2.2f
