package com.glancemap.glancemapwearos.core.service.location.model

import android.location.Location
import android.os.Bundle
import kotlin.math.abs

/**
 * Satellite evidence for a direct watch-GPS fix, taken from the latest GNSS status report.
 *
 * Some watches report fixed placeholder accuracies (125 m horizontal, 250 m vertical), so
 * these measured values are recorded to calibrate a real accuracy estimate later. They are
 * evidence only: no recording decision reads them yet.
 */
data class GnssFixQuality(
    val satellitesUsedInFix: Int,
    val usedCn0AvgDbHz: Float?,
    val statusElapsedRealtimeMs: Long,
)

internal const val GNSS_STATUS_MAX_FIX_SKEW_MS = 3_000L

private const val EXTRA_GNSS_SATELLITES_USED = "com.glancemap.gnssSatellitesUsed"
private const val EXTRA_GNSS_USED_CN0_AVG_DB_HZ = "com.glancemap.gnssUsedCn0AvgDbHz"

/** Returns [quality] only when the status report was taken close to the fix measurement. */
internal fun gnssFixQualityForFix(
    quality: GnssFixQuality?,
    fixElapsedRealtimeMs: Long,
    maxSkewMs: Long = GNSS_STATUS_MAX_FIX_SKEW_MS,
): GnssFixQuality? {
    if (quality == null || fixElapsedRealtimeMs <= 0L || quality.satellitesUsedInFix <= 0) return null
    return quality.takeIf { abs(it.statusElapsedRealtimeMs - fixElapsedRealtimeMs) <= maxSkewMs }
}

internal fun Location.attachGnssFixQuality(quality: GnssFixQuality) {
    val bundle = extras?.let(::Bundle) ?: Bundle()
    bundle.putInt(EXTRA_GNSS_SATELLITES_USED, quality.satellitesUsedInFix)
    quality.usedCn0AvgDbHz?.let { bundle.putFloat(EXTRA_GNSS_USED_CN0_AVG_DB_HZ, it) }
    extras = bundle
}

internal fun Location.gnssSatellitesUsed(): Int? = extras?.getInt(EXTRA_GNSS_SATELLITES_USED, -1)?.takeIf { it > 0 }

internal fun Location.gnssUsedCn0AvgDbHz(): Float? =
    extras
        ?.getFloat(EXTRA_GNSS_USED_CN0_AVG_DB_HZ, Float.NaN)
        ?.takeIf { it.isFinite() && it > 0f }
