package com.glancemap.glancemapwearos.presentation.features.navigate.motion

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.location.filter.LocationOutputFilterMetricSummary
import com.glancemap.glancemapwearos.core.service.location.filter.LocationOutputFilterTelemetry
import com.glancemap.glancemapwearos.core.service.location.filter.LocationOutputFilterTelemetrySummary

internal enum class MarkerMotionMode(
    val label: String,
) {
    IDLE("idle"),
    FIXED("fixed"),
    BLEND("blend"),
    PREDICT("predict"),
}

internal data class MarkerMotionMetricSummary(
    val samples: Int = 0,
    val mean: Float? = null,
    val p50: Float? = null,
    val p95: Float? = null,
    val max: Float? = null,
)

internal data class MarkerMotionSummary(
    val acceptedFixes: Int = 0,
    val outlierDrops: Int = 0,
    val predictionUpdates: Int = 0,
    val renderedMotionUpdates: Int = 0,
    val blendStarts: Int = 0,
    val clampedCorrections: Int = 0,
    val blockedTransitions: Int = 0,
    val blockedReasonCounts: Map<String, Int> = emptyMap(),
    val latestMode: MarkerMotionMode = MarkerMotionMode.IDLE,
    val latestReason: String? = null,
    val innovationSamples: Int = 0,
    val innovationMeanM: Float? = null,
    val innovationMaxM: Float? = null,
    val fixGapSamples: Int = 0,
    val fixGapMeanMs: Long? = null,
    val fixGapMaxMs: Long? = null,
    val firstRenderDelaySamples: Int = 0,
    val firstRenderDelayMeanMs: Long? = null,
    val firstRenderDelayMaxMs: Long? = null,
    val activeRenderIntervalSamples: Int = 0,
    val activeRenderIntervalMeanMs: Long? = null,
    val activeRenderIntervalP50Ms: Long? = null,
    val activeRenderIntervalP95Ms: Long? = null,
    val activeRenderIntervalMaxMs: Long? = null,
    val nextFixPredictionResidualM: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val visibleNextFixPredictionResidualM: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val screenOffNextFixPredictionResidualM: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val correctionComponentSamples: Int = 0,
    val correctionAlongTrackMeanM: Float? = null,
    val correctionCrossTrackMeanM: Float? = null,
    val correctionAlongTrackAbsM: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val correctionCrossTrackAbsM: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val renderDisplacementM: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val renderDisplacementPx: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val correctionSettleDurationMs: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val correctionInterruptedCount: Int = 0,
    val rawFilteredPositionOffsetM: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val rawFilteredSpeedOffsetMps: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val rawFilteredBearingOffsetDeg: MarkerMotionMetricSummary = MarkerMotionMetricSummary(),
    val modeDwellMs: Map<MarkerMotionMode, Long> = emptyMap(),
) {
    fun summaryLabel(): String =
        buildString {
            append("mode=${latestMode.label}")
            append(" fix=$acceptedFixes")
            append(" pred=$predictionUpdates")
            append(" render=$renderedMotionUpdates")
            append(" blend=$blendStarts")
            append(" clamp=$clampedCorrections")
            append(" drop=$outlierDrops")
            firstRenderDelayMeanMs?.let { append(" firstRender=${it}ms") }
            activeRenderIntervalMeanMs?.let { append(" renderGap=${it}ms") }
            visibleNextFixPredictionResidualM.p95?.let {
                append(" visibleResidualP95=${it.format(1)}m")
            }
            screenOffNextFixPredictionResidualM.p95?.let {
                append(" screenOffResidualP95=${it.format(1)}m")
            }
            renderDisplacementPx.p95?.let { append(" renderStepP95=${it.format(1)}px") }
        }
}

