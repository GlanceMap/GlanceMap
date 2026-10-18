package com.glancemap.glancemapwearos.core.service.location.adapters

import android.Manifest
import android.annotation.SuppressLint
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import java.util.concurrent.Executor

@RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
internal fun LocationManager.requestLocationUpdatesCompat(
    provider: String,
    request: LocationUpdateRequestParams,
    callbackExecutor: Executor,
    listener: LocationListenerCompat,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        requestLocationUpdates(provider, request.intervalMs, request.minDistanceMeters, callbackExecutor, listener)
    } else {
        LocationManagerCompat.requestLocationUpdates(
            this,
            provider,
            LocationRequestCompat
                .Builder(request.intervalMs)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .setMinUpdateDistanceMeters(request.minDistanceMeters)
                .build(),
            callbackExecutor,
            listener,
        )
    }
}

@SuppressLint("MissingPermission") // Cleanup must also run after permission revocation; callers handle failures.
internal fun LocationManager.removeLocationUpdatesCompat(listener: LocationListenerCompat) {
    LocationManagerCompat.removeUpdates(this, listener)
}
