package com.glancemap.glancemapcompanionapp.library

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun libraryOfflineDataDetails(item: PhoneLibraryItem.OfflineData) {
    Text(stringResource(if (item.isUsable) R.string.library_offline_ready else R.string.library_offline_invalid))
    val coverage = item.coverage
    Text(
        if (coverage == null) {
            stringResource(R.string.library_offline_unknown_coverage)
        } else {
            stringResource(
                R.string.library_offline_coverage,
                coverage.minLatitude,
                coverage.maxLatitude,
                coverage.minLongitude,
                coverage.maxLongitude,
            )
        },
    )
    Text(item.storageLocation.label)
    if (item.areaNames.isNotEmpty()) {
        Text(stringResource(R.string.library_offline_area_usage, item.areaNames.joinToString(", ")))
    }
    Text(stringResource(R.string.library_offline_automatic))
}
