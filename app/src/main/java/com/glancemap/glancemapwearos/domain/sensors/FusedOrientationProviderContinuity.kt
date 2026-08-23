package com.glancemap.glancemapwearos.domain.sensors

internal const val FUSED_READY_TIMEOUT_MS = 1_000L
internal const val FUSED_STALE_SAMPLE_RETRY_REASON = "sample_stale_retry"
private const val FUSED_STALE_RETRY_READY_TIMEOUT_MS = 1_000L
private const val FUSED_STALE_RETRY_LOW_POWER_READY_TIMEOUT_MS = 1_200L

internal fun resolveFusedReadyTimeoutMs(
    requestReason: String,
    lowPowerMode: Boolean,
    recalibrationBoostActive: Boolean,
): Long =
    when {
        requestReason != FUSED_STALE_SAMPLE_RETRY_REASON -> FUSED_READY_TIMEOUT_MS
        lowPowerMode && !recalibrationBoostActive ->
            FUSED_STALE_RETRY_LOW_POWER_READY_TIMEOUT_MS
        else -> FUSED_STALE_RETRY_READY_TIMEOUT_MS
    }

internal fun isCurrentFusedReadyTimeout(
    timeoutIsCurrent: Boolean,
    started: Boolean,
    usingFallback: Boolean,
    awaitingFusedReady: Boolean,
    timeoutRequestGeneration: Long,
    activeRequestGeneration: Long,
): Boolean =
    timeoutIsCurrent &&
        started &&
        !usingFallback &&
        awaitingFusedReady &&
        timeoutRequestGeneration == activeRequestGeneration
