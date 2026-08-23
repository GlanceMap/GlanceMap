package com.glancemap.glancemapwearos.domain.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.WindowManager
import com.glancemap.glancemapwearos.core.service.diagnostics.isCompassTelemetryCaptureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class CompassDisplayRotationUpdate(
    val rotation: Int,
    val sampledAtMs: Long,
)

internal data class CompassHeadingLogUpdate(
    val sampledAtMs: Long,
    val message: String,
)

internal data class CompassMagneticInterferenceUpdateResult(
    val state: MagneticInterferenceState,
    val changed: Boolean,
    val combinedAccuracy: Int,
    val logMessage: String?,
)

internal data class CompassRotationVectorUpdateResult(
    val uncertaintyDeg: Float,
    val changed: Boolean,
    val combinedAccuracy: Int,
    val logMessage: String?,
)

internal data class CompassHeadingSourcePublication(
    val activeSource: HeadingSource,
    val status: HeadingSourceStatus,
    val changed: Boolean,
    val logMessages: List<String>,
)

internal data class CompassHeadingRelockUpdate(
    val headingRelockUntilElapsedMs: Long,
    val logMessage: String,
)

internal data class CompassMagneticGraceReset(
    val magneticInterferenceStartupGraceUntilElapsedMs: Long,
    val logMessage: String,
)

internal fun computeCompassDisplayRotationUpdate(
    windowManager: WindowManager,
    nowElapsedMs: Long,
    lastSampleAtMs: Long,
): CompassDisplayRotationUpdate? {
    if (!shouldSampleDisplayRotation(nowElapsedMs, lastSampleAtMs)) return null
    return CompassDisplayRotationUpdate(
        rotation = queryDisplayRotation(windowManager),
        sampledAtMs = nowElapsedMs,
    )
}

internal fun buildCompassHeadingLogUpdate(
    rawHeading: Float,
    pendingBootstrapRawSamplesToIgnore: Int,
    lastHeadingDebugLogAtMs: Long,
    nowElapsedMs: Long,
    smoothedHeading: Float,
    combinedAccuracy: Int,
    sensorReportedAccuracy: Int,
    inferredHeadingAccuracy: Int,
    declinationDeg: Float?,
    northReferenceMode: NorthReferenceMode,
    sensorRateMode: SensorRateMode,
    northStatus: NorthReferenceStatus,
    activeHeadingSource: HeadingSource,
    headingSourceMode: CompassHeadingSourceMode,
    magneticFieldStrengthEmaUt: Float,
    magneticInterferenceDetected: Boolean,
): CompassHeadingLogUpdate? {
    if (!isCompassTelemetryCaptureActive()) return null
    if (pendingBootstrapRawSamplesToIgnore > 0) return null
    if (nowElapsedMs - lastHeadingDebugLogAtMs < HEADING_DEBUG_SAMPLE_MS) return null
    return CompassHeadingLogUpdate(
        sampledAtMs = nowElapsedMs,
        message =
            buildCompassHeadingSampleLog(
                rawHeading = rawHeading,
                smoothedHeading = smoothedHeading,
                combinedAccuracy = combinedAccuracy,
                sensorReportedAccuracy = sensorReportedAccuracy,
                inferredHeadingAccuracy = inferredHeadingAccuracy,
                declinationDeg = declinationDeg,
                northReferenceMode = northReferenceMode,
                sensorRateMode = sensorRateMode,
                northStatus = northStatus,
                activeHeadingSource = activeHeadingSource,
                headingSourceMode = headingSourceMode,
                magneticFieldStrengthEmaUt = magneticFieldStrengthEmaUt,
                magneticInterferenceDetected = magneticInterferenceDetected,
            ),
    )
}

internal fun computeCircularMean(values: ArrayDeque<Float>): Float {
    var sx = 0.0
    var sy = 0.0
    values.forEach {
        val rad = Math.toRadians(it.toDouble())
        sx += cos(rad)
        sy += sin(rad)
    }
    return normalize360Deg(Math.toDegrees(atan2(sy, sx)).toFloat())
}

