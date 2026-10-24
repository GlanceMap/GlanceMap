package com.glancemap.glancemapwearos.core.service.transfer.notifications

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemePackageException
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemePackageFailureReason

internal fun themePackageFailureMessage(
    error: Throwable,
    resources: Resources,
): String? {
    val reason = (error as? ThemePackageException)?.reason
    val messageRes =
        when (reason) {
            ThemePackageFailureReason.INVALID_PACKAGE -> R.string.map_not_a_valid_mapsforge_theme_package
            ThemePackageFailureReason.MISSING_RESOURCES -> R.string.map_theme_resources_are_missing
            ThemePackageFailureReason.NO_XML -> R.string.map_no_render_theme_xml_found
            ThemePackageFailureReason.INSTALL_FAILED -> R.string.map_theme_could_not_be_installed
            ThemePackageFailureReason.REMOVE_FAILED -> R.string.map_theme_files_could_not_be_removed
            ThemePackageFailureReason.DAMAGED -> R.string.map_theme_package_is_damaged
            ThemePackageFailureReason.TOO_LARGE -> R.string.map_theme_package_is_too_large
            else -> null
        }
    return messageRes?.let { resources.getString(it) } ?: error.message
}
