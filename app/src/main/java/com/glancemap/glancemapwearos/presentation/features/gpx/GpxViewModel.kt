package com.glancemap.glancemapwearos.presentation.features.gpx

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterConfig
import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterDefaults
import com.glancemap.glancemapwearos.core.routing.RoutePlanner
import com.glancemap.glancemapwearos.core.routing.RoutePlannerRequest
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.GpxExportRepository
import com.glancemap.glancemapwearos.data.repository.GpxRepositoryImpl
import com.glancemap.glancemapwearos.data.repository.PoiRepository
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.SyncManager
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceSession
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceTuning
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionSource
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.buildGpxGuidanceSession
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.haversineMeters
import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.RecordingElevationProvider
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingCalorieEstimate
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingDashboardSnapshot
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.estimateRecordingCalories
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolCreatePreview
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolKind
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolModifyPreview
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSaveResult
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.io.File
import kotlin.math.roundToInt

data class GpxGuidanceStartResult(
    val warningMessage: String? = null,
)

private fun String.telemetryToken(): String =
    replace(Regex("\\s+"), "_")
        .take(100)

private data class GpxGuidanceBuildResult(
    val session: GpxGuidanceSession,
    val warningMessage: String? = null,
    val guidanceMode: String = "exact_gpx",
)

data class GpxRouteServices(
    val planner: RoutePlanner,
    val elevationProvider: RecordingElevationProvider,
)