internal data class MarkerMotionSnapshot(
    val mode: MarkerMotionMode = MarkerMotionMode.IDLE,
    val reason: String? = null,
    val fixAgeMs: Long? = null,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val correctionDistanceM: Float? = null,
    val correctionAgeMs: Long? = null,
    val updatedAtElapsedMs: Long = 0L,
) {
    fun compactLabel(): String {
        if (mode == MarkerMotionMode.IDLE && updatedAtElapsedMs <= 0L) return "idle"

        val parts = mutableListOf("mode=${mode.label}")
        reasonLabel(reason)?.let { parts += "why=$it" }
        fixAgeMs?.let { parts += "age=${it}ms" }
        accuracyM?.let { parts += "acc=${it.format(0)}m" }
        speedMps?.let { parts += "v=${it.format(1)}" }
        bearingDeg?.let { parts += "brg=${it.format(0)}" }
        return parts.joinToString(" ")
    }

    // Keep the existing presentation branches and fallbacks while resolving localized labels.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun overlayLabel(resources: Resources): String? {
        if (mode == MarkerMotionMode.IDLE && updatedAtElapsedMs <= 0L) return null

        val header =
            buildString {
                append(
                    when (mode) {
                        MarkerMotionMode.IDLE -> resources.getString(R.string.debug_idle)
                        MarkerMotionMode.FIXED -> resources.getString(R.string.debug_fixed)
                        MarkerMotionMode.BLEND -> resources.getString(R.string.debug_blend)
                        MarkerMotionMode.PREDICT -> resources.getString(R.string.debug_predict)
                    },
                )
                when (reason) {
                    null -> null
                    "await_fresh_fix" -> resources.getString(R.string.debug_wait_fix)
                    "wake_anchor" -> resources.getString(R.string.debug_wake)
                    "initial_fix" -> resources.getString(R.string.debug_first)
                    "gps_correction" -> resources.getString(R.string.debug_correct)
                    "correction_clamped" -> resources.getString(R.string.debug_clamp)
                    "stationary_jitter" -> resources.getString(R.string.debug_steady)
                    "deadband_snap" -> resources.getString(R.string.debug_snap)
                    "stale_fix" -> resources.getString(R.string.debug_stale_fix)
                    "outlier_drop" -> resources.getString(R.string.debug_outlier)
                    "prediction_delay" -> resources.getString(R.string.debug_delay)
                    "stale" -> resources.getString(R.string.debug_stale)
                    "bad_accuracy" -> resources.getString(R.string.debug_bad_acc)
                    "no_bearing" -> resources.getString(R.string.debug_no_brg)
                    "slow" -> resources.getString(R.string.debug_slow)
                    "too_close" -> resources.getString(R.string.debug_tiny)
                    "between_fixes" -> resources.getString(R.string.debug_between)
                    "degraded_gps" -> resources.getString(R.string.debug_gps_weak)
                    "watch_gps_catch_up" -> resources.getString(R.string.debug_watch_catch_up)
                    "auto_fused_catch_up" -> resources.getString(R.string.debug_fused_catch_up)
                    "auto_fused_high_speed_catch_up" -> resources.getString(R.string.debug_fused_fast_catch_up)
                    "screen_off_anchor" -> resources.getString(R.string.debug_screen_off_anchor)
                    "reset" -> resources.getString(R.string.debug_reset)
                    "interactive_start" -> resources.getString(R.string.debug_screen_wake)
                    "tracking_stopped" -> resources.getString(R.string.debug_track_off)
                    "fresh_fix_release" -> resources.getString(R.string.debug_fresh_start)
                    "marker_hidden" -> resources.getString(R.string.debug_hidden)
                    "dispose" -> resources.getString(R.string.debug_dispose)
                    else -> reason.replace('_', ' ')
                }?.let {
                    append(' ')
                    append(it)
                }
            }
        val details = mutableListOf<String>()
        fixAgeMs?.let { details += resources.getString(R.string.debug_age_ms, it) }
        accuracyM?.let { details += resources.getString(R.string.debug_acc_m, it.format(0)) }
        speedMps?.let { details += resources.getString(R.string.debug_v, it.format(1)) }
        bearingDeg?.let { details += resources.getString(R.string.debug_brg, it.format(0)) }
        correctionDistanceM?.let { details += resources.getString(R.string.debug_corr_m, it.format(1)) }
        correctionAgeMs?.let { details += resources.getString(R.string.debug_corrage_ms, it) }
        return if (details.isEmpty()) {
            header
        } else {
            resources.getString(
                R.string.debug_value,
                header,
                details.joinToString(" "),
            )
        }
    }
}

internal data class CorrectionClampTelemetryEvent(
    val nowElapsedMs: Long,
    val actualCorrectionDistanceM: Float,
    val visibleCorrectionDistanceM: Float,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float?,
)

internal object MarkerMotionTelemetry {
    private const val TAG = "MarkerMotion"

    private val lock = Any()

    @Volatile private var collectionEnabledForTests = false
    private var latestSnapshot = MarkerMotionSnapshot()
    private var acceptedFixes: Int = 0
    private var outlierDrops: Int = 0
    private var predictionUpdates: Int = 0
    private var renderedMotionUpdates: Int = 0
    private var blendStarts: Int = 0
    private var clampedCorrections: Int = 0
    private var blockedTransitions: Int = 0
    private var innovationSamples: Int = 0
    private var innovationTotalM: Double = 0.0
    private var innovationMaxM: Float = 0f
    private var fixGapSamples: Int = 0
    private var fixGapTotalMs: Long = 0L
    private var fixGapMaxMs: Long = 0L
    private var firstRenderDelaySamples: Int = 0
    private var firstRenderDelayTotalMs: Long = 0L
    private var firstRenderDelayMaxMs: Long = 0L
    private var activeRenderIntervalSamples: Int = 0
    private var activeRenderIntervalTotalMs: Long = 0L
    private var activeRenderIntervalMaxMs: Long = 0L
    private val activeRenderIntervalsMs = StreamingMetricAccumulator(RENDER_INTERVAL_BUCKETS_MS)
    private val nextFixPredictionResidualsM = StreamingMetricAccumulator(DISTANCE_BUCKETS_M)
    private val visibleNextFixPredictionResidualsM = StreamingMetricAccumulator(DISTANCE_BUCKETS_M)
    private val screenOffNextFixPredictionResidualsM = StreamingMetricAccumulator(DISTANCE_BUCKETS_M)
    private val correctionAlongTrackAbsM = StreamingMetricAccumulator(DISTANCE_BUCKETS_M)
    private val correctionCrossTrackAbsM = StreamingMetricAccumulator(DISTANCE_BUCKETS_M)
    private var correctionComponentSamples: Int = 0
    private var correctionAlongTrackTotalM: Double = 0.0
    private var correctionCrossTrackTotalM: Double = 0.0
    private val renderDisplacementsM = StreamingMetricAccumulator(DISTANCE_BUCKETS_M)
    private val renderDisplacementsPx = StreamingMetricAccumulator(PIXEL_BUCKETS)
    private val correctionSettleDurationsMs = StreamingMetricAccumulator(CORRECTION_DURATION_BUCKETS_MS)
    private var correctionInterruptedCount: Int = 0
    private val modeDwellMs = MarkerMotionMode.entries.associateWithTo(linkedMapOf()) { 0L }
    private var dwellMode: MarkerMotionMode? = null
    private var dwellUpdatedAtElapsedMs: Long? = null
    private var pendingFirstRenderFixAtElapsedMs: Long? = null
    private var lastMotionRenderedAtElapsedMs: Long? = null
    private val blockedReasonCounts = linkedMapOf<String, Int>()
    private var lastLoggedStateSignature: String? = null

