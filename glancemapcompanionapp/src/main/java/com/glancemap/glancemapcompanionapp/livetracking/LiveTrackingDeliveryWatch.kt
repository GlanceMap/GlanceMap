package com.glancemap.glancemapcompanionapp.livetracking

/** Diagnostic clock only: never requests a GPS fix or consumes cadence admission. */
internal class LiveTrackingDeliveryWatch(
    startedElapsedMillis: Long,
    private val intervalMillis: Long,
) {
    private var lastDeliveryMillis = startedElapsedMillis
    private var lastGapReportMillis = startedElapsedMillis
    var deliveredCallbacks = 0
        private set

    @Synchronized
    fun delivered(nowElapsedMillis: Long) {
        lastDeliveryMillis = nowElapsedMillis
        lastGapReportMillis = nowElapsedMillis
        deliveredCallbacks += 1
    }

    @Synchronized
    fun deliveryGapMillis(nowElapsedMillis: Long): Long? {
        val gap = nowElapsedMillis - lastDeliveryMillis
        return if (gap >= intervalMillis * 2 && nowElapsedMillis - lastGapReportMillis >= intervalMillis) {
            lastGapReportMillis = nowElapsedMillis
            gap
        } else {
            null
        }
    }
}
