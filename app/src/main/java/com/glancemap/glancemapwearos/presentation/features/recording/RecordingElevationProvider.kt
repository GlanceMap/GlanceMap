package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.maps.ReliefDemRepository
import com.glancemap.trailcore.recording.RecordingSettings
import com.glancemap.trailcore.recording.resolveRecordingAbsoluteElevation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.glancemap.trailcore.recording.RECORDING_ELEVATION_SOURCE_DEM_MISSING as SHARED_DEM_MISSING

class RecordingElevationProvider(
    context: Context,
) {
    private val demRepositories =
        DemSource.entries.associateWith { demSource ->
            ReliefDemRepository(
                demRootDir = Dem3CoverageUtils.demRootDir(context.applicationContext, demSource),
                tag = "TraceRecordingDem-${demSource.shortLabel}",
            )
        }

    suspend fun resolveElevation(
        latitude: Double,
        longitude: Double,
        gpsAltitudeMeters: Double?,
        source: String,
        demSource: DemSource = DemSource.DEFAULT,
    ): RecordingElevationResult =
        withContext(Dispatchers.IO) {
            val sanitizedSource = RecordingSettings.normalizeElevationSource(source)
            val demAttempted = shouldReadDem(sanitizedSource)
            val demSample =
                if (demAttempted) {
                    resolveDemSample(latitude, longitude, demSource)
                } else {
                    null
                }
            val demElevation = demSample?.elevationMeters
            val absolute = resolveRecordingAbsoluteElevation(sanitizedSource, demElevation, gpsAltitudeMeters)

            RecordingElevationResult(
                elevationMeters = absolute.elevationMeters,
                resolvedSource = absolute.source,
                demElevationMeters = demElevation,
                gpsElevationMeters = gpsAltitudeMeters?.takeIf(Double::isFinite),
                demAttempted = demAttempted,
                demHit = demElevation != null,
                gpsUsed =
                    absolute.elevationMeters != null &&
                        absolute.source == SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS,
                demTileId = demSample?.tileId,
                demAxisLen = demSample?.axisLen,
                demResolutionLabel = demSample?.resolutionLabel,
            )
        }

    private fun shouldReadDem(source: String): Boolean =
        source != SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS &&
            source != SettingsRepository.RECORDING_SOURCE_DISABLED

    private fun resolveDemSample(
        latitude: Double,
        longitude: Double,
        demSource: DemSource,
    ) = demSource
        .readFallbackOrder()
        .firstNotNullOfOrNull { candidate ->
            demRepositories[candidate]?.elevationSampleAt(latitude, longitude)
        }?.takeIf {
            it.elevationMeters.isFinite() &&
                it.elevationMeters > DEM_VOID_ELEVATION_METERS
        }
}

data class RecordingElevationResult(
    val elevationMeters: Double?,
    val resolvedSource: String,
    val demElevationMeters: Double?,
    val gpsElevationMeters: Double?,
    val demAttempted: Boolean,
    val demHit: Boolean,
    val gpsUsed: Boolean,
    val demTileId: String?,
    val demAxisLen: Int?,
    val demResolutionLabel: String?,
)

private const val DEM_VOID_ELEVATION_METERS = -10_000.0
internal const val RECORDING_ELEVATION_SOURCE_DEM_MISSING = SHARED_DEM_MISSING