    fun clear() {
        LocationOutputFilterTelemetry.clear()
        synchronized(lock) {
            latestSnapshot = MarkerMotionSnapshot()
            acceptedFixes = 0
            outlierDrops = 0
            predictionUpdates = 0
            renderedMotionUpdates = 0
            blendStarts = 0
            clampedCorrections = 0
            blockedTransitions = 0
            innovationSamples = 0
            innovationTotalM = 0.0
            innovationMaxM = 0f
            fixGapSamples = 0
            fixGapTotalMs = 0L
            fixGapMaxMs = 0L
            firstRenderDelaySamples = 0
            firstRenderDelayTotalMs = 0L
            firstRenderDelayMaxMs = 0L
            activeRenderIntervalSamples = 0
            activeRenderIntervalTotalMs = 0L
            activeRenderIntervalMaxMs = 0L
            activeRenderIntervalsMs.clear()
            nextFixPredictionResidualsM.clear()
            visibleNextFixPredictionResidualsM.clear()
            screenOffNextFixPredictionResidualsM.clear()
            correctionAlongTrackAbsM.clear()
            correctionCrossTrackAbsM.clear()
            correctionComponentSamples = 0
            correctionAlongTrackTotalM = 0.0
            correctionCrossTrackTotalM = 0.0
            renderDisplacementsM.clear()
            renderDisplacementsPx.clear()
            correctionSettleDurationsMs.clear()
            correctionInterruptedCount = 0
            modeDwellMs.keys.forEach { modeDwellMs[it] = 0L }
            dwellMode = null
            dwellUpdatedAtElapsedMs = null
            pendingFirstRenderFixAtElapsedMs = null
            lastMotionRenderedAtElapsedMs = null
            blockedReasonCounts.clear()
            lastLoggedStateSignature = null
        }
    }

    internal fun setCollectionEnabledForTests(enabled: Boolean) {
        collectionEnabledForTests = enabled
    }

    fun latestSnapshot(): MarkerMotionSnapshot =
        synchronized(lock) {
            latestSnapshot
        }

    fun latestStatusLabel(): String = latestSnapshot().compactLabel()

    fun summary(observedAtElapsedMs: Long? = null): MarkerMotionSummary {
        val outputFilterSummary = LocationOutputFilterTelemetry.summary()
        return synchronized(lock) {
            observedAtElapsedMs?.let { observedAt ->
                dwellMode?.let { activeMode -> updateModeDwellLocked(activeMode, observedAt) }
            }
            buildSummaryLocked(outputFilterSummary)
        }
    }

