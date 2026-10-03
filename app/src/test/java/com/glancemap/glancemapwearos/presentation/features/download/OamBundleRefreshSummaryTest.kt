package com.glancemap.glancemapwearos.presentation.features.download

import com.glancemap.glancemapwearos.core.maps.DemSource
import org.junit.Assert.assertEquals
import org.junit.Test

class OamBundleRefreshSummaryTest {
    @Test
    fun bundlesToRefreshOnlyIncludesConfirmedUpdates() {
        val updateBundle = installedBundle(areaId = "update")
        val unknownBundle = installedBundle(areaId = "unknown")
        val upToDateBundle = installedBundle(areaId = "up-to-date")

        val summary =
            OamBundleRefreshSummary(
                checks =
                    listOf(
                        updateCheck(updateBundle, OamBundleUpdateStatus.UPDATE_AVAILABLE),
                        updateCheck(unknownBundle, OamBundleUpdateStatus.UNKNOWN),
                        updateCheck(upToDateBundle, OamBundleUpdateStatus.UP_TO_DATE),
                    ),
            )

        assertEquals(listOf(updateBundle), summary.bundlesToRefresh)
        assertEquals(1, summary.updateAvailableCount)
        assertEquals(1, summary.unknownCount)
        assertEquals(1, summary.upToDateCount)
    }

    @Test
    fun refreshForcesOnlyIncludesChangedRemoteFiles() {
        val bundle =
            installedBundle(
                areaId = "area",
                routingFileNames = listOf("E5_N45.rd5", "E5_N46.rd5"),
                demTileIds = listOf("N45E006", "N45E007"),
            )
        val check =
            OamBundleUpdateCheck(
                bundle = bundle,
                status = OamBundleUpdateStatus.UPDATE_AVAILABLE,
                checkedFileCount = 4,
                changedFileNames = listOf("Area.Poi.zip", "E5_N46.rd5", "N45E007.hgt.zip"),
            )

        val forces = check.refreshForces(area())

        assertEquals(false, forces.forceMap)
        assertEquals(true, forces.forcePoi)
        assertEquals(setOf("E5_N46.rd5"), forces.forceRoutingFileNames)
        assertEquals(setOf("N45E007"), forces.forceDemTileIds)
    }

    @Test
    fun repairChecksAreIncludedAndForceOnlyDamagedFiles() {
        val bundle =
            installedBundle(
                areaId = "area",
                routingFileNames = listOf("E5_N45.rd5", "E5_N46.rd5"),
                demTileIds = listOf("N45E006", "N45E007"),
            )
        val check =
            OamBundleUpdateCheck(
                bundle = bundle,
                status = OamBundleUpdateStatus.REPAIR_NEEDED,
                checkedFileCount = 4,
                repairFileNames = listOf("Area.zip", "E5_N45.rd5", "N45E006.hgt.zip"),
            )
        val summary = OamBundleRefreshSummary(listOf(check))

        val forces = check.refreshForces(area())

        assertEquals(listOf(bundle), summary.bundlesToRefresh)
        assertEquals(1, summary.repairNeededCount)
        assertEquals(true, forces.forceMap)
        assertEquals(false, forces.forcePoi)
        assertEquals(setOf("E5_N45.rd5"), forces.forceRoutingFileNames)
        assertEquals(setOf("N45E006"), forces.forceDemTileIds)
    }

    @Test
    fun refreshSelectionUnionsInstalledAndCurrentlyEnabledComponents() {
        val bundle = installedBundle(areaId = "area")
        val selection =
            OamDownloadSelection(
                includeMap = true,
                includePoi = true,
                includeRouting = true,
                includeDem = true,
                demSource = DemSource.MAPZEN_SKADI_1S,
                includeRefugesInfo = true,
            )

        val refreshSelection = bundle.toDownloadSelection(selection)

        assertEquals(selection, refreshSelection)
        assertEquals(selection.copy(includeMap = false), bundle.missingComponentsFor(selection))
    }

    @Test
    fun refreshKeepsInstalledComponentsAndDemSourceWhenTogglesAreOff() {
        val bundle =
            installedBundle(
                areaId = "area",
                routingFileNames = listOf("E5_N45.rd5"),
                demSource = DemSource.MAPZEN_SKADI_1S,
                demTileIds = listOf("N45E006"),
            ).copy(
                poiFileName = "area.poi",
                refugesInfoFileName = "area.refuges-info.poi",
            )

        val refreshSelection =
            bundle.toDownloadSelection(
                OamDownloadSelection(
                    includeMap = false,
                    includePoi = false,
                    includeRouting = false,
                    includeDem = false,
                    demSource = DemSource.MAPZEN_SKADI_1S,
                    includeRefugesInfo = false,
                ),
            )

        assertEquals(true, refreshSelection.includeMap)
        assertEquals(true, refreshSelection.includePoi)
        assertEquals(true, refreshSelection.includeRouting)
        assertEquals(true, refreshSelection.includeDem)
        assertEquals(true, refreshSelection.includeRefugesInfo)
        assertEquals(DemSource.MAPZEN_SKADI_1S, refreshSelection.demSource)
    }