class GpxViewModel(
    private val gpxRepository: GpxRepositoryImpl,
    private val gpxExportRepository: GpxExportRepository,
    private val syncManager: SyncManager,
    private val settingsRepository: SettingsRepository,
    private val poiRepository: PoiRepository,
    private val routeServices: GpxRouteServices,
) : ViewModel() {
    private val _gpxFiles = MutableStateFlow<List<GpxFileState>>(emptyList())
    val gpxFiles: StateFlow<List<GpxFileState>> = _gpxFiles.asStateFlow()

    private val _lastVisitedGpxListPage =
        MutableStateFlow(SettingsRepository.DEFAULT_GPX_LIST_PAGE)
    val lastVisitedGpxListPage: StateFlow<String> = _lastVisitedGpxListPage.asStateFlow()

    private val _activeGpxDetails = MutableStateFlow<List<GpxTrackDetails>>(emptyList())
    val activeGpxDetails: StateFlow<List<GpxTrackDetails>> = _activeGpxDetails.asStateFlow()

    private val _inspectionUiState = MutableStateFlow<GpxInspectionUiState?>(null)
    val inspectionUiState: StateFlow<GpxInspectionUiState?> = _inspectionUiState.asStateFlow()

    private val _selectedPointA = MutableStateFlow<LatLong?>(null)
    val selectedPointA: StateFlow<LatLong?> = _selectedPointA.asStateFlow()

    private val _selectedPointB = MutableStateFlow<LatLong?>(null)
    val selectedPointB: StateFlow<LatLong?> = _selectedPointB.asStateFlow()

    private val _selectingPointB = MutableStateFlow(false)
    val selectingPointB: StateFlow<Boolean> = _selectingPointB.asStateFlow()

    private val _elevationProfileUiState = MutableStateFlow<GpxElevationProfileUiState?>(null)
    val elevationProfileUiState: StateFlow<GpxElevationProfileUiState?> =
        _elevationProfileUiState.asStateFlow()

    private val _exportUiState = MutableStateFlow(GpxExportUiState())
    val exportUiState: StateFlow<GpxExportUiState> = _exportUiState.asStateFlow()

    private val _turnByTurnGuidanceSession = MutableStateFlow<GpxGuidanceSession?>(null)
    val turnByTurnGuidanceSession: StateFlow<GpxGuidanceSession?> = _turnByTurnGuidanceSession.asStateFlow()

    private val _turnByTurnGuidancePaused = MutableStateFlow(false)
    val turnByTurnGuidancePaused: StateFlow<Boolean> = _turnByTurnGuidancePaused.asStateFlow()

    private var lastObservedActiveGpxPaths: Set<String>? = null
    private var previousGpxWaypointPoiFolderLinkEnabled: Boolean? = null

    // ----------------------------
    // Internal inspection session state
    // ----------------------------
    private var aPos: TrackPosition? = null
    private var bPos: TrackPosition? = null
    private var selectingB: Boolean = false
    private var selectBTimeoutJob: Job? = null

    // ✅ Delay popup so user sees the yellow dot first
    private var popupDelayJob: Job? = null
    private val popupDelayMs = 1_000L

    // ----------------------------
    // CACHES
    // ----------------------------
    private data class CachedMeta(
        val sig: FileSig,
        val title: String?,
        val distance: Double,
        val elevationGain: Double,
        val elevationLoss: Double,
        val isActivity: Boolean,
        val activityProfile: String?,
        val activityDurationSec: Double?,
        val activitySummary: RecordingDashboardSnapshot?,
    )

    private data class CachedEta(
        val sig: FileSig,
        val modelConfig: GpxEtaModelConfig,
        val projection: GpxEtaProjection?,
    )

    private data class UserProfileWeights(
        val userWeightKg: Float,
        val backpackWeightKg: Float,
        val bikeWeightKg: Float,
        val activityProfile: String,
    )

    private val metaCache = LinkedHashMap<String, CachedMeta>(64, 0.75f, true)
    private val profileCache = LinkedHashMap<String, TrackProfile>(16, 0.75f, true)
    private val etaCache = LinkedHashMap<String, CachedEta>(16, 0.75f, true)
    private val cacheLock = Any()
    private var reloadGeneration = 0L

    private val maxMetaCacheEntries = 128
    private val maxProfileCacheEntries = 24
    private var etaModelConfig =
        GpxEtaModelConfig(
            flatSpeedMps = SettingsRepository.DEFAULT_GPX_FLAT_SPEED_MPS.toDouble(),
            advancedVerticalRateEnabled = SettingsRepository.DEFAULT_GPX_ADVANCED_ETA_ENABLED,
            staminaAdjustmentEnabled = SettingsRepository.DEFAULT_GPX_STAMINA_ADJUSTMENT_ENABLED,
            activityProfile = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
            userWeightKg = SettingsRepository.DEFAULT_USER_WEIGHT_KG.toDouble(),
            backpackWeightKg = SettingsRepository.DEFAULT_BACKPACK_WEIGHT_KG.toDouble(),
            bikeWeightKg = SettingsRepository.DEFAULT_BIKE_WEIGHT_KG.toDouble(),
            uphillVerticalMetersPerHour = SettingsRepository.DEFAULT_GPX_UPHILL_VERTICAL_METERS_PER_HOUR.toDouble(),
            downhillVerticalMetersPerHour = SettingsRepository.DEFAULT_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR.toDouble(),
        )
    private var elevationFilterConfig = GpxElevationFilterDefaults.defaultConfig()
    private var userWeightKg = SettingsRepository.DEFAULT_USER_WEIGHT_KG
    private var backpackWeightKg = SettingsRepository.DEFAULT_BACKPACK_WEIGHT_KG
    private var bikeWeightKg = SettingsRepository.DEFAULT_BIKE_WEIGHT_KG
    private var activeActivityProfile = SettingsRepository.DEFAULT_ACTIVITY_PROFILE
    private val routeToolOperations =
        GpxRouteToolOperations(
            gpxRepository = gpxRepository,
            routePlanner = routeServices.planner,
            activeGpxFiles = { _gpxFiles.value },
            elevationFilterConfig = { elevationFilterConfig },
            etaModelConfig = { etaModelConfig },
        )

    // Require press to be close to route
    private val pressThresholdMeters = 30.0

    init {
        settingsRepository.gpxLastVisitedListPage
            .onEach { page -> _lastVisitedGpxListPage.value = page }
            .launchIn(viewModelScope)

        combine(
            combine(
                settingsRepository.gpxFlatSpeedMps,
                settingsRepository.gpxAdvancedEtaEnabled,
                settingsRepository.gpxStaminaAdjustmentEnabled,
                settingsRepository.gpxUphillVerticalMetersPerHour,
                settingsRepository.gpxDownhillVerticalMetersPerHour,
            ) { flatSpeedMps, advancedEnabled, staminaEnabled, uphillMetersPerHour, downhillMetersPerHour ->
                GpxEtaModelConfig(
                    flatSpeedMps =
                        flatSpeedMps
                            .toDouble()
                            .coerceIn(0.0, SettingsRepository.MAX_GPX_FLAT_SPEED_MPS.toDouble()),
                    advancedVerticalRateEnabled = advancedEnabled,
                    staminaAdjustmentEnabled = staminaEnabled,
                    uphillVerticalMetersPerHour =
                        uphillMetersPerHour
                            .toDouble()
                            .coerceIn(
                                SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR.toDouble(),
                                SettingsRepository.MAX_GPX_UPHILL_VERTICAL_METERS_PER_HOUR.toDouble(),
                            ),
                    downhillVerticalMetersPerHour =
                        downhillMetersPerHour
                            .toDouble()
                            .coerceIn(
                                SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR.toDouble(),
                                SettingsRepository.MAX_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR.toDouble(),
                            ),
                )
            },
            settingsRepository.activityProfile,
            settingsRepository.userWeightKg,
            settingsRepository.backpackWeightKg,
            settingsRepository.bikeWeightKg,
        ) { config, activityProfile, userWeightKg, backpackWeightKg, bikeWeightKg ->
            config.copy(
                activityProfile = activityProfile.resolvedActivityProfile(),
                userWeightKg = userWeightKg.toDouble(),
                backpackWeightKg = backpackWeightKg.toDouble(),
                bikeWeightKg = bikeWeightKg.toDouble(),
            )
        }.onEach { config ->
            if (config == etaModelConfig) return@onEach

            etaModelConfig = config
            clearEtaProjectionCache()

            reloadFromDisk()
            refreshOpenEtaUi()
        }.launchIn(viewModelScope)

        combine(
            settingsRepository.gpxElevationSmoothingDistanceMeters,
            settingsRepository.gpxElevationNeutralDiffThresholdMeters,
            settingsRepository.gpxElevationTrendActivationThresholdMeters,
            settingsRepository.gpxElevationAutoAdjustPerGpx,
        ) { smoothingDistanceMeters, neutralDiffThresholdMeters, trendActivationThresholdMeters, autoAdjustPerGpx ->
            GpxElevationFilterDefaults.sanitize(
                GpxElevationFilterConfig(
                    smoothingDistanceMeters = smoothingDistanceMeters,
                    neutralDiffThresholdMeters = neutralDiffThresholdMeters,
                    trendActivationThresholdMeters = trendActivationThresholdMeters,
                    autoAdjustPerGpx = autoAdjustPerGpx,
                ),
            )
        }.onEach { config ->
            if (config == elevationFilterConfig) return@onEach

            elevationFilterConfig = config
            clearProfileMetadataCaches()

            reloadFromDisk()
            refreshOpenEtaUi()
        }.launchIn(viewModelScope)

        combine(
            settingsRepository.userWeightKg,
            settingsRepository.backpackWeightKg,
            settingsRepository.bikeWeightKg,
            settingsRepository.activityProfile,
        ) { userWeightKg, backpackWeightKg, bikeWeightKg, activityProfile ->
            UserProfileWeights(
                userWeightKg = userWeightKg,
                backpackWeightKg = backpackWeightKg,
                bikeWeightKg = bikeWeightKg,
                activityProfile = activityProfile,
            )
        }.onEach { profileWeights ->
            if (
                profileWeights.userWeightKg == userWeightKg &&
                profileWeights.backpackWeightKg == backpackWeightKg &&
                profileWeights.bikeWeightKg == bikeWeightKg &&
                profileWeights.activityProfile == activeActivityProfile
            ) {
                return@onEach
            }

            userWeightKg = profileWeights.userWeightKg
            backpackWeightKg = profileWeights.backpackWeightKg
            bikeWeightKg = profileWeights.bikeWeightKg
            activeActivityProfile = profileWeights.activityProfile
            clearMetadataCache()

            reloadFromDisk()
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            reloadFromDisk()
            restoreTurnByTurnGuidanceSession()
        }

        gpxRepository
            .getActiveGpxFiles()
            .onEach { activePaths ->
                val files = gpxRepository.listGpxFiles()
                loadAndProcessGpxFiles(files, activePaths)
                syncLinkedGpxWaypointPoiFolders(activePaths)
            }.launchIn(viewModelScope)

        settingsRepository.linkGpxWaypointPoiFolders
            .onEach { enabled ->
                val wasEnabled = previousGpxWaypointPoiFolderLinkEnabled
                previousGpxWaypointPoiFolderLinkEnabled = enabled
                if (enabled && wasEnabled == false) {
                    gpxRepository
                        .getActiveGpxFiles()
                        .first()
                        .forEach { path -> setLinkedGpxWaypointPoiEnabled(path = path, enabled = true) }
                }
            }.launchIn(viewModelScope)

        syncManager.gpxSyncRequest
            .onEach { reloadFromDisk() }
            .launchIn(viewModelScope)
    }

    fun loadGpxFiles() {
        viewModelScope.launch { reloadFromDisk() }
    }

    fun setLastVisitedGpxListPage(page: String) {
        if (_lastVisitedGpxListPage.value == page) return

        _lastVisitedGpxListPage.value = page
        viewModelScope.launch {
            settingsRepository.setGpxLastVisitedListPage(page)
        }
    }

    private fun beginReloadGeneration(): Long =
        synchronized(cacheLock) {
            reloadGeneration += 1L
            reloadGeneration
        }

    private fun isCurrentReloadGeneration(generation: Long): Boolean =
        synchronized(cacheLock) {
            generation == reloadGeneration
        }

    private fun clearAllGpxCaches() {
        synchronized(cacheLock) {
            reloadGeneration += 1L
            metaCache.clear()
            profileCache.clear()
            etaCache.clear()
        }
    }

    private fun clearEtaProjectionCache() {
        synchronized(cacheLock) {
            reloadGeneration += 1L
            etaCache.clear()
        }
    }

    private fun clearProfileMetadataCaches() {
        synchronized(cacheLock) {
            reloadGeneration += 1L
            metaCache.clear()
            profileCache.clear()
            etaCache.clear()
        }
    }

    private fun clearMetadataCache() {
        synchronized(cacheLock) {
            reloadGeneration += 1L
            metaCache.clear()
        }
    }

    private fun removeCachedGpx(path: String) {
        synchronized(cacheLock) {
            reloadGeneration += 1L
            metaCache.remove(path)
            profileCache.remove(path)
            etaCache.remove(path)
        }
    }

    private fun pruneCachesToExistingPaths(existingPaths: Set<String>) {
        synchronized(cacheLock) {
            metaCache.keys.retainAll(existingPaths)
            profileCache.keys.retainAll(existingPaths)
            etaCache.keys.retainAll(existingPaths)
        }
    }

    private fun cachedMeta(
        path: String,
        sig: FileSig,
    ): CachedMeta? =
        synchronized(cacheLock) {
            metaCache[path]?.takeIf { it.sig == sig }
        }

    private fun cachedProfile(
        path: String,
        sig: FileSig? = null,
        elevationConfig: GpxElevationFilterConfig? = null,
    ): TrackProfile? =
        synchronized(cacheLock) {
            profileCache[path]?.takeIf { profile ->
                (sig == null || profile.sig == sig) &&
                    (elevationConfig == null || profile.elevationFilterConfig == elevationConfig)
            }
        }

    private fun putProfileCache(
        path: String,
        profile: TrackProfile,
    ) {
        synchronized(cacheLock) {
            profileCache[path] = profile
            profileCache.trimTo(maxProfileCacheEntries)
        }
    }

    private fun putMetaCache(
        path: String,
        meta: CachedMeta,
    ) {
        synchronized(cacheLock) {
            metaCache[path] = meta
            metaCache.trimTo(maxMetaCacheEntries)
        }
    }

    private fun cachedEta(
        path: String,
        sig: FileSig,
        modelConfig: GpxEtaModelConfig,
    ): CachedEta? =
        synchronized(cacheLock) {
            etaCache[path]
                ?.takeIf { cached ->
                    cached.sig == sig && cached.modelConfig == modelConfig
                }
        }

    private fun putEtaCache(
        path: String,
        sig: FileSig,
        modelConfig: GpxEtaModelConfig,
        projection: GpxEtaProjection?,
    ) {
        synchronized(cacheLock) {
            etaCache[path] =
                CachedEta(
                    sig = sig,
                    modelConfig = modelConfig,
                    projection = projection,
                )
            etaCache.trimTo(maxProfileCacheEntries)
        }
    }

    suspend fun clearDerivedCaches() {
        clearAllGpxCaches()
        reloadFromDisk()
        refreshOpenEtaUi()
    }

    private suspend fun reloadFromDisk() {
        val files = gpxRepository.listGpxFiles()
        val activePaths = gpxRepository.getActiveGpxFiles().first()
        loadAndProcessGpxFiles(files, activePaths)
    }

    private suspend fun loadAndProcessGpxFiles(
        files: List<File>,
        activePaths: Set<String>,
    ) {
        val reloadId = beginReloadGeneration()
        val existingPaths = files.asSequence().map { it.absolutePath }.toSet()
        pruneCachesToExistingPaths(existingPaths)
        val filterConfig = elevationFilterConfig
        val etaConfig = etaModelConfig

        val fileStates =
            withContext(Dispatchers.IO) {
                files.map { file ->
                    val path = file.absolutePath
                    val sig = sigOf(file)

                    val cachedMeta = cachedMeta(path, sig)
                    val cachedProfile =
                        cachedProfile(
                            path = path,
                            sig = sig,
                            elevationConfig = filterConfig,
                        )

                    val parsed =
                        if (cachedMeta != null && cachedProfile != null) {
                            null
                        } else {
                            parseGpxData(file)
                        }
                    val profile =
                        cachedProfile ?: buildProfile(
                            sig = sig,
                            pts = parsed?.points ?: emptyList(),
                            elevationFilterConfig = filterConfig,
                        ).also { created ->
                            if (isCurrentReloadGeneration(reloadId)) {
                                putProfileCache(path, created)
                            }
                        }
                    val isActivity =
                        cachedMeta?.isActivity
                            ?: parsed?.isActivity
                            ?: file.name.startsWith("Recording-", ignoreCase = true)
                    val activityProfile =
                        if (isActivity) {
                            recoverActivityDemProfileIfNeeded(
                                sig = sig,
                                profile = profile,
                                parsed = parsed,
                            )
                        } else {
                            profile
                        }
                    val activitySummary =
                        when {
                            !isActivity -> null
                            cachedMeta?.activitySummary?.hasElevationData == true -> cachedMeta.activitySummary
                            parsed != null ->
                                buildSavedActivitySummary(
                                    profile = activityProfile,
                                    parsed = parsed,
                                )
                            cachedMeta?.activitySummary != null ->
                                cachedMeta.activitySummary.withRecoveredElevationIfAvailable(activityProfile)
                            else -> null
                        }
                    val recordingActivityProfile =
                        if (isActivity) {
                            parsed?.activitySummary?.activityProfile?.resolvedActivityProfile()
                                ?: cachedMeta?.activityProfile
                                ?: SettingsRepository.ACTIVITY_PROFILE_HIKE
                        } else {
                            null
                        }
                    val canonicalMeta =
                        CachedMeta(
                            sig = sig,
                            title = cachedMeta?.title ?: parsed?.title,
                            distance =
                                activitySummary?.distanceMeters?.takeIf { it > 0.0 }
                                    ?: profile.totalDistance.takeIf { it > 0.0 }
                                    ?: parsed?.totalDistance
                                    ?: 0.0,
                            elevationGain = activityProfile.totalAscent,
                            elevationLoss = activityProfile.totalDescent,
                            isActivity = isActivity,
                            activityProfile = recordingActivityProfile,
                            activityDurationSec = cachedMeta?.activityDurationSec ?: parsed?.activityDurationSec,
                            activitySummary = activitySummary,
                        )
                    val meta =
                        if (cachedMeta == canonicalMeta) {
                            cachedMeta
                        } else {
                            canonicalMeta.also { created ->
                                if (isCurrentReloadGeneration(reloadId)) {
                                    putMetaCache(path, created)
                                }
                            }
                        }

                    val etaSeconds =
                        getOrBuildEtaProjection(
                            path = path,
                            sig = sig,
                            profile = profile,
                            modelConfig = etaConfig,
                            cacheGeneration = reloadId,
                        )?.totalSeconds

                    GpxFileState(
                        name =
                            normalizeUserFacingGpxText(file.nameWithoutExtension)
                                ?: file.nameWithoutExtension,
                        path = path,
                        title = meta.title,
                        distance = meta.distance,
                        elevationGain = meta.elevationGain,
                        elevationLoss = meta.elevationLoss,
                        estimatedDurationSec = etaSeconds,
                        isActive = path in activePaths,
                        isActivity = meta.isActivity,
                        activityProfile = meta.activityProfile,
                        activityDurationSec = meta.activityDurationSec,
                        activitySummary = meta.activitySummary,
                    )
                }
            }

        if (!isCurrentReloadGeneration(reloadId)) {
            DebugTelemetry.log(
                "GpxViewModel",
                "reload skipped stale generation=$reloadId files=${files.size}",
            )
            return
        }
        _gpxFiles.value = fileStates
        updateActiveGpxDetails(
            activeFiles = fileStates.filter { it.isActive },
            reloadId = reloadId,
        )

        val aTrack = aPos?.trackId
        if (aTrack != null && aTrack !in existingPaths) {
            dismissInspection()
        }
        val elevationTrack = _elevationProfileUiState.value?.trackPath
        if (elevationTrack != null && elevationTrack !in existingPaths) {
            dismissElevationProfile()
        }
        val guidanceTrack = _turnByTurnGuidanceSession.value?.trackId
        if (guidanceTrack != null && guidanceTrack !in existingPaths) {
            clearTurnByTurnGuidance()
        }
    }

    private suspend fun recoverActivityDemProfileIfNeeded(
        sig: FileSig,
        profile: TrackProfile,
        parsed: ParsedGpxData?,
    ): TrackProfile {
        if (profile.points.hasElevationData()) return profile
        val selectedDemSource = settingsRepository.demSource.first()
        val recoveredPoints =
            profile.points.map { point ->
                val demElevation =
                    routeServices.elevationProvider
                        .resolveElevation(
                            latitude = point.latLong.latitude,
                            longitude = point.latLong.longitude,
                            gpsAltitudeMeters = null,
                            source = SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM,
                            demSource = selectedDemSource,
                        ).elevationMeters
                if (demElevation != null) point.copy(elevation = demElevation) else point
            }
        val recoveredCount = recoveredPoints.count { it.elevation?.isFinite() == true }
        if (recoveredCount == 0) return profile
        DebugTelemetry.log(
            "TraceRecording",
            "event=activity_dem_recovered points=${profile.points.size} recoveredPoints=$recoveredCount " +
                "parsedPoints=${parsed?.points?.size ?: -1} summaryPoints=${parsed?.activitySummary?.pointCount ?: -1} " +
                "summaryDistanceMeters=${parsed?.activitySummary?.distanceMeters?.toInt() ?: -1}",
        )
        return buildProfile(
            sig = sig,
            pts = recoveredPoints,
            elevationFilterConfig = elevationFilterConfig,
        )
    }

    private fun buildSavedActivitySummary(
        profile: TrackProfile,
        parsed: ParsedGpxData,
    ): RecordingDashboardSnapshot? {
        if (!parsed.isActivity) return null

        val points = profile.points
        val hasElevationData = points.hasElevationData()
        if (points.isEmpty()) return null
        parsed.activitySummary?.let { summary ->
            return summary.toRecordingDashboardSnapshot(
                fallbackProfile = profile,
                fallbackPoints = points,
                fallbackDurationSeconds = parsed.activityDurationSec ?: points.durationFromTimestampsSeconds(),
            )
        }

        val durationSeconds = parsed.activityDurationSec ?: points.durationFromTimestampsSeconds()
        val recordedPoints = points.toRecordedTracePoints()
        val lastPoint = points.lastOrNull()
        val averageSpeedMps =
            durationSeconds
                ?.takeIf { it > 0.0 }
                ?.let { profile.totalDistance / it }
        val calorieEstimate =
            if (recordedPoints.size >= 2) {
                estimateRecordingCalories(
                    points = recordedPoints,
                    userWeightKg = userWeightKg,
                    backpackWeightKg = backpackWeightKg,
                    bikeWeightKg = bikeWeightKg,
                    activityProfile = parsed.activitySummary?.activityProfile?.resolvedActivityProfile() ?: activeActivityProfile,
                )
            } else {
                RecordingCalorieEstimate()
            }

        return RecordingDashboardSnapshot(
            activityProfile = parsed.activitySummary?.activityProfile?.resolvedActivityProfile() ?: activeActivityProfile,
            durationSeconds = durationSeconds ?: 0.0,
            totalDurationSeconds = durationSeconds ?: 0.0,
            distanceMeters = profile.totalDistance,
            elevationGainMeters = profile.totalAscent,
            elevationLossMeters = profile.totalDescent,
            currentElevationMeters = lastPoint?.elevation,
            currentSpeedMps = lastPoint?.speedMps ?: points.lastSegmentSpeedMps(),
            averageSpeedMps = averageSpeedMps,
            fastestSpeedMps = points.fastestSpeedMps(),
            gpsAccuracyMeters = points.lastMappedNotNull { it.accuracyMeters },
            pointCount = points.size,
            gpsActiveDurationSeconds = durationSeconds ?: 0.0,
            recordingGapCount = 0,
            recordingMaxGapSeconds = 0.0,
            userWeightKg = userWeightKg,
            backpackWeightKg = backpackWeightKg,
            calorieEstimate = calorieEstimate,
            heartRateBpm = points.lastMappedNotNull { it.heartRateBpm },
            averageHeartRateBpm = points.averageHeartRateBpm(),
            maxHeartRateBpm = points.maxHeartRateBpm(),
            stepCount = points.lastMappedNotNull { it.stepCount },
            cadenceSpm = points.lastMappedNotNull { it.cadenceSpm },
            averageCadenceSpm = points.averageCadenceSpm(),
            maxCadenceSpm = points.maxCadenceSpm(),
            powerWatts = points.lastMappedNotNull { it.powerWatts },
            averagePowerWatts = points.averagePowerWatts(),
            maxPowerWatts = points.maxPowerWatts(),
            powerFromBluetooth = points.lastMappedNotNull { it.powerWatts } != null,
            barometricPressureHpa = points.lastMappedNotNull { it.barometricPressureHpa },
            hasElevationData = hasElevationData,
        )
    }

    private fun GpxActivitySummary.toRecordingDashboardSnapshot(
        fallbackProfile: TrackProfile,
        fallbackPoints: List<TrackPoint>,
        fallbackDurationSeconds: Double?,
    ): RecordingDashboardSnapshot {
        val lastPoint = fallbackPoints.lastOrNull()
        val duration = durationSeconds ?: fallbackDurationSeconds ?: 0.0
        val distance = distanceMeters ?: fallbackProfile.totalDistance
        val fallbackHasElevationData = fallbackPoints.hasElevationData()
        val shouldUseRecoveredElevation =
            fallbackHasElevationData &&
                currentElevationMeters == null &&
                (elevationGainMeters == null || elevationGainMeters == 0.0) &&
                (elevationLossMeters == null || elevationLossMeters == 0.0)
        val recoveredCalories =
            if (shouldUseRecoveredElevation) {
                val recordedPoints = fallbackPoints.toRecordedTracePoints()
                estimateRecordingCalories(
                    points = recordedPoints,
                    userWeightKg = userWeightKg,
                    backpackWeightKg = backpackWeightKg,
                    bikeWeightKg = bikeWeightKg,
                    activityProfile = activityProfile.resolvedActivityProfile(),
                )
            } else {
                null
            }
        return RecordingDashboardSnapshot(
            activityProfile = activityProfile.resolvedActivityProfile(),
            durationSeconds = duration,
            totalDurationSeconds = totalDurationSeconds ?: duration,
            distanceMeters = distance,
            elevationGainMeters =
                if (shouldUseRecoveredElevation) {
                    fallbackProfile.totalAscent
                } else {
                    elevationGainMeters ?: fallbackProfile.totalAscent
                },
            elevationLossMeters =
                if (shouldUseRecoveredElevation) {
                    fallbackProfile.totalDescent
                } else {
                    elevationLossMeters ?: fallbackProfile.totalDescent
                },
            currentElevationMeters = currentElevationMeters ?: lastPoint?.elevation,
            currentSpeedMps = currentSpeedMps ?: lastPoint?.speedMps ?: fallbackPoints.lastSegmentSpeedMps(),
            averageSpeedMps =
                averageSpeedMps
                    ?: duration
                        .takeIf { it > 0.0 }
                        ?.let { distance / it },
            fastestSpeedMps = fastestSpeedMps ?: fallbackPoints.fastestSpeedMps(),
            gpsAccuracyMeters = gpsAccuracyMeters ?: fallbackPoints.lastMappedNotNull { it.accuracyMeters },
            pointCount = pointCount ?: fallbackPoints.size,
            gpsActiveDurationSeconds = gpsActiveDurationSeconds ?: duration,
            recordingGapCount = recordingGapCount ?: 0,
            recordingMaxGapSeconds = recordingMaxGapSeconds ?: 0.0,
            userWeightKg = userWeightKg,
            backpackWeightKg = backpackWeightKg,
            calorieEstimate =
                recoveredCalories
                    ?: RecordingCalorieEstimate(
                        grossKcal = caloriesGrossKcal ?: 0.0,
                        activeKcal = caloriesActiveKcal ?: 0.0,
                        restingKcal = caloriesRestingKcal ?: 0.0,
                        model = calorieModel ?: defaultCalorieModelForActivityProfile(activityProfile),
                        cyclingMechanicalKj = cyclingMechanicalKj ?: 0.0,
                        cyclingPowerSampleSegments = cyclingPowerSampleSegments ?: 0,
                        cyclingPhysicsSegments = cyclingPhysicsSegments ?: 0,
                    ),
            heartRateBpm = fallbackPoints.lastMappedNotNull { it.heartRateBpm },
            averageHeartRateBpm = averageHeartRateBpm ?: heartRateBpm ?: fallbackPoints.averageHeartRateBpm(),
            maxHeartRateBpm = maxHeartRateBpm ?: fallbackPoints.maxHeartRateBpm() ?: heartRateBpm,
            stepCount = stepCount ?: fallbackPoints.lastMappedNotNull { it.stepCount },
            cadenceSpm = cadenceSpm ?: fallbackPoints.lastMappedNotNull { it.cadenceSpm },
            averageCadenceSpm = averageCadenceSpm ?: fallbackPoints.averageCadenceSpm(),
            maxCadenceSpm = maxCadenceSpm ?: fallbackPoints.maxCadenceSpm() ?: cadenceSpm,
            powerWatts = powerWatts ?: fallbackPoints.lastMappedNotNull { it.powerWatts },
            averagePowerWatts = averagePowerWatts ?: fallbackPoints.averagePowerWatts(),
            maxPowerWatts = maxPowerWatts ?: fallbackPoints.maxPowerWatts() ?: powerWatts,
            powerFromBluetooth = (powerWatts ?: fallbackPoints.lastMappedNotNull { it.powerWatts }) != null,
            barometricPressureHpa = barometricPressureHpa ?: fallbackPoints.lastMappedNotNull { it.barometricPressureHpa },
            hasElevationData = fallbackHasElevationData || currentElevationMeters != null,
        )
    }

    private fun RecordingDashboardSnapshot.withRecoveredElevationIfAvailable(
        profile: TrackProfile,
    ): RecordingDashboardSnapshot {
        if (hasElevationData || !profile.points.hasElevationData()) return this
        val recoveredCalories =
            estimateRecordingCalories(
                points = profile.points.toRecordedTracePoints(),
                userWeightKg = userWeightKg,
                backpackWeightKg = backpackWeightKg,
                bikeWeightKg = bikeWeightKg,
                activityProfile = calorieEstimate.model.activityProfileFromCalorieModel(),
            )
        return copy(
            elevationGainMeters = profile.totalAscent,
            elevationLossMeters = profile.totalDescent,
            currentElevationMeters = profile.points.lastMappedNotNull { it.elevation },
            calorieEstimate = recoveredCalories,
            hasElevationData = true,
        )
    }

    fun toggleGpxFile(path: String) {
        viewModelScope.launch {
            val currentFiles = _gpxFiles.value
            val currentActive =
                if (currentFiles.any { it.path == path }) {
                    currentFiles
                        .asSequence()
                        .filter { it.isActive }
                        .map { it.path }
                        .toSet()
                } else {
                    gpxRepository.getActiveGpxFiles().first()
                }
            val newActive =
                if (currentActive.contains(path)) currentActive - path else currentActive + path
            _gpxFiles.value =
                currentFiles.map { file ->
                    if (file.path == path) {
                        file.copy(isActive = path in newActive)
                    } else {
                        file
                    }
                }
            DebugTelemetry.log(
                "GpxViewModel",
                "event=toggle_active_optimistic path=${File(path).name.telemetryToken()} active=${path in newActive}",
            )
            gpxRepository.setActiveGpxFiles(newActive)
        }
    }

    suspend fun resetActiveGpxFilesAndWait() {
        gpxRepository.setActiveGpxFiles(emptySet())
        _gpxFiles.value = _gpxFiles.value.map { file -> file.copy(isActive = false) }
        _activeGpxDetails.value = emptyList()
        DebugTelemetry.log("GpxViewModel", "event=reset_active_files")
    }

    private suspend fun syncLinkedGpxWaypointPoiFolders(activePaths: Set<String>) {
        val previousPaths = lastObservedActiveGpxPaths
        lastObservedActiveGpxPaths = activePaths
        if (previousPaths == null) {
            activePaths.forEach { path -> setLinkedGpxWaypointPoiEnabled(path = path, enabled = true) }
            return
        }
        (previousPaths - activePaths).forEach { path ->
            setLinkedGpxWaypointPoiEnabled(path = path, enabled = false)
        }
        (activePaths - previousPaths).forEach { path ->
            setLinkedGpxWaypointPoiEnabled(path = path, enabled = true)
        }
    }

    private suspend fun setLinkedGpxWaypointPoiEnabled(
        path: String,
        enabled: Boolean,
    ) {
        if (settingsRepository.linkGpxWaypointPoiFolders.first()) {
            val gpxFileName = File(path).name
            val linkedPoiFiles = findLinkedGpxWaypointPoiFiles(gpxFileName)
            if (linkedPoiFiles.isNotEmpty()) {
                val changed = syncLinkedGpxWaypointPoiFiles(linkedPoiFiles, gpxFileName, enabled)
                if (changed) {
                    syncManager.requestPoiSync()
                    DebugTelemetry.log(
                        "GpxViewModel",
                        "event=linked_waypoints_sync file=${gpxFileName.telemetryToken()} " +
                            "active=$enabled folders=${linkedPoiFiles.size}",
                    )
                }
            }
        }
    }

    private suspend fun findLinkedGpxWaypointPoiFiles(gpxFileName: String): List<File> =
        runCatching { poiRepository.findGpxWaypointPoiFiles(gpxFileName) }
            .getOrElse { error ->
                DebugTelemetry.log(
                    "GpxViewModel",
                    "event=linked_waypoints_sync_failed file=${gpxFileName.telemetryToken()} " +
                        "reason=${error.javaClass.simpleName}",
                )
                emptyList()
            }

    private suspend fun syncLinkedGpxWaypointPoiFiles(
        linkedPoiFiles: List<File>,
        gpxFileName: String,
        enabled: Boolean,
    ): Boolean {
        var changed = false
        linkedPoiFiles.forEach { poiFile ->
            runCatching {
                val categories = poiRepository.readCategories(poiFile.absolutePath)
                poiRepository.setFileEnabled(poiFile.absolutePath, enabled)
                poiRepository.setEnabledCategories(
                    path = poiFile.absolutePath,
                    enabledCategoryIds = if (enabled) categories.map { it.id }.toSet() else emptySet(),
                )
                changed = true
            }.onFailure { error ->
                DebugTelemetry.log(
                    "GpxViewModel",
                    "event=linked_waypoints_sync_failed file=${gpxFileName.telemetryToken()} " +
                        "poi=${poiFile.name.telemetryToken()} reason=${error.javaClass.simpleName}",
                )
            }
        }
        return changed
    }

    fun startTurnByTurnGuidance(
        path: String,
        onComplete: (Result<GpxGuidanceStartResult>) -> Unit,
    ) {
        viewModelScope.launch {
            val buildStartElapsedMs = SystemClock.elapsedRealtime()
            DebugTelemetry.log(
                "TurnByTurnStart",
                "event=build_start file=${File(path).name.telemetryToken()} activeSession=${_turnByTurnGuidanceSession.value?.trackId?.let { File(it).name.telemetryToken() } ?: "none"}",
            )
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        buildTurnByTurnGuidanceSession(
                            path = path,
                            startReached = false,
                            reversed = false,
                        )
                    }
                }

            result
                .onSuccess { buildResult ->
                    val session = buildResult.session
                    _turnByTurnGuidanceSession.value = session
                    _turnByTurnGuidancePaused.value = false
                    persistTurnByTurnGuidance(
                        trackPath = session.trackId,
                        startReached = session.startReached,
                        reversed = session.reversed,
                    )
                    val currentActive = gpxRepository.getActiveGpxFiles().first()
                    if (session.trackId !in currentActive) {
                        gpxRepository.setActiveGpxFiles(currentActive + session.trackId)
                    }
                    DebugTelemetry.log(
                        "TurnByTurnStart",
                        "event=build_success elapsedMs=${(SystemClock.elapsedRealtime() - buildStartElapsedMs).coerceAtLeast(0L)} " +
                            "file=${File(path).name.telemetryToken()} points=${session.trackPoints.size} " +
                            "instructions=${session.instructions.size} distanceMeters=${session.totalDistanceMeters.toInt()} " +
                            "guidanceMode=${buildResult.guidanceMode} geometry=original_gpx " +
                            "embeddedHints=${session.instructions.count { it.source == RouteInstructionSource.BROUTER_HINT }} " +
                            "generatedTurns=${session.instructions.count { it.source == RouteInstructionSource.GPX_GEOMETRY }} " +
                            "warning=${buildResult.warningMessage?.telemetryToken() ?: "none"}",
                    )
                }.onFailure { error ->
                    DebugTelemetry.log(
                        "TurnByTurnStart",
                        "event=build_failure elapsedMs=${(SystemClock.elapsedRealtime() - buildStartElapsedMs).coerceAtLeast(0L)} " +
                            "file=${File(path).name.telemetryToken()} error=${error.javaClass.simpleName.telemetryToken()} " +
                            "message=${error.localizedMessage?.telemetryToken() ?: "na"}",
                    )
                }
            onComplete(result.map { GpxGuidanceStartResult(warningMessage = it.warningMessage) })
        }
    }

    fun stopTurnByTurnGuidance() {
        viewModelScope.launch {
            clearTurnByTurnGuidance()
        }
    }

    fun pauseTurnByTurnGuidance() {
        if (_turnByTurnGuidanceSession.value == null) return
        _turnByTurnGuidancePaused.value = true
    }

    fun resumeTurnByTurnGuidance() {
        if (_turnByTurnGuidanceSession.value == null) return
        _turnByTurnGuidancePaused.value = false
    }

    fun buildTurnByTurnGuideBackRoute(
        origin: LatLong,
        destination: LatLong,
        onComplete: (Result<List<LatLong>>) -> Unit,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        routeServices.planner
                            .createRoute(
                                RoutePlannerRequest(
                                    origin = origin,
                                    destination = destination,
                                ),
                            ).points
                            .map { it.latLong }
                            .also { points ->
                                require(points.size >= 2) { "BRouter did not return a guide-back route." }
                            }
                    }
                }
            onComplete(result)
        }
    }

    fun markTurnByTurnStartReached() {
        val current = _turnByTurnGuidanceSession.value ?: return
        if (current.startReached) return
        val updated = current.copy(startReached = true)
        _turnByTurnGuidanceSession.value = updated
        viewModelScope.launch {
            settingsRepository.setTurnByTurnStartReached(true)
        }
    }

    fun reverseTurnByTurnGuidance() {
        val current = _turnByTurnGuidanceSession.value ?: return
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        buildTurnByTurnGuidanceSession(
                            path = current.trackId,
                            startReached = false,
                            reversed = !current.reversed,
                        )
                    }
                }
            result.onSuccess { buildResult ->
                val session = buildResult.session
                _turnByTurnGuidanceSession.value = session
                _turnByTurnGuidancePaused.value = false
                persistTurnByTurnGuidance(
                    trackPath = session.trackId,
                    startReached = session.startReached,
                    reversed = session.reversed,
                )
            }
        }
    }

    private suspend fun restoreTurnByTurnGuidanceSession() {
        val persistedPath = settingsRepository.turnByTurnActiveTrackPath.first() ?: return
        val startReached = settingsRepository.turnByTurnStartReached.first()
        val reversed = settingsRepository.turnByTurnActiveTrackReversed.first()
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    buildTurnByTurnGuidanceSession(
                        path = persistedPath,
                        startReached = startReached,
                        reversed = reversed,
                    )
                }
            }

        result
            .onSuccess { buildResult ->
                val session = buildResult.session
                _turnByTurnGuidanceSession.value = session
                _turnByTurnGuidancePaused.value = false
                val currentActive = gpxRepository.getActiveGpxFiles().first()
                if (session.trackId !in currentActive) {
                    gpxRepository.setActiveGpxFiles(currentActive + session.trackId)
                }
            }.onFailure {
                clearTurnByTurnGuidance()
            }
    }

    private suspend fun buildTurnByTurnGuidanceSession(
        path: String,
        startReached: Boolean,
        reversed: Boolean,
    ): GpxGuidanceBuildResult {
        val file = File(path)
        require(file.exists()) { "The GPX could not be found on disk." }

        val absolutePath = file.absolutePath
        val profile = getOrBuildProfile(path = absolutePath, file = file, sig = sigOf(file))
        require(profile.points.size >= 2) {
            "The GPX does not contain enough points for guidance."
        }
        val displayTitle =
            _gpxFiles.value.firstOrNull { it.path == absolutePath }?.displayTitle
                ?: normalizeUserFacingGpxText(file.nameWithoutExtension)
                ?: file.nameWithoutExtension
        val basePoints =
            if (reversed) {
                profile.points.asReversed()
            } else {
                profile.points
            }

        return GpxGuidanceBuildResult(
            session =
                buildGpxGuidanceSession(
                    trackId = absolutePath,
                    trackTitle = if (reversed) "$displayTitle reverse" else displayTitle,
                    trackPoints = basePoints,
                    startReached = startReached,
                    reversed = reversed,
                    tuning = GpxGuidanceTuning(),
                ),
        )
    }

    private suspend fun persistTurnByTurnGuidance(
        trackPath: String,
        startReached: Boolean,
        reversed: Boolean,
    ) {
        settingsRepository.setTurnByTurnActiveTrackPath(trackPath)
        settingsRepository.setTurnByTurnStartReached(startReached)
        settingsRepository.setTurnByTurnActiveTrackReversed(reversed)
    }

    private suspend fun clearTurnByTurnGuidance() {
        _turnByTurnGuidanceSession.value = null
        _turnByTurnGuidancePaused.value = false
        settingsRepository.setTurnByTurnActiveTrackPath(null)
        settingsRepository.setTurnByTurnStartReached(false)
        settingsRepository.setTurnByTurnActiveTrackReversed(false)
    }

    fun deleteGpxFile(path: String) {
        viewModelScope.launch {
            gpxRepository.deleteGpxFile(path)
            removeCachedGpx(path)
            if (_turnByTurnGuidanceSession.value?.trackId == path) stopTurnByTurnGuidance()
            if (aPos?.trackId == path) dismissInspection()
            if (_elevationProfileUiState.value?.trackPath == path) dismissElevationProfile()
            reloadFromDisk()
        }
    }

    fun renameGpxFile(
        filePath: String,
        newName: String,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        routeToolOperations.renameGpxFileOnDisk(
                            filePath = filePath,
                            newName = newName,
                        )
                    }
                }
            result.getOrNull()?.let { savedFile ->
                val renamedWaypointFolders =
                    poiRepository.updateLinkedGpxWaypointFileName(
                        previousGpxFileName = File(filePath).name,
                        newGpxFileName = savedFile.name,
                    )
                if (renamedWaypointFolders > 0) {
                    syncManager.requestPoiSync()
                    val activePaths = gpxRepository.getActiveGpxFiles().first()
                    setLinkedGpxWaypointPoiEnabled(
                        path = savedFile.absolutePath,
                        enabled = savedFile.absolutePath in activePaths,
                    )
                }
                clearAllGpxCaches()
                if (_turnByTurnGuidanceSession.value?.trackId == filePath) stopTurnByTurnGuidance()
                if (aPos?.trackId == filePath) dismissInspection()
                if (_elevationProfileUiState.value?.trackPath == filePath) dismissElevationProfile()
                reloadFromDisk()
            }
            onComplete(result.map { })
        }
    }

    fun sendGpxToPhone(path: String) {
        val fileState = _gpxFiles.value.firstOrNull { it.path == path }
        val displayName = fileState?.displayTitle ?: File(path).nameWithoutExtension
        viewModelScope.launch {
            _exportUiState.value =
                GpxExportUiState(
                    filePath = path,
                    isSending = true,
                    message = "Sending ${displayName.take(18)}…",
                )
            val result =
                withContext(Dispatchers.IO) {
                    gpxExportRepository.sendGpxToPhone(
                        file = File(path),
                        displayName = displayName,
                    )
                }
            _exportUiState.value =
                result.fold(
                    onSuccess = {
                        GpxExportUiState(
                            filePath = path,
                            message = "Sent to phone",
                        )
                    },
                    onFailure = { error ->
                        GpxExportUiState(
                            filePath = path,
                            message =
                                error.localizedMessage
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "Send failed",
                        )
                    },
                )
            delay(3_000L)
            if (_exportUiState.value.filePath == path && !_exportUiState.value.isSending) {
                _exportUiState.value = GpxExportUiState()
            }
        }
    }

    fun sendGpxFilesToPhone(paths: List<String>) {
        val uniquePaths = paths.distinct().filter { path -> _gpxFiles.value.any { it.path == path } }
        if (uniquePaths.isEmpty()) return
        viewModelScope.launch {
            var sentCount = 0
            var failedCount = 0
            uniquePaths.forEachIndexed { index, path ->
                val fileState = _gpxFiles.value.firstOrNull { it.path == path }
                val displayName = fileState?.displayTitle ?: File(path).nameWithoutExtension
                _exportUiState.value =
                    GpxExportUiState(
                        filePath = path,
                        isSending = true,
                        message = "Sending ${index + 1}/${uniquePaths.size}…",
                    )
                val result =
                    withContext(Dispatchers.IO) {
                        gpxExportRepository.sendGpxToPhone(
                            file = File(path),
                            displayName = displayName,
                        )
                    }
                result.fold(
                    onSuccess = {
                        sentCount += 1
                        _exportUiState.value =
                            GpxExportUiState(
                                filePath = path,
                                message = "Sent ${index + 1}/${uniquePaths.size}",
                            )
                    },
                    onFailure = { error ->
                        failedCount += 1
                        _exportUiState.value =
                            GpxExportUiState(
                                filePath = path,
                                message =
                                    error.localizedMessage
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "Send failed",
                            )
                    },
                )
                delay(500L)
            }

            _exportUiState.value =
                GpxExportUiState(
                    filePath = uniquePaths.last(),
                    message = batchSendSummary(sentCount = sentCount, failedCount = failedCount),
                )
            delay(3_000L)
            if (!_exportUiState.value.isSending) {
                _exportUiState.value = GpxExportUiState()
            }
        }
    }

    fun showElevationProfile(path: String) {
        viewModelScope.launch {
            val uiState =
                withContext(Dispatchers.IO) {
                    val file = File(path)
                    if (!file.exists()) return@withContext null

                    val profile = getOrBuildProfile(path = path, file = file, sig = sigOf(file))
                    val totalDistance = profile.totalDistance
                    val totalAscent = profile.totalAscent
                    val totalDescent = profile.totalDescent
                    val etaProjection = getOrBuildEtaProjection(path = path, sig = profile.sig, profile = profile)
                    val totalDurationSec = etaProjection?.totalSeconds
                    val rawSamples =
                        profile.points.mapIndexedNotNull { index, point ->
                            val elevation = point.elevation ?: return@mapIndexedNotNull null
                            val distance = profile.cumDist.getOrElse(index) { totalDistance }
                            ElevationSample(
                                distance = distance,
                                elevation = elevation,
                                cumulativeAscent = profile.cumAscent.getOrElse(index) { totalAscent },
                                cumulativeDescent = profile.cumDescent.getOrElse(index) { totalDescent },
                                cumulativeDurationSec = etaProjection?.secondsAtPointIndex(index),
                            )
                        }
                    val samples = downsampleElevationSamples(rawSamples, maxSamples = 120)
                    val trackTitle =
                        _gpxFiles.value.firstOrNull { it.path == path }?.displayTitle
                            ?: file.nameWithoutExtension

                    GpxElevationProfileUiState(
                        trackPath = path,
                        trackTitle = trackTitle,
                        totalDistance = totalDistance,
                        totalAscent = totalAscent,
                        totalDescent = totalDescent,
                        totalDurationSec = totalDurationSec,
                        samples = samples,
                        minElevation = samples.minOfOrNull { it.elevation },
                        maxElevation = samples.maxOfOrNull { it.elevation },
                    )
                }
            _elevationProfileUiState.value = uiState
        }
    }

    fun dismissElevationProfile() {
        _elevationProfileUiState.value = null
    }

    private suspend fun updateActiveGpxDetails(
        activeFiles: List<GpxFileState>,
        reloadId: Long,
    ) {
        val details =
            withContext(Dispatchers.IO) {
                activeFiles.mapNotNull { fileState ->
                    val file = File(fileState.path)
                    if (!file.exists()) return@mapNotNull null

                    val path = file.absolutePath
                    val profile =
                        getOrBuildProfile(
                            path = path,
                            file = file,
                            sig = sigOf(file),
                            cacheGeneration = reloadId,
                        )

                    val start = profile.points.firstOrNull()?.latLong
                    val end = profile.points.lastOrNull()?.latLong

                    GpxTrackDetails(
                        id = path,
                        points = profile.points.map { it.latLong },
                        trackPoints = profile.points,
                        title = fileState.displayTitle,
                        distance = profile.totalDistance,
                        elevationGain = profile.totalAscent,
                        startPoint = start,
                        endPoint = end,
                    )
                }
            }

        if (!isCurrentReloadGeneration(reloadId)) {
            DebugTelemetry.log(
                "GpxViewModel",
                "active details skipped stale generation=$reloadId activeFiles=${activeFiles.size}",
            )
            return
        }
        _activeGpxDetails.value = details
    }

    private fun getOrBuildProfile(
        path: String,
        file: File,
        sig: FileSig,
        cacheGeneration: Long? = null,
    ): TrackProfile {
        val filterConfig = elevationFilterConfig
        val cached =
            cachedProfile(
                path = path,
                sig = sig,
                elevationConfig = filterConfig,
            )
        if (
            cached != null
        ) {
            return cached
        }

        return buildProfile(
            sig = sig,
            pts = parseGpxPoints(file),
            elevationFilterConfig = filterConfig,
        ).also { profile ->
            if (cacheGeneration == null || isCurrentReloadGeneration(cacheGeneration)) {
                putProfileCache(path, profile)
            }
        }
    }

    private fun getOrBuildEtaProjection(
        path: String,
        sig: FileSig,
        profile: TrackProfile,
        modelConfig: GpxEtaModelConfig = etaModelConfig,
        cacheGeneration: Long? = null,
    ): GpxEtaProjection? {
        val cached =
            cachedEta(
                path = path,
                sig = sig,
                modelConfig = modelConfig,
            )
        if (cached != null) {
            return cached.projection
        }

        val projection = buildEtaProjection(profile, modelConfig)
        if (cacheGeneration == null || isCurrentReloadGeneration(cacheGeneration)) {
            putEtaCache(
                path = path,
                sig = sig,
                modelConfig = modelConfig,
                projection = projection,
            )
        }
        return projection
    }

    private suspend fun refreshOpenEtaUi() {
        val a = aPos
        if (a != null) {
            val b = bPos
            if (b != null && b.trackId == a.trackId) {
                publishAB(a.trackId, a, b)
            } else {
                publishA(a.trackId, a)
            }
        }

        val openProfilePath = _elevationProfileUiState.value?.trackPath
        if (openProfilePath != null) {
            showElevationProfile(openProfilePath)
        }
    }

    private fun downsampleElevationSamples(
        samples: List<ElevationSample>,
        maxSamples: Int,
    ): List<ElevationSample> {
        if (samples.size <= maxSamples || maxSamples <= 1) return samples

        val lastIndex = samples.lastIndex
        val step = lastIndex.toDouble() / (maxSamples - 1).toDouble()
        return List(maxSamples) { sampleIndex ->
            val pointIndex = (sampleIndex * step).toInt().coerceIn(0, lastIndex)
            samples[pointIndex]
        }
    }

    // -------------------------------------------------------------------------
    // Inspection API
    // -------------------------------------------------------------------------

    fun onMapLongPress(press: LatLong) {
        viewModelScope.launch(Dispatchers.Default) {
            val tracks = activeGpxDetails.value
            if (tracks.isEmpty()) return@launch

            val allowedTrackId = if (selectingB) aPos?.trackId else null

            val found =
                findClosestTrackPosition(
                    press = press,
                    tracks = tracks,
                    profileProvider = { id -> cachedProfile(id) },
                    allowedTrackId = allowedTrackId,
                ) ?: return@launch

            val pos = found.pos
            val snapped = found.snapped
            val distToLineMeters = found.distanceToLineMeters

            if (distToLineMeters > pressThresholdMeters) return@launch

            // Cancel any pending delayed popup (A or AB)
            popupDelayJob?.cancel()

            if (!selectingB) {
                aPos = pos
                bPos = null
                selectingB = false
                _selectingPointB.value = false
                selectBTimeoutJob?.cancel()

                _selectedPointB.value = null
                _selectedPointA.value = snapped

                popupDelayJob =
                    viewModelScope.launch(Dispatchers.Default) {
                        delay(popupDelayMs)
                        publishA(pos.trackId, pos)
                    }
            } else {
                val a = aPos ?: return@launch
                selectingB = false
                _selectingPointB.value = false
                selectBTimeoutJob?.cancel()
                bPos = pos

                _selectedPointB.value = snapped

                popupDelayJob =
                    viewModelScope.launch(Dispatchers.Default) {
                        delay(popupDelayMs)
                        publishAB(a.trackId, a, pos)
                    }
            }
        }
    }

    fun startSelectingB() {
        val a = aPos ?: return
        selectingB = true
        _selectingPointB.value = true
        bPos = null

        // Hide popup for B selection
        _inspectionUiState.value = null

        // Keep A visible; clear B while selecting
        _selectedPointB.value = null

        // Cancel any pending popup (A delayed)
        popupDelayJob?.cancel()

        selectBTimeoutJob?.cancel()
        selectBTimeoutJob =
            viewModelScope.launch {
                delay(15_000L)
                if (selectingB && aPos == a) {
                    selectingB = false
                    _selectingPointB.value = false
                    publishA(a.trackId, a)
                }
            }
    }

    fun cancelSelectingB() {
        val a = aPos ?: return
        selectingB = false
        _selectingPointB.value = false
        bPos = null
        selectBTimeoutJob?.cancel()
        popupDelayJob?.cancel()
        _selectedPointB.value = null
        publishA(a.trackId, a)
    }

    fun dismissInspection() {
        selectingB = false
        _selectingPointB.value = false
        aPos = null
        bPos = null
        selectBTimeoutJob?.cancel()
        popupDelayJob?.cancel()

        _inspectionUiState.value = null
        _selectedPointA.value = null
        _selectedPointB.value = null
    }

    internal fun applyRouteToolModification(
        session: RouteToolSession,
        onProgress: (String) -> Unit = {},
        onComplete: (Result<RouteToolSaveResult>) -> Unit,
    ) {
        if (session.options.toolKind != RouteToolKind.MODIFY) {
            onComplete(Result.failure(IllegalArgumentException("Only GPX modify actions are supported here.")))
            return
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { routeToolOperations.applyModification(session, onProgress) }
                }
            if (result.isSuccess) {
                clearAllGpxCaches()
                dismissInspection()
                reloadFromDisk()
                onComplete(result)
            } else {
                onComplete(result)
            }
        }
    }

    internal fun previewRouteToolModification(
        session: RouteToolSession,
        onComplete: (Result<RouteToolModifyPreview>) -> Unit,
    ) {
        if (session.options.toolKind != RouteToolKind.MODIFY) {
            onComplete(Result.failure(IllegalArgumentException("Only GPX modify actions are supported here.")))
            return
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { routeToolOperations.previewModification(session) }
                }
            onComplete(result)
        }
    }

    internal fun previewRouteToolCreation(
        session: RouteToolSession,
        currentLocation: LatLong?,
        onComplete: (Result<RouteToolCreatePreview>) -> Unit,
    ) {
        if (session.options.toolKind != RouteToolKind.CREATE) {
            onComplete(Result.failure(IllegalArgumentException("Only GPX create actions are supported here.")))
            return
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { routeToolOperations.previewCreation(session, currentLocation) }
                }
            onComplete(result)
        }
    }

    internal fun applyRouteToolCreation(
        session: RouteToolSession,
        currentLocation: LatLong?,
        preview: RouteToolCreatePreview? = null,
        onProgress: (String) -> Unit = {},
        onComplete: (Result<RouteToolSaveResult>) -> Unit,
    ) {
        if (session.options.toolKind != RouteToolKind.CREATE) {
            onComplete(Result.failure(IllegalArgumentException("Only GPX create actions are supported here.")))
            return
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        routeToolOperations.applyCreation(
                            session = session,
                            currentLocation = currentLocation,
                            preview = preview,
                            onProgress = onProgress,
                        )
                    }
                }
            if (result.isSuccess) {
                clearAllGpxCaches()
                dismissInspection()
                onComplete(result)
                launch { reloadFromDisk() }
            } else {
                onComplete(result)
            }
        }
    }

    internal fun renameRouteToolResult(
        filePath: String,
        newName: String,
        onComplete: (Result<RouteToolSaveResult>) -> Unit,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { routeToolOperations.renameSavedRoute(filePath, newName) }
                }
            if (result.isSuccess) {
                clearAllGpxCaches()
                if (aPos?.trackId == filePath) dismissInspection()
                if (_elevationProfileUiState.value?.trackPath == filePath) dismissElevationProfile()
                reloadFromDisk()
            }
            onComplete(result)
        }
    }

    // -------------------------------------------------------------------------
    // Publishing UI state
    // -------------------------------------------------------------------------

    private fun publishA(
        trackId: String,
        pos: TrackPosition,
    ) {
        val profile = cachedProfile(trackId) ?: return
        val title = activeGpxDetails.value.firstOrNull { it.id == trackId }?.title
        val etaProjection = getOrBuildEtaProjection(path = trackId, sig = profile.sig, profile = profile)

        _inspectionUiState.value =
            buildInspectionAUiState(
                trackTitle = title,
                profile = profile,
                pos = pos,
                etaProjection = etaProjection,
            )
    }

    private fun publishAB(
        trackId: String,
        a: TrackPosition,
        b: TrackPosition,
    ) {
        if (a.trackId != b.trackId) return
        val profile = cachedProfile(trackId) ?: return
        val title = activeGpxDetails.value.firstOrNull { it.id == trackId }?.title
        val etaProjection = getOrBuildEtaProjection(path = trackId, sig = profile.sig, profile = profile)

        _inspectionUiState.value =
            buildInspectionABUiState(
                trackTitle = title,
                profile = profile,
                a = a,
                b = b,
                etaProjection = etaProjection,
            )
    }
}

