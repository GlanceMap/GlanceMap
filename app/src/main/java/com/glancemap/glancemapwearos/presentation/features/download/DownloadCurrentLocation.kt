package com.glancemap.glancemapwearos.presentation.features.download

import android.content.Context
import android.location.Location
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.core.service.location.adapters.CurrentLocationRequestParams
import com.glancemap.glancemapwearos.core.service.location.adapters.FusedLocationGateway
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

internal suspend fun requestDownloadCurrentLocation(context: Context): Location? =
    FusedLocationGateway(
        client = LocationServices.getFusedLocationProviderClient(context),
        callbackExecutor = ContextCompat.getMainExecutor(context),
    ).getCurrentLocation(
        CurrentLocationRequestParams(
            priority = Priority.PRIORITY_HIGH_ACCURACY,
            maxUpdateAgeMs = DOWNLOAD_LOCATION_MAX_CACHED_AGE_MS,
            durationMs = DOWNLOAD_LOCATION_TIMEOUT_MS,
        ),
    )

private const val DOWNLOAD_LOCATION_TIMEOUT_MS = 10_000L
private const val DOWNLOAD_LOCATION_MAX_CACHED_AGE_MS = 15_000L
