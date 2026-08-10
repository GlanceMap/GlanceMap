package com.glancemap.glancemapwearos.domain.sensors

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class CompassTrackingState(
    val telemetryToken: String,
) {
    ACQUIRING("acquiring"),
    TRACKING("tracking"),
    DEGRADED("degraded"),
}

enum class CompassTrackingReason(
    val telemetryToken: String,
) {
    STARTUP("startup"),
    STABLE("stable"),
    ABSOLUTE_WINDOW_UNSTABLE("absolute_window_unstable"),
    MAGNETIC_INTERFERENCE("magnetic_interference"),
    ABSOLUTE_RELATIVE_DISAGREEMENT("absolute_relative_disagreement"),
    ABSOLUTE_UNUSABLE("absolute_unusable"),
    RELATIVE_UNAVAILABLE("relative_unavailable"),
    RECOVERING("recovering"),
}

enum class CompassMagneticQuality(
    val telemetryToken: String,
) {
    UNKNOWN("unknown"),
    GOOD("good"),
    RECOVERING("recovering"),
    INTERFERENCE("interference"),
    UNAVAILABLE("unavailable"),
}

enum class CompassNorthBasis(
    val telemetryToken: String,
) {
    GOOGLE_AUTOMATIC("google_automatic"),
    TRUE_APP_DECLINATION("true_app_declination"),
    MAGNETIC("magnetic"),
    UNKNOWN("unknown"),
}

internal data class FusedAbsoluteHeadingSample(
    val headingDeg: Float,
    val liveErrorDeg: Float?,
    val conservativeErrorDeg: Float?,
    val atElapsedMs: Long,
)

internal data class FusedHeadingIntegritySnapshot(
    val state: CompassTrackingState,
    val reason: CompassTrackingReason,
    val renderHeadingDeg: Float?,
    val renderable: Boolean,
    val trusted: Boolean,
    val magneticQuality: CompassMagneticQuality,
    val magneticFieldUt: Float?,
    val relativeHeadingDeg: Float?,
    val relativeWitnessAvailable: Boolean,
    val relativeWitnessSuppressed: Boolean,
    val relativeWitnessSupportsHighRate: Boolean,
    val relativeHorizontalProjection: Float?,
    val absoluteRelativeDisagreementDeg: Float?,
    val residualSpreadDeg: Float?,
    val quarantinedAbsoluteHeadingDeg: Float?,
    val quarantineActive: Boolean,
    val recoveryActive: Boolean,
    val recoveryCorrectionDeg: Float,
)

internal data class FusedHeadingIntegrityConfig(
    val acquisitionWindowMs: Long = 400L,
    val acquisitionMinimumSamples: Int = 8,
    val acquisitionResidualSpreadDeg: Float = 18f,
    val relativeSampleFreshnessMs: Long = 300L,
    val trackingDisagreementEnterDeg: Float = 45f,
    val weakConfidenceDisagreementEnterDeg: Float = 20f,
    val trackingDisagreementExitDeg: Float = 18f,
    val magneticNormalMinimumUt: Float = 15f,
    val magneticNormalMaximumUt: Float = 85f,
    val magneticHardMinimumUt: Float = 10f,
    val magneticHardMaximumUt: Float = 120f,
    val initialMagneticStabilityMs: Long = 200L,
    val magneticRecoveryStabilityMs: Long = 800L,
    val magneticSampleStaleMs: Long = 1_000L,
    val magneticSampleUnavailableMs: Long = 3_000L,
    val recoveryEvidenceWindowMs: Long = 1_000L,
    val recoveryResidualSpreadDeg: Float = 12f,
    val strongLiveErrorDeg: Float = 12f,
    val strongConservativeErrorDeg: Float = 45f,
    val witnessSuppressionMinimumSamples: Int = 3,
    val witnessSuppressionMinimumDurationMs: Long = 200L,
    val verifiedFusedCorrectionRateDegPerSec: Float = 720f,
    val unverifiedFusedCorrectionRateDegPerSec: Float = 180f,
    val unverifiedFusedFastTurnCorrectionRateDegPerSec: Float = 360f,
    val unverifiedFusedFastTurnEnterRateDegPerSec: Float = 120f,
    val unverifiedFusedFastTurnMinimumSamples: Int = 2,
)

private data class AbsoluteMovementEvidence(
    val elapsedSinceAbsoluteMs: Long,
    val relativeHeadingDeg: Float?,
    val relativeStepDeg: Float?,
    val absoluteStepDeg: Float?,
    val stepDisagreementDeg: Float?,
    val residualDeg: Float?,
)

