@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
)

package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.routing.RouteGeometryPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPosition
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackProfile
import com.glancemap.glancemapwearos.presentation.features.gpx.totalAscent
import com.glancemap.glancemapwearos.presentation.features.gpx.totalDistance
import org.mapsforge.core.model.LatLong

// Keep every existing validation guard and its typed, localizable failure.
@Suppress("ThrowsCount")
internal fun buildRouteToolEditOutput(
    sourcePath: String,
    sourceFileName: String,
    sourceTitle: String?,
    profile: TrackProfile,
    session: RouteToolSession,
    resources: Resources,
): RouteToolEditOutput {
    require(session.options.toolKind == RouteToolKind.MODIFY) {
        throw RouteToolArgumentException(
            R.string.gpx_only_modify_actions_are_supported_by_the_gpx_editor,
            "Only modify actions are supported by the GPX editor.",
        )
    }

    val sourceTrack =
        GpxTrackDetails(
            id = sourcePath,
            points = profile.points.map { it.latLong },
            title = sourceTitle,
            distance = profile.totalDistance,
            elevationGain = profile.totalAscent,
            startPoint = profile.points.firstOrNull()?.latLong,
            endPoint = profile.points.lastOrNull()?.latLong,
        )

    val editedPoints =
        when (session.options.modifyMode) {
            RouteModifyMode.RESHAPE_ROUTE -> {
                throw RouteToolStateException(
                    R.string.gpx_use_buildroutetoolreshapeoutput_for_reshape_edits,
                    "Use buildRouteToolReshapeOutput for reshape edits.",
                )
            }

            RouteModifyMode.REPLACE_SECTION_A_TO_B -> {
                throw RouteToolStateException(
                    R.string.gpx_use_buildroutetoolreplacesectionoutput_for_routed_section_replacement,
                    "Use buildRouteToolReplaceSectionOutput for routed section replacement.",
                )
            }

            RouteModifyMode.KEEP_ONLY_A_TO_B -> {
                val a =
                    session.pointA
                        ?: throw RouteToolStateException(R.string.gpx_point_a_is_required, "Point A is required.")
                val b =
                    session.pointB
                        ?: throw RouteToolStateException(R.string.gpx_point_b_is_required, "Point B is required.")
                val posA = snapToTrackPosition(target = a, sourceTrack = sourceTrack, profile = profile)
                val posB = snapToTrackPosition(target = b, sourceTrack = sourceTrack, profile = profile)
                slicePointsBetween(profile.points, posA, posB)
            }

            RouteModifyMode.TRIM_START_TO_HERE -> {
                val a =
                    session.pointA
                        ?: throw RouteToolStateException(R.string.gpx_point_a_is_required, "Point A is required.")
                val posA =
                    session.pointATrackPosition?.resolveRouteToolTrackPosition(profile)
                        ?: snapToTrackPosition(target = a, sourceTrack = sourceTrack, profile = profile)
                trimTrackStart(profile.points, posA)
            }

            RouteModifyMode.TRIM_END_FROM_HERE -> {
                val b =
                    session.pointB
                        ?: throw RouteToolStateException(R.string.gpx_point_b_is_required, "Point B is required.")
                val posB =
                    session.pointBTrackPosition?.resolveRouteToolTrackPosition(profile)
                        ?: snapToTrackPosition(target = b, sourceTrack = sourceTrack, profile = profile)
                trimTrackEnd(profile.points, posB)
            }

            RouteModifyMode.REVERSE_GPX -> {
                profile.points.reversed()
            }
        }

    require(editedPoints.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_selected_edit_would_produce_an_empty_gpx,
            "The selected edit would produce an empty GPX.",
        )
    }

    val titleBase =
        sourceTitle?.takeIf { it.isNotBlank() }
            ?: sourceFileName.removeSuffix(".gpx")
    val fileName =
        when (session.options.saveBehavior) {
            RouteSaveBehavior.REPLACE_CURRENT -> sourceFileName
            RouteSaveBehavior.SAVE_AS_NEW -> {
                buildEditedFileName(
                    sourceFileName = sourceFileName,
                    mode = session.options.modifyMode,
                )
            }
        }

    val title =
        when (session.options.saveBehavior) {
            RouteSaveBehavior.REPLACE_CURRENT -> titleBase
            RouteSaveBehavior.SAVE_AS_NEW -> resources.getString(R.string.gpx_edited, titleBase)
        }

    return RouteToolEditOutput(
        fileName = fileName,
        title = title,
        points = editedPoints,
    )
}

