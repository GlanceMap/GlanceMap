package com.glancemap.glancemapwearos.core.service.location.service

internal fun isAutoFusedPhoneRecheckEligible(
    fallbackToWatchGps: Boolean,
    autoFusedActive: Boolean,
    watchGpsOnly: Boolean,
): Boolean = (fallbackToWatchGps || autoFusedActive) && !watchGpsOnly

internal fun isPhoneConnectionRecheckDue(
    nowElapsedMs: Long,
    lastRecheckAtElapsedMs: Long,
    intervalMs: Long,
): Boolean = lastRecheckAtElapsedMs <= 0L || nowElapsedMs - lastRecheckAtElapsedMs >= intervalMs
