package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Resources
import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.routing.LoopRouteSuggestionException

// Stable diagnostics remain available to tests and logs; the UI resolves the resource ID.
internal class RouteToolArgumentException(
    @StringRes val messageRes: Int,
    diagnostic: String,
) : IllegalArgumentException(diagnostic)

internal class RouteToolStateException(
    @StringRes val messageRes: Int,
    diagnostic: String,
) : IllegalStateException(diagnostic)

internal fun Throwable.routeToolFailureMessage(resources: Resources): String? =
    when (this) {
        is RouteToolArgumentException -> resources.getString(messageRes)
        is RouteToolStateException -> resources.getString(messageRes)
        is LoopRouteSuggestionException ->
            resources.getString(
                when {
                    lowerDistanceMeters != null && higherDistanceMeters != null ->
                        R.string.gpx_loop_try_shorter_or_longer
                    lowerDistanceMeters != null -> R.string.gpx_loop_try_shorter
                    higherDistanceMeters != null -> R.string.gpx_loop_try_longer
                    else -> R.string.gpx_loop_not_found
                },
            )
        else -> localizedMessage
    }