internal fun computeAvgAbsAngularDiff(values: ArrayDeque<Float>): Float {
    if (values.size < 2) return 0f
    return values.zipWithNext { a, b -> abs(shortestAngleDiffDeg(b, a)) }.average().toFloat()
}

internal fun computeCompassMagneticInterferenceUpdate(
    values: FloatArray,
    magneticFieldStrengthUt: Float,
    magneticFieldStrengthEmaUt: Float,
    magneticInterferenceHoldUntilElapsedMs: Long,
    magneticInterferenceDetected: Boolean,
    nowElapsedMs: Long,
    startupGraceUntilElapsedMs: Long,
    sensorAccuracy: Int,
    inferredAccuracy: Int,
    usingRotationVector: Boolean,
    usingHeadingSensor: Boolean,
): CompassMagneticInterferenceUpdateResult? {
    if (values.size < 3) return null
    val x = values[0]
    val y = values[1]
    val z = values[2]
    val strengthUt = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    if (!strengthUt.isFinite()) return null
    val step =
        stepMagneticInterferenceState(
            state =
                MagneticInterferenceState(
                    strengthUt = magneticFieldStrengthUt,
                    emaUt = magneticFieldStrengthEmaUt,
                    holdUntilElapsedMs = magneticInterferenceHoldUntilElapsedMs,
                    detected = magneticInterferenceDetected,
                ),
            strengthUt = strengthUt,
            nowElapsedMs = nowElapsedMs,
            startupGraceUntilElapsedMs = startupGraceUntilElapsedMs,
        )
    val changed = magneticInterferenceDetected != step.state.detected
    val combinedAccuracy =
        combineCompassAccuracy(
            sensorAccuracy = sensorAccuracy,
            inferredAccuracy = inferredAccuracy,
            usingRotationVector = usingRotationVector || usingHeadingSensor,
            hasMagneticInterference = step.state.detected,
        )
    return CompassMagneticInterferenceUpdateResult(
        state = step.state,
        changed = changed,
        combinedAccuracy = combinedAccuracy,
        logMessage =
            if (changed) {
                "magnetic_interference active=${step.state.detected} reason=${step.reason} fieldUt=${step.smoothedStrengthUt.format(1)} deltaUt=${step.deltaUt.format(1)}"
            } else {
                null
            },
    )
}

internal fun computeCompassHeadingRelockUpdate(
    currentHeadingRelockUntilElapsedMs: Long,
    nowElapsedMs: Long,
    reason: String,
): CompassHeadingRelockUpdate {
    val until =
        maxOf(
            currentHeadingRelockUntilElapsedMs,
            nowElapsedMs + HEADING_RELOCK_WINDOW_MS,
        )
    return CompassHeadingRelockUpdate(
        headingRelockUntilElapsedMs = until,
        logMessage = "heading_relock armed reason=$reason windowMs=$HEADING_RELOCK_WINDOW_MS until=$until",
    )
}

internal fun computeCompassMagneticGraceReset(
    currentMagneticInterferenceStartupGraceUntilElapsedMs: Long,
    nowElapsedMs: Long,
    reason: String,
): CompassMagneticGraceReset {
    val until =
        maxOf(
            currentMagneticInterferenceStartupGraceUntilElapsedMs,
            nowElapsedMs + MAG_INTERFERENCE_STARTUP_GRACE_MS,
        )
    return CompassMagneticGraceReset(
        magneticInterferenceStartupGraceUntilElapsedMs = until,
        logMessage = "magnetic_interference_grace armed reason=$reason windowMs=$MAG_INTERFERENCE_STARTUP_GRACE_MS until=$until",
    )
}