private fun List<TrackPoint>.durationFromTimestampsSeconds(): Double? {
    val first = firstNotNullOfOrNull { it.timeMillis } ?: return null
    val last = lastMappedNotNull { it.timeMillis } ?: return null
    return ((last - first).coerceAtLeast(0L) / 1000.0).takeIf { it > 0.0 }
}

private inline fun <T, R : Any> List<T>.lastMappedNotNull(transform: (T) -> R?): R? {
    for (index in lastIndex downTo 0) {
        transform(this[index])?.let { return it }
    }
    return null
}

private fun List<TrackPoint>.lastSegmentSpeedMps(): Float? {
    val last = lastOrNull() ?: return null
    val previous = dropLast(1).lastOrNull() ?: return null
    val lastTime = last.timeMillis ?: return null
    val previousTime = previous.timeMillis ?: return null
    val elapsedSeconds = ((lastTime - previousTime).coerceAtLeast(0L) / 1000.0).takeIf { it > 0.0 } ?: return null
    val distanceMeters = haversineMeters(previous.latLong, last.latLong)
    return (distanceMeters / elapsedSeconds).toFloat().takeIf { it.isFinite() && it >= 0f }
}

private fun List<TrackPoint>.fastestSpeedMps(): Double? =
    mapNotNull { point -> point.speedMps?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } }
        .maxOrNull()

