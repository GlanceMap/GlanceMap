package com.glancemap.glancemapwearos.domain.sensors

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class CompassDeclinationController(
    private val appContext: Context,
    private val locationManager: LocationManager,
    private val onStatusChanged: () -> Unit,
    private val logDiagnostics: (String) -> Unit,
) {
    private val _declination = MutableStateFlow<Float?>(null)
    val declination = _declination.asStateFlow()

    private var declinationDeg: Float? = null
    private var lastDeclinationLat: Double = Double.NaN
    private var lastDeclinationLon: Double = Double.NaN
    private var lastDeclinationAtElapsedMs: Long = 0L
    private var seededDeclinationNeedsLiveRefresh: Boolean = false
    private var loggedDeclinationUnavailable = false
    private val declinationCachePrefs: SharedPreferences =
        appContext.getSharedPreferences(DECLINATION_CACHE_PREFS_NAME, Context.MODE_PRIVATE)

    val currentDeclination: Float?
        get() = declinationDeg

    val hasDeclination: Boolean
        get() = declinationDeg != null

    fun primeFromApproximateLocation(
        latitude: Double,
        longitude: Double,
        altitudeM: Float = 0f,
    ) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        if (declinationDeg != null) return
        val nowWallMs = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val field =
            GeomagneticField(
                latitude.toFloat(),
                longitude.toFloat(),
                altitudeM,
                nowWallMs,
            )
        applyDeclination(
            declinationDeg = field.declination,
            latitude = latitude,
            longitude = longitude,
            nowElapsedMs = nowElapsedMs,
            sourceTimestampMs = nowWallMs,
            markNeedsLiveRefresh = true,
        )
        logDiagnostics(
            "declination primed from approximate location " +
                "deg=${field.declination.format(2)} " +
                "lat=${latitude.format(5)} lon=${longitude.format(5)}",
        )
    }

    fun updateFromLocation(location: Location) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val forceRefreshAfterSeed = seededDeclinationNeedsLiveRefresh
        if (!forceRefreshAfterSeed && lastDeclinationAtElapsedMs != 0L) {
            val dtMs = nowElapsedMs - lastDeclinationAtElapsedMs
            val distanceM =
                distanceMeters(
                    lat1 = lastDeclinationLat,
                    lon1 = lastDeclinationLon,
                    lat2 = location.latitude,
                    lon2 = location.longitude,
                )
            if (
                dtMs < DECLINATION_REFRESH_MIN_INTERVAL_MS &&
                distanceM < DECLINATION_REFRESH_MIN_DISTANCE_M
            ) {
                return
            }
        }

        val timestampMs = if (location.time > 0L) location.time else System.currentTimeMillis()
        val altitudeM = if (location.hasAltitude()) location.altitude.toFloat() else 0f

        val field =
            GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                altitudeM,
                timestampMs,
            )
        applyDeclination(
            declinationDeg = field.declination,
            latitude = location.latitude,
            longitude = location.longitude,
            nowElapsedMs = nowElapsedMs,
            sourceTimestampMs = timestampMs,
            markNeedsLiveRefresh = false,
        )
        logDiagnostics(
            "declination updated deg=${field.declination.format(2)} " +
                "lat=${location.latitude.format(5)} lon=${location.longitude.format(5)} " +
                "forced=$forceRefreshAfterSeed",
        )
    }

    fun maybeInitializeFromLastKnownLocation() {
        if (!hasAnyLocationPermission()) {
            logDiagnostics("declination seed skipped: location permission missing")
            return
        }
        if (lastDeclinationAtElapsedMs != 0L) return

        val nowWallMs = System.currentTimeMillis()
        val candidate =
            runCatching {
                locationManager
                    .getProviders(true)
                    .asSequence()
                    .mapNotNull { provider ->
                        readLastKnownLocationSafely(provider)
                    }.filter { location ->
                        val ageMs = nowWallMs - location.time
                        val accuracyM = location.accuracy
                        ageMs in 0..MAX_DECLINATION_SEED_LOCATION_AGE_MS &&
                            location.hasAccuracy() &&
                            accuracyM.isFinite() &&
                            accuracyM in 0f..MAX_DECLINATION_SEED_LOCATION_ACCURACY_M
                    }.maxByOrNull { it.time }
            }.getOrNull()

        if (candidate != null) {
            val timestampMs = if (candidate.time > 0L) candidate.time else nowWallMs
            val altitudeM = if (candidate.hasAltitude()) candidate.altitude.toFloat() else 0f
            val field =
                GeomagneticField(
                    candidate.latitude.toFloat(),
                    candidate.longitude.toFloat(),
                    altitudeM,
                    timestampMs,
                )
            applyDeclination(
                declinationDeg = field.declination,
                latitude = candidate.latitude,
                longitude = candidate.longitude,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                sourceTimestampMs = timestampMs,
                markNeedsLiveRefresh = true,
            )
            logDiagnostics(
                "declination seeded from last known location " +
                    "deg=${field.declination.format(2)} " +
                    "ageMs=${(nowWallMs - candidate.time)} " +
                    "accM=${candidate.accuracy.format(1)}",
            )
        } else {
            logDiagnostics("declination seed unavailable")
        }
    }

    fun maybeInitializeFromCache() {
        if (lastDeclinationAtElapsedMs != 0L) return
        val cachedDeclination =
            declinationCachePrefs.getFloat(
                PREF_KEY_DECLINATION_DEG,
                Float.NaN,
            )
        if (!cachedDeclination.isFinite()) return
        if (!declinationCachePrefs.contains(PREF_KEY_LAT_BITS) ||
            !declinationCachePrefs.contains(PREF_KEY_LON_BITS)
        ) {
            return
        }
        val sourceTimestampMs = declinationCachePrefs.getLong(PREF_KEY_SOURCE_TIMESTAMP_MS, 0L)
        val nowWallMs = System.currentTimeMillis()
        if (sourceTimestampMs <= 0L ||
            (nowWallMs - sourceTimestampMs) !in 0L..MAX_DECLINATION_CACHE_AGE_MS
        ) {
            return
        }
        val latitude = Double.fromBits(declinationCachePrefs.getLong(PREF_KEY_LAT_BITS, 0L))
        val longitude = Double.fromBits(declinationCachePrefs.getLong(PREF_KEY_LON_BITS, 0L))
        if (!latitude.isFinite() || !longitude.isFinite()) return
        applyDeclination(
            declinationDeg = cachedDeclination,
            latitude = latitude,
            longitude = longitude,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            sourceTimestampMs = sourceTimestampMs,
            markNeedsLiveRefresh = true,
        )
        logDiagnostics(
            "declination restored from cache " +
                "deg=${cachedDeclination.format(2)} " +
                "ageMs=${(nowWallMs - sourceTimestampMs)}",
        )
    }

    fun resolveCorrection(northReferenceMode: NorthReferenceMode): Float? {
        if (northReferenceMode != NorthReferenceMode.TRUE) return null
        val correction = declinationDeg
        if (correction != null) {
            loggedDeclinationUnavailable = false
            return correction
        }
        if (!loggedDeclinationUnavailable) {
            loggedDeclinationUnavailable = true
            logDiagnostics("declination unavailable; temporary fallback to magnetic north")
        }
        return null
    }

    fun headingSensorHeadingWithNorthReference(
        northReferenceMode: NorthReferenceMode,
        headingDeg: Float,
    ): Float {
        val normalized = normalize360Deg(headingDeg)
        return when (northReferenceMode) {
            NorthReferenceMode.TRUE -> normalized
            NorthReferenceMode.MAGNETIC -> {
                val correction = declinationDeg
                if (correction != null) {
                    loggedDeclinationUnavailable = false
                    normalize360Deg(normalized - correction)
                } else {
                    if (!loggedDeclinationUnavailable) {
                        loggedDeclinationUnavailable = true
                        logDiagnostics(
                            "declination unavailable; heading sensor fallback keeps true-north basis",
                        )
                    }
                    normalized
                }
            }
        }
    }

    private fun applyDeclination(
        declinationDeg: Float,
        latitude: Double,
        longitude: Double,
        nowElapsedMs: Long,
        sourceTimestampMs: Long,
        markNeedsLiveRefresh: Boolean,
    ) {
        this.declinationDeg = declinationDeg
        _declination.value = declinationDeg
        loggedDeclinationUnavailable = false
        lastDeclinationLat = latitude
        lastDeclinationLon = longitude
        lastDeclinationAtElapsedMs = nowElapsedMs
        seededDeclinationNeedsLiveRefresh = markNeedsLiveRefresh
        onStatusChanged()
        persistDeclinationCache(
            declinationDeg = declinationDeg,
            latitude = latitude,
            longitude = longitude,
            sourceTimestampMs = sourceTimestampMs,
        )
    }

    private fun persistDeclinationCache(
        declinationDeg: Float,
        latitude: Double,
        longitude: Double,
        sourceTimestampMs: Long,
    ) {
        declinationCachePrefs
            .edit()
            .putFloat(PREF_KEY_DECLINATION_DEG, declinationDeg)
            .putLong(PREF_KEY_LAT_BITS, latitude.toBits())
            .putLong(PREF_KEY_LON_BITS, longitude.toBits())
            .putLong(PREF_KEY_SOURCE_TIMESTAMP_MS, sourceTimestampMs)
            .apply()
    }

    private fun readLastKnownLocationSafely(provider: String): Location? =
        try {
            locationManager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            null
        }

    private fun hasAnyLocationPermission(): Boolean {
        val finePermissionGranted =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        val coarsePermissionGranted =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        return finePermissionGranted || coarsePermissionGranted
    }
}
