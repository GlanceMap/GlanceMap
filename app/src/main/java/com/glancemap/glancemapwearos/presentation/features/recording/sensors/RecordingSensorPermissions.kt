package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import android.Manifest
import android.health.connect.HealthPermissions
import android.os.Build

internal fun recordingHeartRatePermission(sdkInt: Int = Build.VERSION.SDK_INT): String =
    if (sdkInt >= Build.VERSION_CODES.BAKLAVA) {
        HealthPermissions.READ_HEART_RATE
    } else {
        Manifest.permission.BODY_SENSORS
    }