private fun List<TrackPoint>.averageHeartRateBpm(): Int? {
    val values = mapNotNull { point -> point.heartRateBpm?.takeIf { it > 0 } }
    if (values.isEmpty()) return null
    return values.average().roundToInt()
}

private fun List<TrackPoint>.maxHeartRateBpm(): Int? =
    mapNotNull { point -> point.heartRateBpm?.takeIf { it > 0 } }
        .maxOrNull()

private fun List<TrackPoint>.hasElevationData(): Boolean = any { point -> point.elevation?.isFinite() == true }

private fun List<TrackPoint>.averageCadenceSpm(): Int? {
    val values = mapNotNull { point -> point.cadenceSpm?.takeIf { it > 0 } }
    if (values.isEmpty()) return null
    return values.average().roundToInt()
}

private fun List<TrackPoint>.maxCadenceSpm(): Int? =
    mapNotNull { point -> point.cadenceSpm?.takeIf { it > 0 } }
        .maxOrNull()

private fun List<TrackPoint>.averagePowerWatts(): Int? {
    val values = mapNotNull { point -> point.powerWatts?.takeIf { it >= 0 } }
    if (values.isEmpty()) return null
    return values.average().roundToInt()
}

private fun List<TrackPoint>.maxPowerWatts(): Int? =
    mapNotNull { point -> point.powerWatts?.takeIf { it >= 0 } }
        .maxOrNull()

