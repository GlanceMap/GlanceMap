package com.glancemap.glancemapwearos.presentation.features.recording.health

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Read-only physical-device probe: does not register sensors, launch UI, or alter an exercise. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class RecordingHealthCapabilitiesTest {
    @Test
    fun reportCapabilitiesAndCurrentExercise() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val client = HealthServices.getClient(context).exerciseClient
        val capabilities = client.getCapabilitiesAsync().get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val current = client.getCurrentExerciseInfoAsync().get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val device =
            JSONObject()
                .put("event", "device")
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("targetPackage", context.packageName)
                .put(
                    "healthServicesVersion",
                    context.packageManager.getPackageInfo(HEALTH_SERVICES_PACKAGE, 0).versionName,
                )
        Log.i(TAG, device.toString())
        Log.i(
            TAG,
            JSONObject()
                .put("event", "supported_exercises")
                .put("types", JSONArray(capabilities.supportedExerciseTypes.map { it.name }.sorted()))
                .toString(),
        )
        val exercises = JSONArray()
        for ((label, type) in listOf(
            "HIKE_HIKING" to ExerciseType.HIKING,
            "HIKE_WALKING" to ExerciseType.WALKING,
            "BIKE" to ExerciseType.BIKING,
        )) {
            val supported = type in capabilities.supportedExerciseTypes
            val entry =
                JSONObject()
                    .put("event", "exercise_capability")
                    .put("activity", label)
                    .put("type", type.name)
                    .put("supported", supported)
            if (supported) {
                val typeCapabilities = capabilities.getExerciseTypeCapabilities(type)
                val data = typeCapabilities.supportedDataTypes
                entry
                    .put("heartRate", DataType.HEART_RATE_BPM in data)
                    .put("stepTotals", DataType.STEPS_TOTAL in data)
                    .put("stepIntervals", DataType.STEPS in data)
                    .put("cadence", DataType.STEPS_PER_MINUTE in data)
                    .put("autoPause", typeCapabilities.supportsAutoPauseAndResume)
                    .put("dataTypes", JSONArray(data.map { it.toString() }.sorted()))
            }
            exercises.put(entry)
            Log.i(TAG, entry.toString())
        }
        val status =
            JSONObject()
                .put("event", "batching_and_ownership")
                .put(
                    "heartRate5Seconds",
                    BatchingMode.HEART_RATE_5_SECONDS in capabilities.supportedBatchingModeOverrides,
                ).put("batchingModeCount", capabilities.supportedBatchingModeOverrides.size)
                .put("exerciseTrackedStatus", current.exerciseTrackedStatus)
                .put("exerciseType", current.exerciseType.name)
        Log.i(TAG, status.toString())
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("healthServicesDevice", device.toString())
                putString("healthServicesExerciseCapabilities", exercises.toString())
                putString("healthServicesBatchingAndOwnership", status.toString())
            },
        )
    }

    private companion object {
        const val TAG = "RecordingHealthProbe"
        const val QUERY_TIMEOUT_SECONDS = 30L
        const val HEALTH_SERVICES_PACKAGE = "com.google.android.wearable.healthservices"
    }
}
