package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.recording.buildRecordingTitle

/** Default saved-activity title: activity type plus the watch's shared date/start/end format. */
internal fun phoneRecordingDefaultTitle(
    profileLabel: String,
    startedAtMillis: Long,
    endedAtMillis: Long,
): String = "$profileLabel ${buildRecordingTitle(startedAtMillis, endedAtMillis)}"