private fun List<TrackPoint>.toRecordedTracePoints(): List<RecordedTracePoint> =
    mapNotNull { point ->
        val timeMillis = point.timeMillis ?: return@mapNotNull null
        RecordedTracePoint(
            latLong = point.latLong,
            elevationMeters = point.elevation,
            timeMillis = timeMillis,
            accuracyMeters = point.accuracyMeters,
            speedMps = point.speedMps,
            heartRateBpm = point.heartRateBpm,
            stepCount = point.stepCount,
            cadenceSpm = point.cadenceSpm,
            powerWatts = point.powerWatts,
            barometricPressureHpa = point.barometricPressureHpa,
        )
    }

private fun String?.resolvedActivityProfile(): String =
    when (this) {
        SettingsRepository.ACTIVITY_PROFILE_BIKE -> SettingsRepository.ACTIVITY_PROFILE_BIKE
        SettingsRepository.ACTIVITY_PROFILE_HIKE,
        SettingsRepository.ACTIVITY_PROFILE_WALK_HIKE,
        -> SettingsRepository.ACTIVITY_PROFILE_HIKE
        null -> SettingsRepository.DEFAULT_ACTIVITY_PROFILE
        else -> SettingsRepository.DEFAULT_ACTIVITY_PROFILE
    }

private fun String?.activityProfileFromCalorieModel(): String =
    if (this?.startsWith("cycling_", ignoreCase = true) == true) {
        SettingsRepository.ACTIVITY_PROFILE_BIKE
    } else {
        SettingsRepository.DEFAULT_ACTIVITY_PROFILE
    }

private fun defaultCalorieModelForActivityProfile(activityProfile: String?): String =
    if (activityProfile.resolvedActivityProfile() == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        "cycling_physics_fallback_v1"
    } else {
        "pandolf_santee_segment_v2"
    }

private fun batchSendSummary(
    sentCount: Int,
    failedCount: Int,
): String =
    when {
        failedCount == 0 -> {
            if (sentCount == 1) {
                "Sent 1 GPX"
            } else {
                "Sent $sentCount GPX"
            }
        }
        sentCount == 0 -> "Send failed"
        else -> "Sent $sentCount, failed $failedCount"
    }
