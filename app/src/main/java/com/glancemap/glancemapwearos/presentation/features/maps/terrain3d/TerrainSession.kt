package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.glancemap.glancemapwearos.core.maps.DEFAULT_TERRAIN_3D_VIEWING_ANGLE
import com.glancemap.glancemapwearos.core.maps.sanitizeTerrain3dViewingAngle
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.presentation.features.maps.MapHolder
import com.glancemap.glancemapwearos.presentation.features.maps.TerrainSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.mapsforge.core.model.LatLong
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.hypot

internal inline fun installIfCurrent(
    revision: AtomicLong,
    expected: Long,
    install: () -> Unit,
): Boolean =
    synchronized(revision) {
        if (revision.get() != expected) {
            false
        } else {
            install()
            true
        }
    }

@Suppress("TooManyFunctions")
internal class TerrainSession {
    var view by mutableStateOf<TerrainGlView?>(null)
    var center by mutableStateOf<LatLong?>(null)
    var requestedCenter by mutableStateOf<LatLong?>(null)
    var dragging by mutableStateOf(false)
    var gpx by mutableStateOf<TerrainGpxOverlay?>(null)
        private set
    var renderedCenter: LatLong? = null
    var renderedGpx: TerrainGpxOverlay? = null
    var onUserPanStarted: () -> Unit = {}
    var failed by mutableStateOf(false)
    var demUnavailable by mutableStateOf(false)
    var firstFrame by mutableStateOf(false)
    var initializing by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    val isLoading: Boolean
        get() = !failed && (initializing || refreshing)
    var startedAt = 0L
    var telemetrySessionId = "unavailable"
    var telemetryContext = "unavailable"
    var telemetryPans = 0L
    var telemetryZoomUpdates = 0L
    var telemetryOrbitUpdates = 0L
    var telemetryRecenters = 0L
    var viewingAngle = DEFAULT_TERRAIN_3D_VIEWING_ANGLE
        private set
    var interactiveViewingAngle = viewingAngle.toFloat()
        private set
    private var configuredViewingAngle: Int? = null
    var widthMeters = TERRAIN_WIDTH_METERS
    var requestedWidthMeters by mutableStateOf(TERRAIN_WIDTH_METERS)
    var pendingRecenter: LatLong? = null
        private set
    val revision = AtomicLong()
    private var panNotified = false

    private fun invalidatePreparation() = synchronized(revision) { revision.incrementAndGet() }

    val safeRadius: Float
        get() =
            view?.let { terrainSafeRadius(widthMeters, it.terrainCameraDistance, interactiveViewingAngle) }
                ?: widthMeters * 0.25f

    fun updateGpx(overlay: TerrainGpxOverlay) {
        if (gpx != overlay) {
            invalidatePreparation()
            gpx = overlay
        }
    }

    fun setViewingAngle(angle: Int) {
        val next = sanitizeTerrain3dViewingAngle(angle)
        if (configuredViewingAngle == next) return
        configuredViewingAngle = next
        viewingAngle = next
        interactiveViewingAngle = next.toFloat()
        view?.setViewingAngle(next)
    }

    fun setInteractiveViewingAngle(angle: Float) {
        if (angle.isFinite()) interactiveViewingAngle = angle.coerceIn(45f, 70f)
    }

    fun resetInteractiveViewingAngle() {
        interactiveViewingAngle = viewingAngle.toFloat()
    }

    fun requestZoomExtent(distance: Float) {
        val next = terrainPreparedWidth(distance, interactiveViewingAngle)
        if (abs(next - requestedWidthMeters) < 1f) return
        invalidatePreparation()
        requestedWidthMeters = next
        requestedCenter = center ?: renderedCenter
    }

    fun finishPan(
        base: LatLong,
        east: Float,
        north: Float,
    ) {
        updatePan(base, east, north, true)
        dragging = false
        panNotified = false
    }