    private fun buildSummaryLocked(outputFilterSummary: LocationOutputFilterTelemetrySummary): MarkerMotionSummary =
        MarkerMotionSummary(
            acceptedFixes = acceptedFixes,
            outlierDrops = outlierDrops,
            predictionUpdates = predictionUpdates,
            renderedMotionUpdates = renderedMotionUpdates,
            blendStarts = blendStarts,
            clampedCorrections = clampedCorrections,
            blockedTransitions = blockedTransitions,
            blockedReasonCounts = blockedReasonCounts.toMap(),
            latestMode = latestSnapshot.mode,
            latestReason = latestSnapshot.reason,
            innovationSamples = innovationSamples,
            innovationMeanM = innovationTotalM.meanOrNull(innovationSamples),
            innovationMaxM = innovationMaxM.takeIf { innovationSamples > 0 },
            fixGapSamples = fixGapSamples,
            fixGapMeanMs = fixGapTotalMs.meanOrNull(fixGapSamples),
            fixGapMaxMs = fixGapMaxMs.takeIf { fixGapSamples > 0 },
            firstRenderDelaySamples = firstRenderDelaySamples,
            firstRenderDelayMeanMs = firstRenderDelayTotalMs.meanOrNull(firstRenderDelaySamples),
            firstRenderDelayMaxMs = firstRenderDelayMaxMs.takeIf { firstRenderDelaySamples > 0 },
            activeRenderIntervalSamples = activeRenderIntervalSamples,
            activeRenderIntervalMeanMs = activeRenderIntervalTotalMs.meanOrNull(activeRenderIntervalSamples),
            activeRenderIntervalP50Ms = activeRenderIntervalsMs.percentile(0.50f)?.toLong(),
            activeRenderIntervalP95Ms = activeRenderIntervalsMs.percentile(0.95f)?.toLong(),
            activeRenderIntervalMaxMs = activeRenderIntervalMaxMs.takeIf { activeRenderIntervalSamples > 0 },
            nextFixPredictionResidualM = nextFixPredictionResidualsM.summary(),
            visibleNextFixPredictionResidualM = visibleNextFixPredictionResidualsM.summary(),
            screenOffNextFixPredictionResidualM = screenOffNextFixPredictionResidualsM.summary(),
            correctionComponentSamples = correctionComponentSamples,
            correctionAlongTrackMeanM = correctionAlongTrackTotalM.meanOrNull(correctionComponentSamples),
            correctionCrossTrackMeanM = correctionCrossTrackTotalM.meanOrNull(correctionComponentSamples),
            correctionAlongTrackAbsM = correctionAlongTrackAbsM.summary(),
            correctionCrossTrackAbsM = correctionCrossTrackAbsM.summary(),
            renderDisplacementM = renderDisplacementsM.summary(),
            renderDisplacementPx = renderDisplacementsPx.summary(),
            correctionSettleDurationMs = correctionSettleDurationsMs.summary(),
            correctionInterruptedCount = correctionInterruptedCount,
            rawFilteredPositionOffsetM = outputFilterSummary.positionOffsetM.toMarkerMotionSummary(),
            rawFilteredSpeedOffsetMps = outputFilterSummary.speedOffsetMps.toMarkerMotionSummary(),
            rawFilteredBearingOffsetDeg = outputFilterSummary.bearingOffsetDeg.toMarkerMotionSummary(),
            modeDwellMs = modeDwellMs.filterValues { it > 0L },
        )

    fun summaryLabel(): String = summary().summaryLabel()

    fun recordIdle(
        nowElapsedMs: Long,
        reason: String,
    ) {
        if (!shouldCollect()) return
        synchronized(lock) {
            pendingFirstRenderFixAtElapsedMs = null
            lastMotionRenderedAtElapsedMs = null
        }
        recordStateTransition(
            snapshot =
                MarkerMotionSnapshot(
                    mode = MarkerMotionMode.IDLE,
                    reason = reason,
                    updatedAtElapsedMs = nowElapsedMs,
                ),
            logMessage = "idle reason=${reasonLabel(reason) ?: reason}",
        )
    }

    @Suppress("LongParameterList")
    fun recordSeedAnchor(
        nowElapsedMs: Long,
        fixAgeMs: Long,
        accuracyM: Float,
        speedMps: Float,
        bearingDeg: Float?,
        origin: String,
    ) {
        if (!shouldCollect()) return
        val snapshot =
            MarkerMotionSnapshot(
                mode = MarkerMotionMode.FIXED,
                reason = "wake_anchor",
                fixAgeMs = fixAgeMs,
                accuracyM = accuracyM,
                speedMps = speedMps,
                bearingDeg = bearingDeg,
                updatedAtElapsedMs = nowElapsedMs,
            )
        synchronized(lock) {
            updateLatestSnapshotLocked(snapshot)
            lastLoggedStateSignature = stateSignature(snapshot)
        }
        DebugTelemetry.log(
            TAG,
            buildString {
                append("seed reason=wake source=$origin")
                append(" age=${fixAgeMs}ms")
                append(" acc=${accuracyM.format(1)}")
                append(" speed=${speedMps.format(2)}")
                append(" bearing=${bearingDeg.formatOrNa(1)}")
            },
        )
    }

