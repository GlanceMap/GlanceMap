package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.presentation.features.maps.ReliefDemRepository
import com.glancemap.glancemapwearos.presentation.features.maps.TerrainSource
import com.glancemap.glancemapwearos.presentation.features.maps.buildMapRendererThemeOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong

internal const val TERRAIN_GRID_SIZE = 64
internal const val TERRAIN_WIDTH_METERS = 4000f
internal const val TERRAIN_LOG_TAG = "Terrain3D"

internal class TerrainDemUnavailableException : IllegalStateException("Terrain DEM unavailable")

internal data class PreparedTerrain(
    val mesh: TerrainMesh,
    val bitmap: Bitmap,
    val center: LatLong,
    val widthMeters: Float,
)

internal suspend fun prepareTerrain(
    context: Context,
    source: TerrainSource,
    center: LatLong,
    gpx: TerrainGpxOverlay,
    widthMeters: Float = TERRAIN_WIDTH_METERS,
): PreparedTerrain {
    var bitmap: Bitmap? = null
    try {
        val prepared =
            withContext(Dispatchers.IO) {
                val jobContext = currentCoroutineContext()
                val start = SystemClock.elapsedRealtime()
                Log.i(TERRAIN_LOG_TAG, "initialization_start")
                val area = TerrainArea(center.latitude, center.longitude, widthMeters.toDouble())
                val dem =
                    ReliefDemRepository(
                        source.demSource.readFallbackOrder().map { Dem3CoverageUtils.demRootDir(context, it) },
                        TERRAIN_LOG_TAG,
                    )
                var demMs = 0L
                val mesh =
                    try {
                        buildTerrainMesh(TERRAIN_GRID_SIZE, widthMeters) { u, v ->
                            jobContext.ensureActive()
                            val coordinate = area.coordinate(u, v)
                            val sampleStart = SystemClock.elapsedRealtimeNanos()
                            val elevation = dem.elevationAt(coordinate.first, coordinate.second)
                            demMs += SystemClock.elapsedRealtimeNanos() - sampleStart
                            elevation
                        } ?: throw TerrainDemUnavailableException()
                    } finally {
                        dem.clear()
                    }
                val meshMs = SystemClock.elapsedRealtime() - start
                logTerrainMesh(mesh, meshMs, demMs)
                jobContext.ensureActive()
                val theme =
                    checkNotNull(
                        buildMapRendererThemeOrNull(
                            context,
                            source.themeFile,
                            source.mapsforgeThemeName,
                            source.bundledThemeId,
                            source.externalTheme,
                        ),
                    ) { "Selected theme unavailable" }
                val textureStart = SystemClock.elapsedRealtime()
                val texture =
                    renderTerrainTexture(source.mapFile, theme, area, source.textScale) {
                        jobContext.ensureActive()
                    }
                bitmap = texture
                drawTerrainGpx(texture, area, gpx) { jobContext.ensureActive() }
                val textureMs = SystemClock.elapsedRealtime() - textureStart
                logTerrainPreparation(widthMeters, meshMs, demMs, textureMs)
                jobContext.ensureActive()
                PreparedTerrain(mesh, texture, center, widthMeters)
            }
        bitmap = null
        return prepared
    } finally {
        bitmap?.recycle()
    }
}

private fun logTerrainPreparation(
    width: Float,
    meshMs: Long,
    demNanos: Long,
    textureMs: Long,
) {
    Log.i(TERRAIN_LOG_TAG, "texture_ms=$textureMs size=512x512")
    DebugTelemetry.log(TERRAIN_LOG_TAG) {
        val runtime = Runtime.getRuntime()
        "event=preparation extent_m=$width mesh_ms=$meshMs dem_ms=${demNanos / 1_000_000} " +
            "texture_ms=$textureMs heap_used_bytes=${runtime.totalMemory() - runtime.freeMemory()}"
    }
}

private fun logTerrainMesh(
    mesh: TerrainMesh,
    meshMs: Long,
    demNanos: Long,
) {
    Log.i(
        TERRAIN_LOG_TAG,
        "mesh_ms=$meshMs dem_ms=${demNanos / 1_000_000} " +
            "vertices=${mesh.vertices.size / 5} triangles=${mesh.indices.size / 3}",
    )
}