    @Suppress("CyclomaticComplexMethod")
    fun updatePan(
        base: LatLong,
        east: Float,
        north: Float,
        force: Boolean = false,
    ) {
        if (abs(east) > 0.01f || abs(north) > 0.01f) {
            try {
                if (dragging && pendingRecenter != null) {
                    pendingRecenter = null
                    invalidatePreparation()
                    requestedCenter = base
                }
                val destination = terrainPanDestination(base, east, north, widthMeters, safeRadius)
                center = destination.center
                if (dragging && !panNotified) {
                    panNotified = true
                    onUserPanStarted()
                }
                val requested = requestedCenter
                val movedSinceRequest =
                    requested?.let {
                        val area = TerrainArea(base.latitude, base.longitude, widthMeters.toDouble())
                        val (oldEast, oldNorth) = area.metersForCoordinate(it.latitude, it.longitude)
                        hypot((east - oldEast).toDouble(), (north - oldNorth).toDouble())
                    } ?: Double.POSITIVE_INFINITY
                val movedEnough = movedSinceRequest >= widthMeters * 0.1 || (force && movedSinceRequest > 0.01)
                if (destination.requiresRefresh && movedEnough) {
                    invalidatePreparation()
                    requestedCenter = destination.center
                } else if (!destination.requiresRefresh && requestedCenter != base) {
                    invalidatePreparation()
                    requestedCenter = base
                }
            } catch (error: IllegalArgumentException) {
                Log.w(TERRAIN_LOG_TAG, "terrain pan outside supported area", error)
            }
        }
    }

    fun recenter(target: LatLong) {
        val base = renderedCenter ?: return
        telemetryRecenters++
        val area = TerrainArea(base.latitude, base.longitude, widthMeters.toDouble())
        val (east, north) = area.metersForCoordinate(target.latitude, target.longitude)
        center = target
        if (hypot(east.toDouble(), north.toDouble()) >= safeRadius) {
            if (pendingRecenter != target) {
                pendingRecenter = target
                invalidatePreparation()
                requestedCenter = target
            }
        } else {
            pendingRecenter = null
            invalidatePreparation()
            requestedCenter = base
            view?.setTarget(target)
        }
    }

    fun completeRecenter(target: LatLong) {
        if (pendingRecenter == target) pendingRecenter = null
    }

    fun fail(error: Throwable) {
        if (failed) return
        Log.w(TERRAIN_LOG_TAG, "fallback_to_2d reason=${error.message ?: error.javaClass.simpleName}", error)
        failed = true
    }
}

@Composable
internal fun rememberTerrainSession(
    enabled: Boolean,
    holder: MapHolder?,
    mapPath: String?,
    mapAppearanceInProgress: Boolean,
    interactive: Boolean,
): TerrainSession {
    val context = LocalContext.current
    val session = remember(holder, mapPath) { TerrainSession() }
    LaunchedEffect(enabled) {
        if (!enabled) {
            session.failed = false
            session.resetInteractiveViewingAngle()
        }
    }
    val canPrepare = interactive || session.view != null
    LaunchedEffect(enabled, holder, mapPath, mapAppearanceInProgress, session.failed, canPrepare) {
        if (!enabled || holder == null || !canPrepare) return@LaunchedEffect
        if (mapAppearanceInProgress || session.failed) return@LaunchedEffect
        if (mapPath.isNullOrBlank()) {
            session.fail(IllegalStateException("No map selected"))
            return@LaunchedEffect
        }
        runTerrainSession(session, holder, mapPath, context)
    }
    LaunchedEffect(session.view, interactive) {
        val view = session.view ?: return@LaunchedEffect
        view.setActive(interactive)
        if (interactive && !session.firstFrame) {
            delay(10_000)
            if (!session.firstFrame) session.fail(IllegalStateException("OpenGL first-frame timeout"))
        }
    }
    terrainTelemetryEffect(session, enabled, interactive)
    terrainRefreshEffect(context, session, holder, mapPath, interactive)
    return session
}

@Composable
private fun terrainTelemetryEffect(
    session: TerrainSession,
    enabled: Boolean,
    interactive: Boolean,
) {
    LaunchedEffect(enabled, session.view, interactive) {
        if (!enabled || session.view == null || !interactive) return@LaunchedEffect
        var previousDraws = session.view?.drawCount ?: 0L
        while (true) {
            delay(60_000)
            val draws = session.view?.drawCount ?: previousDraws
            val runtime = Runtime.getRuntime()
            DebugTelemetry.log(
                TERRAIN_LOG_TAG,
                "event=interval renderer=custom session_id=${session.telemetrySessionId} " +
                    "draws=${draws - previousDraws} redraws=unavailable ${session.telemetryContext} " +
                    "pan=${session.telemetryPans} zoom_updates=${session.telemetryZoomUpdates} " +
                    "orbit_updates=${session.telemetryOrbitUpdates} recenter=${session.telemetryRecenters} " +
                    "heap_used=${runtime.totalMemory() - runtime.freeMemory()} " +
                    "heap_max=${runtime.maxMemory()} internal_gpu_bytes=unavailable",
            )
            previousDraws = draws
        }
    }
}

