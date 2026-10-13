package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.trailcore.recording.RecordedTraceSummary as SharedRecordedTraceSummary
import com.glancemap.trailcore.recording.buildRecordingFileName as sharedBuildRecordingFileName
import com.glancemap.trailcore.recording.buildRecordingFileNameFromTitle as sharedBuildRecordingFileNameFromTitle
import com.glancemap.trailcore.recording.buildRecordingTitle as sharedBuildRecordingTitle
import com.glancemap.trailcore.recording.encodeRecordedTraceAsGpx as sharedEncodeRecordedTraceAsGpx
import com.glancemap.trailcore.recording.recordedTraceSegments as segments
import com.glancemap.trailcore.recording.startsRecordedTraceSegment as sharedStartsRecordedTraceSegment

internal typealias RecordedTraceSummary = SharedRecordedTraceSummary

internal fun encodeRecordedTraceAsGpx(
    title: String,
    points: List<RecordedTracePoint>,
    summary: RecordedTraceSummary? = null,
): ByteArray = sharedEncodeRecordedTraceAsGpx(title, points, summary)

internal fun recordedTraceSegments(points: List<RecordedTracePoint>): List<List<RecordedTracePoint>> = segments(points)

internal fun startsRecordedTraceSegment(
    previous: RecordedTracePoint?,
    current: RecordedTracePoint,
): Boolean = sharedStartsRecordedTraceSegment(previous, current)

internal fun buildRecordingFileName(
    startedAtMillis: Long,
    endedAtMillis: Long,
): String = sharedBuildRecordingFileName(startedAtMillis, endedAtMillis)

internal fun buildRecordingFileNameFromTitle(title: String): String = sharedBuildRecordingFileNameFromTitle(title)

internal fun buildRecordingTitle(
    startedAtMillis: Long,
    endedAtMillis: Long,
): String = sharedBuildRecordingTitle(startedAtMillis, endedAtMillis)