// Keep every existing validation guard and its typed, localizable failure.
@Suppress("ThrowsCount")
internal fun buildRouteToolEndpointChangeOutput(
    sourceFileName: String,
    sourceTitle: String?,
    profile: TrackProfile,
    session: RouteToolSession,
    snappedPosition: TrackPosition,
    routedPoints: List<RouteGeometryPoint>,
    resources: Resources,
): RouteToolEditOutput {
    require(session.options.toolKind == RouteToolKind.MODIFY) {
        throw RouteToolArgumentException(
            R.string.gpx_only_modify_actions_are_supported_by_the_gpx_editor,
            "Only modify actions are supported by the GPX editor.",
        )
    }
    require(
        session.options.modifyMode == RouteModifyMode.TRIM_START_TO_HERE ||
            session.options.modifyMode == RouteModifyMode.TRIM_END_FROM_HERE,
    ) {
        throw RouteToolArgumentException(
            R.string.gpx_only_change_start_end_are_supported_here,
            "Only Change start/end are supported here.",
        )
    }
    require(routedPoints.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_routed_endpoint_change_is_empty,
            "The routed endpoint change is empty.",
        )
    }

    val merged = ArrayList<TrackPoint>(profile.points.size + routedPoints.size)
    when (session.options.modifyMode) {
        RouteModifyMode.TRIM_START_TO_HERE -> {
            routedPoints.forEach { point ->
                appendUnique(merged, point.toTrackPoint())
            }
            trimTrackStart(profile.points, snappedPosition).forEach { point ->
                appendUnique(merged, point)
            }
        }

        RouteModifyMode.TRIM_END_FROM_HERE -> {
            trimTrackEnd(profile.points, snappedPosition).forEach { point ->
                appendUnique(merged, point)
            }
            routedPoints.forEach { point ->
                appendUnique(merged, point.toTrackPoint())
            }
        }
    }

    require(merged.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_selected_edit_would_produce_an_empty_gpx,
            "The selected edit would produce an empty GPX.",
        )
    }

    val titleBase =
        sourceTitle?.takeIf { it.isNotBlank() }
            ?: sourceFileName.removeSuffix(".gpx")
    val fileName =
        when (session.options.saveBehavior) {
            RouteSaveBehavior.REPLACE_CURRENT -> sourceFileName
            RouteSaveBehavior.SAVE_AS_NEW -> {
                buildEditedFileName(
                    sourceFileName = sourceFileName,
                    mode = session.options.modifyMode,
                )
            }
        }
    val title =
        when (session.options.saveBehavior) {
            RouteSaveBehavior.REPLACE_CURRENT -> titleBase
            RouteSaveBehavior.SAVE_AS_NEW -> resources.getString(R.string.gpx_edited, titleBase)
        }

    return RouteToolEditOutput(
        fileName = fileName,
        title = title,
        points = merged,
    )
}

// Keep every existing validation guard and its typed, localizable failure.
@Suppress("ThrowsCount")
internal fun buildRouteToolExtensionOutput(
    sourceFileName: String,
    sourceTitle: String?,
    profile: TrackProfile,
    routedPoints: List<RouteGeometryPoint>,
    resources: Resources,
): RouteToolEditOutput {
    require(profile.points.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_active_gpx_does_not_contain_enough_points_to_extend,
            "The active GPX does not contain enough points to extend.",
        )
    }
    require(routedPoints.size >= 2) {
        throw RouteToolArgumentException(R.string.gpx_the_routed_extension_is_empty, "The routed extension is empty.")
    }

    val merged = ArrayList<TrackPoint>(profile.points.size + routedPoints.size)
    profile.points.forEach { point ->
        appendUnique(merged, point)
    }
    routedPoints.forEach { point ->
        appendUnique(merged, point.toTrackPoint())
    }

    require(merged.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_routed_extension_would_produce_an_empty_gpx,
            "The routed extension would produce an empty GPX.",
        )
    }

    val titleBase =
        sourceTitle?.takeIf { it.isNotBlank() }
            ?: sourceFileName.removeSuffix(".gpx")

    return RouteToolEditOutput(
        fileName =
            buildEditedFileName(
                sourceFileName = sourceFileName,
                modeSlug = "extend",
            ),
        title = resources.getString(R.string.gpx_extended, titleBase),
        points = merged,
    )
}

