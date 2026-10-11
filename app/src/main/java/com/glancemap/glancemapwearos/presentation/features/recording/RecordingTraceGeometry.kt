package com.glancemap.glancemapwearos.presentation.features.recording

import org.mapsforge.core.model.LatLong

/** Immutable views keep drawing on the Mapsforge thread without copying canonical coordinates. */
internal class RecordingTraceGeometry(
    val points: List<RecordedTracePoint>,
    val segmentStarts: List<Int>,
    val revision: Long,
    val startedAtMillis: Long?,
) {
    val segments: List<List<LatLong>> =
        object : AbstractList<List<LatLong>>() {
            override val size: Int = segmentStarts.size

            override fun get(index: Int): List<LatLong> {
                val start = segmentStarts[index]
                val end = segmentStarts.getOrNull(index + 1) ?: points.size
                return object : AbstractList<LatLong>() {
                    override val size: Int = end - start

                    override fun get(index: Int): LatLong {
                        checkElementIndex(index)
                        return points[start + index].latLong
                    }

                    private fun checkElementIndex(index: Int) {
                        if (index !in 0 until size) throw IndexOutOfBoundsException(index.toString())
                    }
                }
            }
        }
}

internal fun prepareRecordingTraceGeometry(
    state: TraceRecordingUiState,
    previous: RecordingTraceGeometry?,
): RecordingTraceGeometry {
    if (previous != null && previous.points === state.points && previous.startedAtMillis == state.startedAtMillis) {
        return if (previous.revision == state.pointsRevision) {
            previous
        } else {
            RecordingTraceGeometry(
                points = state.points,
                segmentStarts = previous.segmentStarts,
                revision = state.pointsRevision,
                startedAtMillis = state.startedAtMillis,
            )
        }
    }
    val continuesSession =
        previous != null &&
            previous.startedAtMillis == state.startedAtMillis &&
            state.pointsRevision == previous.revision + 1L
    val changedFrom =
        if (
            previous != null &&
            continuesSession &&
            state.pointsChangedFromIndex in 0..minOf(previous.points.size, state.points.size)
        ) {
            state.pointsChangedFromIndex
        } else {
            // Missed updates, restore and new sessions rebuild rather than trusting a stale prefix.
            0
        }
    return RecordingTraceGeometry(
        points = state.points,
        segmentStarts = recordingTraceSegmentStarts(state.points, previous?.segmentStarts, changedFrom),
        revision = state.pointsRevision,
        startedAtMillis = state.startedAtMillis,
    )
}

private fun recordingTraceSegmentStarts(
    points: List<RecordedTracePoint>,
    previousStarts: List<Int>?,
    changedFrom: Int,
): List<Int> =
    buildList {
        if (points.isNotEmpty()) {
            add(0)
            previousStarts?.forEach { start ->
                if (start in 1 until changedFrom) add(start)
            }
            for (index in maxOf(1, changedFrom) until points.size) {
                if (startsRecordedTraceSegment(points[index - 1], points[index])) add(index)
            }
        }
    }
