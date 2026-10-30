package com.glancemap.glancemapcompanionapp.map

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerPredictionCadence
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerPredictionWindow
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerVisualAnchor
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerVisualCorrectionPlan
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerVisualTrajectory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.mapsforge.core.model.LatLong

/** A display-only copy of a fix; the actual location never leaves the recording pipeline. */
internal class PhoneMapVisualMotion {
    private val trajectory = MarkerVisualTrajectory()
    private var current: PhoneMapLocation? = null
    private var window: MarkerPredictionWindow? = null
    private var canPredict = false

    fun reset() {
        trajectory.reset()
        current = null
        window = null
        canPredict = false
    }

    fun update(fix: PhoneMapLocation, now: Long, expectedInterval: Long): PhoneMapLocation? {
        val previous = current
        if (previous != null && fix.fixElapsedRealtimeMillis < previous.fixElapsedRealtimeMillis) return sample(now)
        if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) {
            reset()
            current = fix
            return fix
        }
        val newWindow = MarkerPredictionCadence(expectedInterval).predictionWindow(12_000L, 12_000L, 50L)
        val accuracy = fix.accuracyMeters
        val trustworthy = accuracy != null && accuracy.isFinite() && accuracy in 0f..35f &&
            fix.fixElapsedRealtimeMillis > 0 && fix.fixElapsedRealtimeMillis <= now + 1_000 &&
            now - fix.fixElapsedRealtimeMillis <= newWindow.stopAtMs
        val speed = fix.speedMetersPerSecond
        val bearing = fix.bearingDegrees
        val moving = trustworthy && speed != null && speed.isFinite() && speed in 0.35f..25f &&
            bearing != null && bearing.isFinite() &&
            (fix.speedAccuracyMetersPerSecond == null || fix.speedAccuracyMetersPerSecond < speed) &&
            (fix.bearingAccuracyDegrees == null || fix.bearingAccuracyDegrees <= 45f)
        val anchor = MarkerVisualAnchor(
            latLong = LatLong(fix.latitude, fix.longitude),
            fixElapsedMs = fix.fixElapsedRealtimeMillis,
            accuracyM = accuracy ?: 0f,
            speedMps = if (moving) speed else 0f,
            bearingDeg = if (moving) bearing else null,
        )
        val previousPosition = if (previous != null && window != null) sample(now) else null
        val previousFresh = previous != null && window != null && now - previous.fixElapsedRealtimeMillis <= window!!.stopAtMs
        val distance = previousPosition?.let { LatLong(it.latitude, it.longitude).sphericalDistance(anchor.latLong) }
        if (trustworthy && previousFresh && distance != null && distance <= 50.0) {
            trajectory.rebase(anchor, LatLong(previousPosition.latitude, previousPosition.longitude), now, newWindow,
                MarkerVisualCorrectionPlan(durationMs = 1_200L, bypassRemovalRateLimit = !moving))
        } else {
            trajectory.seed(anchor)
        }
        current = fix
        window = newWindow
        canPredict = moving
        return sample(now)
    }

    fun sample(now: Long): PhoneMapLocation? {
        val fix = current ?: return null
        val currentWindow = window ?: return fix
        val point = trajectory.sample(now, currentWindow)?.latLong ?: return fix
        return fix.copy(latitude = point.latitude, longitude = point.longitude)
    }

    fun needsFrames(now: Long): Boolean {
        val fix = current ?: return false
        val currentWindow = window ?: return false
        return now - fix.fixElapsedRealtimeMillis < currentWindow.stopAtMs &&
            (canPredict || trajectory.hasActiveCorrection(now, currentWindow))
    }
}

@Composable
internal fun rememberPhoneMapVisualLocation(
    rawLocation: PhoneMapLocation?,
    enabled: Boolean,
    sourceKey: Boolean,
    following: Boolean,
    expectedIntervalMillis: Long,
): State<PhoneMapLocation?> {
    val motion = remember(enabled, sourceKey) { PhoneMapVisualMotion() }
    val wake = remember(motion) { Channel<Unit>(Channel.CONFLATED) }
    val display = remember { mutableStateOf(rawLocation) }
    LaunchedEffect(motion, rawLocation, expectedIntervalMillis) {
        display.value = if (enabled && rawLocation != null) {
            motion.update(rawLocation, SystemClock.elapsedRealtime(), expectedIntervalMillis)
        } else {
            motion.reset()
            rawLocation
        }
        wake.trySend(Unit)
    }
    LaunchedEffect(motion, following) {
        if (!enabled) return@LaunchedEffect
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            if (motion.needsFrames(now)) {
                withTimeoutOrNull(if (following) 100L else 250L) { wake.receive() }
                display.value = motion.sample(SystemClock.elapsedRealtime())
            } else {
                wake.receive()
            }
        }
    }
    return display
}
