package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.trailcore.recording.EARTH_RADIUS_METERS as SHARED_EARTH_RADIUS_METERS
import com.glancemap.trailcore.recording.RecordingCanonicalAppendResult as SharedRecordingCanonicalAppendResult
import com.glancemap.trailcore.recording.RecordingLocalMeters as SharedRecordingLocalMeters
import com.glancemap.trailcore.recording.RecordingPointSmoothingOptions as SharedRecordingPointSmoothingOptions
import com.glancemap.trailcore.recording.RecordingPointSmoothingResult as SharedRecordingPointSmoothingResult
import com.glancemap.trailcore.recording.RecordingTrajectorySmoothingDiagnostics as SharedSmoothingDiag
import com.glancemap.trailcore.recording.appendCanonicalRecordingPoint as sharedAppendCanonicalRecordingPoint
import com.glancemap.trailcore.recording.flushCanonicalRecordingTail as sharedFlushCanonicalRecordingTail
import com.glancemap.trailcore.recording.smoothRecordingMiddlePoint as sharedSmoothRecordingMiddlePoint
import com.glancemap.trailcore.recording.smoothRecordingStraightDrift as sharedSmoothStraightDrift

internal typealias RecordingCanonicalAppendResult = SharedRecordingCanonicalAppendResult
internal typealias RecordingTrajectorySmoothingDiagnostics = SharedSmoothingDiag
internal typealias RecordingPointSmoothingOptions = SharedRecordingPointSmoothingOptions
internal typealias RecordingPointSmoothingResult = SharedRecordingPointSmoothingResult
internal typealias LocalMeters = SharedRecordingLocalMeters

internal const val EARTH_RADIUS_METERS = SHARED_EARTH_RADIUS_METERS

internal fun appendCanonicalRecordingPoint(
    existingPoints: List<RecordedTracePoint>,
    point: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): RecordingCanonicalAppendResult = sharedAppendCanonicalRecordingPoint(existingPoints, point, options)

internal fun flushCanonicalRecordingTail(
    existingPoints: List<RecordedTracePoint>,
    options: RecordingPointSmoothingOptions,
): RecordingCanonicalAppendResult = sharedFlushCanonicalRecordingTail(existingPoints, options)

internal fun smoothRecordingStraightDrift(
    before: RecordedTracePoint,
    firstInterior: RecordedTracePoint,
    secondInterior: RecordedTracePoint,
    after: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): RecordingPointSmoothingResult? = sharedSmoothStraightDrift(before, firstInterior, secondInterior, after, options)

internal fun smoothRecordingMiddlePoint(
    before: RecordedTracePoint,
    middle: RecordedTracePoint,
    after: RecordedTracePoint,
    options: RecordingPointSmoothingOptions,
): RecordingPointSmoothingResult? = sharedSmoothRecordingMiddlePoint(before, middle, after, options)

internal fun smoothRecordingMiddlePoint(
    before: RecordedTracePoint,
    middle: RecordedTracePoint,
    after: RecordedTracePoint,
    mode: String,
    activityProfile: String,
): RecordingPointSmoothingResult? = sharedSmoothRecordingMiddlePoint(before, middle, after, mode, activityProfile)
