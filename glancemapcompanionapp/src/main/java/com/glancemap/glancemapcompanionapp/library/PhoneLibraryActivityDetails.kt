package com.glancemap.glancemapcompanionapp.library

import android.content.Context
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneGeneralSettingsPreferences
import com.glancemap.glancemapcompanionapp.map.phoneActivityStatisticsGrid
import com.glancemap.glancemapcompanionapp.routes.CompanionGpxRouteParser
import com.glancemap.glancemapcompanionapp.routes.RecordedActivityStatistics
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import com.glancemap.glancemapcompanionapp.routes.recordedActivityGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

internal fun loadLibraryActivityStatistics(
    context: Context,
    item: PhoneLibraryItem,
): RecordedActivityStatistics? {
    val uri =
        when (item) {
            is PhoneLibraryItem.Gpx -> RouteLibraryRepository(context).contentUriFor(item.route.id)
            is PhoneLibraryItem.FolderGpx ->
                item.item.id
                    .takeIf { it.startsWith("folder:") }
                    ?.removePrefix("folder:")
                    ?.let(Uri::parse)
            is PhoneLibraryItem.ReceivedGpx -> Uri.fromFile(item.file)
            else -> return null
        } ?: throw FileNotFoundException()
    val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
    val parsed = input.use(CompanionGpxRouteParser::parse)
    return when {
        parsed.activityStatistics != null -> parsed.activityStatistics
        item.gpxKind() == PhoneLibraryGpxKind.RECORDING -> recordedActivityGeometry(parsed.points)
        else -> null
    }
}

@Composable
internal fun libraryActivityDetails(item: PhoneLibraryItem) {
    val context = LocalContext.current
    val isMetric = remember(context) { PhoneGeneralSettingsPreferences(context).load().isMetric }
    var loading by remember(item.key) { mutableStateOf(true) }
    var failed by remember(item.key) { mutableStateOf(false) }
    var statistics by remember(item.key) { mutableStateOf<RecordedActivityStatistics?>(null) }
    LaunchedEffect(item) {
        loading = true
        failed = false
        try {
            statistics = withContext(Dispatchers.IO) { loadLibraryActivityStatistics(context, item) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    when {
        loading -> Text(stringResource(R.string.activity_details_loading))
        failed -> Text(stringResource(R.string.activity_details_failed))
        statistics != null -> {
            Text(stringResource(R.string.activity_details_title))
            phoneActivityStatisticsGrid(requireNotNull(statistics), isMetric)
        }
        else -> Text(libraryItemSummary(item))
    }
}
