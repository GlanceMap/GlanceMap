@file:Suppress("LongMethod", "ReturnCount")

package com.glancemap.glancemapwearos.presentation.features.maps.theme

import android.content.Context
import android.content.res.Resources
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.maps.DemSignatureStore
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.core.service.diagnostics.DemDownloadDiagnostics
import com.glancemap.glancemapwearos.core.service.transfer.runtime.DownloadNetworkLease
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferLockManager
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemeRepository
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeListItem
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeUiIds
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.util.Locale
import kotlin.coroutines.coroutineContext

private enum class OverlayPreset {
    DEFAULT,
    MINIMAL,
    ALL_ON,
}

data class DemDownloadUiState(
    val isDownloading: Boolean = false,
    val activeMapPath: String? = null,
    val totalTiles: Int = 0,
    val processedTiles: Int = 0,
    val currentTileBytes: Long = 0L,
    val currentTileTotalBytes: Long? = null,
    val downloadedTiles: Int = 0,
    val skippedTiles: Int = 0,
    val missingTiles: Int = 0,
    val failedTiles: Int = 0,
    val networkUnavailable: Boolean = false,
    val statusMessage: String = "",
    val lastCompletedAtMillis: Long = 0L,
) {
    val progressPercent: Int?
        get() =
            demDownloadProgressPercent(
                totalTiles = totalTiles,
                completedTiles = processedTiles,
                currentTileBytes = currentTileBytes,
                currentTileTotalBytes = currentTileTotalBytes,
            )
}

data class DemMapReadiness(
    val isReady: Boolean,
    val hasAnyTerrain: Boolean,
    val selectedSource: DemSource,
    val usesFallbackTerrain: Boolean,
    val requiredTiles: Int,
    val availableTiles: Int,
    val isCoverageKnown: Boolean,
    val selectedAvailableTiles: Int,
    val selectedCoverageKnown: Boolean,
    val fallbackAvailableTiles: Int,
)

internal fun demDownloadProgressPercent(
    totalTiles: Int,
    completedTiles: Int,
    currentTileBytes: Long,
    currentTileTotalBytes: Long?,
): Int? {
    if (totalTiles <= 0) return null
    val completed = completedTiles.coerceIn(0, totalTiles)
    val currentTileFraction =
        currentTileTotalBytes
            ?.takeIf { it > 0L && completed < totalTiles }
            ?.let { totalBytes ->
                currentTileBytes.coerceIn(0L, totalBytes).toDouble() / totalBytes.toDouble()
            } ?: 0.0
    return (((completed + currentTileFraction) / totalTiles) * 100.0)
        .toInt()
        .coerceIn(0, 100)
}

private data class DemDownloadResult(
    val totalTiles: Int,
    val processedTiles: Int,
    val downloadedTiles: Int,
    val skippedTiles: Int,
    val missingTiles: Int,
    val failedTiles: Int,
    val networkUnavailable: Boolean,
    val statusMessage: String,
)

private data class DemTileDownloadOutcome(
    val success: Boolean,
    val missingUpstream: Boolean,
    val networkUnavailable: Boolean,
)

