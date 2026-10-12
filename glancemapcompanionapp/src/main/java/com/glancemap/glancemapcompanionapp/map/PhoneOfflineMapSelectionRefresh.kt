package com.glancemap.glancemapcompanionapp.map

internal fun PhoneOfflineMap.refreshedFrom(
    offlineMaps: List<PhoneOfflineMap>,
): PhoneOfflineMap = offlineMaps.firstOrNull { it.selectionIdentity == selectionIdentity } ?: this

/** Rebind installed revisions while retaining presentation, follow mode, and pending commands. */
internal fun PhoneMapUiState.refreshOfflineMapSelections(offlineMaps: List<PhoneOfflineMap>?): PhoneMapUiState {
    // An undiscovered list cannot invalidate a restored selection.
    if (offlineMaps == null) return this
    val refreshedSource =
        when (val selectedSource = source) {
            PhoneMapSource.Online -> selectedSource
            is PhoneMapSource.Offline -> selectedSource.copy(map = selectedSource.map.refreshedFrom(offlineMaps))
        }
    var refreshedState = changeBaseSource(refreshedSource)
    val offlineComparison = comparison.layer as? PhoneMapComparisonLayer.Offline
    if (offlineComparison != null) {
        val refreshedMap = offlineComparison.map.refreshedFrom(offlineMaps)
        if (refreshedMap != offlineComparison.map) {
            refreshedState = refreshedState.selectComparisonLayer(PhoneMapComparisonLayer.Offline(refreshedMap))
        }
    }
    return refreshedState.clearUnavailableComparison(offlineMaps)
}
