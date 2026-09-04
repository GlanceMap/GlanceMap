package com.glancemap.glancemapcompanionapp.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.geo.GeoPoint

/** A temporary, renderer-neutral map marker used while the user is choosing a POI or route point. */
internal data class PhoneMapPointSelectionMarker(
    val kind: PhoneMapPointSelectionMarkerKind,
    val point: PhoneMapCoordinate,
)

internal enum class PhoneMapPointSelectionMarkerKind(
    val label: String,
    val colorArgb: Int,
) {
    POI(label = "POI", colorArgb = 0xFF2563EB.toInt()),
    POINT_A(label = "A", colorArgb = 0xFF16A34A.toInt()),
    POINT_B(label = "B", colorArgb = 0xFFEA580C.toInt()),
    DESTINATION(label = "D", colorArgb = 0xFF7C3AED.toInt()),
    WAYPOINT(label = "•", colorArgb = 0xFF0891B2.toInt()),
    MEASUREMENT_FIRST(label = "1", colorArgb = 0xFF0284C7.toInt()),
    MEASUREMENT_SECOND(label = "2", colorArgb = 0xFFDC2626.toInt()),
}

internal enum class PhoneMapPointSelectionPhase {
    DESTINATION,
    POINT_A,
    POINT_B,
    CHAIN_POINT,
    RESHAPE_BEND,
}

internal data class PhoneMapPointSelectionProgress(
    val completed: Int,
    val total: Int?,
) {
    val currentStep: Int
        get() = completed + 1
}

@Suppress(
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
) // The selection phase is a direct map of the route-tool state machine.
internal fun PhoneRouteToolsUiState.pointSelectionPhase(): PhoneMapPointSelectionPhase? =
    when (mode) {
        PhoneRouteCreationMode.CURRENT_TO_DESTINATION ->
            PhoneMapPointSelectionPhase.DESTINATION.takeIf { destination == null && !isRouting }

        PhoneRouteCreationMode.POINT_A_TO_B ->
            when {
                isRouting -> null
                pointA == null -> PhoneMapPointSelectionPhase.POINT_A
                pointB == null -> PhoneMapPointSelectionPhase.POINT_B
                else -> null
            }

        PhoneRouteCreationMode.MULTI_POINT_CHAIN ->
            PhoneMapPointSelectionPhase.CHAIN_POINT.takeIf {
                !isRouting && chainPoints.size < PHONE_ROUTE_TOOLS_MAX_CHAIN_POINTS
            }

        PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION ->
            PhoneMapPointSelectionPhase.DESTINATION.takeIf {
                !isRouting && selectedRouteId != null && destination == null
            }

        PhoneRouteCreationMode.COORDINATES -> null

        PhoneRouteCreationMode.MODIFY_ROUTE ->
            if (isRouting || selectedRouteId == null) {
                null
            } else {
                when (modificationMode) {
                    PhoneRouteModificationMode.RESHAPE_ROUTE ->
                        when {
                            pointA == null -> PhoneMapPointSelectionPhase.POINT_A
                            destination == null -> PhoneMapPointSelectionPhase.RESHAPE_BEND
                            else -> null
                        }

                    PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B,
                    PhoneRouteModificationMode.KEEP_ONLY_A_TO_B,
                    ->
                        when {
                            pointA == null -> PhoneMapPointSelectionPhase.POINT_A
                            pointB == null -> PhoneMapPointSelectionPhase.POINT_B
                            else -> null
                        }

                    PhoneRouteModificationMode.TRIM_START_TO_HERE ->
                        PhoneMapPointSelectionPhase.POINT_A.takeIf { pointA == null }

                    PhoneRouteModificationMode.TRIM_END_FROM_HERE ->
                        PhoneMapPointSelectionPhase.POINT_B.takeIf { pointB == null }

                    PhoneRouteModificationMode.REVERSE_GPX -> null
                }
            }

        null -> null
    }

