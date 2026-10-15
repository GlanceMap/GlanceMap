package com.glancemap.glancemapcompanionapp.map

/** Publish installed maps before the linked folder's potentially large content checks. */
internal suspend fun loadPhoneOfflineMapsOnStartup(
    discover: suspend () -> List<PhoneOfflineMap>,
    synchronizeFolder: suspend () -> PhoneOfflineMapFolderSyncResult,
    onDiscovered: (List<PhoneOfflineMap>) -> Unit,
    onSynchronized: (PhoneOfflineMapFolderSyncResult, List<PhoneOfflineMap>) -> Unit,
    onFinished: () -> Unit,
) {
    try {
        onDiscovered(discover())
        val result = synchronizeFolder()
        onSynchronized(result, discover())
    } finally {
        onFinished()
    }
}

internal fun shouldWaitForPhoneOfflineMapStartup(
    startupComplete: Boolean,
    source: PhoneMapSource,
    comparison: PhoneMapComparisonLayer?,
    installedMaps: List<PhoneOfflineMap>,
): Boolean {
    if (startupComplete) return false
    val selectedMaps =
        listOfNotNull(
            (source as? PhoneMapSource.Offline)?.map,
            (comparison as? PhoneMapComparisonLayer.Offline)?.map,
        )
    return selectedMaps.any { selected ->
        installedMaps.none { installed -> installed.selectionIdentity == selected.selectionIdentity }
    }
}
