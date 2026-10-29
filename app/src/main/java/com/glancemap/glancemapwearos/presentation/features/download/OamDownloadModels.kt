package com.glancemap.glancemapwearos.presentation.features.download

import android.content.res.Resources
import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.maps.DemSource
import java.io.File
import java.net.URI
import java.util.Locale
import com.glancemap.trailcore.oam.OamDownloadCatalog as SharedOamDownloadCatalog

enum class OamBundleChoice(
    @StringRes val labelRes: Int,
    @StringRes val secondaryLabelRes: Int,
    val includeMap: Boolean,
    val includePoi: Boolean,
) {
    MAP_ONLY(R.string.gpx_map_only, R.string.gpx_oam_map_with_contours, includeMap = true, includePoi = false),
    POI_ONLY(R.string.gpx_poi_only, R.string.download_mapsforge_poi, includeMap = false, includePoi = true),
    MAP_AND_POI(R.string.gpx_map_poi, R.string.gpx_map_and_mapsforge_poi, includeMap = true, includePoi = true),
}

data class OamDownloadSelection(
    val includeMap: Boolean = true,
    val includePoi: Boolean = true,
    val includeRouting: Boolean = true,
    val includeDem: Boolean = true,
    val demSource: DemSource = DemSource.DEFAULT,
    val includeRefugesInfo: Boolean = false,
) {
    val canDownload: Boolean
        get() = includeMap || includePoi || includeRouting || includeDem || includeRefugesInfo

    fun toBundleChoice(): OamBundleChoice =
        when {
            includeMap && includePoi -> OamBundleChoice.MAP_AND_POI
            includeMap -> OamBundleChoice.MAP_ONLY
            includePoi -> OamBundleChoice.POI_ONLY
            else -> OamBundleChoice.MAP_AND_POI
        }

    fun label(resources: Resources): String =
        listOfNotNull(
            resources.getString(R.string.download_map).takeIf { includeMap },
            resources.getString(R.string.common_poi).takeIf { includePoi },
            resources.getString(R.string.download_routing).takeIf { includeRouting },
            resources
                .getString(
                    R.string.download_elevation,
                    resources.getString(demSource.shortLabelRes),
                ).takeIf { includeDem },
            resources.getString(R.string.download_refuges_info).takeIf { includeRefugesInfo },
        ).joinToString(" + ").ifBlank { resources.getString(R.string.download_nothing_selected) }
}

data class OamInstalledBundle(
    val areaId: String,
    val areaLabel: String,
    val bundleChoice: OamBundleChoice,
    val mapFileName: String?,
    val poiFileName: String?,
    val refugesInfoFileName: String? = null,
    val routingFileNames: List<String> = emptyList(),
    val downloadedRoutingFileNames: List<String> = emptyList(),
    val demSource: DemSource = DemSource.DEFAULT,
    val demTileIds: List<String> = emptyList(),
    val downloadedDemTileIds: List<String> = emptyList(),
    val installedAtMillis: Long,
    val remoteFiles: List<OamRemoteFileMetadata> = emptyList(),
)

data class OamRemoteFileMetadata(
    val url: String,
    val fileName: String,
    val entityTag: String?,
    val lastModifiedMillis: Long?,
    val contentLengthBytes: Long?,
)

enum class OamBundleUpdateStatus {
    REPAIR_NEEDED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    UNKNOWN,
}

data class OamBundleLocalHealth(
    val repairFileNames: List<String> = emptyList(),
) {
    val needsRepair: Boolean
        get() = repairFileNames.isNotEmpty()
}

data class OamBundleUpdateCheck(
    val bundle: OamInstalledBundle,
    val status: OamBundleUpdateStatus,
    val checkedFileCount: Int,
    val changedFileNames: List<String> = emptyList(),
    val repairFileNames: List<String> = emptyList(),
    val unknownFileNames: List<String> = emptyList(),
)

