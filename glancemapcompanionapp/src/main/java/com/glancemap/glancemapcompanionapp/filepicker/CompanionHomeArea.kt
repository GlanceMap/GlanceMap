package com.glancemap.glancemapcompanionapp.filepicker

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.SendToMobile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpatialTracking
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.ui.graphics.vector.ImageVector
import com.glancemap.glancemapcompanionapp.R

internal enum class CompanionHomeArea(
    @StringRes val titleResource: Int,
    val icon: ImageVector,
) {
    MAP(R.string.map_title, Icons.Filled.Map),
    HOME(R.string.menu_today_hike, Icons.Filled.Today),
    LIBRARY(R.string.library_title, Icons.Filled.FolderOpen),
    ROUTES(R.string.menu_routes, Icons.Filled.Route),
    SEND_TO_WATCH(R.string.transfer_title_send_to_watch, Icons.AutoMirrored.Filled.SendToMobile),
    LIVE_HIKE(R.string.menu_live_hike, Icons.Filled.SpatialTracking),
    MISSION_PLAN(R.string.menu_mission_plan, Icons.Filled.CalendarMonth),
    ROUTE_WEATHER(R.string.menu_route_weather, Icons.Filled.WbCloudy),
    LIVE_TRACKING(R.string.live_tracking_title, Icons.Filled.SpatialTracking),
    MAP_LEGEND(R.string.map_legend_title, Icons.AutoMirrored.Filled.MenuBook),
    SETTINGS(R.string.map_tool_settings_title, Icons.Filled.Settings),
}

internal val companionMenuAreas =
    listOf(
        CompanionHomeArea.MISSION_PLAN,
        CompanionHomeArea.ROUTE_WEATHER,
        CompanionHomeArea.LIVE_TRACKING,
        CompanionHomeArea.SETTINGS,
    )

internal data class CompanionMenuState(
    val activeArea: CompanionHomeArea,
    val debugCaptureActive: Boolean,
    val liveHikeSyncEnabled: Boolean = false,
)

internal data class CompanionMenuActions(
    val onNavigate: (CompanionHomeArea) -> Unit,
    val onDebug: () -> Unit,
    val onQuickGuide: () -> Unit,
    val onCreditsLegal: () -> Unit,
    val onLiveHikeSyncChanged: (Boolean) -> Unit = {},
)

internal fun initialCompanionHomeArea(
    openLiveTrackingToken: Long,
    openSendToWatchToken: Long,
): CompanionHomeArea =
    when {
        openLiveTrackingToken != 0L -> CompanionHomeArea.LIVE_TRACKING
        openSendToWatchToken != 0L -> CompanionHomeArea.SEND_TO_WATCH
        else -> CompanionHomeArea.MAP
    }
