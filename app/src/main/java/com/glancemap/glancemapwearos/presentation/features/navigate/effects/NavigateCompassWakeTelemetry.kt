package com.glancemap.glancemapwearos.presentation.features.navigate.effects

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.isCompassTelemetryCaptureActive
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.domain.sensors.COMPASS_TELEMETRY_TAG
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun NavigateCompassWakeTelemetry(
    isScreenResumed: Boolean,
    screenState: LocationScreenState,
    isOfflineMode: Boolean,
    renderState: CompassRenderState,
    renderedHeadingDeg: Float,
    renderedMapRotationDeg: Float,
) {
    var sessionId by remember { mutableLongStateOf(0L) }
    var startedAtMs by remember { mutableLongStateOf(0L) }
    var firstSourceLogged by remember { mutableStateOf(false) }
    var fusedLogged by remember { mutableStateOf(false) }
    var renderedLogged by remember { mutableStateOf(false) }
    var firstRenderableLogged by remember { mutableStateOf(false) }
    var firstTrustedLogged by remember { mutableStateOf(false) }
    val startupMetrics = remember { CompassStartupMetrics() }
    val interactive = isScreenResumed && screenState == LocationScreenState.INTERACTIVE && !isOfflineMode

    LaunchedEffect(interactive) {
        val now = SystemClock.elapsedRealtime()
        if (interactive) {
            sessionId = nextCompassWakeSessionId()
            startedAtMs = now
            firstSourceLogged = false
            fusedLogged = false
            renderedLogged = false
            firstRenderableLogged = false
            firstTrustedLogged = false
            if (isCompassTelemetryCaptureActive()) {
                startupMetrics.start(
                    sessionId = sessionId,
                    nowElapsedMs = now,
                    initialState =
                        CompassStartupInitialState(
                            headingDeg = renderState.headingDeg,
                            renderedHeadingDeg = renderedHeadingDeg,
                            mapRotationDeg = renderedMapRotationDeg,
                            source = renderState.headingSource,
                        ),
                )
            }
            logCompassWake(
                "wake_session stage=start id=$sessionId screenState=${screenState.name} " +
                    CompassSessionHistory.previousSessionDescription(
                        nextHeadingDeg = renderState.headingDeg,
                        nowElapsedMs = now,
                    ),
            )
            val sampleAgeMs = renderState.headingSampleAgeMs(now)
            if (renderState.headingRenderable && !renderState.headingSampleStale) {
                firstRenderableLogged = true
                logCompassWake(
                    "wake_session stage=first_renderable id=$sessionId latencyMs=0 " +
                        "state=${renderState.trackingState.telemetryToken} " +
                        "reason=${renderState.trackingReason.telemetryToken} " +
                        "cached=true sampleAgeMs=${sampleAgeMs ?: "na"}",
                )
            }
            if (renderState.headingTrusted && !renderState.headingSampleStale) {
                if (sampleAgeMs != null && sampleAgeMs <= TRUSTED_CACHED_HEADING_MAX_AGE_MS) {
                    firstTrustedLogged = true
                    logCompassWake(
                        "wake_session stage=first_trusted id=$sessionId latencyMs=0 " +
                            "state=${renderState.trackingState.telemetryToken} " +
                            "reason=${renderState.trackingReason.telemetryToken} " +
                            "cached=true sampleAgeMs=$sampleAgeMs",
                    )
                }
            }
        } else if (startedAtMs > 0L) {
            startupMetrics.finish(
                nowElapsedMs = now,
                finalHeadingDeg = renderState.headingDeg,
                finalRenderedHeadingDeg = renderedHeadingDeg,
                finalMapRotationDeg = renderedMapRotationDeg,
            )
            logCompassWake(
                "wake_session stage=end id=$sessionId durationMs=${(now - startedAtMs).coerceAtLeast(0L)} " +
                    "firstSource=$firstSourceLogged fused=$fusedLogged rendered=$renderedLogged " +
                    "firstRenderable=$firstRenderableLogged firstTrusted=$firstTrustedLogged " +
                    "screenState=${screenState.name} offline=$isOfflineMode",
            )
            startedAtMs = 0L
        }
    }

    LaunchedEffect(interactive, sessionId) {
        if (!interactive || startedAtMs <= 0L) return@LaunchedEffect
        delay(STARTUP_METRICS_WINDOW_MS)
        startupMetrics.logStartupSummary(SystemClock.elapsedRealtime())
    }

    SideEffect {
        if (interactive && startedAtMs > 0L) {
            startupMetrics.record(
                nowElapsedMs = SystemClock.elapsedRealtime(),
                headingDeg = renderState.headingDeg,
                renderedHeadingDeg = renderedHeadingDeg,
                mapRotationDeg = renderedMapRotationDeg,
                source =
                    if (renderState.headingSampleStale) {
                        HeadingSource.NONE
                    } else {
                        renderState.headingSource
                    },
            )
        }
    }

    LaunchedEffect(
        interactive,
        sessionId,
        renderState.headingRenderable,
        renderState.headingTrusted,
        renderState.trackingState,
        renderState.trackingReason,
        renderState.headingSampleElapsedRealtimeMs,
        renderState.headingSampleStale,
    ) {
        if (!interactive || startedAtMs <= 0L || renderState.headingSampleStale) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        val latencyMs = (now - startedAtMs).coerceAtLeast(0L)
        val sampleAgeMs = renderState.headingSampleAgeMs(now)
        if (!firstRenderableLogged && renderState.headingRenderable) {
            firstRenderableLogged = true
            logCompassWake(
                "wake_session stage=first_renderable id=$sessionId latencyMs=$latencyMs " +
                    "state=${renderState.trackingState.telemetryToken} " +
                    "reason=${renderState.trackingReason.telemetryToken} " +
                    "cached=false sampleAgeMs=${sampleAgeMs ?: "na"}",
            )
        }
        val trustedSampleCurrent =
            renderState.headingSampleElapsedRealtimeMs?.let { sampleAtMs ->
                sampleAtMs >= startedAtMs || (sampleAgeMs ?: Long.MAX_VALUE) <= TRUSTED_CACHED_HEADING_MAX_AGE_MS
            } == true
        if (!firstTrustedLogged && renderState.headingTrusted && trustedSampleCurrent) {
            firstTrustedLogged = true
            logCompassWake(
                "wake_session stage=first_trusted id=$sessionId latencyMs=$latencyMs " +
                    "state=${renderState.trackingState.telemetryToken} " +
                    "reason=${renderState.trackingReason.telemetryToken} " +
                    "cached=false sampleAgeMs=${sampleAgeMs ?: "na"}",
            )
        }
    }

    LaunchedEffect(
        interactive,
        renderState.headingSource,
        renderState.headingSampleElapsedRealtimeMs,
        renderState.headingSampleStale,
    ) {
        if (!interactive || startedAtMs <= 0L) return@LaunchedEffect
        val sampleAtMs = renderState.headingSampleElapsedRealtimeMs ?: return@LaunchedEffect
        if (
            sampleAtMs < startedAtMs ||
            renderState.headingSource == HeadingSource.NONE ||
            renderState.headingSampleStale
        ) {
            return@LaunchedEffect
        }
        val latencyMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
        if (!firstSourceLogged) {
            firstSourceLogged = true
            logCompassWake(
                "wake_session stage=first_source id=$sessionId latencyMs=$latencyMs " +
                    "source=${renderState.headingSource.telemetryToken} " +
                    "stale=${renderState.headingSampleStale} " +
                    "heading=${renderState.headingDeg.format(1)}",
            )
        }
        if (
            !fusedLogged &&
            renderState.headingSource == HeadingSource.FUSED_ORIENTATION
        ) {
            fusedLogged = true
            logCompassWake(
                "wake_session stage=fused_ready id=$sessionId latencyMs=$latencyMs " +
                    "heading=${renderState.headingDeg.format(1)}",
            )
        }
    }

    LaunchedEffect(
        interactive,
        renderState.headingDeg,
        renderState.headingSource,
        renderState.headingSampleElapsedRealtimeMs,
        renderState.headingSampleStale,
        renderedHeadingDeg,
        renderedMapRotationDeg,
    ) {
        if (!interactive || startedAtMs <= 0L || renderedLogged) return@LaunchedEffect
        val sampleAtMs = renderState.headingSampleElapsedRealtimeMs ?: return@LaunchedEffect
        if (
            sampleAtMs < startedAtMs ||
            renderState.headingSource == HeadingSource.NONE ||
            renderState.headingSampleStale
        ) {
            return@LaunchedEffect
        }
        val deltaDeg = shortestHeadingDeltaDeg(renderedHeadingDeg, renderState.headingDeg)
        if (deltaDeg > COMPASS_WAKE_RENDER_ALIGNMENT_DEG) return@LaunchedEffect
        renderedLogged = true
        logCompassWake(
            "wake_session stage=render_aligned id=$sessionId " +
                "latencyMs=${(SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)} " +
                "source=${renderState.headingSource.telemetryToken} " +
                "heading=${renderState.headingDeg.format(1)} rendered=${renderedHeadingDeg.format(1)} " +
                "mapRotation=${renderedMapRotationDeg.format(1)} deltaDeg=${deltaDeg.format(1)}",
        )
    }
}

