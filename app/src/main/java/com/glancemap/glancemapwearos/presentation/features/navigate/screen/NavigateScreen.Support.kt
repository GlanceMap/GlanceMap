package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPosition
import com.glancemap.glancemapwearos.presentation.features.routetools.LoopShapeMode
import com.glancemap.glancemapwearos.presentation.features.routetools.LoopStartMode
import com.glancemap.glancemapwearos.presentation.features.routetools.LoopTargetMode
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteCreateMode
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteModifyMode
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteSaveBehavior
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolKind
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolOptions
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSession
import com.glancemap.glancemapwearos.presentation.features.routetools.routeStylePresetFromSavedName
import com.glancemap.glancemapwearos.presentation.features.routetools.withVisibleLoopDefaults
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt

internal val routeToolOptionsSaver: Saver<RouteToolOptions, Any> =
    listSaver(
        save = { options ->
            listOf(
                options.toolKind.name,
                options.createMode.name,
                options.modifyMode.name,
                options.routeStyle.name,
                options.loopTargetMode.name,
                options.loopDistanceKm,
                options.loopDurationMinutes,
                options.loopShapeMode.name,
                options.loopStartMode.name,
                options.coordinateLatitude,
                options.coordinateLongitude,
                options.useElevation,
                options.allowFerries,
                options.showAdvancedOptions,
                options.saveBehavior.name,
            )
        },
        restore = { values ->
            RouteToolOptions(
                toolKind = RouteToolKind.valueOf(values[0] as String),
                createMode = RouteCreateMode.valueOf(values[1] as String),
                modifyMode = RouteModifyMode.valueOf(values[2] as String),
                routeStyle = routeStylePresetFromSavedName(values[3] as String),
                loopTargetMode = LoopTargetMode.valueOf(values[4] as String),
                loopDistanceKm = values[5] as Int,
                loopDurationMinutes = values[6] as Int,
                loopShapeMode = LoopShapeMode.valueOf(values[7] as String),
                loopStartMode = LoopStartMode.valueOf(values[8] as String),
                coordinateLatitude = values[9] as Double?,
                coordinateLongitude = values[10] as Double?,
                useElevation = values[11] as Boolean,
                allowFerries = values[12] as Boolean,
                showAdvancedOptions = values[13] as Boolean,
                saveBehavior = RouteSaveBehavior.valueOf(values[14] as String),
            ).withVisibleLoopDefaults()
        },
    )

internal val routeToolSessionSaver: Saver<RouteToolSession?, Any> =
    listSaver(
        save = { session ->
            if (session == null) {
                listOf(null)
            } else {
                listOf(
                    session.options.toolKind.name,
                    session.options.createMode.name,
                    session.options.modifyMode.name,
                    session.options.routeStyle.name,
                    session.options.loopTargetMode.name,
                    session.options.loopDistanceKm,
                    session.options.loopDurationMinutes,
                    session.options.loopShapeMode.name,
                    session.options.loopStartMode.name,
                    session.options.coordinateLatitude,
                    session.options.coordinateLongitude,
                    session.options.useElevation,
                    session.options.allowFerries,
                    session.options.showAdvancedOptions,
                    session.options.saveBehavior.name,
                    session.pointA?.latitude,
                    session.pointA?.longitude,
                    session.pointB?.latitude,
                    session.pointB?.longitude,
                    session.destination?.latitude,
                    session.destination?.longitude,
                    session.loopCenter?.latitude,
                    session.loopCenter?.longitude,
                    ArrayList(session.chainPoints.map { it.latitude }),
                    ArrayList(session.chainPoints.map { it.longitude }),
                    session.loopVariationIndex,
                    session.pointATrackPosition?.segmentIndex,
                    session.pointATrackPosition?.t,
                    session.pointBTrackPosition?.segmentIndex,
                    session.pointBTrackPosition?.t,
                )
            }
        },
        restore = { values ->
            if (values.firstOrNull() == null) {
                null
            } else {
                val options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.valueOf(values[0] as String),
                        createMode = RouteCreateMode.valueOf(values[1] as String),
                        modifyMode = RouteModifyMode.valueOf(values[2] as String),
                        routeStyle = routeStylePresetFromSavedName(values[3] as String),
                        loopTargetMode = LoopTargetMode.valueOf(values[4] as String),
                        loopDistanceKm = values[5] as Int,
                        loopDurationMinutes = values[6] as Int,
                        loopShapeMode = LoopShapeMode.valueOf(values[7] as String),
                        loopStartMode = LoopStartMode.valueOf(values[8] as String),
                        coordinateLatitude = values[9] as Double?,
                        coordinateLongitude = values[10] as Double?,
                        useElevation = values[11] as Boolean,
                        allowFerries = values[12] as Boolean,
                        showAdvancedOptions = values[13] as Boolean,
                        saveBehavior = RouteSaveBehavior.valueOf(values[14] as String),
                    ).withVisibleLoopDefaults()
                val chainLatitudes = (values[23] as ArrayList<*>).mapNotNull { it as? Double }
                val chainLongitudes = (values[24] as ArrayList<*>).mapNotNull { it as? Double }
                RouteToolSession(
                    options = options,
                    pointA = latLongOrNull(values[15], values[16]),
                    pointB = latLongOrNull(values[17], values[18]),
                    pointATrackPosition = trackPositionOrNull(values.getOrNull(26), values.getOrNull(27)),
                    pointBTrackPosition = trackPositionOrNull(values.getOrNull(28), values.getOrNull(29)),
                    destination = latLongOrNull(values[19], values[20]),
                    loopCenter = latLongOrNull(values[21], values[22]),
                    chainPoints =
                        chainLatitudes.zip(chainLongitudes) { lat, lon ->
                            LatLong(lat, lon)
                        },
                    loopVariationIndex = values.getOrNull(25) as? Int ?: 0,
                )
            }
        },
    )