internal fun computeCompassRotationVectorUpdate(
    previousUncertaintyDeg: Float,
    values: FloatArray,
    sensorAccuracy: Int,
    inferredAccuracy: Int,
    usingRotationVector: Boolean,
    usingHeadingSensor: Boolean,
    hasMagneticInterference: Boolean,
): CompassRotationVectorUpdateResult {
    val update =
        decodeRotationVectorUncertainty(
            previousUncertaintyDeg = previousUncertaintyDeg,
            values = values,
        )
    val combinedAccuracy =
        combineCompassAccuracy(
            sensorAccuracy = sensorAccuracy,
            inferredAccuracy = inferredAccuracy,
            usingRotationVector = usingRotationVector || usingHeadingSensor,
            hasMagneticInterference = hasMagneticInterference,
        )
    return CompassRotationVectorUpdateResult(
        uncertaintyDeg = update.uncertaintyDeg,
        changed = update.changed,
        combinedAccuracy = combinedAccuracy,
        logMessage =
            if (update.changed) {
                "rotvec uncertaintyDeg=${update.uncertaintyDeg.takeIf { it.isFinite() }?.format(1) ?: "n/a"}"
            } else {
                null
            },
    )
}

internal fun computeCompassCombinedAccuracy(
    sensorAccuracy: Int,
    inferredAccuracy: Int,
    usingRotationVector: Boolean,
    usingHeadingSensor: Boolean,
    hasMagneticInterference: Boolean,
): Int =
    combineCompassAccuracy(
        sensorAccuracy = sensorAccuracy,
        inferredAccuracy = inferredAccuracy,
        usingRotationVector = usingRotationVector || usingHeadingSensor,
        hasMagneticInterference = hasMagneticInterference,
    )

internal fun computeCompassHeadingSourcePublication(
    headingSourceMode: CompassHeadingSourceMode,
    headingSensor: Sensor?,
    rotationVector: Sensor?,
    accelerometer: Sensor?,
    magnetometer: Sensor?,
    usingHeadingSensor: Boolean,
    usingRotationVector: Boolean,
    usingMagAccelFallback: Boolean,
    activeHeadingSource: HeadingSource,
    currentHeadingSource: HeadingSource,
    currentStatus: HeadingSourceStatus,
): CompassHeadingSourcePublication {
    val pipeline =
        resolveCurrentHeadingPipeline(
            usingHeadingSensor = usingHeadingSensor,
            usingRotationVector = usingRotationVector,
            usingMagAccelFallback = usingMagAccelFallback,
        )
    val source = resolveActiveHeadingSource(pipeline)
    val availability =
        resolveCompassSensorAvailability(
            headingSensor = headingSensor,
            rotationVector = rotationVector,
            accelerometer = accelerometer,
            magnetometer = magnetometer,
        )
    val status =
        HeadingSourceStatus(
            requestedMode = headingSourceMode,
            activeSource = source,
            headingSensorAvailable = availability.headingSensorAvailable,
            rotationVectorAvailable = availability.rotationVectorAvailable,
            magAccelFallbackAvailable = availability.magAccelFallbackAvailable,
        )
    val changed = !(activeHeadingSource == source && currentHeadingSource == source && currentStatus == status)
    val logMessages =
        if (!changed) {
            emptyList()
        } else {
            listOf(
                "heading_source ${source.telemetryToken}",
                "heading_source_status requested=${status.requestedMode.name} active=${status.activeSource.telemetryToken} headingAvailable=${status.headingSensorAvailable} rotVecAvailable=${status.rotationVectorAvailable} magFallbackAvailable=${status.magAccelFallbackAvailable}",
            )
        }
    return CompassHeadingSourcePublication(
        activeSource = source,
        status = status,
        changed = changed,
        logMessages = logMessages,
    )
}

