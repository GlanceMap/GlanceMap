package com.glancemap.glancemapwearos.presentation.features.recording

/**
 * Bounds the short high-accuracy location burst requested while REC is paused for auto-resume.
 *
 * A burst is requested only when a paused fix reports moving speed (Doppler) but its accuracy is
 * too poor to confirm the restart, so a usable fix can arrive in seconds instead of at the next
 * sparse paused update. Position jumps alone never trigger it, because drift is what poor GPS
 * produces. At most one burst per pause, and at least [cooldownMillis] between bursts, keep the
 * worst case in poor-GPS stop-and-go riding to about 30 bursts of at most 8 s per hour.
 */
internal class RecordingAutoResumeRestartBurstBudget(
    private val cooldownMillis: Long = AUTO_RESUME_RESTART_BURST_COOLDOWN_MS,
) {
    private var requestedThisPause = false
    private var lastRequestElapsedMillis = Long.MIN_VALUE

    /** A new pause may request one burst again (still subject to the cooldown). */
    fun onPauseStarted() {
        requestedThisPause = false
    }

    fun reset() {
        requestedThisPause = false
        lastRequestElapsedMillis = Long.MIN_VALUE
    }

    /** Returns true and records the request when a burst should be requested for this fix. */
    fun requestIfNeeded(
        reliableFix: Boolean,
        speedMps: Float?,
        resumeSpeedMps: Float,
        nowElapsedMillis: Long,
    ): Boolean {
        val speed = speedMps?.takeIf { it.isFinite() && it >= 0f }
        val cooledDown =
            lastRequestElapsedMillis == Long.MIN_VALUE ||
                nowElapsedMillis - lastRequestElapsedMillis >= cooldownMillis
        val request = !reliableFix && !requestedThisPause && speed != null && speed >= resumeSpeedMps && cooledDown
        if (request) {
            requestedThisPause = true
            lastRequestElapsedMillis = nowElapsedMillis
        }
        return request
    }
}

internal const val AUTO_RESUME_RESTART_BURST_COOLDOWN_MS = 120_000L

/** Not prefixed with "ui_", so the screen-wake skip rules for fresh fixes do not suppress it. */
internal const val AUTO_RESUME_RESTART_BURST_SOURCE = "recording_auto_resume_poor_fix"
