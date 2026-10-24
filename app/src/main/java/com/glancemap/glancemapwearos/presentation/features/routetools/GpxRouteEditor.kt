package com.glancemap.glancemapwearos.presentation.features.routetools

import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPosition
import org.mapsforge.core.model.LatLong

internal data class RouteToolSaveResult(
    val fileName: String,
    val filePath: String,
    val displayTitle: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val estimatedDurationSec: Double?,
    val replacedCurrent: Boolean,
    @StringRes val successMessageRes: Int? = null,
) {
    @get:StringRes val messageRes: Int
        get() =
            successMessageRes ?: if (replacedCurrent) {
                R.string.gpx_gpx_updated
            } else {
                R.string.gpx_new_gpx_saved
            }
}

internal data class RouteToolModifyPreview(
    val previewPoints: List<LatLong>,
)

internal data class RouteToolCreatePreview(
    val previewPoints: List<LatLong>,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val estimatedDurationSec: Double?,
    val plannedCreation: RouteToolPlannedCreation? = null,
    val multiPointChainPointCount: Int? = null,
)

internal data class RouteToolPlannedCreation(
    val fileName: String,
    val gpxBytes: ByteArray,
)

internal data class RouteToolEditOutput(
    val fileName: String,
    val title: String,
    val points: List<TrackPoint>,
)

internal data class RouteReshapeHandle(
    val pointIndex: Int,
    val latLong: LatLong,
    val isEndpoint: Boolean,
)

internal data class RouteReshapeBounds(
    val startPosition: TrackPosition,
    val endPosition: TrackPosition,
    val startPoint: TrackPoint,
    val endPoint: TrackPoint,
)

internal data class RouteToolTrackMatch(
    val position: TrackPosition,
    val latLong: LatLong,
    val distanceMeters: Double,
)

internal enum class RouteReshapeDirection {
    START,
    END,
}