internal fun shortestHeadingDeltaDeg(
    firstDeg: Float,
    secondDeg: Float,
): Float {
    val normalized = ((firstDeg - secondDeg + 540f) % 360f) - 180f
    return abs(normalized)
}

private fun logCompassWake(message: String) {
    if (!isCompassTelemetryCaptureActive()) return
    DebugTelemetry.log(COMPASS_TELEMETRY_TAG, message)
}

private fun CompassRenderState.headingSampleAgeMs(
    nowElapsedMs: Long,
): Long? = headingSampleElapsedRealtimeMs?.let { (nowElapsedMs - it).coerceAtLeast(0L) }

private const val TRUSTED_CACHED_HEADING_MAX_AGE_MS = 5_000L

internal data class CompassStartupSnapshot(
    val sessionId: Long,
    val windowMs: Long,
    val sampleCount: Int,
    val headingSpanDeg: Float,
    val maxHeadingJumpDeg: Float,
    val cumulativeHeadingRotationDeg: Float,
    val directionReversalCount: Int,
    val cumulativeMapRotationDeg: Float,
    val visibleHeadingMaxJumpDeg: Float,
    val visibleMapRotationMaxJumpDeg: Float,
    val sourceHandoffCount: Int,
    val sourceHandoffMaxJumpDeg: Float,
    val renderErrorAvgDeg: Float?,
    val renderErrorMaxDeg: Float,
    val stable3Ms: Long?,
    val stable5Ms: Long?,
    val fusedReadyMs: Long?,
    val startHeadingDeg: Float?,
    val endHeadingDeg: Float?,
)

