package com.glancemap.glancemapwearos.data.repository

import com.glancemap.trailcore.recording.inferRecordingGpsUsageProfile
import com.glancemap.trailcore.recording.recordingGpsUsageTiming
import com.glancemap.trailcore.recording.RecordingGpsUsageTiming as SharedRecordingGpsUsageTiming

internal typealias GpsUsageTiming = SharedRecordingGpsUsageTiming

internal fun gpsUsageTiming(
    usageProfile: String,
    activityProfile: String,
): GpsUsageTiming = recordingGpsUsageTiming(usageProfile, activityProfile)

internal fun inferGpsUsageProfile(
    activityProfile: String,
    timing: GpsUsageTiming,
): String = inferRecordingGpsUsageProfile(activityProfile, timing)