    @Suppress("LongParameterList")
    fun recordFixAccepted(
        mode: MarkerMotionMode,
        reason: String,
        nowElapsedMs: Long,
        fixAgeMs: Long,
        accuracyM: Float,
        speedMps: Float,
        bearingDeg: Float?,
        correctionDistanceM: Float?,
        blendDurationMs: Long?,
        innovationDistanceM: Float?,
        fixGapMs: Long?,
        rawSpeedMps: Float?,
        speedAccuracyMps: Float?,
        sourceMode: String,
    ) {
        if (!shouldCollect()) return
        val snapshot =
            MarkerMotionSnapshot(
                mode = mode,
                reason = reason,
                fixAgeMs = fixAgeMs,
                accuracyM = accuracyM,
                speedMps = speedMps,
                bearingDeg = bearingDeg,
                correctionDistanceM = correctionDistanceM,
                updatedAtElapsedMs = nowElapsedMs,
            )
        synchronized(lock) {
            acceptedFixes += 1
            if (mode == MarkerMotionMode.BLEND) {
                blendStarts += 1
            }
            innovationDistanceM?.takeIf { it.isFinite() && it >= 0f }?.let { innovationM ->
                innovationSamples += 1
                innovationTotalM += innovationM.toDouble()
                innovationMaxM = maxOf(innovationMaxM, innovationM)
            }
            fixGapMs?.takeIf { it >= 0L }?.let { gapMs ->
                fixGapSamples += 1
                fixGapTotalMs += gapMs
                fixGapMaxMs = maxOf(fixGapMaxMs, gapMs)
            }
            updateLatestSnapshotLocked(snapshot)
            lastLoggedStateSignature = stateSignature(snapshot)
        }
        DebugTelemetry.log(
            TAG,
            buildString {
                append("fix mode=${mode.label}")
                append(" reason=${reasonLabel(reason) ?: reason}")
                append(" age=${fixAgeMs}ms")
                append(" acc=${accuracyM.format(1)}")
                append(" speed=${speedMps.format(2)}")
                append(" rawSpeed=${rawSpeedMps.formatOrNa(2)}")
                append(" speedAcc=${speedAccuracyMps.formatOrNa(2)}")
                append(" source=$sourceMode")
                append(" bearing=${bearingDeg.formatOrNa(1)}")
                correctionDistanceM?.let { append(" corr=${it.format(1)}") }
                blendDurationMs?.let { append(" blendMs=$it") }
                innovationDistanceM?.let { append(" innovation=${it.format(1)}") }
                fixGapMs?.let { append(" gapMs=$it") }
            },
        )
    }

    fun recordBlendState(
        nowElapsedMs: Long,
        fixAgeMs: Long,
        anchor: MarkerVisualAnchor,
        sample: MarkerVisualTrajectorySample,
    ) {
        if (!shouldCollect()) return
        val snapshot =
            MarkerMotionSnapshot(
                mode = MarkerMotionMode.BLEND,
                reason = sample.correctionReason ?: "gps_correction",
                fixAgeMs = fixAgeMs,
                accuracyM = anchor.accuracyM,
                speedMps = anchor.speedMps,
                bearingDeg = anchor.bearingDeg,
                correctionDistanceM = sample.correctionRemainingM,
                correctionAgeMs = sample.correctionAgeMs,
                updatedAtElapsedMs = nowElapsedMs,
            )
        synchronized(lock) {
            updateLatestSnapshotLocked(snapshot)
        }
    }

    fun recordCorrectionSettled(
        reason: String,
        durationMs: Long,
    ) {
        if (!shouldCollect()) return
        synchronized(lock) {
            correctionSettleDurationsMs.record(durationMs.coerceAtLeast(0L).toFloat())
        }
        DebugTelemetry.log(
            TAG,
            "correctionSettled reason=${reasonLabel(reason) ?: reason} durationMs=$durationMs",
        )
    }

    fun recordCorrectionInterrupted(
        reason: String,
        durationMs: Long,
    ) {
        if (!shouldCollect()) return
        synchronized(lock) {
            correctionInterruptedCount += 1
        }
        DebugTelemetry.log(
            TAG,
            "correctionInterrupted reason=${reasonLabel(reason) ?: reason} durationMs=$durationMs",
        )
    }

    fun recordCorrectionClamped(event: CorrectionClampTelemetryEvent) {
        if (!shouldCollect()) return
        synchronized(lock) {
            clampedCorrections += 1
        }
        DebugTelemetry.log(
            TAG,
            buildString {
                append("clamp actual=${event.actualCorrectionDistanceM.format(1)}")
                append(" visible=${event.visibleCorrectionDistanceM.format(1)}")
                append(" acc=${event.accuracyM.format(1)}")
                append(" speed=${event.speedMps.format(2)}")
                append(" bearing=${event.bearingDeg.formatOrNa(1)}")
                append(" at=${event.nowElapsedMs}ms")
            },
        )
    }

    fun recordOutlierDropped(
        nowElapsedMs: Long,
        fixAgeMs: Long,
        accuracyM: Float,
        jumpMeters: Float,
        impliedSpeedMps: Float,
        dtSec: Float,
    ) {
        if (!shouldCollect()) return
        val snapshot =
            MarkerMotionSnapshot(
                mode = MarkerMotionMode.FIXED,
                reason = "outlier_drop",
                fixAgeMs = fixAgeMs,
                accuracyM = accuracyM,
                speedMps = impliedSpeedMps,
                correctionDistanceM = jumpMeters,
                updatedAtElapsedMs = nowElapsedMs,
            )
        synchronized(lock) {
            outlierDrops += 1
            updateLatestSnapshotLocked(snapshot)
            lastLoggedStateSignature = stateSignature(snapshot)
        }
        DebugTelemetry.log(
            TAG,
            buildString {
                append("drop reason=outlier")
                append(" jump=${jumpMeters.format(1)}")
                append(" impliedSpeed=${impliedSpeedMps.format(1)}")
                append(" dt=${dtSec.format(2)}")
                append(" acc=${accuracyM.format(1)}")
            },
        )
    }

