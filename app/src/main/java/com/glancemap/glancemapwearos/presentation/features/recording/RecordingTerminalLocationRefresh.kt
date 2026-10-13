package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import com.glancemap.trailcore.recording.RecordingTerminalAction as SharedRecordingTerminalAction
import com.glancemap.trailcore.recording.RecordingTerminalActionRequest as SharedRecordingTerminalActionRequest
import com.glancemap.trailcore.recording.RecordingTerminalLocationRefresh as SharedRecordingTerminalLocationRefresh
import com.glancemap.trailcore.recording.RecordingTerminalLocationRefreshInput as SharedRecordingTerminalLocationRefreshInput
import com.glancemap.trailcore.recording.shouldRequestTerminalRecordingLocationRefresh as sharedShouldRequestTerminalRecordingLocationRefresh

data class RecordingRuntimeDependencies(
    val applicationContext: Context? = null,
    val requestImmediateLocation: (String) -> Unit = {},
)

internal typealias RecordingTerminalAction = SharedRecordingTerminalAction
internal typealias RecordingTerminalActionRequest = SharedRecordingTerminalActionRequest
internal typealias RecordingTerminalLocationRefreshInput = SharedRecordingTerminalLocationRefreshInput
internal typealias RecordingTerminalLocationRefresh = SharedRecordingTerminalLocationRefresh

internal fun shouldRequestTerminalRecordingLocationRefresh(
    input: RecordingTerminalLocationRefreshInput,
): Boolean = sharedShouldRequestTerminalRecordingLocationRefresh(input)