// Keep every existing validation guard and its typed, localizable failure.
@Suppress("ThrowsCount")
internal fun buildRouteToolReplaceSectionOutput(
    sourcePath: String,
    sourceFileName: String,
    sourceTitle: String?,
    profile: TrackProfile,
    session: RouteToolSession,
    routedPoints: List<RouteGeometryPoint>,
    resources: Resources,
): RouteToolEditOutput {
    require(session.options.modifyMode == RouteModifyMode.REPLACE_SECTION_A_TO_B) {
        throw RouteToolArgumentException(
            R.string.gpx_only_replace_a_b_is_supported_here,
            "Only Replace A-B is supported here.",
        )
    }
    require(routedPoints.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_routed_replacement_is_empty,
            "The routed replacement is empty.",
        )
    }

    val sourceTrack =
        GpxTrackDetails(
            id = sourcePath,
            points = profile.points.map { it.latLong },
            title = sourceTitle,
            distance = profile.totalDistance,
            elevationGain = profile.totalAscent,
            startPoint = profile.points.firstOrNull()?.latLong,
            endPoint = profile.points.lastOrNull()?.latLong,
        )
    val a = session.pointA ?: throw RouteToolStateException(R.string.gpx_point_a_is_required, "Point A is required.")
    val b = session.pointB ?: throw RouteToolStateException(R.string.gpx_point_b_is_required, "Point B is required.")
    val posA = snapToTrackPosition(target = a, sourceTrack = sourceTrack, profile = profile)
    val posB = snapToTrackPosition(target = b, sourceTrack = sourceTrack, profile = profile)
    val (start, end) = orderedPositions(posA, posB)

    val merged = ArrayList<TrackPoint>(profile.points.size + routedPoints.size)
    trimTrackEnd(profile.points, start).forEach { point ->
        appendUnique(merged, point)
    }
    routedPoints.forEach { point ->
        appendUnique(merged, point.toTrackPoint())
    }
    trimTrackStart(profile.points, end).forEach { point ->
        appendUnique(merged, point)
    }

    require(merged.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_selected_replacement_would_produce_an_empty_gpx,
            "The selected replacement would produce an empty GPX.",
        )
    }

    val titleBase =
        sourceTitle?.takeIf { it.isNotBlank() }
            ?: sourceFileName.removeSuffix(".gpx")
    val fileName =
        when (session.options.saveBehavior) {
            RouteSaveBehavior.REPLACE_CURRENT -> sourceFileName
            RouteSaveBehavior.SAVE_AS_NEW -> {
                buildEditedFileName(
                    sourceFileName = sourceFileName,
                    mode = session.options.modifyMode,
                )
            }
        }
    val title =
        when (session.options.saveBehavior) {
            RouteSaveBehavior.REPLACE_CURRENT -> titleBase
            RouteSaveBehavior.SAVE_AS_NEW -> resources.getString(R.string.gpx_rerouted, titleBase)
        }

    return RouteToolEditOutput(
        fileName = fileName,
        title = title,
        points = merged,
    )
}

