package com.glancemap.glancemapwearos.domain.sensors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow

internal class CompassHeadingProcessor {
    @Suppress("LongParameterList") // This adapter intentionally forwards the existing smoothing callbacks.
    fun launch(
        scope: CoroutineScope,
        rawHeadingFlow: SharedFlow<SensorRawHeadingSample>,
        settleWindowMs: Long,
        getStartAtMs: () -> Long,
        getHeadingRelockUntilElapsedMs: () -> Long,
        consumeResetSmoothingRequested: () -> Boolean,
        getDisplayedHeading: () -> Float,
        publishDisplayedHeading: (Float, SensorRawHeadingSample) -> Unit,
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
    ): Job =
        launchCompassSmoothingJob(
            scope = scope,
            rawHeadingFlow = rawHeadingFlow,
            settleWindowMs = settleWindowMs,
            getStartAtMs = getStartAtMs,
            getHeadingRelockUntilElapsedMs = getHeadingRelockUntilElapsedMs,
            consumeResetSmoothingRequested = consumeResetSmoothingRequested,
            getDisplayedHeading = getDisplayedHeading,
            publishDisplayedHeading = publishDisplayedHeading,
            getPendingBootstrapRawSamplesToIgnore = getPendingBootstrapRawSamplesToIgnore,
            setPendingBootstrapRawSamplesToIgnore = setPendingBootstrapRawSamplesToIgnore,
            getPendingStartupBogusSamplesToIgnore = getPendingStartupBogusSamplesToIgnore,
            setPendingStartupBogusSamplesToIgnore = setPendingStartupBogusSamplesToIgnore,
            getPendingStartupHeadingPublishesToMask = getPendingStartupHeadingPublishesToMask,
            setPendingStartupHeadingPublishesToMask = setPendingStartupHeadingPublishesToMask,
            getStartupStabilizationUntilElapsedMs = getStartupStabilizationUntilElapsedMs,
            getStartupHeadingPublishMaskUntilElapsedMs = getStartupHeadingPublishMaskUntilElapsedMs,
            isUsingRotationVector = isUsingRotationVector,
            isUsingHeadingSensor = isUsingHeadingSensor,
            updateInferredHeadingAccuracy = updateInferredHeadingAccuracy,
            logDiagnostics = logDiagnostics,
        )
}