internal fun latLongOrNull(
    lat: Any?,
    lon: Any?,
): LatLong? {
    val latitude = lat as? Double ?: return null
    val longitude = lon as? Double ?: return null
    return LatLong(latitude, longitude)
}

@Suppress("ReturnCount")
internal fun trackPositionOrNull(
    segmentIndex: Any?,
    t: Any?,
): TrackPosition? {
    val index = segmentIndex as? Int ?: return null
    val fraction = t as? Double ?: return null
    return TrackPosition(trackId = "", segmentIndex = index, t = fraction)
}

internal fun vibratorFrom(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

internal fun displayTargetCompassQuality(
    rawQuality: CompassMarkerQuality,
    nowElapsedMs: Long,
    lastCalibrationConfirmedAtElapsedMs: Long,
): CompassMarkerQuality {
    if (lastCalibrationConfirmedAtElapsedMs <= 0L) return rawQuality
    val sinceCalibrationMs = nowElapsedMs - lastCalibrationConfirmedAtElapsedMs
    return when {
        sinceCalibrationMs in 0 until COMPASS_POST_CALIBRATION_GREEN_HOLD_MS ->
            CompassMarkerQuality.GOOD
        rawQuality == CompassMarkerQuality.UNRELIABLE &&
            sinceCalibrationMs in 0 until COMPASS_POST_CALIBRATION_UNRELIABLE_FLOOR_MS ->
            CompassMarkerQuality.LOW
        else -> rawQuality
    }
}

internal fun applyCompassStartupWarmupGuard(
    rawQuality: CompassMarkerQuality,
    displayedQuality: CompassMarkerQuality,
    nowElapsedMs: Long,
    warmupUntilElapsedMs: Long,
): CompassMarkerQuality {
    if (nowElapsedMs >= warmupUntilElapsedMs) return rawQuality
    return if (compassMarkerQualityRank(rawQuality) < compassMarkerQualityRank(displayedQuality)) {
        displayedQuality
    } else {
        rawQuality
    }
}

internal fun compassMarkerQualityRank(quality: CompassMarkerQuality): Int =
    when (quality) {
        CompassMarkerQuality.UNRELIABLE -> 0
        CompassMarkerQuality.LOW -> 1
        CompassMarkerQuality.MEDIUM -> 2
        CompassMarkerQuality.GOOD -> 3
    }

internal fun poiFocusZoomLevel(
    mapView: MapView,
    latitude: Double,
    minZoom: Int,
    maxZoom: Int,
): Int {
    val widthPx = mapView.width
    if (widthPx <= 0) {
        return FALLBACK_POI_FOCUS_ZOOM.coerceIn(minZoom, maxZoom)
    }
    val safeLatitude = latitude.coerceIn(-85.0, 85.0)
    val desiredVisibleMeters = POI_FOCUS_TARGET_SCALE_METERS / POI_SCALE_INDICATOR_TARGET_RATIO
    val desiredMetersPerPixel = desiredVisibleMeters / widthPx.toDouble()
    if (!desiredMetersPerPixel.isFinite() || desiredMetersPerPixel <= 0.0) {
        return FALLBACK_POI_FOCUS_ZOOM.coerceIn(minZoom, maxZoom)
    }
    val latitudeScale = cos(Math.toRadians(safeLatitude))
    if (!latitudeScale.isFinite() || latitudeScale <= 0.0) {
        return FALLBACK_POI_FOCUS_ZOOM.coerceIn(minZoom, maxZoom)
    }
    val rawZoom = log2((METERS_PER_PIXEL_EQUATOR_ZOOM_0 * latitudeScale) / desiredMetersPerPixel)
    val roundedZoom =
        if (rawZoom.isFinite()) {
            rawZoom.roundToInt()
        } else {
            FALLBACK_POI_FOCUS_ZOOM
        }
    return roundedZoom.coerceIn(minZoom, maxZoom)
}

internal const val COMPASS_QUALITY_STARTUP_GRACE_MS = 2_200L
internal const val COMPASS_POST_CALIBRATION_GREEN_HOLD_MS = 5_000L
internal const val COMPASS_POST_CALIBRATION_UNRELIABLE_FLOOR_MS = 12_000L
internal const val POI_FOCUS_TARGET_SCALE_METERS = 500.0
internal const val POI_SCALE_INDICATOR_TARGET_RATIO = 0.28
internal const val METERS_PER_PIXEL_EQUATOR_ZOOM_0 = 156543.03392804097
internal const val FALLBACK_POI_FOCUS_ZOOM = 14