// Keep every existing validation guard and its typed, localizable failure.
@Suppress("ThrowsCount")
internal fun buildRouteToolReshapeOutput(
    sourcePath: String,
    sourceFileName: String,
    sourceTitle: String?,
    profile: TrackProfile,
    session: RouteToolSession,
    firstLegPoints: List<RouteGeometryPoint>,
    secondLegPoints: List<RouteGeometryPoint>,
    bounds: RouteReshapeBounds? = null,
    resources: Resources,
): RouteToolEditOutput {
    require(session.options.modifyMode == RouteModifyMode.RESHAPE_ROUTE) {
        throw RouteToolArgumentException(
            R.string.gpx_only_reshape_edits_are_supported_here,
            "Only reshape edits are supported here.",
        )
    }
    require(firstLegPoints.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_first_routed_reshape_leg_is_empty,
            "The first routed reshape leg is empty.",
        )
    }
    require(secondLegPoints.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_second_routed_reshape_leg_is_empty,
            "The second routed reshape leg is empty.",
        )
    }

    val selectedPoint =
        session.pointA
            ?: throw RouteToolStateException(R.string.gpx_select_the_route_point_first, "Select the route point first.")
    require(session.destination != null) {
        throw RouteToolArgumentException(R.string.gpx_pick_the_new_bend_point_first, "Pick the new bend point first.")
    }
    val resolvedBounds =
        bounds ?: resolveRouteReshapeBounds(
            sourcePath = sourcePath,
            sourceTitle = sourceTitle,
            profile = profile,
            anchor = selectedPoint,
            rejoinHint = session.destination,
        )

    val merged = ArrayList<TrackPoint>(profile.points.size + firstLegPoints.size + secondLegPoints.size)
    trimTrackEnd(profile.points, resolvedBounds.startPosition).forEach { point ->
        appendUnique(merged, point)
    }
    firstLegPoints.forEach { point ->
        appendUnique(merged, point.toTrackPoint())
    }
    secondLegPoints.forEach { point ->
        appendUnique(merged, point.toTrackPoint())
    }
    trimTrackStart(profile.points, resolvedBounds.endPosition).forEach { point ->
        appendUnique(merged, point)
    }

    require(merged.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_reshape_would_produce_an_empty_gpx,
            "The reshape would produce an empty GPX.",
        )
    }

    val titleBase =
        sourceTitle?.takeIf { it.isNotBlank() }
            ?: sourceFileName.removeSuffix(".gpx")
    val fileName =
        when (session.options.saveBehavior) {
            RouteSaveBehavior.REPLACE_CURRENT -> sourceFileName
            RouteSaveBehavior.SAVE_AS_NEW ->
                buildEditedFileName(
                    sourceFileName = sourceFileName,
                    modeSlug = "reshape",
                )
        }
    val title =
        when (session.options.saveBehavior) {
            RouteSaveBehavior.REPLACE_CURRENT -> titleBase
            RouteSaveBehavior.SAVE_AS_NEW -> resources.getString(R.string.gpx_reshaped, titleBase)
        }

    return RouteToolEditOutput(
        fileName = fileName,
        title = title,
        points = merged,
    )
}

// Keep every existing validation guard and its typed, localizable failure.
@Suppress("ThrowsCount")
internal fun buildRouteToolReshapePreview(
    sourcePath: String,
    sourceTitle: String?,
    profile: TrackProfile,
    session: RouteToolSession,
    firstLegPoints: List<RouteGeometryPoint>,
    secondLegPoints: List<RouteGeometryPoint>,
    bounds: RouteReshapeBounds? = null,
): RouteToolModifyPreview {
    require(session.options.modifyMode == RouteModifyMode.RESHAPE_ROUTE) {
        throw RouteToolArgumentException(
            R.string.gpx_only_reshape_previews_are_supported_here,
            "Only reshape previews are supported here.",
        )
    }
    require(firstLegPoints.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_first_routed_reshape_leg_is_empty,
            "The first routed reshape leg is empty.",
        )
    }
    require(secondLegPoints.size >= 2) {
        throw RouteToolArgumentException(
            R.string.gpx_the_second_routed_reshape_leg_is_empty,
            "The second routed reshape leg is empty.",
        )
    }

    val selectedPoint =
        session.pointA
            ?: throw RouteToolStateException(R.string.gpx_select_the_route_point_first, "Select the route point first.")
    require(session.destination != null) {
        throw RouteToolArgumentException(R.string.gpx_pick_the_new_bend_point_first, "Pick the new bend point first.")
    }
    val resolvedBounds =
        bounds ?: resolveRouteReshapeBounds(
            sourcePath = sourcePath,
            sourceTitle = sourceTitle,
            profile = profile,
            anchor = selectedPoint,
            rejoinHint = session.destination,
        )

    val previewPoints = ArrayList<LatLong>(firstLegPoints.size + secondLegPoints.size + 2)
    appendUniqueLatLong(previewPoints, resolvedBounds.startPoint.latLong)
    firstLegPoints.forEach { point ->
        appendUniqueLatLong(previewPoints, point.latLong)
    }
    secondLegPoints.forEach { point ->
        appendUniqueLatLong(previewPoints, point.latLong)
    }
    appendUniqueLatLong(previewPoints, resolvedBounds.endPoint.latLong)

    return RouteToolModifyPreview(previewPoints = previewPoints)
}
