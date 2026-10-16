package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.recording.recordingStrictFreshFixMaxAgeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun isPhoneGuidanceFixFresh(
    fixElapsedRealtimeMillis: Long?,
    nowElapsedRealtimeMillis: Long,
    deliveryIntervalMillis: Long,
): Boolean =
    fixElapsedRealtimeMillis != null &&
        fixElapsedRealtimeMillis > 0L &&
        nowElapsedRealtimeMillis - fixElapsedRealtimeMillis in 0L..recordingStrictFreshFixMaxAgeMillis(deliveryIntervalMillis)

/** Expire from the fix's timestamp, so subscription changes cannot extend its lifetime. */
internal fun schedulePhoneGuidanceLocationExpiry(
    scope: CoroutineScope,
    referenceElapsedRealtimeMillis: Long,
    deliveryIntervalMillis: Long,
    nowElapsedRealtimeMillis: () -> Long,
    onExpired: suspend () -> Unit,
): Job =
    scope.launch {
        val ageMillis = (nowElapsedRealtimeMillis() - referenceElapsedRealtimeMillis).coerceAtLeast(0L)
        val maxAgeMillis = recordingStrictFreshFixMaxAgeMillis(deliveryIntervalMillis)
        delay((maxAgeMillis - ageMillis + 1L).coerceAtLeast(0L))
        onExpired()
    }
