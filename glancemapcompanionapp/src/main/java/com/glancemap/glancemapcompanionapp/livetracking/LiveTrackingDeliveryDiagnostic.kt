package com.glancemap.glancemapcompanionapp.livetracking

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture

internal data class LiveTrackingDeliveryDiagnostic(
    val event: String,
    val pointId: String? = null,
    val fixTimestampEpochMillis: Long? = null,
    val count: Int? = null,
    val durationMillis: Long? = null,
    val failure: String? = null,
    val context: LiveTrackingDiagnosticContext? = null,
)

internal fun recordLiveTrackingDelivery(diagnostic: LiveTrackingDeliveryDiagnostic) {
    PhoneDebugCapture.log(
        "LiveTracking",
        buildString {
            append("delivery event=").append(diagnostic.event)
            append(" pointId=").append(diagnostic.pointId ?: "na")
            append(" fixTsMs=").append(diagnostic.fixTimestampEpochMillis ?: "na")
            diagnostic.count?.let { append(" count=").append(it) }
            diagnostic.durationMillis?.let { append(" durationMs=").append(it.coerceAtLeast(0L)) }
            diagnostic.failure?.let { append(" failure=").append(it) }
            appendTrackingContext(diagnostic.context)
        },
    )
}
