package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import java.util.concurrent.atomic.AtomicReference

/** Small, redacted snapshot of the companion compass path for an explicit debug capture. */
@Suppress("LongParameterList") // One immutable diagnostic snapshot keeps every captured state explicit.
internal data class PhoneCompassDiagnosticsSnapshot(
    val started: Boolean,
    val providerMode: PhoneCompassProviderMode,
    val headingSourceMode: PhoneCompassHeadingSourceMode,
    val activePipeline: PhoneCompassPipeline,
    val rotationVectorAvailable: Boolean,
    val headingSensorAvailable: Boolean,
    val magAccelerometerFallbackAvailable: Boolean,
    val sensorRegistrationSucceeded: Boolean?,
    val fusedSampleReceived: Boolean,
    val fusedUsableSampleReceived: Boolean,
    val hasSample: Boolean,
    val isRenderable: Boolean,
    val accuracyLabel: String,
    val calibrationRecommended: Boolean,
    val magneticInterference: Boolean,
    val northReferenceMode: PhoneMapNorthReferenceMode,
) {
    fun toReportSection(): String =
        buildString {
            appendLine("Compass")
            appendLine("Active: $started")
            appendLine("Provider: $providerMode")
            appendLine("Requested source: $headingSourceMode")
            appendLine("Pipeline: $activePipeline")
            appendLine("Rotation vector available: $rotationVectorAvailable")
            appendLine("Heading sensor available: $headingSensorAvailable")
            appendLine("Magnetometer + accelerometer available: $magAccelerometerFallbackAvailable")
            appendLine("Sensor registration: ${sensorRegistrationSucceeded ?: "not applicable"}")
            appendLine("Fused sample received: $fusedSampleReceived")
            appendLine("Fused usable sample received: $fusedUsableSampleReceived")
            appendLine("Heading sample received: $hasSample")
            appendLine("Renderable: $isRenderable")
            appendLine("Accuracy: $accuracyLabel")
            appendLine("Calibration recommended: $calibrationRecommended")
            appendLine("Magnetic interference: $magneticInterference")
            append("North reference: $northReferenceMode")
        }
}

/** Retains only structural compass state; live heading values are intentionally not captured. */
internal object PhoneCompassDiagnostics {
    private val latest = AtomicReference<PhoneCompassDiagnosticsSnapshot?>(null)

    fun record(snapshot: PhoneCompassDiagnosticsSnapshot) {
        if (latest.getAndSet(snapshot) != snapshot) {
            PhoneDebugCapture.updateSection("compass", snapshot.toReportSection())
        }
    }

    fun latestReportSection(): String? = latest.get()?.toReportSection()

    internal fun clear() {
        latest.set(null)
    }
}
