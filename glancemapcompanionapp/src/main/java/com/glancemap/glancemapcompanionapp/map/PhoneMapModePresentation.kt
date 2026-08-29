package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.R

/** Companion presentation mapping; the map-mode reducer itself remains renderer and resource free. */
internal fun PhoneMapMode.labelResource(): Int =
    when {
        follow == PhoneMapFollowMode.FOLLOW_LOCATION -> R.string.map_mode_follow
        orientation == PhoneMapOrientation.HEADING_UP -> R.string.map_mode_heading_up
        else -> R.string.map_mode_north_up
    }