data class OamBundleRefreshSummary(
    val checks: List<OamBundleUpdateCheck>,
    val selection: OamDownloadSelection? = null,
) {
    val totalCount: Int
        get() = checks.size

    val upToDateCount: Int
        get() = checks.count { it.status == OamBundleUpdateStatus.UP_TO_DATE }

    val updateAvailableCount: Int
        get() = checks.count { it.status == OamBundleUpdateStatus.UPDATE_AVAILABLE }

    val repairNeededCount: Int
        get() = checks.count { it.status == OamBundleUpdateStatus.REPAIR_NEEDED }

    val unknownCount: Int
        get() = checks.count { it.status == OamBundleUpdateStatus.UNKNOWN }

    val checksToRefresh: List<OamBundleUpdateCheck>
        get() = checksToRefreshOrUpdates(selection)

    val selectedAdditionCount: Int
        get() =
            selection?.let { currentSelection ->
                checks.count {
                    it.status == OamBundleUpdateStatus.UP_TO_DATE &&
                        it.hasSelectedAdditions(currentSelection)
                }
            } ?: 0

    fun checksToRefresh(currentSelection: OamDownloadSelection): List<OamBundleUpdateCheck> =
        checks.filter { check ->
            check.status == OamBundleUpdateStatus.UPDATE_AVAILABLE ||
                check.status == OamBundleUpdateStatus.REPAIR_NEEDED ||
                (check.status == OamBundleUpdateStatus.UP_TO_DATE && check.hasSelectedAdditions(currentSelection))
        }

    val bundlesToRefresh: List<OamInstalledBundle>
        get() = checksToRefresh.map { it.bundle }

    private fun checksToRefreshOrUpdates(currentSelection: OamDownloadSelection?): List<OamBundleUpdateCheck> =
        currentSelection?.let(::checksToRefresh) ?: checks.filter { check ->
            check.status == OamBundleUpdateStatus.UPDATE_AVAILABLE ||
                check.status == OamBundleUpdateStatus.REPAIR_NEEDED
        }
}

internal data class OamBundleRefreshForces(
    val forceMap: Boolean = false,
    val forcePoi: Boolean = false,
    val forceRefugesInfo: Boolean = false,
    val forceRoutingFileNames: Set<String> = emptySet(),
    val forceDemTileIds: Set<String> = emptySet(),
)

internal fun OamBundleUpdateCheck.refreshForces(area: OamDownloadArea): OamBundleRefreshForces {
    val changedNames = (changedFileNames + repairFileNames).map { File(it).name }.toSet()
    return OamBundleRefreshForces(
        forceMap = oamRemoteFileName(area.mapZipUrl) in changedNames,
        forcePoi = oamRemoteFileName(area.poiZipUrl) in changedNames,
        forceRefugesInfo = bundle.refugesInfoFileName?.let { File(it).name in changedNames } == true,
        forceRoutingFileNames =
            bundle.routingFileNames
                .map { File(it).name }
                .filter { it in changedNames }
                .toSet(),
        forceDemTileIds =
            bundle.demTileIds
                .map { it.uppercase(Locale.ROOT) }
                .filter { bundle.demSource.remoteFileName(it) in changedNames }
                .toSet(),
    )
}

internal fun OamBundleUpdateCheck.hasSelectedAdditions(
    selection: OamDownloadSelection,
): Boolean = bundle.missingComponentsFor(selection).canDownload

internal fun OamInstalledBundle.missingComponentsFor(selection: OamDownloadSelection): OamDownloadSelection =
    OamDownloadSelection(
        includeMap = selection.includeMap && mapFileName == null,
        includePoi = selection.includePoi && poiFileName == null,
        includeRouting = selection.includeRouting && routingFileNames.isEmpty(),
        includeDem = selection.includeDem && demTileIds.isEmpty(),
        demSource = selection.demSource,
        includeRefugesInfo = selection.includeRefugesInfo && refugesInfoFileName == null,
    )

internal fun oamRemoteFileName(url: String): String =
    runCatching { File(URI(url).path).name }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: url.substringAfterLast('/').ifBlank { "download" }

object OamDownloadCatalog {
    val areas: List<OamDownloadArea> = SharedOamDownloadCatalog.areas
}
