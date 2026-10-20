package com.glancemap.glancemapcompanionapp.weather

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.glancemap.glancemapcompanionapp.map.PhoneMapLocation
import com.glancemap.glancemapcompanionapp.map.hasPhoneMapLocationPermission
import com.glancemap.glancemapcompanionapp.map.phoneMapLocationPermissions
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun rememberCurrentWeatherRequest(onPermission: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onPermission(context.hasPhoneMapLocationPermission())
        }
    return {
        if (context.hasPhoneMapLocationPermission()) {
            onPermission(true)
        } else {
            launcher.launch(phoneMapLocationPermissions)
        }
    }
}

/** A bounded, foreground one-shot request; never starts recording or a background location subscription. */
@SuppressLint("MissingPermission") // Permission is checked immediately before requesting the fix.
internal suspend fun requestCurrentWeatherLocation(context: Context, label: String): WeatherForecastLocation? {
    if (!context.hasPhoneMapLocationPermission()) return null
    val cancellation = CancellationTokenSource()
    return try {
        val location =
            withTimeoutOrNull(20000L) {
                val request =
                    CurrentLocationRequest
                        .Builder()
                        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .setMaxUpdateAgeMillis(120000L)
                        .setDurationMillis(15000L)
                        .build()
                LocationServices
                    .getFusedLocationProviderClient(context.applicationContext)
                    .getCurrentLocation(request, cancellation.token)
                    .await()
            }
        currentWeatherLocation(
            location?.let { PhoneMapLocation(it.latitude, it.longitude, null, it.elapsedRealtimeNanos / 1000000L) },
            SystemClock.elapsedRealtime(),
            label,
        )
    } finally {
        cancellation.cancel()
    }
}

internal fun currentWeatherLocation(
    location: PhoneMapLocation?,
    nowElapsedMillis: Long,
    label: String,
): WeatherForecastLocation? {
    if (location == null ||
        location.fixElapsedRealtimeMillis <= 0L ||
        nowElapsedMillis - location.fixElapsedRealtimeMillis !in 0L..120000L
    ) {
        return null
    }
    return runCatching { WeatherForecastLocation(location.latitude, location.longitude, null, label) }.getOrNull()
}