// Theme and DEM state deliberately share one lifecycle owner so downloads survive screen recomposition.
@Suppress("LargeClass")
class ThemeViewModel(
    private val themeRepository: ThemeRepository,
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    companion object {
        private const val DEM_USER_AGENT = "GlanceMap-DEM/1.0"
        private const val TAG = "ThemeViewModel"
        private const val DEM_TILE_MAX_ATTEMPTS = 3
        private const val DEM_TILE_RETRY_BASE_DELAY_MS = 1_500L
        private const val DEM_INTERNET_WAIT_TIMEOUT_MS = 8_000L
        private const val DEM_SERVICE_TIMEOUT = "DEM foreground service timed out"
        private const val DEM_DOWNLOAD_WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L
    }

    private val appContext: Context = context.applicationContext

    private val _demDownloadUiState = MutableStateFlow(DemDownloadUiState())
    val demDownloadUiState: StateFlow<DemDownloadUiState> = _demDownloadUiState.asStateFlow()
    private var demDownloadJob: Job? = null
    private val demDownloadLockManager by lazy { TransferLockManager(appContext) }
    private var demDownloadWakeLock: PowerManager.WakeLock? = null
    private var demDownloadNetwork: DownloadNetworkLease? = null

    @Volatile
    private var activeDemConnection: HttpURLConnection? = null
    val demSource: StateFlow<DemSource> =
        settingsRepository.demSource.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DemSource.DEFAULT,
        )

    val themeItems: StateFlow<List<ThemeListItem>> =
        themeRepository
            .getThemeItems()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    fun setTheme(themeId: String) {
        viewModelScope.launch {
            Log.d("Theme", "setTheme called with: $themeId")
            themeRepository.setTheme(themeId)
        }
    }

    // The filesystem/provider boundary must surface a short error without leaking implementation details.
    @Suppress("TooGenericExceptionCaught")
    fun deleteExternalPackage(
        packageId: String,
        onComplete: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                themeRepository.deleteExternalPackage(packageId)
                onComplete(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "theme_delete_failed package=$packageId", error)
                onComplete(context.getString(R.string.map_theme_files_could_not_be_removed_try_again))
            }
        }
    }

    fun setMapStyle(styleId: String) {
        viewModelScope.launch {
            Log.d("Theme", "setMapStyle called with: $styleId")
            themeRepository.setMapStyle(styleId)
        }
    }

    fun toggleOverlay(overlayId: String) {
        viewModelScope.launch {
            // overlayId is the concrete overlay layer id for the selected bundled theme/style.
            Log.d("Theme", "toggleOverlay called with: $overlayId")

            val snapshot = themeItems.first()
            val currentStyleId =
                snapshot
                    .filterIsInstance<ThemeListItem.Style>()
                    .firstOrNull { it.selected }
                    ?.id
                    ?: run {
                        Log.w("Theme", "toggleOverlay: no selected style found, ignoring")
                        return@launch
                    }

            val bundledThemeSelected =
                snapshot
                    .filterIsInstance<ThemeListItem.ThemeOption>()
                    .firstOrNull { it.selected }
                    ?.id
                    .let { MapsforgeThemeCatalog.isBundledAssetTheme(it) }
            if (!bundledThemeSelected) {
                Log.d("Theme", "toggleOverlay ignored because bundled asset theme is not selected")
                return@launch
            }

            Log.d("Theme", "toggleOverlay: currentStyleId=$currentStyleId overlayId=$overlayId")
            themeRepository.toggleOverlay(currentStyleId, overlayId)
        }
    }

    fun applyOverlayPresetDefault() {
        applyOverlayPreset(OverlayPreset.DEFAULT)
    }

    fun applyOverlayPresetMinimal() {
        applyOverlayPreset(OverlayPreset.MINIMAL)
    }

    fun applyOverlayPresetAllOn() {
        applyOverlayPreset(OverlayPreset.ALL_ON)
    }

    fun resetCurrentStyleOverlaysToDefault() {
        applyOverlayPreset(OverlayPreset.DEFAULT)
    }

    private fun applyOverlayPreset(preset: OverlayPreset) {
        viewModelScope.launch {
            val snapshot = themeItems.first()

            val selectedThemeId =
                snapshot
                    .filterIsInstance<ThemeListItem.ThemeOption>()
                    .firstOrNull { it.selected }
                    ?.id
            if (!MapsforgeThemeCatalog.isBundledAssetTheme(selectedThemeId)) return@launch

            val currentStyleId =
                snapshot
                    .filterIsInstance<ThemeListItem.Style>()
                    .firstOrNull { it.selected }
                    ?.id
                    ?: ThemeUiIds.DEFAULT_STYLE_ID

            val overlays = snapshot.filterIsInstance<ThemeListItem.Overlay>()
            if (overlays.isEmpty()) return@launch

            val enabledOverlayIds =
                when (preset) {
                    OverlayPreset.DEFAULT ->
                        overlays
                            .asSequence()
                            .filter { it.defaultEnabled }
                            .map { it.layerId }
                            .toSet()

                    OverlayPreset.ALL_ON ->
                        overlays
                            .asSequence()
                            .map { it.layerId }
                            .toSet()

                    OverlayPreset.MINIMAL ->
                        overlays
                            .asSequence()
                            .filter { isMinimalOverlay(it.layerId) }
                            .map { it.layerId }
                            .toSet()
                }

            themeRepository.setOverlaysForStyle(
                styleId = currentStyleId,
                enabledOverlayLayerIds = enabledOverlayIds,
            )
        }
    }

    private fun isMinimalOverlay(layerId: String): Boolean {
        val id = layerId.lowercase(Locale.ROOT)
        return id.contains("routes") ||
            id.contains("waymarks") ||
            id.contains("winter_reference") ||
            id.contains("winter_symbol") ||
            id.contains("skipiste") ||
            id.contains("skitour") ||
            id.contains("skiloipe") ||
            id.contains("schneeschuh") ||
            id.contains("rodeln") ||
            id.contains("hundeschlitten") ||
            id.contains("eislaufen") ||
            id.contains("schneepark")
    }

    fun setGlobalToggle(
        toggleId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            when (toggleId) {
                ThemeUiIds.HILL_SHADING -> {
                    Log.d("Theme", "setGlobalToggle: hillShading=$enabled")
                    themeRepository.setHillShadingEnabled(enabled)
                }
                ThemeUiIds.RELIEF_OVERLAY -> {
                    Log.d("Theme", "setGlobalToggle: reliefOverlay=$enabled")
                    themeRepository.setReliefOverlayEnabled(enabled)
                }
                ThemeUiIds.NIGHT_MODE -> {
                    Log.d("Theme", "setGlobalToggle: nightMode=$enabled")
                    themeRepository.setNightModeEnabled(enabled)
                }
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            resetToDefaultsAndWait()
        }
    }

    suspend fun resetToDefaultsAndWait() {
        Log.d("Theme", "resetToDefaults called")
        themeRepository.resetToDefaults()
    }

    fun downloadDemForSelectedMap(selectedMapPath: String?) {
        downloadDemForMap(selectedMapPath)
    }

    fun downloadDemForMap(mapPath: String?) {
        if (_demDownloadUiState.value.isDownloading) return
        if (mapPath.isNullOrBlank()) {
            DemDownloadDiagnostics.record("start_rejected", "reason=no_map_selected")
            _demDownloadUiState.value =
                DemDownloadUiState(
                    statusMessage = context.getString(R.string.dem_no_map_selected_select_a_map_first),
                    lastCompletedAtMillis = System.currentTimeMillis(),
                )
            return
        }
        _demDownloadUiState.value =
            DemDownloadUiState(
                isDownloading = true,
                activeMapPath = mapPath,
                statusMessage = context.getString(R.string.dem_checking_watch_internet),
            )
        runCatching { StandaloneDemDownloadForegroundService.start(appContext, mapPath) }
            .onFailure(::reportDemDownloadServiceFailure)
    }

    internal suspend fun runDemDownloadForMap(
        mapPath: String,
        network: DownloadNetworkLease,
    ) {
        val owner = coroutineContext[Job]
        demDownloadJob?.takeIf { it !== owner }?.join()
        demDownloadJob = owner
        demDownloadNetwork = network
        try {
            if (mapPath.isNullOrBlank()) {
                DemDownloadDiagnostics.record(
                    event = "start_rejected",
                    detail = "reason=no_map_selected",
                )
                _demDownloadUiState.value =
                    DemDownloadUiState(
                        activeMapPath = null,
                        statusMessage =
                            context.resources.getString(
                                R.string.dem_no_map_selected_select_a_map_first,
                            ),
                        lastCompletedAtMillis = System.currentTimeMillis(),
                    )
                return
            }

            val selectedMapFile = File(mapPath)
            if (!selectedMapFile.exists()) {
                DemDownloadDiagnostics.record(
                    event = "start_rejected",
                    detail = "reason=map_missing path=${mapPath.demDiagValue()}",
                )
                _demDownloadUiState.value =
                    DemDownloadUiState(
                        activeMapPath = null,
                        statusMessage =
                            context.resources.getString(
                                R.string.dem_selected_map_not_found_on_watch,
                            ),
                        lastCompletedAtMillis = System.currentTimeMillis(),
                    )
                return
            }

            _demDownloadUiState.value =
                DemDownloadUiState(
                    isDownloading = true,
                    activeMapPath = mapPath,
                    statusMessage = context.resources.getString(R.string.dem_checking_watch_internet),
                )

            if (!waitForWatchInternetConnection()) {
                DemDownloadDiagnostics.record(
                    event = "start_rejected",
                    detail = "reason=no_internet path=${mapPath.demDiagValue()}",
                )
                _demDownloadUiState.value =
                    DemDownloadUiState(
                        isDownloading = false,
                        activeMapPath = null,
                        networkUnavailable = true,
                        statusMessage =
                            context.getString(
                                R.string.maps_dem_internet_required,
                            ),
                        lastCompletedAtMillis = System.currentTimeMillis(),
                    )
                return
            }

            _demDownloadUiState.value =
                _demDownloadUiState.value.copy(
                    statusMessage = context.resources.getString(R.string.dem_reading_map_area),
                )
            val source = settingsRepository.demSource.first()
            acquireDemDownloadKeepAlive(source)

            runCatching {
                withContext(Dispatchers.IO) {
                    downloadDemForMapInternal(selectedMapFile, source)
                }
            }.onSuccess { result ->
                _demDownloadUiState.value =
                    DemDownloadUiState(
                        isDownloading = false,
                        activeMapPath = null,
                        totalTiles = result.totalTiles,
                        processedTiles = result.processedTiles,
                        downloadedTiles = result.downloadedTiles,
                        skippedTiles = result.skippedTiles,
                        missingTiles = result.missingTiles,
                        failedTiles = result.failedTiles,
                        networkUnavailable = result.networkUnavailable,
                        statusMessage = result.statusMessage,
                        lastCompletedAtMillis = System.currentTimeMillis(),
                    )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "DEM download failed", error)
                val networkUnavailable =
                    classifyDemFailureAsNetworkUnavailable(
                        throwable = error,
                        internetAvailableNow = hasWatchInternetConnection(),
                    )
                _demDownloadUiState.value =
                    DemDownloadUiState(
                        isDownloading = false,
                        activeMapPath = null,
                        networkUnavailable = networkUnavailable,
                        statusMessage =
                            buildDemFailureMessage(
                                error,
                                networkUnavailable,
                                resources = context.resources,
                            ),
                        lastCompletedAtMillis = System.currentTimeMillis(),
                    )
            }
        } catch (error: CancellationException) {
            publishDemCancellation(error)
            throw error
        } finally {
            activeDemConnection = null
            releaseDemDownloadKeepAlive()
            demDownloadNetwork = null
            demDownloadJob = null
        }
    }

    fun cancelDemDownload() {
        if (!_demDownloadUiState.value.isDownloading) return
        DemDownloadDiagnostics.record(
            event = "cancel_requested",
            detail = "path=${_demDownloadUiState.value.activeMapPath.orEmpty().demDiagValue()}",
        )
        StandaloneDemDownloadForegroundService.cancel(appContext)
    }

    internal fun cancelActiveDemDownload(
        timedOut: Boolean = false,
        expectedJob: Job? = null,
    ) {
        if (expectedJob != null && demDownloadJob !== expectedJob) return
        activeDemConnection?.disconnect()
        val reason = if (timedOut) DEM_SERVICE_TIMEOUT else "DEM download cancelled"
        demDownloadJob?.cancel(CancellationException(reason))
        if (demDownloadJob == null && _demDownloadUiState.value.isDownloading) {
            publishDemCancellation(CancellationException(reason))
        }
    }

    private fun publishDemCancellation(error: CancellationException) {
        DemDownloadDiagnostics.record("cancelled", "reason=${error.message.orEmpty().demDiagValue()}")
        val message =
            if (error.message == DEM_SERVICE_TIMEOUT) {
                R.string.dem_dem_download_timed_out_retry_when_the_watch_internet_connection_is_sta
            } else {
                R.string.dem_elevation_download_cancelled
            }
        _demDownloadUiState.value =
            DemDownloadUiState(
                statusMessage = context.getString(message),
                lastCompletedAtMillis = System.currentTimeMillis(),
            )
    }

    internal fun reportDemDownloadServiceFailure(error: Throwable) {
        Log.e(TAG, "DEM download service failed", error)
        DemDownloadDiagnostics.record("service_failed", "error=${error.javaClass.simpleName}")
        _demDownloadUiState.value =
            DemDownloadUiState(
                statusMessage = context.getString(R.string.maps_dem_download_failed),
                lastCompletedAtMillis = System.currentTimeMillis(),
            )
    }

    suspend fun demReadinessForMap(mapPath: String?): DemMapReadiness =
        withContext(Dispatchers.IO) {
            // Do not rely on demSource.value here. This ViewModel intentionally keeps that flow
            // WhileSubscribed, so a newly opened Map settings screen can otherwise momentarily
            // use the default Standard terrain instead of the saved user selection.
            val selectedSource = settingsRepository.demSource.first()
            val mapFile =
                mapPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?: return@withContext DemMapReadiness(
                        isReady = false,
                        hasAnyTerrain = false,
                        selectedSource = selectedSource,
                        usesFallbackTerrain = false,
                        requiredTiles = 0,
                        availableTiles = 0,
                        isCoverageKnown = false,
                        selectedAvailableTiles = 0,
                        selectedCoverageKnown = false,
                        fallbackAvailableTiles = 0,
                    )
            val selectedCoverage =
                Dem3CoverageUtils.coverageForMap(
                    context = appContext,
                    mapFile = mapFile,
                    sources = listOf(selectedSource),
                )
            val runtimeCoverage =
                Dem3CoverageUtils.coverageForMap(
                    context = appContext,
                    mapFile = mapFile,
                    sources = selectedSource.readFallbackOrder(),
                )
            DemMapReadiness(
                isReady = runtimeCoverage.isReady,
                hasAnyTerrain = runtimeCoverage.availableTiles > 0,
                selectedSource = selectedSource,
                usesFallbackTerrain = runtimeCoverage.isReady && !selectedCoverage.isReady,
                requiredTiles = runtimeCoverage.requiredTiles,
                availableTiles = runtimeCoverage.availableTiles,
                isCoverageKnown = runtimeCoverage.isCoverageKnown,
                selectedAvailableTiles = selectedCoverage.availableTiles,
                selectedCoverageKnown = selectedCoverage.isCoverageKnown,
                fallbackAvailableTiles =
                    (runtimeCoverage.availableTiles - selectedCoverage.availableTiles).coerceAtLeast(0),
            )
        }

    // This is the validated per-tile state machine; extracting it purely for Detekt would obscure its boundaries.
    @Suppress("CyclomaticComplexMethod")
    private suspend fun downloadDemForMapInternal(
        selectedMapFile: File,
        source: DemSource,
    ): DemDownloadResult {
        val tileIds =
            Dem3CoverageUtils
                .requiredTileIdsForMap(selectedMapFile)
                ?.sorted()
                ?: run {
                    DemDownloadDiagnostics.record(
                        event = "complete",
                        detail =
                            "source=${source.id} status=map_area_failed " +
                                "path=${selectedMapFile.absolutePath.demDiagValue()}",
                    )
                    return DemDownloadResult(
                        totalTiles = 0,
                        processedTiles = 0,
                        downloadedTiles = 0,
                        skippedTiles = 0,
                        missingTiles = 0,
                        failedTiles = 0,
                        networkUnavailable = false,
                        statusMessage = context.resources.getString(R.string.dem_failed_reading_selected_map_area),
                    )
                }

        DemDownloadDiagnostics.record(
            event = "start",
            detail =
                "source=${source.id} path=${selectedMapFile.absolutePath.demDiagValue()} totalTiles=${tileIds.size} " +
                    "firstTile=${tileIds.firstOrNull().orEmpty()} lastTile=${tileIds.lastOrNull().orEmpty()}",
        )

        if (tileIds.isEmpty()) {
            DemDownloadDiagnostics.record(
                event = "complete",
                detail =
                    "source=${source.id} status=no_tiles " +
                        "path=${selectedMapFile.absolutePath.demDiagValue()}",
            )
            return DemDownloadResult(
                totalTiles = 0,
                processedTiles = 0,
                downloadedTiles = 0,
                skippedTiles = 0,
                missingTiles = 0,
                failedTiles = 0,
                networkUnavailable = false,
                statusMessage = context.resources.getString(R.string.dem_no_dem_tiles_required_for_this_map),
            )
        }

        val outputRoot = getDemOutputRoot(source)
        outputRoot.mkdirs()

        var downloaded = 0
        var skipped = 0
        var missing = 0
        var failed = 0
        var processedTiles = 0
        var networkUnavailable = false

        for ((index, tileId) in tileIds.withIndex()) {
            coroutineContext.ensureActive()
            val processed = index + 1
            _demDownloadUiState.value =
                _demDownloadUiState.value.copy(
                    isDownloading = true,
                    activeMapPath = selectedMapFile.absolutePath,
                    totalTiles = tileIds.size,
                    processedTiles = index,
                    currentTileBytes = 0L,
                    currentTileTotalBytes = null,
                    downloadedTiles = downloaded,
                    skippedTiles = skipped,
                    missingTiles = missing,
                    failedTiles = failed,
                    statusMessage = "Downloading...",
                )

            val folder = source.folderForTile(tileId)
            val fileName = source.localFileName(tileId)
            val localDir = File(outputRoot, folder).apply { mkdirs() }
            val localFile = File(localDir, fileName)
            if (localFile.exists()) {
                val existingValid =
                    runCatching {
                        validateDemTileFile(localFile)
                        true
                    }.getOrElse { error ->
                        Log.w(TAG, "Deleting invalid existing DEM tile ${localFile.absolutePath}", error)
                        localFile.delete()
                        false
                    }
                if (existingValid) {
                    skipped += 1
                    processedTiles = processed
                    publishCompletedDemTile(
                        processedTiles = processedTiles,
                        downloaded = downloaded,
                        skipped = skipped,
                        missing = missing,
                        failed = failed,
                    )
                    DemDownloadDiagnostics.record(
                        event = "tile_skipped",
                        detail =
                            "source=${source.id} tile=$tileId index=$processed total=${tileIds.size} " +
                                "reason=already_valid",
                    )
                    continue
                }
            }

            if (isDemTileMarkedUnavailable(outputRoot, tileId)) {
                missing += 1
                processedTiles = processed
                publishCompletedDemTile(
                    processedTiles = processedTiles,
                    downloaded = downloaded,
                    skipped = skipped,
                    missing = missing,
                    failed = failed,
                )
                DemDownloadDiagnostics.record(
                    event = "tile_missing",
                    detail =
                        "source=${source.id} tile=$tileId index=$processed total=${tileIds.size} " +
                            "reason=upstream_404_marked",
                )
                continue
            }

            val url = source.remoteUrl(tileId)
            val outcome =
                downloadTileWithRetries(
                    url = url,
                    target = localFile,
                    source = source,
                    processed = processed,
                    total = tileIds.size,
                )

            processedTiles = processed
            if (outcome.success) {
                downloaded += 1
                DemDownloadDiagnostics.record(
                    event = "tile_downloaded",
                    detail = "source=${source.id} tile=$tileId index=$processed total=${tileIds.size}",
                )
            } else if (outcome.missingUpstream) {
                missing += 1
                DemDownloadDiagnostics.record(
                    event = "tile_missing",
                    detail =
                        "source=${source.id} tile=$tileId index=$processed total=${tileIds.size} " +
                            "reason=upstream_404",
                )
            } else {
                failed += 1
                DemDownloadDiagnostics.record(
                    event = "tile_failed",
                    detail =
                        "source=${source.id} tile=$tileId index=$processed total=${tileIds.size} " +
                            "networkUnavailable=${outcome.networkUnavailable}",
                )
                networkUnavailable = outcome.networkUnavailable
            }
            publishCompletedDemTile(
                processedTiles = processedTiles,
                downloaded = downloaded,
                skipped = skipped,
                missing = missing,
                failed = failed,
            )
            if (networkUnavailable) break
        }

        if (downloaded > 0 || missing > 0) {
            DemSignatureStore.markDirty(appContext)
            Dem3CoverageUtils.clearCaches()
        }

        val remaining = (tileIds.size - processedTiles).coerceAtLeast(0)
        val summary =
            buildDemSummaryMessage(
                downloaded = downloaded,
                skipped = skipped,
                missing = missing,
                failed = failed,
                remaining = remaining,
                resources = context.resources,
            )
        val finalMessage =
            when {
                networkUnavailable && remaining > 0 ->
                    context.resources.getString(
                        R.string.dem_no_internet_on_watch_downloaded_already_on_watch_remaining_reconnect_a,
                        context.resources.getQuantityString(R.plurals.dem_downloaded_count, downloaded, downloaded),
                        context.resources.getQuantityString(R.plurals.dem_already_present_count, skipped, skipped),
                        context.resources.getQuantityString(R.plurals.dem_remaining_count, remaining, remaining),
                    )
                networkUnavailable ->
                    context.getString(
                        R.string.maps_dem_internet_required,
                    )
                else -> summary
            }
        val hasUnfinishedTiles = failed > 0 || remaining > 0
        val completionStatus =
            when {
                networkUnavailable || hasUnfinishedTiles -> "partial"
                missing > 0 -> "complete_with_unavailable"
                else -> "ready"
            }
        DemDownloadDiagnostics.record(
            event = "complete",
            detail =
                "source=${source.id} status=$completionStatus " +
                    "total=${tileIds.size} processed=$processedTiles downloaded=$downloaded skipped=$skipped " +
                    "missing=$missing failed=$failed remaining=$remaining networkUnavailable=$networkUnavailable " +
                    "message=${finalMessage.demDiagValue()}",
        )
        return DemDownloadResult(
            totalTiles = tileIds.size,
            processedTiles = processedTiles,
            downloadedTiles = downloaded,
            skippedTiles = skipped,
            missingTiles = missing,
            failedTiles = failed,
            networkUnavailable = networkUnavailable,
            statusMessage = finalMessage,
        )
    }

    fun setDemSource(source: DemSource) {
        viewModelScope.launch {
            settingsRepository.setDemSource(source)
        }
    }

    private fun getDemOutputRoot(source: DemSource): File = Dem3CoverageUtils.demRootDir(appContext, source)

    private fun acquireDemDownloadKeepAlive(source: DemSource) {
        demDownloadWakeLock =
            runCatching {
                demDownloadLockManager.acquireWakeLock(
                    tag = "GlanceMap:StandaloneDemDownload",
                    timeoutMs = DEM_DOWNLOAD_WAKE_LOCK_TIMEOUT_MS,
                )
            }.getOrNull()
        DemDownloadDiagnostics.record(
            event = "keepalive_acquired",
            detail =
                "source=${source.id} partialWakeLock=${demDownloadWakeLock?.isHeld == true} " +
                    "networkHeld=${demDownloadNetwork?.currentNetwork != null}",
        )
    }

    private fun releaseDemDownloadKeepAlive() {
        val wakeLock = demDownloadWakeLock
        val wakeLockWasHeld = wakeLock?.isHeld == true
        demDownloadWakeLock = null
        wakeLock?.let { runCatching { demDownloadLockManager.releaseWakeLock(it) } }
        if (wakeLock != null) {
            DemDownloadDiagnostics.record(
                event = "keepalive_released",
                detail =
                    "partialWakeLock=$wakeLockWasHeld",
            )
        }
    }

    private fun publishCompletedDemTile(
        processedTiles: Int,
        downloaded: Int,
        skipped: Int,
        missing: Int,
        failed: Int,
    ) {
        _demDownloadUiState.value =
            _demDownloadUiState.value.copy(
                processedTiles = processedTiles,
                currentTileBytes = 0L,
                currentTileTotalBytes = null,
                downloadedTiles = downloaded,
                skippedTiles = skipped,
                missingTiles = missing,
                failedTiles = failed,
            )
    }

    private fun publishCurrentDemTileProgress(
        bytesDone: Long,
        totalBytes: Long?,
    ) {
        val current = _demDownloadUiState.value
        if (!current.isDownloading) return
        val updated =
            current.copy(
                currentTileBytes = bytesDone.coerceAtLeast(0L),
                currentTileTotalBytes = totalBytes?.takeIf { it > 0L },
            )
        if (updated.progressPercent != current.progressPercent ||
            updated.currentTileTotalBytes != current.currentTileTotalBytes
        ) {
            _demDownloadUiState.value = updated
        }
    }

    private suspend fun downloadTileWithRetries(
        url: String,
        target: File,
        source: DemSource,
        processed: Int,
        total: Int,
    ): DemTileDownloadOutcome {
        var lastError: Throwable? = null

        repeat(DEM_TILE_MAX_ATTEMPTS) { attemptIndex ->
            coroutineContext.ensureActive()
            val attemptNumber = attemptIndex + 1
            if (attemptNumber > 1) {
                _demDownloadUiState.value =
                    _demDownloadUiState.value.copy(
                        statusMessage =
                            context.resources.getString(
                                R.string.dem_retrying_download_attempt,
                                attemptNumber,
                                DEM_TILE_MAX_ATTEMPTS,
                            ),
                    )
            }

            val network = demDownloadNetwork?.awaitNetwork(DEM_INTERNET_WAIT_TIMEOUT_MS)
            if (network == null) {
                return DemTileDownloadOutcome(
                    success = false,
                    missingUpstream = false,
                    networkUnavailable = true,
                )
            }

            val success =
                runCatching {
                    downloadDemFile(
                        request =
                            DemDownloadRequest(
                                sourceId = source.id,
                                url = url,
                                target = target,
                                demRoot = getDemOutputRoot(source),
                                userAgent = DEM_USER_AGENT,
                            ),
                        onConnectionOpened = { connection ->
                            activeDemConnection = connection
                        },
                        onProgress = ::publishCurrentDemTileProgress,
                        openConnection = { url -> network.openConnection(url) as HttpURLConnection },
                    )
                    true
                }.getOrElse { error ->
                    coroutineContext.ensureActive()
                    lastError = error
                    false
                }

            if (success) {
                return DemTileDownloadOutcome(
                    success = true,
                    missingUpstream = false,
                    networkUnavailable = false,
                )
            }

            val error = lastError
            if (error != null) {
                DemDownloadDiagnostics.record(
                    event = "tile_attempt_failed",
                    detail =
                        "source=${source.id} processed=$processed total=$total attempt=$attemptNumber " +
                            "error=${error.javaClass.simpleName} message=${error.message.orEmpty().demDiagValue()}",
                )
            }
            if (error != null && error !is FileNotFoundException) {
                Log.w(
                    TAG,
                    "Failed downloading DEM tile $processed/$total from $url on attempt $attemptNumber/$DEM_TILE_MAX_ATTEMPTS",
                    error,
                )
            }

            if (attemptNumber >= DEM_TILE_MAX_ATTEMPTS || !isRetryableDemDownloadFailure(error)) {
                return DemTileDownloadOutcome(
                    success = false,
                    missingUpstream = error is FileNotFoundException,
                    networkUnavailable =
                        error?.let {
                            classifyDemFailureAsNetworkUnavailable(
                                throwable = it,
                                internetAvailableNow = hasWatchInternetConnection(),
                            )
                        } == true,
                )
            }

            delay(DEM_TILE_RETRY_BASE_DELAY_MS * attemptNumber)
        }

        return DemTileDownloadOutcome(
            success = false,
            missingUpstream = lastError is FileNotFoundException,
            networkUnavailable =
                lastError?.let {
                    classifyDemFailureAsNetworkUnavailable(
                        throwable = it,
                        internetAvailableNow = hasWatchInternetConnection(),
                    )
                } == true,
        )
    }

    private suspend fun waitForWatchInternetConnection(): Boolean {
        val network = demDownloadNetwork?.awaitNetwork(DEM_INTERNET_WAIT_TIMEOUT_MS)
        return network != null
    }

    private fun hasWatchInternetConnection(): Boolean = demDownloadNetwork?.currentNetwork != null
}

