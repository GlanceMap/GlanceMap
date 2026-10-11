package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterConfig

// List summaries remain useful after the much larger profiles and ETA projections are evicted.
internal data class GpxLibrarySummary(
    val sig: FileSig,
    val elevationFilterConfig: GpxElevationFilterConfig,
    val etaModelConfig: GpxEtaModelConfig,
    val fileState: GpxFileState,
    // DEM-derived elevations can change without the GPX file changing.
    val requiresDemRecovery: Boolean,
)

internal suspend fun GpxLibrarySummary?.loadOrReuseGpxFileState(
    sig: FileSig,
    elevationFilterConfig: GpxElevationFilterConfig,
    etaModelConfig: GpxEtaModelConfig,
    isActive: Boolean,
    load: suspend () -> GpxFileState,
): GpxFileState {
    val cached =
        this?.takeIf { summary ->
            summary.sig == sig &&
                summary.elevationFilterConfig == elevationFilterConfig &&
                summary.etaModelConfig == etaModelConfig &&
                !summary.requiresDemRecovery
        }
    return if (cached == null) {
        load()
    } else {
        val state = cached.fileState
        if (state.isActive == isActive) state else state.copy(isActive = isActive)
    }
}