private data class AbsoluteHeadingEvidence(
    val absoluteHeadingDeg: Float,
    val relativeStepDeg: Float?,
    val absoluteStepDeg: Float?,
    val stepDisagreementDeg: Float?,
    val elapsedSinceAbsoluteMs: Long,
    val fieldAcceptable: Boolean,
    val residualSpreadDeg: Float?,
    val absoluteSpreadDeg: Float?,
    val hardDisagreement: Boolean,
    val strongAbsoluteConfidence: Boolean,
    val disagreementDeg: Float?,
    val residualDeg: Float?,
    val atElapsedMs: Long,
)

/**
 * Validates one absolute Google Fused heading against a magnetometer-independent relative turn.
 *
 * The game rotation vector never supplies north. It only validates whether a Google Fused turn
 * is plausible; it never drives the heading rendered on the map.
 * Event and state handlers intentionally share this single mutable state owner.
 */
@Suppress("TooManyFunctions")
internal class FusedHeadingIntegrityEngine(
    private val relativeSensorAvailable: Boolean,
    private val magnetometerAvailable: Boolean,
    private val config: FusedHeadingIntegrityConfig = FusedHeadingIntegrityConfig(),
) {
    private var state = CompassTrackingState.ACQUIRING
    private var reason = CompassTrackingReason.STARTUP
    private var renderHeadingDeg: Float? = null
    private var lastAbsoluteHeadingDeg: Float? = null
    private var lastAbsoluteAtElapsedMs = 0L
    private var evidenceStartedAtElapsedMs = 0L
    private var latestRelativeHeadingDeg: Float? = null
    private var latestRelativeAtElapsedMs = 0L
    private val relativeWitnessValidator = RelativeHeadingWitnessValidator(config)
    private var relativeHeadingAtLastAbsoluteDeg: Float? = null
    private var relativeAtLastAbsoluteElapsedMs = 0L
    private var trackingResidualAnchorDeg: Float? = null
    private var magneticQuality =
        if (magnetometerAvailable) {
            CompassMagneticQuality.UNKNOWN
        } else {
            CompassMagneticQuality.UNAVAILABLE
        }
    private var magneticFieldUt: Float? = null
    private var lastMagneticAtElapsedMs = 0L
    private var magneticGoodSinceElapsedMs = 0L
    private var magneticInterferenceSeen = false
    private var recoveryActive = false
    private var quarantineActive = false
    private var trusted = false
    private var lastDisagreementDeg: Float? = null
    private var lastResidualSpreadDeg: Float? = null
    private var lastRecoveryCorrectionDeg = 0f
    private var quarantinedAbsoluteHeadingDeg: Float? = null
    private var unverifiedFastTurnDirection = 0
    private var unverifiedFastTurnSampleCount = 0
    private val relativeHistory = ArrayDeque<TimedCircularValue>()
    private val residualWindow = ArrayDeque<TimedCircularValue>()
    private val absoluteWindow = ArrayDeque<TimedCircularValue>()

    fun reset(
        seedHeadingDeg: Float?,
        atElapsedMs: Long,
        clearSensorEvidence: Boolean = false,
    ) {
        state = CompassTrackingState.ACQUIRING
        reason = CompassTrackingReason.STARTUP
        renderHeadingDeg = seedHeadingDeg?.takeIf(Float::isFinite)?.let(::normalize360Deg)
        lastAbsoluteHeadingDeg = null
        lastAbsoluteAtElapsedMs = atElapsedMs
        evidenceStartedAtElapsedMs = atElapsedMs
        relativeHeadingAtLastAbsoluteDeg = null
        relativeAtLastAbsoluteElapsedMs = 0L
        trackingResidualAnchorDeg = null
        relativeWitnessValidator.reset()
        recoveryActive = false
        quarantineActive = false
        trusted = false
        lastDisagreementDeg = null
        lastResidualSpreadDeg = null
        lastRecoveryCorrectionDeg = 0f
        quarantinedAbsoluteHeadingDeg = null
        resetUnverifiedFastTurnEvidence()
        residualWindow.clear()
        absoluteWindow.clear()
        if (clearSensorEvidence) {
            latestRelativeHeadingDeg = null
            latestRelativeAtElapsedMs = 0L
            relativeHistory.clear()
            magneticFieldUt = null
            lastMagneticAtElapsedMs = 0L
            magneticGoodSinceElapsedMs = 0L
            magneticInterferenceSeen = false
            magneticQuality =
                if (magnetometerAvailable) {
                    CompassMagneticQuality.UNKNOWN
                } else {
                    CompassMagneticQuality.UNAVAILABLE
                }
        }
    }

    fun onRelativeHeading(
        headingDeg: Float,
        horizontalProjection: Float = 1f,
        atElapsedMs: Long,
    ): FusedHeadingIntegritySnapshot {
        if (headingDeg.isFinite()) {
            val normalizedHeadingDeg = normalize360Deg(headingDeg)
            latestRelativeHeadingDeg = normalizedHeadingDeg
            latestRelativeAtElapsedMs = atElapsedMs
            relativeWitnessValidator.onHeading(horizontalProjection)
            relativeHistory.addLast(TimedCircularValue(atElapsedMs, normalizedHeadingDeg))
            trimWindow(relativeHistory, atElapsedMs, RELATIVE_HISTORY_WINDOW_MS)
        }
        return buildSnapshot()
    }

    fun onRelativeWitnessUnavailable(
        horizontalProjection: Float,
    ): FusedHeadingIntegritySnapshot {
        latestRelativeHeadingDeg = null
        latestRelativeAtElapsedMs = 0L
        relativeHistory.clear()
        relativeWitnessValidator.onUnavailable(horizontalProjection)
        return buildSnapshot()
    }

    fun onMagneticField(
        strengthUt: Float,
        atElapsedMs: Long,
    ): FusedHeadingIntegritySnapshot {
        if (magnetometerAvailable && strengthUt.isFinite()) {
            magneticFieldUt = strengthUt
            lastMagneticAtElapsedMs = atElapsedMs

            val hardInvalid =
                strengthUt < config.magneticHardMinimumUt ||
                    strengthUt > config.magneticHardMaximumUt
            val outsideNormal =
                strengthUt < config.magneticNormalMinimumUt ||
                    strengthUt > config.magneticNormalMaximumUt
            if (hardInvalid || outsideNormal) {
                magneticInterferenceSeen = true
                magneticGoodSinceElapsedMs = 0L
                magneticQuality = CompassMagneticQuality.INTERFERENCE
                enterDegraded(
                    degradationReason = CompassTrackingReason.MAGNETIC_INTERFERENCE,
                    quarantinedHeadingDeg = lastAbsoluteHeadingDeg,
                )
            } else {
                updateAcceptableMagneticField(atElapsedMs)
            }
        }
        return buildSnapshot()
    }

    private fun updateAcceptableMagneticField(atElapsedMs: Long) {
        if (magneticGoodSinceElapsedMs <= 0L) {
            magneticGoodSinceElapsedMs = atElapsedMs
        }
        val requiredStableMs =
            if (magneticInterferenceSeen) {
                config.magneticRecoveryStabilityMs
            } else {
                config.initialMagneticStabilityMs
            }
        magneticQuality =
            if (atElapsedMs - magneticGoodSinceElapsedMs >= requiredStableMs) {
                CompassMagneticQuality.GOOD
            } else {
                CompassMagneticQuality.RECOVERING
            }
    }

    fun onAbsoluteHeading(sample: FusedAbsoluteHeadingSample): FusedHeadingIntegritySnapshot {
        val atElapsedMs = sample.atElapsedMs
        refreshMagneticStaleness(atElapsedMs)
        if (!sample.headingDeg.isFinite()) {
            enterDegraded(
                degradationReason = CompassTrackingReason.ABSOLUTE_UNUSABLE,
                quarantinedHeadingDeg = null,
            )
            trusted = false
            return snapshot()
        }

        val evidence = collectAbsoluteHeadingEvidence(sample)
        relativeWitnessValidator.update(evidence)
        val correction =
            when (state) {
                CompassTrackingState.ACQUIRING -> updateWhileAcquiring(evidence)
                CompassTrackingState.TRACKING -> updateWhileTracking(evidence)
                CompassTrackingState.DEGRADED -> updateWhileDegraded(evidence)
            }

        trusted =
            state == CompassTrackingState.TRACKING &&
            !recoveryActive &&
            evidence.fieldAcceptable &&
            evidence.strongAbsoluteConfidence
        lastDisagreementDeg = evidence.disagreementDeg
        lastResidualSpreadDeg = evidence.residualSpreadDeg ?: evidence.absoluteSpreadDeg
        lastRecoveryCorrectionDeg = correction
        return buildSnapshot()
    }

    private fun collectAbsoluteHeadingEvidence(
        sample: FusedAbsoluteHeadingSample,
    ): AbsoluteHeadingEvidence {
        val absoluteHeadingDeg = normalize360Deg(sample.headingDeg)
        val atElapsedMs = sample.atElapsedMs
        val movement = measureAbsoluteMovement(absoluteHeadingDeg, atElapsedMs)
        appendEvidence(
            absoluteHeadingDeg = absoluteHeadingDeg,
            residualDeg = movement.residualDeg,
            atElapsedMs = atElapsedMs,
        )
        val residualSpreadDeg = circularWindowSpreadDeg(residualWindow)
        val absoluteSpreadDeg = circularWindowSpreadDeg(absoluteWindow)
        val residualAnchorDeg = trackingResidualAnchorDeg
        val anchorDisagreementDeg =
            if (movement.residualDeg != null && residualAnchorDeg != null) {
                abs(shortestAngleDiffDeg(movement.residualDeg, residualAnchorDeg))
            } else {
                null
            }
        val disagreementDeg =
            maxOfNullable(movement.stepDisagreementDeg, anchorDisagreementDeg)

        lastAbsoluteHeadingDeg = absoluteHeadingDeg
        lastAbsoluteAtElapsedMs = atElapsedMs
        if (movement.relativeHeadingDeg != null) {
            relativeHeadingAtLastAbsoluteDeg = movement.relativeHeadingDeg
            relativeAtLastAbsoluteElapsedMs = latestRelativeAtElapsedMs
        } else {
            relativeHeadingAtLastAbsoluteDeg = null
            relativeAtLastAbsoluteElapsedMs = 0L
        }

        val fieldAcceptable = magneticFieldAcceptable()
        val strongAbsoluteConfidence = hasStrongAbsoluteConfidence(sample)
        val disagreementEnterDeg = disagreementEnterThresholdDeg(strongAbsoluteConfidence)
        val hardDisagreement =
            when {
                disagreementDeg != null -> disagreementDeg >= disagreementEnterDeg
                relativeSensorAvailable && movement.relativeHeadingDeg == null -> false
                movement.absoluteStepDeg != null ->
                    abs(movement.absoluteStepDeg) >= disagreementEnterDeg
                else -> false
            }
        return AbsoluteHeadingEvidence(
            absoluteHeadingDeg = absoluteHeadingDeg,
            relativeStepDeg = movement.relativeStepDeg,
            absoluteStepDeg = movement.absoluteStepDeg,
            stepDisagreementDeg = movement.stepDisagreementDeg,
            elapsedSinceAbsoluteMs = movement.elapsedSinceAbsoluteMs,
            fieldAcceptable = fieldAcceptable,
            residualSpreadDeg = residualSpreadDeg,
            absoluteSpreadDeg = absoluteSpreadDeg,
            hardDisagreement = hardDisagreement,
            strongAbsoluteConfidence = strongAbsoluteConfidence,
            disagreementDeg = disagreementDeg,
            residualDeg = movement.residualDeg,
            atElapsedMs = atElapsedMs,
        )
    }

    private fun measureAbsoluteMovement(
        absoluteHeadingDeg: Float,
        atElapsedMs: Long,
    ): AbsoluteMovementEvidence {
        val previousAbsoluteHeadingDeg = lastAbsoluteHeadingDeg
        val elapsedSinceAbsoluteMs =
            (atElapsedMs - lastAbsoluteAtElapsedMs)
                .coerceAtLeast(1L)
                .coerceAtMost(MAX_CONTINUITY_STEP_INTERVAL_MS)
        val relativeHeadingDeg = freshRelativeHeading(atElapsedMs)
        val previousRelativeHeadingDeg = relativeHeadingAtLastAbsoluteDeg
        val relativeStepDeg =
            if (
                relativeHeadingDeg != null &&
                previousRelativeHeadingDeg != null &&
                relativeAtLastAbsoluteElapsedMs > 0L
            ) {
                shortestAngleDiffDeg(relativeHeadingDeg, previousRelativeHeadingDeg)
            } else {
                null
            }
        val absoluteStepDeg =
            previousAbsoluteHeadingDeg?.let { previous ->
                shortestAngleDiffDeg(absoluteHeadingDeg, previous)
            }
        val stepDisagreementDeg =
            if (absoluteStepDeg != null && relativeStepDeg != null) {
                abs(shortestAngleDiffDeg(absoluteStepDeg, relativeStepDeg))
            } else {
                null
            }
        val residualDeg =
            relativeHeadingDeg?.let { relative ->
                normalize360Deg(absoluteHeadingDeg - relative)
            }
        return AbsoluteMovementEvidence(
            elapsedSinceAbsoluteMs = elapsedSinceAbsoluteMs,
            relativeHeadingDeg = relativeHeadingDeg,
            relativeStepDeg = relativeStepDeg,
            absoluteStepDeg = absoluteStepDeg,
            stepDisagreementDeg = stepDisagreementDeg,
            residualDeg = residualDeg,
        )
    }

    fun snapshot(): FusedHeadingIntegritySnapshot = buildSnapshot()

    private fun updateWhileAcquiring(evidence: AbsoluteHeadingEvidence): Float =
        when {
            !evidence.fieldAcceptable -> {
                reason = unavailableMagneticReason()
                0f
            }
            else -> {
                val useRelativeEvidence = hasRelativeEvidence(evidence.atElapsedMs)
                val evidenceWindow =
                    if (useRelativeEvidence) residualWindow else absoluteWindow
                val evidenceSpread =
                    if (useRelativeEvidence) {
                        evidence.residualSpreadDeg
                    } else {
                        evidence.absoluteSpreadDeg
                    }
                val evidenceReady =
                    evidenceWindow.size >= config.acquisitionMinimumSamples &&
                        windowAgeMs(evidenceWindow, evidence.atElapsedMs) >=
                        config.acquisitionWindowMs &&
                        evidenceSpread != null &&
                        evidenceSpread <= config.acquisitionResidualSpreadDeg
                if (!evidenceReady) {
                    reason = CompassTrackingReason.ABSOLUTE_WINDOW_UNSTABLE
                    0f
                } else {
                    // Preserve the previous rendered heading until the new provider has
                    // established a stable reference. A first fused sample can be far from
                    // the last known heading while its magnetic integrity is still unknown.
                    renderHeadingDeg = moveTowardFusedHeading(evidence)
                    completeAcquisition()
                }
            }
        }

    private fun completeAcquisition(): Float {
        trackingResidualAnchorDeg = residualWindow.lastOrNull()?.valueDeg
        state = CompassTrackingState.TRACKING
        reason = CompassTrackingReason.STABLE
        recoveryActive = false
        quarantineActive = false
        quarantinedAbsoluteHeadingDeg = null
        return 0f
    }

    private fun updateWhileTracking(evidence: AbsoluteHeadingEvidence): Float =
        if (!evidence.fieldAcceptable) {
            enterDegraded(
                degradationReason =
                    if (!evidence.fieldAcceptable) {
                        unavailableMagneticReason()
                    } else {
                        CompassTrackingReason.ABSOLUTE_RELATIVE_DISAGREEMENT
                    },
                quarantinedHeadingDeg = evidence.absoluteHeadingDeg,
            )
            renderHeadingDeg = moveTowardFusedHeading(evidence)
            0f
        } else {
            updateTrackingAnchor(evidence)
            renderHeadingDeg = moveTowardFusedHeading(evidence)
            reason = CompassTrackingReason.STABLE
            recoveryActive = false
            quarantineActive = false
            quarantinedAbsoluteHeadingDeg = null
            0f
        }

    private fun updateTrackingAnchor(evidence: AbsoluteHeadingEvidence) {
        val residualDeg = evidence.residualDeg
        val disagreementDeg = evidence.disagreementDeg
        if (
            residualDeg != null &&
            disagreementDeg != null &&
            disagreementDeg <= config.trackingDisagreementExitDeg
        ) {
            val anchor = trackingResidualAnchorDeg ?: residualDeg
            trackingResidualAnchorDeg =
                normalize360Deg(
                    anchor +
                        shortestAngleDiffDeg(residualDeg, anchor) *
                        TRACKING_ANCHOR_ADAPTATION_ALPHA,
                )
        }
    }

    private fun updateWhileDegraded(evidence: AbsoluteHeadingEvidence): Float {
        renderHeadingDeg = moveTowardFusedHeading(evidence)
        return when {
            !evidence.fieldAcceptable -> {
                reason = unavailableMagneticReason()
                recoveryActive = false
                0f
            }
            else -> recoverDegradedHeading(evidence)
        }
    }

    private fun recoverDegradedHeading(evidence: AbsoluteHeadingEvidence): Float {
        val useRelativeEvidence = hasRelativeEvidence(evidence.atElapsedMs)
        val evidenceWindow = if (useRelativeEvidence) residualWindow else absoluteWindow
        val evidenceSpreadDeg =
            if (useRelativeEvidence) evidence.residualSpreadDeg else evidence.absoluteSpreadDeg
        val recoveryEvidenceReady =
            evidenceWindow.size >= config.acquisitionMinimumSamples &&
                windowAgeMs(evidenceWindow, evidence.atElapsedMs) >=
                config.recoveryEvidenceWindowMs &&
                evidenceSpreadDeg != null &&
                evidenceSpreadDeg <= config.recoveryResidualSpreadDeg
        val trustworthyPersistentCorrection =
            evidence.hardDisagreement &&
                evidence.strongAbsoluteConfidence &&
                recoveryEvidenceReady
        val recoveryBlocked =
            !recoveryEvidenceReady ||
                (evidence.hardDisagreement && !trustworthyPersistentCorrection)
        return if (recoveryBlocked) {
            reason =
                if (evidence.hardDisagreement) {
                    CompassTrackingReason.ABSOLUTE_RELATIVE_DISAGREEMENT
                } else {
                    CompassTrackingReason.ABSOLUTE_WINDOW_UNSTABLE
                }
            recoveryActive = false
            0f
        } else {
            applyDegradedRecovery()
        }
    }

    private fun applyDegradedRecovery(): Float {
        state = CompassTrackingState.TRACKING
        reason = CompassTrackingReason.STABLE
        trackingResidualAnchorDeg = residualWindow.lastOrNull()?.valueDeg
        recoveryActive = false
        quarantineActive = false
        quarantinedAbsoluteHeadingDeg = null
        return 0f
    }

    /** The map receives only Google Fused heading, never a game-RV-derived heading. */
    private fun moveTowardFusedHeading(evidence: AbsoluteHeadingEvidence): Float {
        val current = renderHeadingDeg ?: return evidence.absoluteHeadingDeg
        val verifiedTurn =
            !relativeWitnessValidator.suppressed &&
                evidence.relativeStepDeg != null &&
                evidence.stepDisagreementDeg != null &&
                !evidence.hardDisagreement
        val coherentUnverifiedFastTurn =
            if (verifiedTurn) {
                resetUnverifiedFastTurnEvidence()
                false
            } else {
                observeUnverifiedFastTurn(evidence)
            }
        val correctionRateDegPerSec =
            when {
                verifiedTurn -> config.verifiedFusedCorrectionRateDegPerSec
                coherentUnverifiedFastTurn ->
                    config.unverifiedFusedFastTurnCorrectionRateDegPerSec
                else -> config.unverifiedFusedCorrectionRateDegPerSec
            }
        val maximumStepDeg = correctionRateDegPerSec * evidence.elapsedSinceAbsoluteMs / 1_000f
        val step =
            shortestAngleDiffDeg(evidence.absoluteHeadingDeg, current)
                .coerceIn(-maximumStepDeg, maximumStepDeg)
        return normalize360Deg(current + step)
    }

    private fun observeUnverifiedFastTurn(evidence: AbsoluteHeadingEvidence): Boolean {
        val absoluteStepDeg = evidence.absoluteStepDeg
        val absoluteRateDegPerSec =
            absoluteStepDeg?.let { stepDeg ->
                abs(stepDeg) * 1_000f / evidence.elapsedSinceAbsoluteMs.coerceAtLeast(1L)
            }
        val eligible =
            !evidence.hardDisagreement &&
                absoluteStepDeg != null &&
                absoluteRateDegPerSec != null &&
                absoluteRateDegPerSec >= config.unverifiedFusedFastTurnEnterRateDegPerSec
        if (!eligible) {
            resetUnverifiedFastTurnEvidence()
        } else {
            val direction = if (requireNotNull(absoluteStepDeg) > 0f) 1 else -1
            if (direction == unverifiedFastTurnDirection) {
                unverifiedFastTurnSampleCount += 1
            } else {
                unverifiedFastTurnDirection = direction
                unverifiedFastTurnSampleCount = 1
            }
        }
        return eligible &&
            unverifiedFastTurnSampleCount >= config.unverifiedFusedFastTurnMinimumSamples
    }

    private fun resetUnverifiedFastTurnEvidence() {
        unverifiedFastTurnDirection = 0
        unverifiedFastTurnSampleCount = 0
    }

    private fun disagreementEnterThresholdDeg(strongAbsoluteConfidence: Boolean): Float =
        if (strongAbsoluteConfidence) {
            config.trackingDisagreementEnterDeg
        } else {
            config.weakConfidenceDisagreementEnterDeg
        }

    private fun enterDegraded(
        degradationReason: CompassTrackingReason,
        quarantinedHeadingDeg: Float?,
    ) {
        state = CompassTrackingState.DEGRADED
        reason = degradationReason
        recoveryActive = false
        quarantineActive = true
        trusted = false
        quarantinedAbsoluteHeadingDeg = quarantinedHeadingDeg
        residualWindow.clear()
        absoluteWindow.clear()
    }

    private fun appendEvidence(
        absoluteHeadingDeg: Float,
        residualDeg: Float?,
        atElapsedMs: Long,
    ) {
        absoluteWindow.addLast(TimedCircularValue(atElapsedMs, absoluteHeadingDeg))
        val evidenceRetentionMs =
            maxOf(config.acquisitionWindowMs, config.recoveryEvidenceWindowMs) +
                EVIDENCE_WINDOW_RETENTION_SLACK_MS
        trimWindow(absoluteWindow, atElapsedMs, evidenceRetentionMs)
        if (residualDeg != null) {
            residualWindow.addLast(TimedCircularValue(atElapsedMs, residualDeg))
            trimWindow(
                residualWindow,
                atElapsedMs,
                evidenceRetentionMs,
            )
        }
    }

    private fun freshRelativeHeading(atElapsedMs: Long): Float? {
        var matchedHeadingDeg: Float? = null
        if (relativeSensorAvailable && relativeHistory.isNotEmpty()) {
            val samples = relativeHistory.descendingIterator()
            while (samples.hasNext() && matchedHeadingDeg == null) {
                val sample = samples.next()
                if (sample.atElapsedMs <= atElapsedMs) {
                    val ageMs = atElapsedMs - sample.atElapsedMs
                    matchedHeadingDeg =
                        sample.valueDeg.takeIf { ageMs <= config.relativeSampleFreshnessMs }
                    break
                }
            }
        }
        return matchedHeadingDeg
    }

    private fun hasRelativeEvidence(atElapsedMs: Long): Boolean =
        relativeSensorAvailable &&
            !relativeWitnessValidator.suppressed &&
            residualWindow.isNotEmpty() &&
            freshRelativeHeading(atElapsedMs) != null

    private fun refreshMagneticStaleness(atElapsedMs: Long) {
        if (magnetometerAvailable) {
            val sampleAgeMs = (atElapsedMs - lastMagneticAtElapsedMs).coerceAtLeast(0L)
            val startupAgeMs = (atElapsedMs - evidenceStartedAtElapsedMs).coerceAtLeast(0L)
            when {
                lastMagneticAtElapsedMs <= 0L &&
                    startupAgeMs > config.magneticSampleStaleMs -> {
                    magneticQuality = CompassMagneticQuality.UNAVAILABLE
                }
                lastMagneticAtElapsedMs > 0L &&
                    sampleAgeMs > config.magneticSampleUnavailableMs -> {
                    magneticQuality = CompassMagneticQuality.UNAVAILABLE
                    magneticGoodSinceElapsedMs = 0L
                }
                lastMagneticAtElapsedMs > 0L &&
                    sampleAgeMs > config.magneticSampleStaleMs -> {
                    magneticQuality = CompassMagneticQuality.UNKNOWN
                    magneticGoodSinceElapsedMs = 0L
                }
                else -> Unit
            }
        }
    }

    private fun magneticFieldAcceptable(): Boolean =
        magneticQuality == CompassMagneticQuality.GOOD ||
            magneticQuality == CompassMagneticQuality.UNAVAILABLE

    private fun unavailableMagneticReason(): CompassTrackingReason =
        if (magneticQuality == CompassMagneticQuality.INTERFERENCE) {
            CompassTrackingReason.MAGNETIC_INTERFERENCE
        } else {
            CompassTrackingReason.RECOVERING
        }

    private fun hasStrongAbsoluteConfidence(sample: FusedAbsoluteHeadingSample): Boolean {
        val liveTrusted = sample.liveErrorDeg?.let { it.isFinite() && it in 0f..config.strongLiveErrorDeg } == true
        val conservativeTrusted =
            sample.conservativeErrorDeg?.let {
                it.isFinite() && it in 0f..config.strongConservativeErrorDeg
            } == true
        return liveTrusted || conservativeTrusted
    }

    private fun buildSnapshot(): FusedHeadingIntegritySnapshot =
        FusedHeadingIntegritySnapshot(
            state = state,
            reason = reason,
            renderHeadingDeg = renderHeadingDeg,
            renderable = renderHeadingDeg != null,
            trusted = trusted,
            magneticQuality = magneticQuality,
            magneticFieldUt = magneticFieldUt,
            relativeHeadingDeg = latestRelativeHeadingDeg,
            relativeWitnessAvailable = relativeWitnessValidator.available,
            relativeWitnessSuppressed = relativeWitnessValidator.suppressed,
            relativeWitnessSupportsHighRate = relativeWitnessValidator.supportsHighRate,
            relativeHorizontalProjection = relativeWitnessValidator.horizontalProjection,
            absoluteRelativeDisagreementDeg = lastDisagreementDeg,
            residualSpreadDeg = lastResidualSpreadDeg ?: circularWindowSpreadDeg(residualWindow),
            quarantinedAbsoluteHeadingDeg = quarantinedAbsoluteHeadingDeg,
            quarantineActive = quarantineActive,
            recoveryActive = recoveryActive,
            recoveryCorrectionDeg = lastRecoveryCorrectionDeg,
        )
}