internal data class CompassStartupInitialState(
    val headingDeg: Float,
    val renderedHeadingDeg: Float,
    val mapRotationDeg: Float,
    val source: HeadingSource,
)

internal class CompassStartupMetrics {
    private var sessionId = 0L
    private var startedAtMs = 0L
    private var sampleCount = 0
    private var firstHeadingDeg: Float? = null
    private var lastHeadingDeg: Float? = null
    private var lastRenderedHeadingDeg: Float? = null
    private var lastMapRotationDeg: Float? = null
    private var minUnwrappedHeadingDeg = 0f
    private var maxUnwrappedHeadingDeg = 0f
    private var unwrappedHeadingDeg = 0f
    private var cumulativeHeadingRotationDeg = 0f
    private var cumulativeMapRotationDeg = 0f
    private var maxHeadingJumpDeg = 0f
    private var visibleHeadingMaxJumpDeg = 0f
    private var visibleMapRotationMaxJumpDeg = 0f
    private var sourceHandoffCount = 0
    private var sourceHandoffMaxJumpDeg = 0f
    private var lastHeadingSource = HeadingSource.NONE
    private var directionReversalCount = 0
    private var previousDirection = 0
    private var renderErrorTotalDeg = 0f
    private var renderErrorMaxDeg = 0f
    private var renderErrorSampleCount = 0
    private var stable3AtMs: Long? = null
    private var stable5AtMs: Long? = null
    private var stable3SinceMs = 0L
    private var stable5SinceMs = 0L
    private var stable3AnchorDeg: Float? = null
    private var stable5AnchorDeg: Float? = null
    private var firstFusedAtMs: Long? = null
    private var summaryLogged = false