    @Test
    fun upToDateBundleWithMissingEnabledComponentsIsRefreshableWithoutRemoteChange() {
        val bundle = installedBundle(areaId = "map-only")
        val selection =
            OamDownloadSelection(
                includeMap = true,
                includePoi = true,
                includeRouting = true,
                includeDem = true,
                includeRefugesInfo = true,
            )
        val check = updateCheck(bundle, OamBundleUpdateStatus.UP_TO_DATE)

        val summary = OamBundleRefreshSummary(checks = listOf(check), selection = selection)

        assertEquals(listOf(bundle), summary.bundlesToRefresh)
        assertEquals(1, summary.selectedAdditionCount)
        assertEquals(emptyList<String>(), check.changedFileNames)
        assertEquals(OamBundleUpdateStatus.UP_TO_DATE, check.status)
        assertEquals(emptyList<OamBundleUpdateCheck>(), OamBundleRefreshSummary(listOf(check)).checksToRefresh)
    }

    @Test
    fun selectedAdditionsDoNotForceHealthyExistingFiles() {
        val bundle = installedBundle(areaId = "map-only")
        val forces =
            OamBundleUpdateCheck(
                bundle = bundle,
                status = OamBundleUpdateStatus.UP_TO_DATE,
                checkedFileCount = 1,
            ).refreshForces(area())

        assertEquals(false, forces.forceMap)
        assertEquals(false, forces.forcePoi)
        assertEquals(false, forces.forceRefugesInfo)
        assertEquals(emptySet<String>(), forces.forceRoutingFileNames)
        assertEquals(emptySet<String>(), forces.forceDemTileIds)
    }

    @Test
    fun multiBundleRefreshIncludesUpdatesAndSelectedAdditions() {
        val additionBundle = installedBundle(areaId = "addition")
        val updateBundle = installedBundle(areaId = "update")
        val selection = OamDownloadSelection(includeMap = true, includePoi = true)
        val updateCheck =
            OamBundleUpdateCheck(
                bundle = updateBundle,
                status = OamBundleUpdateStatus.UPDATE_AVAILABLE,
                checkedFileCount = 1,
                changedFileNames = listOf("update.poi.zip"),
            )

        val summary =
            OamBundleRefreshSummary(
                checks =
                    listOf(
                        updateCheck,
                        updateCheck.copy(
                            bundle = additionBundle,
                            status = OamBundleUpdateStatus.UP_TO_DATE,
                            changedFileNames = emptyList(),
                        ),
                    ),
                selection = selection,
            )

        assertEquals(2, summary.bundlesToRefresh.size)
        assertEquals(1, summary.updateAvailableCount)
        assertEquals(1, summary.selectedAdditionCount)
        assertEquals(emptyList<String>(), summary.checks[1].changedFileNames)
    }

    @Test
    fun remoteMetadataIgnoresChangedEtagWhenContentLengthMatches() {
        val previous = remoteMetadata(entityTag = "\"old\"", lastModifiedMillis = 1L, contentLengthBytes = 100L)
        val current = remoteMetadata(entityTag = "\"new\"", lastModifiedMillis = 2L, contentLengthBytes = 100L)

        assertEquals(RemoteMetadataComparison.SAME, previous.compareWith(current))
    }

    @Test
    fun remoteMetadataIgnoresChangedLastModifiedWhenContentLengthMatches() {
        val previous = remoteMetadata(entityTag = null, lastModifiedMillis = 1L, contentLengthBytes = 100L)
        val current = remoteMetadata(entityTag = null, lastModifiedMillis = 2L, contentLengthBytes = 100L)

        assertEquals(RemoteMetadataComparison.SAME, previous.compareWith(current))
    }

    @Test
    fun pausedRefreshCanResumeWithoutASelectedDownloadArea() {
        val state =
            DownloadUiState(
                isPausedDownload = true,
                pausedOperation = DownloadOperation.REFRESH,
            )

        assertEquals(true, state.canStartOrResumeDownload)
    }

    private fun updateCheck(
        bundle: OamInstalledBundle,
        status: OamBundleUpdateStatus,
    ): OamBundleUpdateCheck =
        OamBundleUpdateCheck(
            bundle = bundle,
            status = status,
            checkedFileCount = if (status == OamBundleUpdateStatus.UNKNOWN) 0 else 1,
        )

    private fun area(): OamDownloadArea =
        OamDownloadArea(
            id = "area",
            continent = "Europe",
            region = "Area",
            mapSizeLabel = "1 MB",
            mapSizeBytes = 1L,
            poiSizeLabel = "1 MB",
            poiSizeBytes = 1L,
            notes = "",
            contourLabel = "",
            mapZipUrl = "https://example.test/Area.zip",
            poiZipUrl = "https://example.test/Area.Poi.zip",
        )

    private fun installedBundle(
        areaId: String,
        routingFileNames: List<String> = emptyList(),
        demSource: DemSource = DemSource.DEFAULT,
        demTileIds: List<String> = emptyList(),
    ): OamInstalledBundle =
        OamInstalledBundle(
            areaId = areaId,
            areaLabel = areaId,
            bundleChoice = OamBundleChoice.MAP_ONLY,
            mapFileName = "$areaId.map",
            poiFileName = null,
            routingFileNames = routingFileNames,
            demSource = demSource,
            demTileIds = demTileIds,
            installedAtMillis = 1L,
        )

    private fun remoteMetadata(
        entityTag: String?,
        lastModifiedMillis: Long?,
        contentLengthBytes: Long?,
    ): OamRemoteFileMetadata =
        OamRemoteFileMetadata(
            url = "https://example.test/file",
            fileName = "file",
            entityTag = entityTag,
            lastModifiedMillis = lastModifiedMillis,
            contentLengthBytes = contentLengthBytes,
        )
}
