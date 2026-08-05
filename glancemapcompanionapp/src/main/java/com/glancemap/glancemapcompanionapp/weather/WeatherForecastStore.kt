package com.glancemap.glancemapcompanionapp.weather

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Local forecast snapshots let the companion label and use prepared weather while offline. */
internal interface WeatherForecastStore {
    suspend fun latest(location: WeatherForecastLocation): WeatherForecast?

    suspend fun history(location: WeatherForecastLocation): List<WeatherForecast>

    suspend fun record(forecast: WeatherForecast)
}

internal class FileWeatherForecastStore(
    context: Context,
) : WeatherForecastStore {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val mutex = Mutex()
    private val directory = File(appContext.filesDir, DIRECTORY_NAME)
    private val storeFile = File(directory, STORE_FILE_NAME)

    override suspend fun latest(location: WeatherForecastLocation): WeatherForecast? = history(location).firstOrNull()

    override suspend fun history(location: WeatherForecastLocation): List<WeatherForecast> =
        mutex.withLock {
            val key = WeatherForecastStoreKey.from(location)
            readSnapshots().filter { snapshot -> WeatherForecastStoreKey.from(snapshot.location) == key }
        }

    override suspend fun record(forecast: WeatherForecast) {
        mutex.withLock {
            val key = WeatherForecastStoreKey.from(forecast.location)
            val remaining =
                readSnapshots()
                    .filterNot { snapshot ->
                        WeatherForecastStoreKey.from(snapshot.location) == key &&
                            snapshot.fetchedAtEpochMillis == forecast.fetchedAtEpochMillis
                    }.let { snapshots ->
                        val sameLocation =
                            snapshots.filter { snapshot -> WeatherForecastStoreKey.from(snapshot.location) == key }
                        val otherLocations =
                            snapshots.filter { snapshot -> WeatherForecastStoreKey.from(snapshot.location) != key }
                        otherLocations + sameLocation.take(MAX_SNAPSHOTS_PER_LOCATION - 1)
                    }
            writeSnapshots((listOf(forecast) + remaining).take(MAX_TOTAL_SNAPSHOTS))
        }
    }

    private fun readSnapshots(): List<WeatherForecast> {
        if (!storeFile.isFile) return emptyList()
        return runCatching {
            storeFile.reader().use { reader ->
                gson.fromJson(reader, WeatherForecastSnapshotIndex::class.java)?.snapshots.orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    private fun writeSnapshots(snapshots: List<WeatherForecast>) {
        directory.mkdirs()
        val temporaryFile = File(directory, "$STORE_FILE_NAME.tmp")
        temporaryFile.writer().use { writer -> gson.toJson(WeatherForecastSnapshotIndex(snapshots), writer) }
        if (!temporaryFile.renameTo(storeFile)) {
            temporaryFile.copyTo(storeFile, overwrite = true)
            temporaryFile.delete()
        }
    }

    private data class WeatherForecastSnapshotIndex(
        val snapshots: List<WeatherForecast> = emptyList(),
    )

    private data class WeatherForecastStoreKey(
        val latitudeBucket: Int,
        val longitudeBucket: Int,
        val elevationBucket: Int?,
    ) {
        companion object {
            fun from(location: WeatherForecastLocation): WeatherForecastStoreKey =
                WeatherForecastStoreKey(
                    latitudeBucket = (location.latitude * CACHE_COORDINATE_SCALE).toInt(),
                    longitudeBucket = (location.longitude * CACHE_COORDINATE_SCALE).toInt(),
                    elevationBucket = location.elevationMeters?.toInt(),
                )
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "weather-forecasts"
        const val STORE_FILE_NAME = "snapshots.json"
        const val CACHE_COORDINATE_SCALE = 1_000.0
        const val MAX_SNAPSHOTS_PER_LOCATION = 12
        const val MAX_TOTAL_SNAPSHOTS = 96
    }
}
