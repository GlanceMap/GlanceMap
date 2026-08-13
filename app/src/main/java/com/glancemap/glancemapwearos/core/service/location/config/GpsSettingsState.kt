package com.glancemap.glancemapwearos.core.service.location.config

internal data class GpsSettingsState(
    val watchOnly: Boolean,
    val intervalMs: Long,
    val ambientIntervalMs: Long,
    val recordingIntervalMs: Long,
    val recordingScreenOffIntervalMs: Long,
    val turnByTurnIntervalMs: Long,
    val turnByTurnScreenOffIntervalMs: Long,
    val turnByTurnScreenOffIntervalAdaptive: Boolean,
    val turnByTurnScreenOffBatchingEnabled: Boolean,
    val ambientGps: Boolean,
    val debugTelemetry: Boolean,
    val diagnosticsCaptureMode: String,
    val passiveLocationExperiment: Boolean,
)
