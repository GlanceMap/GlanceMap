package com.glancemap.trailcore.recording

fun shouldMonitorRecordingAutoResume(
    active: Boolean,
    paused: Boolean,
    autoPaused: Boolean,
    autoPauseEnabled: Boolean,
): Boolean = active && paused && (autoPaused || autoPauseEnabled)

fun effectiveRecordingAutoPauseStartMillis(
    startedAtMillis: Long?,
    confirmationMillis: Long,
    stationaryDurationMillis: Long,
): Long {
    val confirmation = confirmationMillis.coerceAtLeast(0L)
    val earliest = startedAtMillis?.coerceIn(0L, confirmation) ?: 0L
    return (confirmation - stationaryDurationMillis.coerceAtLeast(0L)).coerceAtLeast(earliest)
}

fun recordingAutoPauseAddedMillisAtResume(
    pausedAtMillis: Long?,
    confirmationMillis: Long,
    movingDurationMillis: Long,
): Long {
    val confirmation = confirmationMillis.coerceAtLeast(0L)
    val pausedAt = pausedAtMillis?.coerceIn(0L, confirmation) ?: return 0L
    val effectiveResume = (confirmation - movingDurationMillis.coerceAtLeast(0L)).coerceIn(pausedAt, confirmation)
    return (effectiveResume - pausedAt).coerceAtLeast(0L)
}