    fun start(
        sessionId: Long,
        nowElapsedMs: Long,
        initialState: CompassStartupInitialState,
    ) {
        this.sessionId = sessionId
        startedAtMs = nowElapsedMs
        sampleCount = 0
        firstHeadingDeg = initialState.headingDeg.takeIf(Float::isFinite)
        lastHeadingDeg = initialState.headingDeg.takeIf(Float::isFinite)
        lastRenderedHeadingDeg = initialState.renderedHeadingDeg.takeIf(Float::isFinite)
        lastMapRotationDeg = initialState.mapRotationDeg.takeIf(Float::isFinite)
        minUnwrappedHeadingDeg = 0f
        maxUnwrappedHeadingDeg = 0f
        unwrappedHeadingDeg = 0f
        cumulativeHeadingRotationDeg = 0f
        cumulativeMapRotationDeg = 0f
        maxHeadingJumpDeg = 0f
        visibleHeadingMaxJumpDeg = 0f
        visibleMapRotationMaxJumpDeg = 0f
        sourceHandoffCount = 0
        sourceHandoffMaxJumpDeg = 0f
        lastHeadingSource = initialState.source
        directionReversalCount = 0
        previousDirection = 0
        renderErrorTotalDeg = 0f
        renderErrorMaxDeg = 0f
        renderErrorSampleCount = 0
        stable3AtMs = null
        stable5AtMs = null
        stable3SinceMs = 0L
        stable5SinceMs = 0L
        stable3AnchorDeg = null
        stable5AnchorDeg = null
        firstFusedAtMs = null
        summaryLogged = false
    }

    fun record(
        nowElapsedMs: Long,
        headingDeg: Float,
        renderedHeadingDeg: Float,
        mapRotationDeg: Float,
        source: HeadingSource,
    ) {
        if (startedAtMs <= 0L || nowElapsedMs - startedAtMs > STARTUP_METRICS_WINDOW_MS) return
        if (!headingDeg.isFinite()) return
        if (source == HeadingSource.FUSED_ORIENTATION && firstFusedAtMs == null) {
            firstFusedAtMs = nowElapsedMs
        }

        val previousHeading = lastHeadingDeg
        if (previousHeading != null) {
            val signedDelta = signedHeadingDeltaDeg(headingDeg, previousHeading)
            val absoluteDelta = abs(signedDelta)
            cumulativeHeadingRotationDeg += absoluteDelta
            maxHeadingJumpDeg = max(maxHeadingJumpDeg, absoluteDelta)
            unwrappedHeadingDeg += signedDelta
            minUnwrappedHeadingDeg = min(minUnwrappedHeadingDeg, unwrappedHeadingDeg)
            maxUnwrappedHeadingDeg = max(maxUnwrappedHeadingDeg, unwrappedHeadingDeg)
            val direction =
                when {
                    signedDelta > DIRECTION_REVERSAL_MIN_DELTA_DEG -> 1
                    signedDelta < -DIRECTION_REVERSAL_MIN_DELTA_DEG -> -1
                    else -> 0
                }
            if (direction != 0) {
                if (previousDirection != 0 && direction != previousDirection) {
                    directionReversalCount += 1
                }
                previousDirection = direction
            }
            if (
                source != HeadingSource.NONE &&
                lastHeadingSource != HeadingSource.NONE &&
                source != lastHeadingSource
            ) {
                sourceHandoffCount += 1
                sourceHandoffMaxJumpDeg = max(sourceHandoffMaxJumpDeg, absoluteDelta)
            }
        }
        if (source != HeadingSource.NONE) lastHeadingSource = source
        lastHeadingDeg = headingDeg
        sampleCount += 1

        if (renderedHeadingDeg.isFinite()) {
            lastRenderedHeadingDeg?.let { previousRenderedHeadingDeg ->
                visibleHeadingMaxJumpDeg =
                    max(
                        visibleHeadingMaxJumpDeg,
                        shortestHeadingDeltaDeg(renderedHeadingDeg, previousRenderedHeadingDeg),
                    )
            }
            val renderError = shortestHeadingDeltaDeg(renderedHeadingDeg, headingDeg)
            renderErrorTotalDeg += renderError
            renderErrorMaxDeg = max(renderErrorMaxDeg, renderError)
            renderErrorSampleCount += 1
            lastRenderedHeadingDeg = renderedHeadingDeg
        }
        if (mapRotationDeg.isFinite()) {
            lastMapRotationDeg?.let {
                val mapRotationDeltaDeg = shortestHeadingDeltaDeg(mapRotationDeg, it)
                cumulativeMapRotationDeg += mapRotationDeltaDeg
                visibleMapRotationMaxJumpDeg = max(visibleMapRotationMaxJumpDeg, mapRotationDeltaDeg)
            }
            lastMapRotationDeg = mapRotationDeg
        }

        val strictAnchor = stable3AnchorDeg
        if (
            strictAnchor == null ||
            shortestHeadingDeltaDeg(headingDeg, strictAnchor) > STABLE_STRICT_SPAN_DEG
        ) {
            stable3AnchorDeg = headingDeg
            stable3SinceMs = nowElapsedMs
        } else if (stable3AtMs == null && nowElapsedMs - stable3SinceMs >= STABILITY_WINDOW_MS) {
            stable3AtMs = nowElapsedMs
        }
        val relaxedAnchor = stable5AnchorDeg
        if (
            relaxedAnchor == null ||
            shortestHeadingDeltaDeg(headingDeg, relaxedAnchor) > STABLE_RELAXED_SPAN_DEG
        ) {
            stable5AnchorDeg = headingDeg
            stable5SinceMs = nowElapsedMs
        } else if (stable5AtMs == null && nowElapsedMs - stable5SinceMs >= STABILITY_WINDOW_MS) {
            stable5AtMs = nowElapsedMs
        }
    }

