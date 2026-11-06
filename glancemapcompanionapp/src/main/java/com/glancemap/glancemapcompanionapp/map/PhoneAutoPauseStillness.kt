package com.glancemap.glancemapcompanionapp.map

/**
 * Bike stops are confirmed after this much stillness evidence even when fixes are sparse or missing.
 * Longer than the regular 20 s stop window because it does not need continuous fixes.
 */
internal const val PHONE_BIKE_SPARSE_STOP_DURATION_MS = 30_000L

/**
 * Bike stillness evidence that survives sparse or missing GPS.
 *
 * Once a phone stops moving, the fused provider often slows or stops delivering fixes, and indoors
 * (garage, cellar) it loses the sky entirely. The regular stop window restarts after a 15 s gap, so
 * such stops never auto-pause. Here only a reliable fix that shows movement clears the evidence;
 * inaccurate fixes and silence neither clear nor extend it.
 */
internal class PhoneStillnessEvidence(
    private val requiredMillis: Long = PHONE_BIKE_SPARSE_STOP_DURATION_MS,
) {
    var stillSinceElapsedMillis: Long? = null
        private set

    /** Returns true when this still fix starts a new still run. */
    fun observeStill(elapsedMillis: Long): Boolean {
        if (stillSinceElapsedMillis != null) return false
        stillSinceElapsedMillis = elapsedMillis
        return true
    }

    /** Returns true when a still run was in progress. */
    fun clear(): Boolean {
        val active = stillSinceElapsedMillis != null
        stillSinceElapsedMillis = null
        return active
    }

    fun deadlineElapsedMillis(): Long? = stillSinceElapsedMillis?.plus(requiredMillis)

    /** The still duration once it reaches the required length, otherwise null. */
    fun confirmedDurationAt(elapsedMillis: Long): Long? =
        stillSinceElapsedMillis
            ?.let { elapsedMillis - it }
            ?.takeIf { it >= requiredMillis }
}
