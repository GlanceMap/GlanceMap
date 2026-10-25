package com.glancemap.glancemapwearos.presentation.features.download

internal data class OamExtractionPowerSnapshot(
    val deviceIdleMode: Boolean? = null,
    val ignoringBatteryOptimizations: Boolean? = null,
    val powerSaveMode: Boolean? = null,
    val lowPowerStandbyEnabled: Boolean? = null,
    val lowPowerStandbyExempt: Boolean? = null,
    val backgroundRestricted: Boolean? = null,
    val processImportance: Int? = null,
    val standbyBucket: Int? = null,
) {
    fun telemetryFields(): String =
        "deviceIdleMode=${deviceIdleMode ?: "na"} " +
            "ignoringBatteryOptimizations=${ignoringBatteryOptimizations ?: "na"} " +
            "powerSaveMode=${powerSaveMode ?: "na"} " +
            "lowPowerStandbyEnabled=${lowPowerStandbyEnabled ?: "na"} " +
            "lowPowerStandbyExempt=${lowPowerStandbyExempt ?: "na"} " +
            "backgroundRestricted=${backgroundRestricted ?: "na"} " +
            "processImportance=${processImportance ?: "na"} standbyBucket=${standbyBucket ?: "na"}"
}

internal class OamExtractionPowerSampler(
    private val read: () -> OamExtractionPowerSnapshot,
    private val nowMs: () -> Long,
) {
    private var cached: OamExtractionPowerSnapshot? = null
    private var sampledAtMs = 0L

    @Synchronized
    fun sample(): OamExtractionPowerSnapshot {
        val now = nowMs()
        if (cached == null || now - sampledAtMs >= SAMPLE_INTERVAL_MS) {
            cached = runCatching(read).getOrDefault(OamExtractionPowerSnapshot())
            sampledAtMs = now
        }
        return checkNotNull(cached)
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 5_000L
    }
}