    fun finish(
        nowElapsedMs: Long,
        finalHeadingDeg: Float,
        finalRenderedHeadingDeg: Float,
        finalMapRotationDeg: Float,
    ) {
        if (startedAtMs <= 0L) return
        if (nowElapsedMs - startedAtMs <= STARTUP_METRICS_WINDOW_MS) {
            record(
                nowElapsedMs = nowElapsedMs,
                headingDeg = finalHeadingDeg,
                renderedHeadingDeg = finalRenderedHeadingDeg,
                mapRotationDeg = finalMapRotationDeg,
                source = HeadingSource.NONE,
            )
        }
        logStartupSummary(nowElapsedMs)
        CompassSessionHistory.recordEnd(
            endedAtMs = nowElapsedMs,
            headingDeg = finalHeadingDeg,
            renderedHeadingDeg = finalRenderedHeadingDeg,
            mapRotationDeg = finalMapRotationDeg,
        )
        startedAtMs = 0L
    }

    fun logStartupSummary(nowElapsedMs: Long) {
        if (startedAtMs <= 0L || summaryLogged) return
        summaryLogged = true
        val snapshot = snapshot(nowElapsedMs) ?: return
        logCompassWake(
            "wake_session stage=startup_summary id=${snapshot.sessionId} " +
                "windowMs=${snapshot.windowMs} samples=${snapshot.sampleCount} " +
                "headingSpanDeg=${snapshot.headingSpanDeg.format(1)} " +
                "maxJumpDeg=${snapshot.maxHeadingJumpDeg.format(1)} " +
                "cumulativeHeadingRotationDeg=${snapshot.cumulativeHeadingRotationDeg.format(1)} " +
                "directionReversals=${snapshot.directionReversalCount} " +
                "cumulativeMapRotationDeg=${snapshot.cumulativeMapRotationDeg.format(1)} " +
                "visibleHeadingMaxJumpDeg=${snapshot.visibleHeadingMaxJumpDeg.format(1)} " +
                "visibleMapRotationMaxJumpDeg=${snapshot.visibleMapRotationMaxJumpDeg.format(1)} " +
                "sourceHandoffs=${snapshot.sourceHandoffCount} " +
                "sourceHandoffMaxJumpDeg=${snapshot.sourceHandoffMaxJumpDeg.format(1)} " +
                "renderErrorAvgDeg=${snapshot.renderErrorAvgDeg.formatOrNa(1)} " +
                "renderErrorMaxDeg=${snapshot.renderErrorMaxDeg.format(1)} " +
                "stable3Ms=${snapshot.stable3Ms ?: "na"} stable5Ms=${snapshot.stable5Ms ?: "na"} " +
                "fusedReadyMs=${snapshot.fusedReadyMs ?: "na"} " +
                "startHeading=${snapshot.startHeadingDeg.formatOrNa(1)} " +
                "endHeading=${snapshot.endHeadingDeg.formatOrNa(1)}",
        )
    }

