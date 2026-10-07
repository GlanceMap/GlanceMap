package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassRelativeMotionSample
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.MIN_SCREEN_TOP_HORIZONTAL_PROJECTION
import com.glancemap.glancemapwearos.domain.sensors.isPlausibleRelativeHeadingStep
import com.glancemap.glancemapwearos.domain.sensors.normalize360Deg
import com.glancemap.glancemapwearos.domain.sensors.shortestAngleDiffDeg
import kotlin.math.abs

/** Navigation-only relative turns; never changes the provider's absolute heading or trust. */
internal class NavigateMagneticMotionFallback(
    private val log: (String) -> Unit = {},
) {
    var coneSuppressed = false
        private set

    private var sessionStartedAtMs = Long.MAX_VALUE
    private var hasAcceptedAbsoluteAnchor = false
    private var episodeStartedAtMs: Long? = null
    private var blockedSinceMs: Long? = null
    private var healthySinceMs: Long? = null
    private var previousMotion: CompassRelativeMotionSample? = null
    private var motionHeadingDeg = 0f
    private var recoveringAbsolute = false
    private var expired = false

    fun beginSession(nowElapsedMs: Long) {
        sessionStartedAtMs = nowElapsedMs
        previousMotion = null
        recoveringAbsolute = false
        blockedSinceMs = null
        healthySinceMs = null
        // Keep the episode budget across ambient and panning; a wake must not renew drift time.
    }

    fun resolve(
        state: CompassRenderState,
        absoluteTarget: NavigationRotationTarget?,
        currentDisplayedHeadingDeg: Float,
        nowElapsedMs: Long,
    ): NavigationRotationTarget? {
        val blocked =
            (
                absoluteTarget == null &&
                    (
                        state.magneticInterference ||
                            state.magneticQuality == CompassMagneticQuality.INTERFERENCE
                    )
            ) ||
                state.headingJumpHeld
        // A magnetic warning alone can still permit bounded absolute motion. Only replace an
        // existing navigation hold or an explicit contradictory-jump hold, never normal tracking.
        return when {
            state.providerType != CompassProviderType.GOOGLE_FUSED -> {
                clearEpisode()
                hasAcceptedAbsoluteAnchor = false
                previousMotion = null
                absoluteTarget
            }
            episodeStartedAtMs == null && !blocked -> {
                if (hasHealthyAbsoluteHeading(state, absoluteTarget)) hasAcceptedAbsoluteAnchor = true
                previousMotion = usableMotion(state, nowElapsedMs)
                absoluteTarget
            }
            else -> resolveEpisode(state, absoluteTarget, currentDisplayedHeadingDeg, nowElapsedMs, blocked)
        }
    }

    private fun resolveEpisode(
        state: CompassRenderState,
        absoluteTarget: NavigationRotationTarget?,
        currentHeadingDeg: Float,
        nowElapsedMs: Long,
        blocked: Boolean,
    ): NavigationRotationTarget {
        if (episodeStartedAtMs == null) {
            episodeStartedAtMs = nowElapsedMs
            motionHeadingDeg = currentHeadingDeg
            log(
                "stage=start atMs=$nowElapsedMs anchorDeg=$currentHeadingDeg " +
                    "absoluteHeld=${absoluteTarget == null} jumpHeld=${state.headingJumpHeld}",
            )
        }
        blockedSinceMs = if (blocked) blockedSinceMs ?: nowElapsedMs else null
        if (!coneSuppressed && blockedSinceMs?.let { nowElapsedMs - it >= MAGNETIC_CONE_HIDE_DELAY_MS } == true) {
            coneSuppressed = true
            log("stage=cone_hidden atMs=$nowElapsedMs")
        }
        val healthy = hasHealthyAbsoluteHeading(state, absoluteTarget)
        healthySinceMs = if (healthy) healthySinceMs ?: nowElapsedMs else null
        if (healthySinceMs?.let { nowElapsedMs - it >= MAGNETIC_MOTION_RECOVERY_HOLD_MS } == true) {
            return recoverAbsolute(requireNotNull(absoluteTarget), currentHeadingDeg)
        }
        if (recoveringAbsolute) {
            motionHeadingDeg = currentHeadingDeg
            previousMotion = null
            recoveringAbsolute = false
        }
        return coast(state, currentHeadingDeg, nowElapsedMs)
    }

    private fun recoverAbsolute(
        target: NavigationRotationTarget,
        currentHeadingDeg: Float,
    ): NavigationRotationTarget {
        if (!recoveringAbsolute) log("stage=reconnecting targetDeg=${target.headingDeg}")
        recoveringAbsolute = true
        if (abs(shortestAngleDiffDeg(target.headingDeg, currentHeadingDeg)) <= MAGNETIC_CONE_RESTORE_DELTA_DEG) {
            hasAcceptedAbsoluteAnchor = true
            clearEpisode()
            log("stage=recovered targetDeg=${target.headingDeg}")
        }
        return target.copy(
            maxVisualStepDeg =
                minOf(target.maxVisualStepDeg ?: Float.POSITIVE_INFINITY, MAGNETIC_MOTION_RECOVERY_MAX_STEP_DEG),
        )
    }

    private fun coast(
        state: CompassRenderState,
        currentHeadingDeg: Float,
        nowElapsedMs: Long,
    ): NavigationRotationTarget {
        if (!expired && nowElapsedMs - requireNotNull(episodeStartedAtMs) >= MAGNETIC_MOTION_MAX_DURATION_MS) {
            expired = true
            coneSuppressed = true
            log("stage=expired atMs=$nowElapsedMs")
        }
        val sample = usableMotion(state, nowElapsedMs)
        return if (!hasAcceptedAbsoluteAnchor || expired || sample == null) {
            previousMotion = null
            motionHeadingDeg = currentHeadingDeg
            NavigationRotationTarget(currentHeadingDeg)
        } else {
            advanceMotion(sample, currentHeadingDeg)
            NavigationRotationTarget(motionHeadingDeg, relativeMotionSample = previousMotion)
        }
    }

    private fun advanceMotion(
        sample: CompassRelativeMotionSample,
        currentHeadingDeg: Float,
    ) {
        val previous = previousMotion
        if (previous != null && sample.atElapsedMs <= previous.atElapsedMs) return
        previousMotion = sample
        val elapsedMs = previous?.let { sample.atElapsedMs - it.atElapsedMs }
        val stepDeg = previous?.let { shortestAngleDiffDeg(sample.headingDeg, it.headingDeg) }
        val continuous =
            previous != null &&
                previous.provenance == sample.provenance &&
                previous.displayRotation == sample.displayRotation &&
                elapsedMs != null &&
                elapsedMs <= MAGNETIC_MOTION_SAMPLE_FRESHNESS_MS &&
                stepDeg != null &&
                isPlausibleRelativeHeadingStep(stepDeg, elapsedMs)
        if (continuous) {
            motionHeadingDeg = normalize360Deg(motionHeadingDeg + requireNotNull(stepDeg))
        } else {
            // Re-establish only the relative origin. Never apply a missed/restarted/tilted turn.
            motionHeadingDeg = currentHeadingDeg
            if (previous != null) {
                log("stage=rebase sampleAtMs=${sample.atElapsedMs} generation=${sample.provenance.generation}")
            }
        }
    }

    private fun usableMotion(
        state: CompassRenderState,
        nowElapsedMs: Long,
    ): CompassRelativeMotionSample? =
        state.relativeMotionSample?.takeIf { sample ->
            sample.headingDeg.isFinite() &&
                sample.horizontalProjection.isFinite() &&
                sample.horizontalProjection >= MIN_SCREEN_TOP_HORIZONTAL_PROJECTION &&
                sample.atElapsedMs >= sessionStartedAtMs &&
                sample.atElapsedMs <= nowElapsedMs &&
                nowElapsedMs - sample.atElapsedMs <= MAGNETIC_MOTION_SAMPLE_FRESHNESS_MS &&
                sample.provenance == state.headingProvenance &&
                sample.provenance.provider == CompassProviderType.GOOGLE_FUSED
        }

    private fun hasHealthyAbsoluteHeading(
        state: CompassRenderState,
        target: NavigationRotationTarget?,
    ): Boolean =
        target != null &&
            hasStableMagneticCompassHeading(state) &&
            !state.headingSampleHeldOutput &&
            !state.quarantineActive &&
            !state.unresolvedIndependentDisagreement

    private fun clearEpisode() {
        episodeStartedAtMs = null
        blockedSinceMs = null
        healthySinceMs = null
        recoveringAbsolute = false
        expired = false
        coneSuppressed = false
    }
}

// New fallback UX/drift controls; the absolute-heading integrity thresholds remain unchanged.
internal const val MAGNETIC_CONE_HIDE_DELAY_MS = 500L
internal const val MAGNETIC_MOTION_RECOVERY_HOLD_MS = 1_000L
internal const val MAGNETIC_MOTION_SAMPLE_FRESHNESS_MS = 300L
internal const val MAGNETIC_MOTION_MAX_DURATION_MS = 60_000L
internal const val MAGNETIC_MOTION_RECOVERY_MAX_STEP_DEG = 4f
private const val MAGNETIC_CONE_RESTORE_DELTA_DEG = 5f