internal fun computeCompassNorthReferenceStatus(
    currentPipeline: HeadingPipeline,
    resolvedPipeline: HeadingPipeline,
    northReferenceMode: NorthReferenceMode,
    declinationAvailable: Boolean,
): NorthReferenceStatus {
    val pipeline = currentPipeline.takeIf { it != HeadingPipeline.NONE } ?: resolvedPipeline
    return resolveNorthReferenceStatus(
        requestedMode = northReferenceMode,
        pipeline = pipeline,
        declinationAvailable = declinationAvailable,
    )
}

internal fun resolveCompassManagerHeadingPipeline(
    headingSourceMode: CompassHeadingSourceMode,
    headingSensor: Sensor?,
    rotationVector: Sensor?,
    accelerometer: Sensor?,
    magnetometer: Sensor?,
): HeadingPipeline {
    val availability =
        resolveCompassSensorAvailability(
            headingSensor = headingSensor,
            rotationVector = rotationVector,
            accelerometer = accelerometer,
            magnetometer = magnetometer,
        )
    return resolveHeadingPipeline(
        mode = headingSourceMode,
        headingSensorAvailable = availability.headingSensorAvailable,
        rotationVectorAvailable = availability.rotationVectorAvailable,
        magAccelFallbackAvailable = availability.magAccelFallbackAvailable,
    )
}

