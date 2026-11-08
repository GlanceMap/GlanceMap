package com.glancemap.glancemapwearos.presentation.features.maps

import org.mapsforge.core.graphics.Bitmap

internal data class DemTileData(
    override val axisLen: Int,
    val rowLen: Int,
    val samples: ShortArray,
) : DemTile {
    override val heapBytes: Long get() = samples.size.toLong() * Short.SIZE_BYTES

    override fun elevationOrNaN(
        tileLat: Int,
        tileLon: Int,
        lat: Double,
        lon: Double,
    ): Double = interpolateDemElevationOrNaN(this, tileLat, tileLon, lat, lon)
}

internal data class OverlayTileKey(
    val zoom: Byte,
    val tileX: Long,
    val tileY: Long,
    val tileSize: Int,
)

internal data class OverlayTileEntry(
    val bitmap: Bitmap?,
    val builtElapsedMs: Long,
    val status: OverlayTileStatus,
    val drawMode: OverlayTileDrawMode = OverlayTileDrawMode.STEADY,
    val quality: OverlayBuildQuality = OverlayBuildQuality.FINE,
)

internal enum class OverlayTileStatus {
    READY,
    NO_DATA,
    FAILED,
}

internal enum class OverlayTileDrawMode {
    STEADY,
    FADE_FROM_FALLBACK,
}

internal enum class OverlayBuildQuality(
    val rank: Int,
) {
    COARSE(0),
    FINE(1),
}
