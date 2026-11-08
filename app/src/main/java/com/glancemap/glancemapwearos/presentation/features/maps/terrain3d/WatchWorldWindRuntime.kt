package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.core.maps.DEFAULT_TERRAIN_3D_VIEWING_ANGLE
import com.glancemap.glancemapwearos.presentation.features.maps.MapHolder

/** What the map screen asks of the 3D session; the last values are kept while the screen is hidden. */
internal data class WatchWorldWindInputs(
    val enabled: Boolean,
    val hikingThemeEnabled: Boolean,
    val holder: MapHolder?,
    val mapPath: String?,
    val appearanceInProgress: Boolean,
    val angle: Int = DEFAULT_TERRAIN_3D_VIEWING_ANGLE,
)

/**
 * Owns the 3D session above the navigation routes. Leaving the map screen (menu, settings, ambient)
 * then pauses the session exactly like screen-off, and it is closed only after the same standby
 * timeout, instead of being torn down and rebuilt on every visit.
 */
class WatchWorldWindRuntime {
    internal var inputs by mutableStateOf<WatchWorldWindInputs?>(null)
        private set
    internal var screenInteractive by mutableStateOf(false)
        private set
    internal var bearing by mutableFloatStateOf(0f)
        private set

    /** Whether the map screen is currently shown; set by the screen while it is composed. */
    internal var routeVisible by mutableStateOf(false)

    /** A new session object per map, as the screen previously did with `remember(holder, mapPath)`. */
    internal var session by mutableStateOf(WatchWorldWindSession())
        private set
    private var sessionKey: Pair<MapHolder?, String?>? = null

    internal val interactive: Boolean get() = routeVisible && screenInteractive

    internal fun publish(
        inputs: WatchWorldWindInputs,
        screenInteractive: Boolean,
        bearing: Float,
    ) {
        val key = inputs.holder to inputs.mapPath
        if (sessionKey != null && sessionKey != key) session = WatchWorldWindSession()
        sessionKey = key
        this.inputs = inputs
        this.screenInteractive = screenInteractive
        this.bearing = bearing
    }
}

/** Runs the 3D session for [runtime]; place it above the screens that show the 3D view. */
@Suppress("FunctionNaming") // Compose naming for a Unit-returning composable, as elsewhere in the app.
@Composable
fun RunWatchWorldWind(runtime: WatchWorldWindRuntime) {
    val inputs = runtime.inputs
    rememberWatchWorldWindSession(
        session = runtime.session,
        enabled = inputs?.enabled == true,
        hikingThemeEnabled = inputs?.hikingThemeEnabled == true,
        holder = inputs?.holder,
        mapPath = inputs?.mapPath,
        appearanceInProgress = inputs?.appearanceInProgress == true,
        interactive = runtime.interactive,
        angle = inputs?.angle ?: DEFAULT_TERRAIN_3D_VIEWING_ANGLE,
        bearing = runtime.bearing,
    )
}