internal fun PhoneRouteToolsUiState.pointSelectionProgress(): PhoneMapPointSelectionProgress? {
    val phase = pointSelectionPhase() ?: return null
    return when (phase) {
        PhoneMapPointSelectionPhase.DESTINATION ->
            PhoneMapPointSelectionProgress(completed = 0, total = 1)

        PhoneMapPointSelectionPhase.POINT_A ->
            PhoneMapPointSelectionProgress(
                completed = 0,
                total =
                    if (
                        mode == PhoneRouteCreationMode.MODIFY_ROUTE &&
                        (
                            modificationMode == PhoneRouteModificationMode.TRIM_START_TO_HERE ||
                                modificationMode == PhoneRouteModificationMode.TRIM_END_FROM_HERE
                        )
                    ) {
                        1
                    } else {
                        2
                    },
            )

        PhoneMapPointSelectionPhase.POINT_B ->
            PhoneMapPointSelectionProgress(
                completed =
                    if (
                        mode == PhoneRouteCreationMode.MODIFY_ROUTE &&
                        modificationMode == PhoneRouteModificationMode.TRIM_END_FROM_HERE
                    ) {
                        0
                    } else {
                        1
                    },
                total =
                    if (
                        mode == PhoneRouteCreationMode.MODIFY_ROUTE &&
                        modificationMode == PhoneRouteModificationMode.TRIM_END_FROM_HERE
                    ) {
                        1
                    } else {
                        2
                    },
            )

        PhoneMapPointSelectionPhase.CHAIN_POINT ->
            PhoneMapPointSelectionProgress(completed = chainPoints.size, total = null)

        PhoneMapPointSelectionPhase.RESHAPE_BEND ->
            PhoneMapPointSelectionProgress(completed = 1, total = 2)
    }
}

internal fun PhoneRouteToolsUiState.canUndoLastMapPoint(): Boolean =
    when (mode) {
        PhoneRouteCreationMode.CURRENT_TO_DESTINATION,
        PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION,
        -> destination != null

        PhoneRouteCreationMode.POINT_A_TO_B -> pointB != null || pointA != null
        PhoneRouteCreationMode.MULTI_POINT_CHAIN -> chainPoints.isNotEmpty()
        PhoneRouteCreationMode.COORDINATES -> coordinateLatitude.isNotBlank() || coordinateLongitude.isNotBlank()
        PhoneRouteCreationMode.MODIFY_ROUTE ->
            when (modificationMode) {
                PhoneRouteModificationMode.RESHAPE_ROUTE -> destination != null || pointA != null
                PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B,
                PhoneRouteModificationMode.KEEP_ONLY_A_TO_B,
                -> pointB != null || pointA != null

                PhoneRouteModificationMode.TRIM_START_TO_HERE -> pointA != null
                PhoneRouteModificationMode.TRIM_END_FROM_HERE -> pointB != null
                PhoneRouteModificationMode.REVERSE_GPX -> false
            }

        null -> false
    }

internal fun PhoneRouteToolsUiState.undoLastMapPoint(): PhoneRouteToolsUiState {
    if (!canUndoLastMapPoint()) return this
    return when (mode) {
        PhoneRouteCreationMode.CURRENT_TO_DESTINATION,
        PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION,
        -> copy(destination = null, message = null)

        PhoneRouteCreationMode.POINT_A_TO_B ->
            when {
                pointB != null -> copy(pointB = null, message = null)
                else -> copy(pointA = null, message = null)
            }

        PhoneRouteCreationMode.MULTI_POINT_CHAIN ->
            copy(chainPoints = chainPoints.dropLast(1), message = null)

        PhoneRouteCreationMode.COORDINATES ->
            copy(coordinateLatitude = "", coordinateLongitude = "", message = null)

        PhoneRouteCreationMode.MODIFY_ROUTE ->
            when (modificationMode) {
                PhoneRouteModificationMode.RESHAPE_ROUTE ->
                    when {
                        destination != null -> copy(destination = null, message = null)
                        else -> copy(pointA = null, message = null)
                    }

                PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B,
                PhoneRouteModificationMode.KEEP_ONLY_A_TO_B,
                ->
                    when {
                        pointB != null -> copy(pointB = null, message = null)
                        else -> copy(pointA = null, message = null)
                    }

                PhoneRouteModificationMode.TRIM_START_TO_HERE -> copy(pointA = null, message = null)
                PhoneRouteModificationMode.TRIM_END_FROM_HERE -> copy(pointB = null, message = null)
                PhoneRouteModificationMode.REVERSE_GPX -> this
            }

        null -> this
    }
}

@Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
) // All active route markers are derived in one renderer-neutral pass.
internal fun phoneMapPointSelectionMarkers(
    poiPoint: PhoneMapCoordinate?,
    routeTools: PhoneRouteToolsUiState,
): List<PhoneMapPointSelectionMarker> =
    buildList {
        poiPoint?.let { point ->
            add(PhoneMapPointSelectionMarker(PhoneMapPointSelectionMarkerKind.POI, point))
        }
        when (routeTools.mode) {
            PhoneRouteCreationMode.CURRENT_TO_DESTINATION ->
                routeTools.destination?.let { point ->
                    add(
                        PhoneMapPointSelectionMarker(
                            PhoneMapPointSelectionMarkerKind.DESTINATION,
                            point.toCoordinate(),
                        ),
                    )
                }

            PhoneRouteCreationMode.POINT_A_TO_B,
            -> {
                routeTools.pointA?.let { point ->
                    add(
                        PhoneMapPointSelectionMarker(
                            PhoneMapPointSelectionMarkerKind.POINT_A,
                            point.toCoordinate(),
                        ),
                    )
                }
                routeTools.pointB?.let { point ->
                    add(
                        PhoneMapPointSelectionMarker(
                            PhoneMapPointSelectionMarkerKind.POINT_B,
                            point.toCoordinate(),
                        ),
                    )
                }
            }

            PhoneRouteCreationMode.MULTI_POINT_CHAIN ->
                routeTools.chainPoints.forEach { point ->
                    add(
                        PhoneMapPointSelectionMarker(
                            PhoneMapPointSelectionMarkerKind.WAYPOINT,
                            point.toCoordinate(),
                        ),
                    )
                }

            PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION ->
                routeTools.destination?.let { point ->
                    add(
                        PhoneMapPointSelectionMarker(
                            PhoneMapPointSelectionMarkerKind.DESTINATION,
                            point.toCoordinate(),
                        ),
                    )
                }

            PhoneRouteCreationMode.COORDINATES -> Unit

            PhoneRouteCreationMode.MODIFY_ROUTE -> {
                when (routeTools.modificationMode) {
                    PhoneRouteModificationMode.RESHAPE_ROUTE -> {
                        routeTools.pointA?.let { point ->
                            add(
                                PhoneMapPointSelectionMarker(
                                    PhoneMapPointSelectionMarkerKind.POINT_A,
                                    point.toCoordinate(),
                                ),
                            )
                        }
                        routeTools.destination?.let { point ->
                            add(
                                PhoneMapPointSelectionMarker(
                                    PhoneMapPointSelectionMarkerKind.DESTINATION,
                                    point.toCoordinate(),
                                ),
                            )
                        }
                    }

                    PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B,
                    PhoneRouteModificationMode.KEEP_ONLY_A_TO_B,
                    -> {
                        routeTools.pointA?.let { point ->
                            add(
                                PhoneMapPointSelectionMarker(
                                    PhoneMapPointSelectionMarkerKind.POINT_A,
                                    point.toCoordinate(),
                                ),
                            )
                        }
                        routeTools.pointB?.let { point ->
                            add(
                                PhoneMapPointSelectionMarker(
                                    PhoneMapPointSelectionMarkerKind.POINT_B,
                                    point.toCoordinate(),
                                ),
                            )
                        }
                    }

                    PhoneRouteModificationMode.TRIM_START_TO_HERE ->
                        routeTools.pointA?.let { point ->
                            add(
                                PhoneMapPointSelectionMarker(
                                    PhoneMapPointSelectionMarkerKind.POINT_A,
                                    point.toCoordinate(),
                                ),
                            )
                        }

                    PhoneRouteModificationMode.TRIM_END_FROM_HERE ->
                        routeTools.pointB?.let { point ->
                            add(
                                PhoneMapPointSelectionMarker(
                                    PhoneMapPointSelectionMarkerKind.POINT_B,
                                    point.toCoordinate(),
                                ),
                            )
                        }

                    PhoneRouteModificationMode.REVERSE_GPX -> Unit
                }
            }

            null -> Unit
        }
    }