@Composable
private fun terrainRefreshEffect(
    context: Context,
    session: TerrainSession,
    holder: MapHolder?,
    mapPath: String?,
    interactive: Boolean,
) {
    LaunchedEffect(session.view, session.requestedCenter, session.requestedWidthMeters, session.gpx, interactive) {
        val currentView = session.view ?: return@LaunchedEffect
        val currentGpx = session.gpx ?: return@LaunchedEffect
        if (!interactive) return@LaunchedEffect
        val width = session.requestedWidthMeters
        val appearanceChanged = currentGpx != session.renderedGpx || width != session.widthMeters
        val target = if (appearanceChanged) session.center else session.requestedCenter
        if (target == null || (target == session.renderedCenter && !appearanceChanged)) {
            return@LaunchedEffect
        }
        if (holder != null && !mapPath.isNullOrBlank()) {
            if (width != session.widthMeters) delay(250)
            session.refreshing = true
            try {
                updateTerrainSession(context, session, holder, mapPath, currentView, target, currentGpx, width)
            } finally {
                session.refreshing = false
            }
        }
    }
}

private suspend fun waitForTerrainSource(
    holder: MapHolder,
    mapPath: String,
): TerrainSource {
    repeat(40) {
        val source = holder.renderer.terrainSource()
        if (source?.mapFile?.absolutePath == mapPath) return source
        delay(250)
    }
    error("Selected map is not ready")
}

// File, theme, and platform failures all use the same 2D fallback.
@Suppress("TooGenericExceptionCaught", "LongMethod")
private suspend fun runTerrainSession(
    session: TerrainSession,
    holder: MapHolder,
    mapPath: String,
    context: Context,
) {
    val telemetrySession = UUID.randomUUID().toString()
    session.telemetrySessionId = telemetrySession
    session.telemetryPans = 0
    session.telemetryZoomUpdates = 0
    session.telemetryOrbitUpdates = 0
    session.telemetryRecenters = 0
    session.startedAt = SystemClock.elapsedRealtime()
    DebugTelemetry.log(TERRAIN_LOG_TAG, "event=session_start renderer=custom session_id=$telemetrySession")
    session.firstFrame = false
    session.initializing = true
    var view: TerrainGlView? = null
    var bitmap: Bitmap? = null
    try {
        val graphics = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        check(graphics != null && graphics.deviceConfigurationInfo.reqGlEsVersion >= 0x20000) {
            "OpenGL ES 2.0 is unavailable"
        }
        val source = waitForTerrainSource(holder, mapPath)
        val center = holder.mapView.model.mapViewPosition.center
        val gpx = snapshotFlow { session.gpx }.filterNotNull().first()
        session.telemetryContext = "map=${source.mapFile.name} theme=${source.bundledThemeId} " +
            "dem_selected=${source.demSource.id} dem_actual=unavailable axis=unavailable " +
            "angle=${session.viewingAngle} hill=${source.hillShadingEnabled} gpx=${gpx.tracks.isNotEmpty()}"
        session.widthMeters =
            terrainPreparedWidth(INITIAL_TERRAIN_CAMERA_DISTANCE, session.interactiveViewingAngle).coerceAtMost(12000f)
        session.requestedWidthMeters = session.widthMeters
        DebugTelemetry.log(TERRAIN_LOG_TAG) {
            "event=session_start angle=${session.interactiveViewingAngle} " +
                "lighting=${source.hillShadingEnabled} extent_m=${session.widthMeters}"
        }
        val prepared = prepareTerrain(context.applicationContext, source, center, gpx, session.widthMeters)
        bitmap = prepared.bitmap
        view =
            TerrainGlView(
                context,
                prepared.mesh,
                prepared.bitmap,
                viewingAngle = session.viewingAngle,
                initialCenter = center,
                initialWidth = session.widthMeters,
                hillShadingEnabled = source.hillShadingEnabled,
                callbacks =
                    TerrainCallbacks(
                        onPanStarted = {
                            session.telemetryPans++
                            session.dragging = true
                        },
                        onPanFinished = session::finishPan,
                        onPanMoved = { base, east, north -> session.updatePan(base, east, north) },
                        onTiltChanged = { angle ->
                            session.telemetryOrbitUpdates++
                            session.setInteractiveViewingAngle(angle)
                        },
                        onZoomChanged = { distance ->
                            session.telemetryZoomUpdates++
                            session.requestZoomExtent(distance)
                        },
                        onFailure = { error -> if (session.view === view) session.fail(error) },
                        onFirstFrame = {
                            if (session.view === view && !session.failed) {
                                session.firstFrame = true
                                session.initializing = false
                                val elapsed = SystemClock.elapsedRealtime() - session.startedAt
                                Log.i(TERRAIN_LOG_TAG, "initialization_complete switch_2d_to_3d_ms=$elapsed")
                                DebugTelemetry.log(
                                    TERRAIN_LOG_TAG,
                                    "event=first_frame renderer=custom " +
                                        "session_id=$telemetrySession elapsed_ms=$elapsed",
                                )
                            }
                        },
                    ),
            )
        view.setInteractiveViewingAngle(session.interactiveViewingAngle)
        bitmap = null
        session.center = prepared.center
        session.requestedCenter = prepared.center
        session.renderedCenter = prepared.center
        session.renderedGpx = gpx
        session.view = view
        awaitCancellation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TerrainDemUnavailableException) {
        session.demUnavailable = true
        session.fail(error)
    } catch (error: Exception) {
        session.fail(error)
    } catch (error: OutOfMemoryError) {
        session.fail(error)
    } finally {
        val currentView = session.view
        session.view = null
        session.renderedCenter = null
        session.renderedGpx = null
        session.dragging = false
        session.initializing = false
        bitmap?.recycle()
        if (currentView !== view) currentView?.release()
        view?.release()
        DebugTelemetry.log(TERRAIN_LOG_TAG, "event=session_end renderer=custom session_id=$telemetrySession")
    }
}