    internal fun snapshot(nowElapsedMs: Long): CompassStartupSnapshot? {
        if (startedAtMs <= 0L) return null
        return CompassStartupSnapshot(
            sessionId = sessionId,
            windowMs = min((nowElapsedMs - startedAtMs).coerceAtLeast(0L), STARTUP_METRICS_WINDOW_MS),
            sampleCount = sampleCount,
            headingSpanDeg = maxUnwrappedHeadingDeg - minUnwrappedHeadingDeg,
            maxHeadingJumpDeg = maxHeadingJumpDeg,
            cumulativeHeadingRotationDeg = cumulativeHeadingRotationDeg,
            directionReversalCount = directionReversalCount,
            cumulativeMapRotationDeg = cumulativeMapRotationDeg,
            visibleHeadingMaxJumpDeg = visibleHeadingMaxJumpDeg,
            visibleMapRotationMaxJumpDeg = visibleMapRotationMaxJumpDeg,
            sourceHandoffCount = sourceHandoffCount,
            sourceHandoffMaxJumpDeg = sourceHandoffMaxJumpDeg,
            renderErrorAvgDeg =
                if (renderErrorSampleCount > 0) {
                    renderErrorTotalDeg / renderErrorSampleCount
                } else {
                    null
                },
            renderErrorMaxDeg = renderErrorMaxDeg,
            stable3Ms = stableLatency(stable3AtMs),
            stable5Ms = stableLatency(stable5AtMs),
            fusedReadyMs = stableLatency(firstFusedAtMs),
            startHeadingDeg = firstHeadingDeg,
            endHeadingDeg = lastHeadingDeg,
        )
    }

    private fun stableLatency(atMs: Long?): Long? = atMs?.let { (it - startedAtMs).coerceAtLeast(0L) }
}

private object CompassWakeSessionIds {
    private val latestId = AtomicLong(0L)

    fun next(): Long = latestId.incrementAndGet()
}

internal fun nextCompassWakeSessionId(): Long = CompassWakeSessionIds.next()

private object CompassSessionHistory {
    private var previous: PreviousCompassSession? = null

    @Synchronized
    fun recordEnd(
        endedAtMs: Long,
        headingDeg: Float,
        renderedHeadingDeg: Float,
        mapRotationDeg: Float,
    ) {
        previous =
            PreviousCompassSession(
                endedAtMs = endedAtMs,
                headingDeg = headingDeg,
                renderedHeadingDeg = renderedHeadingDeg,
                mapRotationDeg = mapRotationDeg,
            )
    }

    @Synchronized
    fun previousSessionDescription(
        nextHeadingDeg: Float,
        nowElapsedMs: Long,
    ): String {
        val last = previous ?: return "previousSession=none"
        return "previousSessionAgeMs=${(nowElapsedMs - last.endedAtMs).coerceAtLeast(0L)} " +
            "previousHeading=${last.headingDeg.format(1)} " +
            "previousRendered=${last.renderedHeadingDeg.format(1)} " +
            "previousMapRotation=${last.mapRotationDeg.format(1)} " +
            "restartHeadingDeltaDeg=${shortestHeadingDeltaDeg(nextHeadingDeg, last.headingDeg).format(1)}"
    }
}

private data class PreviousCompassSession(
    val endedAtMs: Long,
    val headingDeg: Float,
    val renderedHeadingDeg: Float,
    val mapRotationDeg: Float,
)

private fun signedHeadingDeltaDeg(
    targetDeg: Float,
    currentDeg: Float,
): Float = ((targetDeg - currentDeg + 540f) % 360f) - 180f

private fun Float?.formatOrNa(decimals: Int): String = this?.takeIf(Float::isFinite)?.format(decimals) ?: "na"

private fun Float.format(decimals: Int): String = "%.${decimals}f".format(Locale.US, this)

private const val COMPASS_WAKE_RENDER_ALIGNMENT_DEG = 3f
private const val STARTUP_METRICS_WINDOW_MS = 5_000L
private const val STABILITY_WINDOW_MS = 1_000L
private const val STABLE_STRICT_SPAN_DEG = 3f
private const val STABLE_RELAXED_SPAN_DEG = 5f
private const val DIRECTION_REVERSAL_MIN_DELTA_DEG = 1f