/** Keeps the optional game-RV witness from influencing the heading rendered by Google Fused. */
private class RelativeHeadingWitnessValidator(
    private val config: FusedHeadingIntegrityConfig,
) {
    var available = false
        private set
    var suppressed = false
        private set
    var supportsHighRate = false
        private set
    var horizontalProjection: Float? = null
        private set

    private var disagreementSamples = 0
    private var firstDisagreementAtElapsedMs = 0L

    fun reset() {
        available = false
        suppressed = false
        supportsHighRate = false
        horizontalProjection = null
        disagreementSamples = 0
        firstDisagreementAtElapsedMs = 0L
    }

    fun onHeading(horizontalProjection: Float) {
        this.horizontalProjection = horizontalProjection.takeIf(Float::isFinite)
        available = !suppressed
    }

    fun onUnavailable(horizontalProjection: Float) {
        this.horizontalProjection = horizontalProjection.takeIf(Float::isFinite)
        available = false
        supportsHighRate = false
    }

    fun update(evidence: AbsoluteHeadingEvidence) {
        when {
            suppressed -> markSuppressed()
            evidence.stepDisagreementDeg == null -> clearTurnValidation()
            !evidence.hardDisagreement -> markAgreement()
            else -> recordDisagreement(evidence.atElapsedMs)
        }
    }

    private fun markSuppressed() {
        available = false
        supportsHighRate = false
    }

    private fun clearTurnValidation() {
        supportsHighRate = false
        disagreementSamples = 0
        firstDisagreementAtElapsedMs = 0L
    }

    private fun markAgreement() {
        available = true
        supportsHighRate = true
        disagreementSamples = 0
        firstDisagreementAtElapsedMs = 0L
    }

    private fun recordDisagreement(atElapsedMs: Long) {
        supportsHighRate = false
        disagreementSamples += 1
        if (firstDisagreementAtElapsedMs <= 0L) {
            firstDisagreementAtElapsedMs = atElapsedMs
        }
        val disagreementDurationMs =
            (atElapsedMs - firstDisagreementAtElapsedMs).coerceAtLeast(0L)
        val hasEnoughSamples = disagreementSamples >= config.witnessSuppressionMinimumSamples
        val hasEnoughDuration = disagreementDurationMs >= config.witnessSuppressionMinimumDurationMs
        if (hasEnoughSamples && hasEnoughDuration) {
            suppressed = true
            markSuppressed()
        }
    }
}