internal fun launchCompassSmoothingJob(
    scope: CoroutineScope,
    rawHeadingFlow: MutableStateFlow<Float?>,
    settleWindowMs: Long,
    getStartAtMs: () -> Long,
    getHeadingRelockUntilElapsedMs: () -> Long,
    consumeResetSmoothingRequested: () -> Boolean,
    getDisplayedHeading: () -> Float,
    publishDisplayedHeading: (Float) -> Unit,
    getPendingBootstrapRawSamplesToIgnore: () -> Int,
    setPendingBootstrapRawSamplesToIgnore: (Int) -> Unit,
    getPendingStartupBogusSamplesToIgnore: () -> Int,
    setPendingStartupBogusSamplesToIgnore: (Int) -> Unit,
    getPendingStartupHeadingPublishesToMask: () -> Int,
    setPendingStartupHeadingPublishesToMask: (Int) -> Unit,
    getStartupStabilizationUntilElapsedMs: () -> Long,
    getStartupHeadingPublishMaskUntilElapsedMs: () -> Long,
    isUsingRotationVector: () -> Boolean,
    isUsingHeadingSensor: () -> Boolean,
    updateInferredHeadingAccuracy: (Int) -> Unit,
    logDiagnostics: (String) -> Unit,
): Job {
    return scope.launch {
        var smoothedHeading = 0f
        var hasInit = false
        var resumedWithPreviousHeading = false
        var startupInputConfirmed = false
        var suppressFirstPublishAfterReset = false
        val window = ArrayDeque<Float>(HEADING_SMOOTHING_WINDOW_SIZE)
        var startupCandidateRawHeading: Float? = null
        var lastRawHeading: Float? = null
        var lastRawAtMs: Long = 0L
        var turnRateEmaDegPerSec = 0f
        var pendingLargeJumpHeading: Float? = null
        var pendingLargeJumpAtMs: Long = 0L
        var reanchorBlendActive = false

        rawHeadingFlow
            .filterNotNull()
            .collect { rawHeading ->
                val now = SystemClock.elapsedRealtime()
                val settling = (now - getStartAtMs()) < settleWindowMs
                val inRelock = now < getHeadingRelockUntilElapsedMs()

                fun publishHeadingCandidate(candidateHeading: Float): Boolean {
                    val currentDisplayedHeading = getDisplayedHeading()
                    if (
                        shouldMaskStartupHeadingPublish(
                            candidateHeadingDeg = candidateHeading,
                            displayedHeadingDeg = currentDisplayedHeading,
                            remainingPublishesToMask = getPendingStartupHeadingPublishesToMask(),
                            withinMaskWindow = now <= getStartupHeadingPublishMaskUntilElapsedMs(),
                        )
                    ) {
                        val remainingPublishesToMask =
                            (getPendingStartupHeadingPublishesToMask() - 1).coerceAtLeast(0)
                        setPendingStartupHeadingPublishesToMask(remainingPublishesToMask)
                        val deltaDeg =
                            abs(
                                shortestAngleDiffDeg(
                                    target = candidateHeading,
                                    current = currentDisplayedHeading,
                                ),
                            )
                        logDiagnostics(
                            "startup_heading_publish masked " +
                                "heading=${candidateHeading.format(1)} " +
                                "delta=${deltaDeg.format(1)} " +
                                "remaining=$remainingPublishesToMask",
                        )
                        return false
                    }
                    publishDisplayedHeading(candidateHeading)
                    return true
                }

                val raw = normalize360Deg(rawHeading)

                if (consumeResetSmoothingRequested()) {
                    val displayedHeading = getDisplayedHeading()
                    resumedWithPreviousHeading =
                        getPendingStartupHeadingPublishesToMask() > 0 &&
                        displayedHeading.isFinite()
                    hasInit = resumedWithPreviousHeading
                    if (resumedWithPreviousHeading) {
                        smoothedHeading = normalize360Deg(displayedHeading)
                        // The prior heading now provides continuity. Let the bounded re-anchor
                        // publish every step instead of hiding early steps and exposing a larger
                        // accumulated jump once the old sample mask is exhausted.
                        setPendingStartupHeadingPublishesToMask(0)
                    }
                    startupInputConfirmed = false
                    suppressFirstPublishAfterReset = !resumedWithPreviousHeading
                    window.clear()
                    startupCandidateRawHeading = null
                    lastRawHeading = null
                    lastRawAtMs = 0L
                    turnRateEmaDegPerSec = 0f
                    pendingLargeJumpHeading = null
                    pendingLargeJumpAtMs = 0L
                    reanchorBlendActive = false
                }

                if (getPendingBootstrapRawSamplesToIgnore() > 0) {
                    val remainingSamples = getPendingBootstrapRawSamplesToIgnore() - 1
                    setPendingBootstrapRawSamplesToIgnore(remainingSamples)
                    logDiagnostics(
                        "bootstrap_sample ignored raw=${raw.format(1)} " +
                            "remaining=$remainingSamples",
                    )
                    return@collect
                }

                val startupTransientDecision =
                    resolveStartupTransientAction(
                        rawDeg = raw,
                        candidateHeadingDeg = startupCandidateRawHeading,
                        remainingSamplesToIgnore = getPendingStartupBogusSamplesToIgnore(),
                        withinStartupWindow = now <= getStartupStabilizationUntilElapsedMs(),
                        usingRotationVector = isUsingRotationVector(),
                        hasInit = startupInputConfirmed,
                    )
                if (startupTransientDecision != null) {
                    startupCandidateRawHeading = startupTransientDecision.nextCandidateHeadingDeg
                    setPendingStartupBogusSamplesToIgnore(
                        startupTransientDecision.nextRemainingSamplesToIgnore,
                    )
                    when (startupTransientDecision.action) {
                        StartupTransientAction.IGNORE_AWAIT_CONFIRMATION,
                        StartupTransientAction.IGNORE_REPLACE_CANDIDATE,
                        -> {
                            updateInferredHeadingAccuracy(
                                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                            )
                            logDiagnostics(
                                "startup_bogus_sample ignored raw=${raw.format(1)} " +
                                    "remaining=${getPendingStartupBogusSamplesToIgnore()} " +
                                    "reason=${startupTransientDecision.action.telemetryToken}",
                            )
                            return@collect
                        }

                        StartupTransientAction.ACCEPT_CONFIRMED -> {
                            startupInputConfirmed = true
                            if (startupTransientDecision.acceptedHeadingDeg != null) {
                                logDiagnostics(
                                    "startup_sample accepted raw=${raw.format(1)} " +
                                        "reason=${startupTransientDecision.action.telemetryToken}",
                                )
                            }
                        }
                    }
                }

                lastRawHeading?.let { previousRaw ->
                    val dtSec = ((now - lastRawAtMs).coerceAtLeast(1L)) / 1000f
                    val rawStep = abs(shortestAngleDiffDeg(target = raw, current = previousRaw))
                    val instantTurnRate = rawStep / dtSec
                    turnRateEmaDegPerSec =
                        if (turnRateEmaDegPerSec <= 0f) {
                            instantTurnRate
                        } else {
                            TURN_RATE_EMA_ALPHA * instantTurnRate +
                                (1f - TURN_RATE_EMA_ALPHA) * turnRateEmaDegPerSec
                        }
                }
                lastRawHeading = raw
                lastRawAtMs = now

                if (!hasInit) {
                    window.clear()
                    window.addLast(raw)
                    smoothedHeading = raw
                    turnRateEmaDegPerSec = 0f
                    hasInit = true
                    val initAccuracy =
                        if (isUsingRotationVector() || isUsingHeadingSensor()) {
                            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                        } else {
                            SensorManager.SENSOR_STATUS_ACCURACY_LOW
                        }
                    updateInferredHeadingAccuracy(initAccuracy)
                    if (suppressFirstPublishAfterReset) {
                        suppressFirstPublishAfterReset = false
                        return@collect
                    }
                    publishHeadingCandidate(raw)
                    return@collect
                }

                if (reanchorBlendActive) {
                    val reanchorDiff =
                        abs(shortestAngleDiffDeg(target = raw, current = smoothedHeading))
                    smoothedHeading =
                        stepHeadingTowardConfirmedReanchor(
                            currentHeadingDeg = smoothedHeading,
                            targetHeadingDeg = raw,
                        )
                    window.clear()
                    window.addLast(raw)
                    updateInferredHeadingAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW)
                    publishHeadingCandidate(smoothedHeading)
                    if (reanchorDiff <= HEADING_REANCHOR_MAX_STEP_DEG) {
                        reanchorBlendActive = false
                        logDiagnostics(
                            "large_jump blend_complete heading=${smoothedHeading.format(1)}",
                        )
                    }
                    return@collect
                }

                val jump = abs(shortestAngleDiffDeg(target = raw, current = smoothedHeading))
                val hasPendingLargeJump =
                    pendingLargeJumpHeading != null &&
                        (now - pendingLargeJumpAtMs) <= HEADING_LARGE_JUMP_CONFIRM_WINDOW_MS
                val pendingDelta =
                    if (hasPendingLargeJump) {
                        val pending = pendingLargeJumpHeading ?: raw
                        abs(shortestAngleDiffDeg(target = raw, current = pending))
                    } else {
                        Float.NaN
                    }
                val pendingAgeMs =
                    if (hasPendingLargeJump) {
                        now - pendingLargeJumpAtMs
                    } else {
                        0L
                    }
                when (
                    resolveSensorLargeJumpAction(
                        SensorLargeJumpInput(
                            jumpDeg = jump,
                            inRelock = inRelock,
                            hasPendingLargeJump = hasPendingLargeJump,
                            pendingDeltaDeg = pendingDelta,
                            pendingAgeMs = pendingAgeMs,
                        ),
                    )
                ) {
                    LargeJumpAction.ACCEPT_IMMEDIATE,
                    LargeJumpAction.ACCEPT_CONFIRMED,
                    -> {
                        reanchorBlendActive = true
                        smoothedHeading =
                            stepHeadingTowardConfirmedReanchor(
                                currentHeadingDeg = smoothedHeading,
                                targetHeadingDeg = raw,
                            )
                        window.clear()
                        window.addLast(raw)
                        pendingLargeJumpHeading = null
                        pendingLargeJumpAtMs = 0L
                        updateInferredHeadingAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW)
                        publishHeadingCandidate(smoothedHeading)
                        logDiagnostics(
                            "large_jump accepted jump=${jump.format(1)} " +
                                "pendingAgeMs=$pendingAgeMs " +
                                "pendingDelta=${pendingDelta.format(1)} " +
                                "relock=$inRelock blend=true",
                        )
                        return@collect
                    }

                    LargeJumpAction.REJECT_PENDING -> {
                        val continuesCoherentPending =
                            hasPendingLargeJump &&
                                pendingDelta.isFinite() &&
                                pendingDelta <= HEADING_LARGE_JUMP_CONFIRM_MAX_DELTA_DEG
                        if (!continuesCoherentPending) {
                            pendingLargeJumpAtMs = now
                            logDiagnostics(
                                "large_jump pending jump=${jump.format(1)} " +
                                    "heading=${raw.format(1)} " +
                                    "current=${smoothedHeading.format(1)} relock=$inRelock",
                            )
                        }
                        pendingLargeJumpHeading = raw
                        updateInferredHeadingAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW)
                        return@collect
                    }

                    LargeJumpAction.NONE -> Unit
                }
                if (pendingLargeJumpHeading != null) {
                    pendingLargeJumpHeading = null
                    pendingLargeJumpAtMs = 0L
                }

                if (settling) {
                    smoothedHeading =
                        if (resumedWithPreviousHeading) {
                            stepHeadingTowardConfirmedReanchor(
                                currentHeadingDeg = smoothedHeading,
                                targetHeadingDeg = raw,
                            )
                        } else {
                            raw
                        }
                    val settlingAccuracy =
                        if (isUsingRotationVector() || isUsingHeadingSensor()) {
                            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                        } else {
                            SensorManager.SENSOR_STATUS_ACCURACY_LOW
                        }
                    updateInferredHeadingAccuracy(settlingAccuracy)
                    publishHeadingCandidate(smoothedHeading)
                    return@collect
                }

                if (window.size == HEADING_SMOOTHING_WINDOW_SIZE) window.removeFirst()
                window.addLast(raw)

                val noise = computeAvgAbsAngularDiff(window)
                val isNoisy = noise >= 6f
                val isFastTurn = turnRateEmaDegPerSec >= FAST_TURN_RATE_DEG_PER_SEC
                val isModerateTurn = turnRateEmaDegPerSec >= MODERATE_TURN_RATE_DEG_PER_SEC
                // During fast turns skip the circular mean — it still lags even with a shorter
                // sample window, so chase the live raw reading directly for responsiveness.
                val avg = if (isFastTurn) raw else computeCircularMean(window)
                updateInferredHeadingAccuracy(
                    inferHeadingAccuracy(
                        noiseDeg = noise,
                        turnRateDegPerSec = turnRateEmaDegPerSec,
                    ),
                )

                val minDelta =
                    resolveHeadingSmoothingMinDelta(
                        isFastTurn = isFastTurn,
                        isModerateTurn = isModerateTurn,
                        isNoisy = isNoisy,
                    )

                val diff = shortestAngleDiffDeg(target = avg, current = smoothedHeading)
                if (abs(diff) < minDelta) {
                    val convergenceAlpha =
                        deadbandConvergenceAlpha(
                            diffDeg = diff,
                            minDeltaDeg = minDelta,
                            isFastTurn = isFastTurn,
                            isModerateTurn = isModerateTurn,
                            isNoisy = isNoisy,
                        )
                    if (convergenceAlpha > 0f) {
                        smoothedHeading =
                            normalize360Deg(smoothedHeading + convergenceAlpha * diff)
                        publishHeadingCandidate(smoothedHeading)
                    }
                    return@collect
                }

                val alpha =
                    resolveHeadingSmoothingAlpha(
                        diffDeg = diff,
                        isFastTurn = isFastTurn,
                        isModerateTurn = isModerateTurn,
                        isNoisy = isNoisy,
                    )

                smoothedHeading = normalize360Deg(smoothedHeading + alpha * diff)
                publishHeadingCandidate(smoothedHeading)
            }
    }
}