    fun recordPredictionBlocked(
        reason: String,
        nowElapsedMs: Long,
        fixAgeMs: Long?,
        accuracyM: Float?,
        speedMps: Float?,
        bearingDeg: Float?,
    ) {
        if (!shouldCollect()) return
        val snapshot =
            MarkerMotionSnapshot(
                mode = MarkerMotionMode.FIXED,
                reason = reason,
                fixAgeMs = fixAgeMs,
                accuracyM = accuracyM,
                speedMps = speedMps,
                bearingDeg = bearingDeg,
                updatedAtElapsedMs = nowElapsedMs,
            )
        recordStateTransition(
            snapshot = snapshot,
            incrementBlockedTransitions = true,
            logMessage =
                buildString {
                    append("hold reason=${reasonLabel(reason) ?: reason}")
                    fixAgeMs?.let { append(" age=${it}ms") }
                    accuracyM?.let { append(" acc=${it.format(1)}") }
                    speedMps?.let { append(" speed=${it.format(2)}") }
                    bearingDeg?.let { append(" bearing=${it.format(1)}") }
                },
        )
    }

    fun recordPredictionDisplayed(
        nowElapsedMs: Long,
        fixAgeMs: Long,
        accuracyM: Float,
        speedMps: Float,
        bearingDeg: Float,
        predictedDistanceM: Float,
    ) {
        if (!shouldCollect()) return
        val snapshot =
            MarkerMotionSnapshot(
                mode = MarkerMotionMode.PREDICT,
                reason = "between_fixes",
                fixAgeMs = fixAgeMs,
                accuracyM = accuracyM,
                speedMps = speedMps,
                bearingDeg = bearingDeg,
                correctionDistanceM = predictedDistanceM,
                updatedAtElapsedMs = nowElapsedMs,
            )
        var shouldLog = false
        synchronized(lock) {
            predictionUpdates += 1
            updateLatestSnapshotLocked(snapshot)
            val signature = stateSignature(snapshot)
            if (signature != lastLoggedStateSignature) {
                lastLoggedStateSignature = signature
                shouldLog = true
            }
        }
        if (shouldLog) {
            DebugTelemetry.log(
                TAG,
                buildString {
                    append("predict age=${fixAgeMs}ms")
                    append(" acc=${accuracyM.format(1)}")
                    append(" speed=${speedMps.format(2)}")
                    append(" bearing=${bearingDeg.format(1)}")
                    append(" dist=${predictedDistanceM.format(1)}")
                },
            )
        }
    }

    fun recordFixAwaitingFirstRender(nowElapsedMs: Long) {
        if (!shouldCollect()) return
        synchronized(lock) {
            pendingFirstRenderFixAtElapsedMs = nowElapsedMs.coerceAtLeast(0L)
        }
    }

    /**
     * Records a rendered map/marker movement without retaining the individual frame sample.
     * Displacements are optional so existing callers can continue recording timing only.
     */
    fun recordMotionRendered(
        nowElapsedMs: Long,
        displacementM: Float? = null,
        displacementPx: Float? = null,
    ) {
        if (!shouldCollect()) return
        synchronized(lock) {
            renderedMotionUpdates += 1
            displacementM.recordNonNegativeIn(renderDisplacementsM)
            displacementPx.recordNonNegativeIn(renderDisplacementsPx)
            val renderedAtElapsedMs = nowElapsedMs.coerceAtLeast(0L)
            pendingFirstRenderFixAtElapsedMs?.let { fixAtElapsedMs ->
                val delayMs = (renderedAtElapsedMs - fixAtElapsedMs).coerceAtLeast(0L)
                firstRenderDelaySamples += 1
                firstRenderDelayTotalMs += delayMs
                firstRenderDelayMaxMs = maxOf(firstRenderDelayMaxMs, delayMs)
                pendingFirstRenderFixAtElapsedMs = null
            }
            lastMotionRenderedAtElapsedMs?.let { previousRenderAtElapsedMs ->
                val intervalMs = renderedAtElapsedMs - previousRenderAtElapsedMs
                if (intervalMs in 1..MAX_ACTIVE_RENDER_INTERVAL_SAMPLE_MS) {
                    activeRenderIntervalSamples += 1
                    activeRenderIntervalTotalMs += intervalMs
                    activeRenderIntervalMaxMs = maxOf(activeRenderIntervalMaxMs, intervalMs)
                    activeRenderIntervalsMs.record(intervalMs.toFloat())
                }
            }
            lastMotionRenderedAtElapsedMs = renderedAtElapsedMs
        }
    }

