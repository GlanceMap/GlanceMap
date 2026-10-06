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
    val callbackArrivalAtElapsedMs: Long = atElapsedMs,
    val sourceSampleId: Long? = null,
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
    val unresolvedIndependentDisagreement: Boolean,
    val relativeHorizontalProjection: Float?,
    val absoluteRelativeDisagreementDeg: Float?,
    val residualSpreadDeg: Float?,
    val quarantinedAbsoluteHeadingDeg: Float?,
    val quarantineActive: Boolean,
    /** True when the current output is preserved for acquisition or quarantine recovery. */
    val heldOutput: Boolean,
    val recoveryActive: Boolean,
    val recoveryCorrectionDeg: Float,
    val absoluteStepDeg: Float?,
    val absoluteStepIntervalMs: Long?,
    val relativeStepDeg: Float?,
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
    val unverifiedHeadingJumpHoldDeg: Float = 60f,
    val unverifiedHeadingJumpMaximumRateDegPerSec: Float = 1_080f,
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
    val liveAbsoluteConfidence: Boolean,
    val conservativeAbsoluteConfidence: Boolean?,
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
    private var heldOutput = false
    private var trusted = false
    private var unresolvedIndependentDisagreement = false
    private var unresolvedRebaselineSawCorroboratedTurn = false
    private var lastDisagreementDeg: Float? = null
    private var lastResidualSpreadDeg: Float? = null
    private var lastRecoveryCorrectionDeg = 0f
    private var quarantinedAbsoluteHeadingDeg: Float? = null

    // This is the last map-safe heading, captured once when an F3 quarantine starts. It must
    // never follow later suspect absolute samples, because it is the stationary recovery target.
    private var quarantineAnchorHeadingDeg: Float? = null
    private var lastAbsoluteStepDeg: Float? = null
    private var lastAbsoluteStepIntervalMs: Long? = null
    private var lastRelativeStepDeg: Float? = null
    private var unverifiedFastTurnDirection = 0
    private var unverifiedFastTurnSampleCount = 0
    private var unverifiedAcquisitionMotionDirection = 0
    private var unverifiedAcquisitionMotionSampleCount = 0
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
        heldOutput = false
        trusted = false
        unresolvedIndependentDisagreement = false
        unresolvedRebaselineSawCorroboratedTurn = false
        lastDisagreementDeg = null
        lastResidualSpreadDeg = null
        lastRecoveryCorrectionDeg = 0f
        quarantinedAbsoluteHeadingDeg = null
        quarantineAnchorHeadingDeg = null
        lastAbsoluteStepDeg = null
        lastAbsoluteStepIntervalMs = null
        lastRelativeStepDeg = null
        resetUnverifiedFastTurnEvidence()
        resetUnverifiedAcquisitionMotion()
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
        val witnessWasSuppressed = relativeWitnessValidator.suppressed
        relativeWitnessValidator.update(evidence)
        if (!witnessWasSuppressed && relativeWitnessValidator.suppressed) {
            beginUnresolvedIndependentDisagreement()
        }
        val returnedToQuarantineAnchor = returnsToQuarantineAnchor(evidence)
        val correction =
            when (state) {
                CompassTrackingState.ACQUIRING -> updateWhileAcquiring(evidence)
                CompassTrackingState.TRACKING -> updateWhileTracking(evidence)
                CompassTrackingState.DEGRADED -> updateWhileDegraded(evidence)
            }
        observeUnresolvedRebaselineEvidence(evidence)
        resolveUnresolvedIndependentDisagreementIfReady(evidence)

        trusted = canTrustAbsoluteHeading(evidence, returnedToQuarantineAnchor)
        lastDisagreementDeg = evidence.disagreementDeg
        lastResidualSpreadDeg = evidence.residualSpreadDeg ?: evidence.absoluteSpreadDeg
        lastRecoveryCorrectionDeg = correction
        return buildSnapshot()
    }

    private fun canTrustAbsoluteHeading(
        evidence: AbsoluteHeadingEvidence,
        returnedToQuarantineAnchor: Boolean,
    ): Boolean =
        state == CompassTrackingState.TRACKING &&
            !recoveryActive &&
            !quarantineActive &&
            !unresolvedIndependentDisagreement &&
            (!evidence.hardDisagreement || returnedToQuarantineAnchor) &&
            evidence.fieldAcceptable &&
            magneticQuality == CompassMagneticQuality.GOOD &&
            evidence.strongAbsoluteConfidence

    @Suppress("LongMethod") // Keeps the evidence snapshot assembled in one audited decision boundary.
    private fun collectAbsoluteHeadingEvidence(
        sample: FusedAbsoluteHeadingSample,
    ): AbsoluteHeadingEvidence {
        val absoluteHeadingDeg = normalize360Deg(sample.headingDeg)
        val atElapsedMs = sample.atElapsedMs
        val movement = measureAbsoluteMovement(absoluteHeadingDeg, atElapsedMs)
        lastAbsoluteStepDeg = movement.absoluteStepDeg
        lastAbsoluteStepIntervalMs = movement.elapsedSinceAbsoluteMs
        lastRelativeStepDeg = movement.relativeStepDeg
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
        val absoluteConfidence = resolveAbsoluteConfidence(sample)
        val strongAbsoluteConfidence = absoluteConfidence.strong
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
            liveAbsoluteConfidence = absoluteConfidence.live,
            conservativeAbsoluteConfidence = absoluteConfidence.conservative,
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
                resetUnverifiedAcquisitionMotion()
                heldOutput = renderHeadingDeg != null
                0f
            }
            renderHeadingDeg == null -> {
                // With no recent angle to preserve, make the first usable provider sample
                // renderable immediately. Integrity validation continues before TRACKING.
                renderHeadingDeg = evidence.absoluteHeadingDeg
                reason = CompassTrackingReason.ABSOLUTE_WINDOW_UNSTABLE
                heldOutput = false
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
                    if (observeUnverifiedAcquisitionMotion(evidence)) {
                        // Without a usable relative witness, keep the provider estimate bounded
                        // but do not freeze a genuine, coherent turn at the wake seed.
                        renderHeadingDeg = moveTowardFusedHeading(evidence)
                        reason = CompassTrackingReason.ABSOLUTE_WINDOW_UNSTABLE
                        heldOutput = false
                    } else {
                        reason = CompassTrackingReason.ABSOLUTE_WINDOW_UNSTABLE
                        heldOutput = true
                    }
                    0f
                } else {
                    // Preserve the previous rendered heading until the new provider has
                    // established a stable reference. A first fused sample can be far from
                    // the last known heading while its magnetic integrity is still unknown.
                    renderHeadingDeg = moveTowardFusedHeading(evidence)
                    resetUnverifiedAcquisitionMotion()
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
        heldOutput = false
        quarantinedAbsoluteHeadingDeg = null
        quarantineAnchorHeadingDeg = null
        return 0f
    }

    private fun updateWhileTracking(evidence: AbsoluteHeadingEvidence): Float =
        if (!evidence.fieldAcceptable) {
            enterDegraded(
                degradationReason = unavailableMagneticReason(),
                quarantinedHeadingDeg = evidence.absoluteHeadingDeg,
            )
            renderHeadingDeg = moveTowardFusedHeading(evidence)
            0f
        } else if (returnsToQuarantineAnchor(evidence)) {
            // A corrected absolute provider can return to the established map-safe heading while
            // the watch stays still. Do not require a relative turn for that recovery path.
            updateTrackingAnchor(evidence)
            reason = CompassTrackingReason.STABLE
            recoveryActive = false
            quarantineActive = false
            heldOutput = false
            quarantinedAbsoluteHeadingDeg = null
            quarantineAnchorHeadingDeg = null
            resetUnverifiedFastTurnEvidence()
            0f
        } else if (shouldHoldUnverifiedHeadingJump(evidence)) {
            // Provider confidence cannot override contradictory independent motion. Hold only
            // suspect samples; an anchor return or corroborated correction can release them.
            holdUnverifiedHeadingJump(evidence)
        } else {
            updateTrackingAnchor(evidence)
            renderHeadingDeg = moveTowardFusedHeading(evidence)
            reason = CompassTrackingReason.STABLE
            recoveryActive = false
            quarantineActive = false
            heldOutput = false
            quarantinedAbsoluteHeadingDeg = null
            quarantineAnchorHeadingDeg = null
            0f
        }

    private fun returnsToQuarantineAnchor(evidence: AbsoluteHeadingEvidence): Boolean {
        val anchorHeadingDeg = quarantineAnchorHeadingDeg ?: return false
        return quarantineActive &&
            abs(shortestAngleDiffDeg(evidence.absoluteHeadingDeg, anchorHeadingDeg)) <=
            config.trackingDisagreementExitDeg
    }

    private fun shouldHoldUnverifiedHeadingJump(evidence: AbsoluteHeadingEvidence): Boolean {
        val renderedHeading = renderHeadingDeg
        val disagreement = evidence.disagreementDeg
        val renderedDeltaDeg =
            renderedHeading?.let { abs(shortestAngleDiffDeg(evidence.absoluteHeadingDeg, it)) }
        val absoluteRateDegPerSec =
            evidence.absoluteStepDeg?.let { stepDeg ->
                abs(stepDeg) * 1_000f / evidence.elapsedSinceAbsoluteMs.coerceAtLeast(1L)
            }
        val unsupportedImplausibleStep =
            !evidence.strongAbsoluteConfidence &&
                evidence.relativeStepDeg == null &&
                evidence.absoluteStepDeg != null &&
                absoluteRateDegPerSec != null &&
                abs(evidence.absoluteStepDeg) >= config.unverifiedHeadingJumpHoldDeg &&
                absoluteRateDegPerSec > config.unverifiedHeadingJumpMaximumRateDegPerSec
        val relativeDisagreement =
            disagreement != null &&
                evidence.relativeStepDeg != null &&
                disagreement >= disagreementEnterThresholdDeg(evidence.strongAbsoluteConfidence)
        val unresolvedQuarantine =
            quarantineAnchorHeadingDeg != null &&
                renderedHeading != null &&
                !hasCorroboratedCorrection(evidence)
        return unresolvedQuarantine ||
            (
                renderedHeading != null &&
                    renderedDeltaDeg != null &&
                    renderedDeltaDeg >= config.unverifiedHeadingJumpHoldDeg &&
                    (relativeDisagreement || unsupportedImplausibleStep)
            )
    }

    private fun holdUnverifiedHeadingJump(evidence: AbsoluteHeadingEvidence): Float {
        reason =
            if (evidence.fieldAcceptable) {
                CompassTrackingReason.ABSOLUTE_RELATIVE_DISAGREEMENT
            } else {
                unavailableMagneticReason()
            }
        trusted = false
        recoveryActive = false
        if (quarantineAnchorHeadingDeg == null) {
            quarantineAnchorHeadingDeg = renderHeadingDeg
        }
        quarantineActive = true
        heldOutput = true
        quarantinedAbsoluteHeadingDeg = evidence.absoluteHeadingDeg
        resetUnverifiedFastTurnEvidence()
        return 0f
    }

    private fun hasCorroboratedCorrection(evidence: AbsoluteHeadingEvidence): Boolean =
        evidence.absoluteStepDeg != null &&
            abs(evidence.absoluteStepDeg) >= CORROBORATED_STEP_MIN_DEG &&
            isMeaningfulRelativeHeadingStep(evidence.relativeStepDeg) &&
            evidence.stepDisagreementDeg != null &&
            !evidence.hardDisagreement

    private fun beginUnresolvedIndependentDisagreement() {
        // The suppression threshold establishes a material independent contradiction. Start the
        // recovery evidence window after that boundary so old absolute or relative history cannot
        // silently re-authorize either source.
        unresolvedIndependentDisagreement = true
        unresolvedRebaselineSawCorroboratedTurn = false
        trackingResidualAnchorDeg = null
        residualWindow.clear()
        absoluteWindow.clear()
    }

    private fun observeUnresolvedRebaselineEvidence(evidence: AbsoluteHeadingEvidence) {
        if (
            unresolvedIndependentDisagreement &&
            !unresolvedRebaselineSawCorroboratedTurn &&
            hasCorroboratedCorrection(evidence)
        ) {
            unresolvedRebaselineSawCorroboratedTurn = true
            // Begin the fresh baseline at the first corroborated physical turn, excluding every
            // earlier post-suppression sample whose absolute source is still unresolved.
            residualWindow.clear()
            absoluteWindow.clear()
            appendEvidence(
                absoluteHeadingDeg = evidence.absoluteHeadingDeg,
                residualDeg = evidence.residualDeg,
                atElapsedMs = evidence.atElapsedMs,
            )
        }
    }

    private fun resolveUnresolvedIndependentDisagreementIfReady(evidence: AbsoluteHeadingEvidence) {
        if (!canResolveUnresolvedIndependentDisagreement(evidence)) return
        val rebaselineReady =
            residualWindow.size >= config.acquisitionMinimumSamples &&
                windowAgeMs(residualWindow, evidence.atElapsedMs) >= config.recoveryEvidenceWindowMs &&
                circularWindowSpreadDeg(residualWindow)?.let {
                    it <= config.recoveryResidualSpreadDeg
                } == true
        if (!rebaselineReady) return

        unresolvedIndependentDisagreement = false
        unresolvedRebaselineSawCorroboratedTurn = false
        trackingResidualAnchorDeg = residualWindow.lastOrNull()?.valueDeg
        relativeWitnessValidator.rebaseline()
    }

    private fun canResolveUnresolvedIndependentDisagreement(evidence: AbsoluteHeadingEvidence): Boolean =
        when {
            !unresolvedIndependentDisagreement -> false
            !unresolvedRebaselineSawCorroboratedTurn -> false
            state != CompassTrackingState.TRACKING -> false
            !evidence.fieldAcceptable -> false
            evidence.hardDisagreement -> false
            else -> freshRelativeHeading(evidence.atElapsedMs) != null
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
        if (!returnsToQuarantineAnchor(evidence) && shouldHoldUnverifiedHeadingJump(evidence)) {
            return holdUnverifiedHeadingJump(evidence)
        }
        // A jump hold releases only on an anchor return or a corroborated correction. Magnetic
        // degradation alone still permits bounded motion when there is no suspect jump to hold.
        quarantineAnchorHeadingDeg = null
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
        heldOutput = false
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

    private fun observeUnverifiedAcquisitionMotion(evidence: AbsoluteHeadingEvidence): Boolean {
        val stepDeg = evidence.absoluteStepDeg
        val rateDegPerSec =
            stepDeg?.let {
                abs(it) * 1_000f / evidence.elapsedSinceAbsoluteMs.coerceAtLeast(1L)
            }
        // The absolute-only disagreement threshold is not an independent contradiction: a
        // plausible turn can cross it between low-power samples. Keep actual witness conflicts
        // blocking, and require nonzero motion before counting a same-direction step.
        val independentContradiction = evidence.disagreementDeg != null && evidence.hardDisagreement
        val eligible =
            !hasRelativeEvidence(evidence.atElapsedMs) &&
                stepDeg != null &&
                abs(stepDeg) > 0f &&
                abs(stepDeg) < config.unverifiedHeadingJumpHoldDeg &&
                rateDegPerSec != null &&
                rateDegPerSec <= config.unverifiedHeadingJumpMaximumRateDegPerSec &&
                !independentContradiction
        if (!eligible) {
            resetUnverifiedAcquisitionMotion()
            return false
        }
        val direction = if (requireNotNull(stepDeg) > 0f) 1 else -1
        if (direction == unverifiedAcquisitionMotionDirection) {
            unverifiedAcquisitionMotionSampleCount += 1
        } else {
            unverifiedAcquisitionMotionDirection = direction
            unverifiedAcquisitionMotionSampleCount = 1
        }
        return unverifiedAcquisitionMotionSampleCount >= config.unverifiedFusedFastTurnMinimumSamples
    }

    private fun resetUnverifiedAcquisitionMotion() {
        unverifiedAcquisitionMotionDirection = 0
        unverifiedAcquisitionMotionSampleCount = 0
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
        // Sensor interference must not erase a jump hold established by independent evidence.
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

    private fun resolveAbsoluteConfidence(sample: FusedAbsoluteHeadingSample): AbsoluteConfidence {
        val liveTrusted =
            sample.liveErrorDeg?.let {
                it.isFinite() && it in 0f..config.strongLiveErrorDeg
            } == true
        val conservative =
            sample.conservativeErrorDeg?.takeIf { it.isFinite() && it >= 0f }?.let {
                it <= config.strongConservativeErrorDeg
            }
        // A known conservative disagreement invalidates the live estimate for trust and for
        // high-confidence contradiction thresholds. Missing conservative metadata remains
        // renderable, but it is never enough to establish strong/trusted output on its own.
        return AbsoluteConfidence(
            live = liveTrusted,
            conservative = conservative,
            strong = liveTrusted && conservative == true,
        )
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
            unresolvedIndependentDisagreement = unresolvedIndependentDisagreement,
            relativeHorizontalProjection = relativeWitnessValidator.horizontalProjection,
            absoluteRelativeDisagreementDeg = lastDisagreementDeg,
            residualSpreadDeg = lastResidualSpreadDeg ?: circularWindowSpreadDeg(residualWindow),
            quarantinedAbsoluteHeadingDeg = quarantinedAbsoluteHeadingDeg,
            quarantineActive = quarantineActive,
            heldOutput = heldOutput || quarantineActive,
            recoveryActive = recoveryActive,
            recoveryCorrectionDeg = lastRecoveryCorrectionDeg,
            absoluteStepDeg = lastAbsoluteStepDeg,
            absoluteStepIntervalMs = lastAbsoluteStepIntervalMs,
            relativeStepDeg = lastRelativeStepDeg,
        )
}

private data class AbsoluteConfidence(
    val live: Boolean,
    val conservative: Boolean?,
    val strong: Boolean,
)

/**
 * Keeps the optional game-RV witness from influencing the heading rendered by Google Fused.
 * Availability means samples are still arriving; suppression makes those samples non-controlling
 * until fresh corroborated evidence re-baselines the witness.
 */
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
        available = true
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
        supportsHighRate = false
    }

    fun rebaseline() {
        suppressed = false
        supportsHighRate = false
        disagreementSamples = 0
        firstDisagreementAtElapsedMs = 0L
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

internal fun isMeaningfulRelativeHeadingStep(
    relativeStepDeg: Float?,
): Boolean = relativeStepDeg?.let { abs(it) >= CORROBORATED_STEP_MIN_DEG } == true

private const val CORROBORATED_STEP_MIN_DEG = 1f
private const val TRACKING_ANCHOR_ADAPTATION_ALPHA = 0.01f
private const val MAX_CONTINUITY_STEP_INTERVAL_MS = 250L
private const val RELATIVE_HISTORY_WINDOW_MS = 1_000L
private const val EVIDENCE_WINDOW_RETENTION_SLACK_MS = 250L