private data class TimedCircularValue(
    val atElapsedMs: Long,
    val valueDeg: Float,
)

private fun trimWindow(
    values: ArrayDeque<TimedCircularValue>,
    atElapsedMs: Long,
    windowMs: Long,
) {
    while (values.isNotEmpty() && atElapsedMs - values.first().atElapsedMs > windowMs) {
        values.removeFirst()
    }
}

private fun windowAgeMs(
    values: ArrayDeque<TimedCircularValue>,
    atElapsedMs: Long,
): Long = values.firstOrNull()?.let { (atElapsedMs - it.atElapsedMs).coerceAtLeast(0L) } ?: 0L

internal fun circularWindowSpreadDeg(
    values: Iterable<Float>,
): Float? = circularSpreadDeg(values.toList())

private fun circularWindowSpreadDeg(
    values: ArrayDeque<TimedCircularValue>,
): Float? = circularSpreadDeg(values.map { it.valueDeg })

private fun circularSpreadDeg(values: List<Float>): Float? =
    when (values.size) {
        0 -> null
        1 -> 0f
        else -> {
            val sumSin = values.sumOf { sin(Math.toRadians(it.toDouble())) }
            val sumCos = values.sumOf { cos(Math.toRadians(it.toDouble())) }
            val centerDeg =
                normalize360Deg(Math.toDegrees(atan2(sumSin, sumCos)).toFloat())
            val maxRadiusDeg =
                values.maxOf { abs(shortestAngleDiffDeg(it, centerDeg)) }
            (maxRadiusDeg * 2f).coerceAtMost(360f)
        }
    }

private fun maxOfNullable(
    first: Float?,
    second: Float?,
): Float? =
    when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

private const val TRACKING_ANCHOR_ADAPTATION_ALPHA = 0.01f
private const val MAX_CONTINUITY_STEP_INTERVAL_MS = 250L
private const val RELATIVE_HISTORY_WINDOW_MS = 1_000L
private const val EVIDENCE_WINDOW_RETENTION_SLACK_MS = 250L