// Keep the existing UI API; resource-backed defaults and labels do not change its inputs.
@Suppress("LongParameterList")
internal fun buildDemSummaryMessage(
    downloaded: Int,
    skipped: Int,
    missing: Int,
    failed: Int,
    remaining: Int,
    resources: Resources,
): String =
    when {
        remaining > 0 ->
            resources.getString(R.string.dem_dem_download_incomplete_retry_to_finish)
        missing > 0 && failed > 0 ->
            resources.getString(
                R.string.dem_dem_download_incomplete_unavailable_failed,
                resources.getQuantityString(R.plurals.dem_unavailable_count, missing, missing),
                resources.getQuantityString(R.plurals.dem_failed_count, failed, failed),
            )
        missing > 0 ->
            resources.getQuantityString(
                R.plurals.dem_dem_download_complete_upstream_tiles_unavailable,
                missing,
                missing,
            )
        failed > 0 ->
            resources.getQuantityString(R.plurals.dem_dem_download_incomplete_failed, failed, failed)
        downloaded > 0 || skipped > 0 ->
            resources.getString(R.string.dem_dem_download_successful)
        else ->
            resources.getString(R.string.dem_dem_download_incomplete)
    }

internal fun isDemTileMarkedUnavailable(
    demRoot: File,
    tileId: String,
): Boolean =
    Dem3CoverageUtils
        .missingTileMarkerCandidates(demRoot = demRoot, tileId = tileId)
        .any { marker -> marker.isFile }

internal fun String.demDiagValue(): String =
    replace(Regex("\\s+"), "_")
        .replace("|", "_")
        .take(180)