    /**
     * Records the motion-model residual at the next accepted fix.
     *
     * The aggregate is retained for continuity, while visible and screen-off buckets prevent
     * invisible screen-off catch-up from being mistaken for on-screen marker quality.
     */
    fun recordNextFixPredictionResidual(
        residualDistanceM: Float,
        alongTrackErrorM: Float? = null,
        crossTrackErrorM: Float? = null,
        isMarkerVisible: Boolean = true,
    ) {
        if (!shouldCollect()) return
        synchronized(lock) {
            residualDistanceM.recordNonNegativeIn(nextFixPredictionResidualsM)
            if (isMarkerVisible) {
                residualDistanceM.recordNonNegativeIn(visibleNextFixPredictionResidualsM)
            } else {
                residualDistanceM.recordNonNegativeIn(screenOffNextFixPredictionResidualsM)
            }
            recordCorrectionComponentsLocked(alongTrackErrorM, crossTrackErrorM)
        }
    }

    /**
     * Records signed correction components in the direction of travel and perpendicular to it.
     * Signed means remain available for detecting systematic lead/lag while percentiles use
     * absolute magnitudes.
     */
    fun recordCorrectionComponents(
        alongTrackM: Float,
        crossTrackM: Float,
    ) {
        if (!shouldCollect()) return
        synchronized(lock) {
            recordCorrectionComponentsLocked(alongTrackM, crossTrackM)
        }
    }

    /**
     * Records optional magnitudes between provider values and the filtered motion estimate.
     * Callers do the coordinate comparison so telemetry remains independent of map libraries.
     */
    fun recordRawVsFilteredOffsets(
        positionOffsetM: Float? = null,
        speedOffsetMps: Float? = null,
        bearingOffsetDeg: Float? = null,
    ) {
        if (!shouldCollect()) return
        LocationOutputFilterTelemetry.record(
            positionOffsetM = positionOffsetM,
            speedOffsetMps = speedOffsetMps,
            bearingOffsetDeg = bearingOffsetDeg,
        )
    }

    /** Advances mode dwell accounting when a caller has no new snapshot to report. */
    fun recordModeObserved(
        mode: MarkerMotionMode,
        nowElapsedMs: Long,
    ) {
        if (!shouldCollect()) return
        synchronized(lock) {
            updateModeDwellLocked(mode, nowElapsedMs)
        }
    }

    private fun recordStateTransition(
        snapshot: MarkerMotionSnapshot,
        incrementBlockedTransitions: Boolean = false,
        logMessage: String,
    ) {
        var shouldLog = false
        synchronized(lock) {
            updateLatestSnapshotLocked(snapshot)
            val signature = stateSignature(snapshot)
            if (signature != lastLoggedStateSignature) {
                if (incrementBlockedTransitions) {
                    blockedTransitions += 1
                    val reason = snapshot.reason ?: "unknown"
                    blockedReasonCounts[reason] = (blockedReasonCounts[reason] ?: 0) + 1
                }
                lastLoggedStateSignature = signature
                shouldLog = true
            }
        }
        if (shouldLog) {
            DebugTelemetry.log(TAG, logMessage)
        }
    }

    private fun recordCorrectionComponentsLocked(
        alongTrackM: Float?,
        crossTrackM: Float?,
    ) {
        val validAlongTrackM = alongTrackM?.takeIf(Float::isFinite) ?: return
        val validCrossTrackM = crossTrackM?.takeIf(Float::isFinite) ?: return
        correctionComponentSamples += 1
        correctionAlongTrackTotalM += validAlongTrackM.toDouble()
        correctionCrossTrackTotalM += validCrossTrackM.toDouble()
        correctionAlongTrackAbsM.record(kotlin.math.abs(validAlongTrackM))
        correctionCrossTrackAbsM.record(kotlin.math.abs(validCrossTrackM))
    }

    private fun updateLatestSnapshotLocked(snapshot: MarkerMotionSnapshot) {
        updateModeDwellLocked(snapshot.mode, snapshot.updatedAtElapsedMs)
        latestSnapshot = snapshot
    }

    private fun updateModeDwellLocked(
        mode: MarkerMotionMode,
        nowElapsedMs: Long,
    ) {
        val observedAtElapsedMs = nowElapsedMs.coerceAtLeast(0L)
        val previousAtElapsedMs = dwellUpdatedAtElapsedMs
        val previousMode = dwellMode
        if (previousAtElapsedMs != null && previousMode != null && observedAtElapsedMs >= previousAtElapsedMs) {
            val elapsedMs = observedAtElapsedMs - previousAtElapsedMs
            modeDwellMs[previousMode] = (modeDwellMs[previousMode] ?: 0L) + elapsedMs
            dwellUpdatedAtElapsedMs = observedAtElapsedMs
            dwellMode = mode
        } else if (previousAtElapsedMs == null) {
            dwellUpdatedAtElapsedMs = observedAtElapsedMs
            dwellMode = mode
        }
    }

    private fun stateSignature(snapshot: MarkerMotionSnapshot): String =
        buildString {
            append(snapshot.mode.label)
            append(':')
            append(snapshot.reason.orEmpty())
        }

    private fun shouldCollect(): Boolean = collectionEnabledForTests || DebugTelemetry.isEnabled()
}

private const val MAX_ACTIVE_RENDER_INTERVAL_SAMPLE_MS = 10_000L