// Prepare the next window while the current GL surface remains visible.
@Suppress("TooGenericExceptionCaught", "LongParameterList")
private suspend fun updateTerrainSession(
    context: Context,
    session: TerrainSession,
    holder: MapHolder,
    mapPath: String,
    currentView: TerrainGlView,
    center: LatLong,
    gpx: TerrainGpxOverlay,
    width: Float,
) {
    var bitmap: Bitmap? = null
    val revision = session.revision.get()
    val start = SystemClock.elapsedRealtime()
    try {
        Log.i(
            TERRAIN_LOG_TAG,
            "terrain_window_refresh_requested old=${session.renderedCenter} new=$center " +
                "extent=${session.widthMeters} threshold=${session.safeRadius}",
        )
        val source = waitForTerrainSource(holder, mapPath)
        val prepared = prepareTerrain(context.applicationContext, source, center, gpx, width)
        bitmap = prepared.bitmap
        if (session.view !== currentView || session.failed || session.revision.get() != revision) return
        val recenterTarget = session.pendingRecenter?.takeIf { it == center }
        currentView.replaceTerrain(prepared, recenterTarget, session.revision, revision) {
            if (session.view === currentView) {
                session.renderedCenter = center
                session.renderedGpx = gpx
                session.widthMeters = width
                if (session.revision.get() == revision) {
                    session.requestedCenter = center
                    if (recenterTarget != null) session.completeRecenter(recenterTarget)
                    val (base, east, north) = currentView.currentPanState()
                    session.updatePan(base, east, north, true)
                }
                Log.i(TERRAIN_LOG_TAG, "terrain_swap_completed preparation_ms=${SystemClock.elapsedRealtime() - start}")
                DebugTelemetry.log(TERRAIN_LOG_TAG) {
                    "event=window_swap elapsed_ms=${SystemClock.elapsedRealtime() - start} extent_m=$width"
                }
            }
        }
        bitmap = null
    } catch (cancelled: CancellationException) {
        Log.i(TERRAIN_LOG_TAG, "obsolete_terrain_preparation_cancelled")
        DebugTelemetry.log(TERRAIN_LOG_TAG, "event=preparation_cancelled")
        throw cancelled
    } catch (error: Exception) {
        Log.w(TERRAIN_LOG_TAG, "terrain refresh failed; keeping current terrain", error)
        DebugTelemetry.log(TERRAIN_LOG_TAG, "event=refresh_failed reason=${error.javaClass.simpleName}")
    } catch (error: OutOfMemoryError) {
        Log.w(TERRAIN_LOG_TAG, "terrain refresh failed; keeping current terrain", error)
    } finally {
        bitmap?.recycle()
    }
}
