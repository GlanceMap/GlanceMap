package com.glancemap.glancemapcompanionapp.library

import androidx.annotation.StringRes
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneInstalledBundle
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleHealth
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleStatus
import java.io.File
import java.util.Locale

internal data class PhoneLibraryOfflineCapability(
    @StringRes val label: Int,
    @StringRes val status: Int,
)

internal fun PhoneLibraryItem.Bundle.effectiveCapabilities(): List<PhoneLibraryOfflineCapability> =
    when {
        capabilities.isEmpty() -> phoneLibraryBundleCapabilities(bundle, health)
        else -> capabilities
    }

internal fun PhoneLibraryItem.Bundle.isOfflineReady(): Boolean {
    val effective = effectiveCapabilities()
    return !health.hasRecovery &&
        health.status != PhoneOfflineBundleStatus.RECOVERY_NEEDED &&
        effective.any { it.status == R.string.library_offline_ready } &&
        effective.all { capability ->
            capability.status == R.string.library_offline_ready ||
                capability.status == R.string.library_offline_not_included
        }
}

internal fun phoneLibraryBundleCapabilities(
    bundle: PhoneInstalledBundle,
    health: PhoneOfflineBundleHealth,
    data: List<PhoneLibraryItem.OfflineData> = emptyList(),
): List<PhoneLibraryOfflineCapability> {
    val available = health.availableFileNames.toMutableSet()
    data.filter { it.isUsable }.forEach { item ->
        available +=
            if (item.section == PhoneLibraryOfflineSection.ELEVATION) {
                item.title.substringBefore('.').uppercase(Locale.ROOT)
            } else {
                item.title
            }
    }
    return listOf(
        Triple(R.string.map_tool_maps_title, "map", listOf("map").filter { bundle.mapFileName.isNotBlank() }),
        Triple(R.string.library_filter_pois, "poi", listOf("poi").filter { bundle.poiFileName.isNotBlank() }),
        Triple(R.string.library_offline_routing, "routing", bundle.routingFileNames.map { File(it).name }),
        Triple(R.string.library_offline_elevation, "dem", bundle.demTileIds.map { it.uppercase(Locale.ROOT) }),
    ).map { (label, prefix, expected) ->
        PhoneLibraryOfflineCapability(label, offlineCapabilityStatus(expected, prefix, available, health))
    }
}

private fun offlineCapabilityStatus(
    expected: List<String>,
    prefix: String,
    available: Set<String>,
    health: PhoneOfflineBundleHealth,
): Int =
    when {
        expected.isEmpty() -> R.string.library_offline_not_included
        expected.all(available::contains) -> R.string.library_offline_ready
        health.invalidFileNames.any { name ->
            name == prefix ||
                name.startsWith("$prefix/") ||
                File(name).name.substringBefore('.').uppercase(Locale.ROOT) in expected ||
                File(name).name in expected
        } -> R.string.library_offline_invalid
        expected.any(available::contains) -> R.string.library_offline_partial
        expected.any(health.unavailableFileNames::contains) -> R.string.library_offline_unavailable
        else -> R.string.library_offline_missing
    }