private val DISTANCE_BUCKETS_M =
    floatArrayOf(
        0.05f,
        0.1f,
        0.25f,
        0.5f,
        1f,
        2f,
        3f,
        5f,
        8f,
        13f,
        20f,
        30f,
        50f,
        75f,
        100f,
        200f,
        500f,
    )
private val PIXEL_BUCKETS =
    floatArrayOf(
        0.05f,
        0.1f,
        0.25f,
        0.5f,
        0.75f,
        1f,
        1.5f,
        2f,
        3f,
        5f,
        8f,
        13f,
        20f,
        30f,
        50f,
        100f,
    )
private val RENDER_INTERVAL_BUCKETS_MS =
    floatArrayOf(
        16f,
        33f,
        50f,
        66f,
        83f,
        100f,
        125f,
        150f,
        200f,
        250f,
        333f,
        500f,
        750f,
        1_000f,
        1_500f,
        2_000f,
        5_000f,
        10_000f,
    )
private val CORRECTION_DURATION_BUCKETS_MS =
    floatArrayOf(100f, 250f, 500f, 750f, 1_000f, 1_500f, 2_000f, 3_000f, 5_000f, 8_000f, 13_000f, 20_000f, 30_000f)

/** Fixed-memory histogram suitable for hot-path aggregate telemetry. */
private class StreamingMetricAccumulator(
    private val upperBounds: FloatArray,
) {
    private val bucketCounts = IntArray(upperBounds.size + 1)
    private var samples = 0
    private var total = 0.0
    private var max = 0f

    fun clear() {
        bucketCounts.fill(0)
        samples = 0
        total = 0.0
        max = 0f
    }

    fun record(value: Float) {
        if (!value.isFinite() || value < 0f) return

        val bucketIndex = upperBounds.indexOfFirst { value <= it }.takeIf { it >= 0 } ?: upperBounds.size
        bucketCounts[bucketIndex] += 1
        samples += 1
        total += value.toDouble()
        max = maxOf(max, value)
    }

    fun percentile(fraction: Float): Float? {
        var result: Float? = null
        if (samples > 0) {
            val targetRank =
                kotlin.math
                    .ceil(samples * fraction.coerceIn(0f, 1f))
                    .toInt()
                    .coerceAtLeast(1)
            var cumulativeSamples = 0
            bucketCounts.forEachIndexed { index, count ->
                cumulativeSamples += count
                if (result == null && cumulativeSamples >= targetRank) {
                    result = if (index < upperBounds.size) upperBounds[index] else max
                }
            }
        }
        return result
    }

    fun summary(): MarkerMotionMetricSummary =
        MarkerMotionMetricSummary(
            samples = samples,
            mean = if (samples > 0) (total / samples).toFloat() else null,
            p50 = percentile(0.50f),
            p95 = percentile(0.95f),
            max = max.takeIf { samples > 0 },
        )
}

private fun Float?.recordNonNegativeIn(accumulator: StreamingMetricAccumulator) {
    this?.takeIf { it.isFinite() && it >= 0f }?.let(accumulator::record)
}

private fun Double.meanOrNull(samples: Int): Float? =
    if (samples > 0) {
        (this / samples).toFloat()
    } else {
        null
    }

private fun Long.meanOrNull(samples: Int): Long? =
    if (samples > 0) {
        this / samples
    } else {
        null
    }

private fun reasonLabel(reason: String?): String? =
    when (reason) {
        null -> null
        "await_fresh_fix" -> "wait fix"
        "wake_anchor" -> "wake"
        "initial_fix" -> "first"
        "gps_correction" -> "correct"
        "correction_clamped" -> "clamp"
        "stationary_jitter" -> "steady"
        "deadband_snap" -> "snap"
        "stale_fix" -> "stale fix"
        "outlier_drop" -> "outlier"
        "prediction_delay" -> "delay"
        "stale" -> "stale"
        "bad_accuracy" -> "bad acc"
        "no_bearing" -> "no brg"
        "slow" -> "slow"
        "too_close" -> "tiny"
        "between_fixes" -> "between"
        "degraded_gps" -> "gps weak"
        "watch_gps_catch_up" -> "watch catch up"
        "auto_fused_catch_up" -> "fused catch up"
        "auto_fused_high_speed_catch_up" -> "fused fast catch up"
        "screen_off_anchor" -> "screen off anchor"
        "reset" -> "reset"
        "interactive_start" -> "screen wake"
        "tracking_stopped" -> "track off"
        "fresh_fix_release" -> "fresh start"
        "marker_hidden" -> "hidden"
        "dispose" -> "dispose"
        else -> reason.replace('_', ' ')
    }

private fun LocationOutputFilterMetricSummary.toMarkerMotionSummary(): MarkerMotionMetricSummary =
    MarkerMotionMetricSummary(
        samples = samples,
        mean = mean,
        p50 = p50,
        p95 = p95,
        max = max,
    )

private fun Float.format(digits: Int): String = "%.${digits}f".format(this)

private fun Float?.formatOrNa(digits: Int): String = this?.let { "%.${digits}f".format(it) } ?: "na"
