package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

internal fun List<RecordingRecapMetric>.inLabelOrder(vararg labels: Int): List<RecordingRecapMetric> {
    val byLabel = associateBy { it.labelRes }
    return labels.asList().mapNotNull(byLabel::get)
}

internal fun List<RecordingRecapMetric>.moveLabelAfter(
    label: Int,
    afterLabel: Int,
): List<RecordingRecapMetric> {
    val moved = firstOrNull { it.labelRes == label }
    val withoutMoved = filterNot { it.labelRes == label }
    val afterIndex = withoutMoved.indexOfFirst { it.labelRes == afterLabel }
    return if (moved == null || afterIndex < 0) {
        this
    } else {
        buildList {
            withoutMoved.forEachIndexed { index, metric ->
                add(metric)
                if (index == afterIndex) add(moved)
            }
        }
    }
}
