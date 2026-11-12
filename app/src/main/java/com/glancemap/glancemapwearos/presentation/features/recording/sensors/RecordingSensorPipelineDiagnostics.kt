package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import com.glancemap.glancemapwearos.core.service.diagnostics.RecordingSensorDiagnosticKind

internal enum class RecordingSensorSource(
    val diagnosticKind: RecordingSensorDiagnosticKind,
) {
    HEART_RATE(RecordingSensorDiagnosticKind.HEART_RATE),
    STEP_COUNTER(RecordingSensorDiagnosticKind.STEP),
    STEP_DETECTOR(RecordingSensorDiagnosticKind.CADENCE),
    PRESSURE(RecordingSensorDiagnosticKind.PRESSURE),
}

internal data class RecordingSensorCallbackSample(
    val value: Float?,
    val accuracy: Int,
    val sourceElapsedMillis: Long,
    val callbackElapsedMillis: Long,
    val callbackUptimeMillis: Long,
    val usable: Boolean,
    val derivedCadenceSpm: Int? = null,
)

internal data class RecordingSensorPipelineSnapshot(
    val source: RecordingSensorSource,
    val callbacks: Long = 0L,
    val usable: Long = 0L,
    val coalesced: Long = 0L,
    val published: Long = 0L,
    val firstCallback: RecordingSensorCallbackSample? = null,
    val lastCallback: RecordingSensorCallbackSample? = null,
    val lastUsable: RecordingSensorCallbackSample? = null,
    val lastCadence: RecordingSensorCallbackSample? = null,
    val publishDelayMillis: Long = -1L,
    val maxDeliveryAgeMillis: Long = -1L,
    val maxPublishDelayMillis: Long = -1L,
    val lastPublicationElapsedMillis: Long = 0L,
    val lastOutcome: String = "none",
) {
    fun telemetry(nowElapsedMillis: Long): String {
        val suspendDelta =
            if (firstCallback != null && lastCallback != null) {
                (
                    lastCallback.callbackElapsedMillis - firstCallback.callbackElapsedMillis -
                        (lastCallback.callbackUptimeMillis - firstCallback.callbackUptimeMillis)
                ).coerceAtLeast(0L)
            } else {
                -1L
            }
        return "source=$source callbacks=$callbacks usable=$usable invalid=${callbacks - usable} " +
            "coalesced=$coalesced published=$published " +
            "rawValue=${lastCallback?.value} rawAccuracy=${lastCallback?.accuracy ?: -1} " +
            "sourceElapsedMs=${lastCallback?.sourceElapsedMillis ?: -1L} " +
            "callbackElapsedMs=${lastCallback?.callbackElapsedMillis ?: -1L} " +
            "callbackUptimeMs=${lastCallback?.callbackUptimeMillis ?: -1L} " +
            "sourceAgeMs=${age(nowElapsedMillis, lastCallback?.sourceElapsedMillis)} " +
            "callbackAgeMs=${age(nowElapsedMillis, lastCallback?.callbackElapsedMillis)} " +
            "validSourceAgeMs=${age(nowElapsedMillis, lastUsable?.sourceElapsedMillis)} " +
            "validCallbackAgeMs=${age(nowElapsedMillis, lastUsable?.callbackElapsedMillis)} " +
            "derivedCadenceSpm=${lastCadence?.derivedCadenceSpm ?: -1} " +
            "cadenceDerivedAgeMs=${age(nowElapsedMillis, lastCadence?.callbackElapsedMillis)} " +
            "publishDelayMs=$publishDelayMillis maxDeliveryAgeMs=$maxDeliveryAgeMillis " +
            "maxPublishDelayMs=$maxPublishDelayMillis " +
            "publicationAgeMs=${age(nowElapsedMillis, lastPublicationElapsedMillis)} " +
            "suspendDeltaMs=$suspendDelta lastOutcome=$lastOutcome"
    }

    private fun age(
        nowMillis: Long,
        timeMillis: Long?,
    ): Long = timeMillis?.takeIf { it > 0L && it <= nowMillis }?.let { nowMillis - it } ?: -1L
}

/** One bounded snapshot per source, shared by the sensor Handler and main-thread publications. */
internal class RecordingSensorPipelineDiagnostics {
    private val states =
        RecordingSensorSource.entries.associateWith { RecordingSensorPipelineSnapshot(it) }.toMutableMap()

    @Synchronized
    fun callback(
        source: RecordingSensorSource,
        sample: RecordingSensorCallbackSample,
    ) {
        val state = states.getValue(source)
        val deliveryAge =
            sample.sourceElapsedMillis
                .takeIf { it > 0L && it <= sample.callbackElapsedMillis }
                ?.let { sample.callbackElapsedMillis - it }
                ?: -1L
        states[source] =
            state.copy(
                callbacks = state.callbacks + 1L,
                usable = state.usable + if (sample.usable) 1L else 0L,
                firstCallback = state.firstCallback ?: sample,
                lastCallback = sample,
                lastUsable = if (sample.usable) sample else state.lastUsable,
                lastCadence = if (sample.derivedCadenceSpm != null) sample else state.lastCadence,
                maxDeliveryAgeMillis = maxOf(state.maxDeliveryAgeMillis, deliveryAge),
                lastOutcome = if (sample.usable) "usable" else "invalid_value",
            )
    }

    @Synchronized
    fun coalesced(source: RecordingSensorSource) {
        val state = states.getValue(source)
        states[source] = state.copy(coalesced = state.coalesced + 1L, lastOutcome = "coalesced")
    }

    @Synchronized
    fun published(
        source: RecordingSensorSource,
        callbackElapsedMillis: Long,
        publicationElapsedMillis: Long,
    ) {
        val state = states.getValue(source)
        val delayMillis = (publicationElapsedMillis - callbackElapsedMillis).coerceAtLeast(0L)
        states[source] =
            state.copy(
                published = state.published + 1L,
                publishDelayMillis = delayMillis,
                maxPublishDelayMillis = maxOf(state.maxPublishDelayMillis, delayMillis),
                lastPublicationElapsedMillis = publicationElapsedMillis,
            )
    }

    @Synchronized
    fun snapshot(): List<RecordingSensorPipelineSnapshot> = states.values.toList()
}
