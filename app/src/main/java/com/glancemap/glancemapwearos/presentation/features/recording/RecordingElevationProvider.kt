package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.maps.ReliefDemRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            val sanitizedSource = sanitizeElevationSource(source)
            val demAttempted = shouldReadDem(sanitizedSource)
            val demSample =
                if (demAttempted) {
                    resolveDemSample(latitude, longitude, demSource)
                } else {
                    null
                }
            val demElevation = demSample?.elevationMeters
            val elevation = resolveElevationValue(sanitizedSource, demElevation, gpsAltitudeMeters)
            val resolvedSource = resolveElevationSource(sanitizedSource, demElevation, gpsAltitudeMeters)

            RecordingElevationResult(
                elevationMeters = elevation,
                resolvedSource = resolvedSource,
                demElevationMeters = demElevation,
                gpsElevationMeters = gpsAltitudeMeters?.takeIf(Double::isFinite),
                demAttempted = demAttempted,
                demHit = demElevation != null,
                gpsUsed = elevation != null && resolvedSource == SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS,
                demTileId = demSample?.tileId,
                demAxisLen = demSample?.axisLen,
                demResolutionLabel = demSample?.resolutionLabel,
            )
        }

    private fun sanitizeElevationSource(source: String): String =
        when (source) {
            SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM,
            SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO,
            SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS,
            SettingsRepository.RECORDING_SOURCE_DISABLED,
            -> source
            else -> SettingsRepository.DEFAULT_RECORDING_ELEVATION_SOURCE
        }

    private fun shouldReadDem(source: String): Boolean =
        source != SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS &&
            source != SettingsRepository.RECORDING_SOURCE_DISABLED

    private fun resolveElevationValue(
        source: String,
        demElevation: Double?,
        gpsElevation: Double?,
    ): Double? =
        when (source) {
            SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM -> demElevation
            SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO -> demElevation ?: gpsElevation
            SettingsRepository.RECORDING_SOURCE_DISABLED -> null
            else -> gpsElevation
        }

    private fun resolveElevationSource(
        source: String,
        demElevation: Double?,
        gpsElevation: Double?,
    ): String =
        when {
            source == SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM && demElevation != null ->
                SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM
            source == SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM -> RECORDING_ELEVATION_SOURCE_DEM_MISSING
            source == SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO && demElevation != null ->
                SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM
            source == SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO && gpsElevation != null ->
                SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS
            source == SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS && gpsElevation != null ->
                SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS
            else -> source
        }

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
internal const val RECORDING_ELEVATION_SOURCE_DEM_MISSING = "DEM_MISSING"
