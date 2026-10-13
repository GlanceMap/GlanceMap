package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.trailcore.recording.RECORDING_FIX_SUSTAINED_CONFIRMATION_WINDOW_MS as SHARED_CONFIRMATION_WINDOW_MS
import com.glancemap.trailcore.recording.isConfirmedSustainedMovement as sharedIsConfirmedSustainedMovement
import com.glancemap.trailcore.recording.isPlausibleConfirmedSustainedTransition as sharedIsPlausibleTransition
import com.glancemap.trailcore.recording.recordingFixProfileSpeedLimit as sharedSpeedLimit

internal const val RECORDING_FIX_SUSTAINED_CONFIRMATION_WINDOW_MS =
    SHARED_CONFIRMATION_WINDOW_MS

internal fun recordingFixProfileSpeedLimit(activityProfile: String): Double = sharedSpeedLimit(activityProfile)

internal fun isConfirmedSustainedMovement(
    previous: RecordingFixSample,
    pending: RecordingFixSample,
    candidate: RecordingFixSample,
    activityProfile: String,
): Boolean = sharedIsConfirmedSustainedMovement(previous, pending, candidate, activityProfile)

internal fun isPlausibleConfirmedSustainedTransition(
    previous: RecordingFixSample,
    candidate: RecordingFixSample,
    activityProfile: String,
): Boolean = sharedIsPlausibleTransition(previous, candidate, activityProfile)