internal fun phoneMapDistanceMeasurementMarkers(
    measurement: PhoneMapDistanceMeasurement?,
): List<PhoneMapPointSelectionMarker> =
    measurement
        ?.let { value ->
            listOf(
                PhoneMapPointSelectionMarker(
                    kind = PhoneMapPointSelectionMarkerKind.MEASUREMENT_FIRST,
                    point = value.first,
                ),
                PhoneMapPointSelectionMarker(
                    kind = PhoneMapPointSelectionMarkerKind.MEASUREMENT_SECOND,
                    point = value.second,
                ),
            )
        }.orEmpty()

@Suppress("CyclomaticComplexMethod") // Each route-tool phase has a distinct user-facing instruction.
internal fun PhoneRouteToolsUiState.pointSelectionHintResource(
    phase: PhoneMapPointSelectionPhase,
): Int =
    when {
        mode == PhoneRouteCreationMode.MULTI_POINT_CHAIN ->
            R.string.map_route_tools_multi_point_hint

        mode == PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION ->
            R.string.map_route_tools_extend_destination_hint

        mode == PhoneRouteCreationMode.MODIFY_ROUTE &&
            modificationMode == PhoneRouteModificationMode.RESHAPE_ROUTE &&
            phase == PhoneMapPointSelectionPhase.POINT_A ->
            R.string.map_route_tools_reshape_anchor_hint

        mode == PhoneRouteCreationMode.MODIFY_ROUTE &&
            modificationMode == PhoneRouteModificationMode.RESHAPE_ROUTE &&
            phase == PhoneMapPointSelectionPhase.RESHAPE_BEND ->
            R.string.map_route_tools_reshape_bend_hint

        mode == PhoneRouteCreationMode.MODIFY_ROUTE &&
            modificationMode == PhoneRouteModificationMode.TRIM_START_TO_HERE ->
            R.string.map_route_tools_new_start_hint

        mode == PhoneRouteCreationMode.MODIFY_ROUTE &&
            modificationMode == PhoneRouteModificationMode.TRIM_END_FROM_HERE ->
            R.string.map_route_tools_new_end_hint

        phase == PhoneMapPointSelectionPhase.DESTINATION ->
            R.string.map_route_tools_destination_hint

        phase == PhoneMapPointSelectionPhase.POINT_A ->
            R.string.map_route_tools_point_a_hint

        phase == PhoneMapPointSelectionPhase.POINT_B ->
            R.string.map_route_tools_point_b_hint

        phase == PhoneMapPointSelectionPhase.CHAIN_POINT ->
            R.string.map_route_tools_multi_point_hint

        phase == PhoneMapPointSelectionPhase.RESHAPE_BEND ->
            R.string.map_route_tools_reshape_bend_hint

        else -> R.string.map_route_tools_destination_hint
    }

internal fun createPhoneMapPointSelectionMarkerBitmap(
    kind: PhoneMapPointSelectionMarkerKind,
): Bitmap {
    val size = 64
    val center = size / 2f
    val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = if (kind.label.length == 1) 26f else 15f
            typeface = Typeface.DEFAULT_BOLD
        }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            drawCircle(center, center, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kind.colorArgb })
            drawCircle(
                center,
                center,
                24f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                },
            )
            drawText(
                kind.label,
                center,
                center - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f,
                textPaint,
            )
        }
    }
}

private fun GeoPoint.toCoordinate(): PhoneMapCoordinate = PhoneMapCoordinate(latitude, longitude)

internal const val PHONE_ROUTE_TOOLS_MAX_CHAIN_POINTS = 12
