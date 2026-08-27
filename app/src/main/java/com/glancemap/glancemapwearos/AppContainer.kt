package com.glancemap.glancemapwearos

import android.app.Application
import android.content.Context
import com.glancemap.glancemapwearos.core.routing.BRouterRoutePlanner
import com.glancemap.glancemapwearos.core.routing.RoutePlanner
import com.glancemap.glancemapwearos.data.repository.*
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemeRepository
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemeRepositoryImpl
import com.glancemap.glancemapwearos.domain.sensors.CompassViewModel
import com.glancemap.glancemapwearos.presentation.SyncManager
import com.glancemap.glancemapwearos.presentation.features.download.DownloadViewModel
import com.glancemap.glancemapwearos.presentation.features.download.OamBundleDownloader
import com.glancemap.glancemapwearos.presentation.features.download.OamDownloadNetworkMonitor
import com.glancemap.glancemapwearos.presentation.features.download.OamDownloadNotificationController
import com.glancemap.glancemapwearos.presentation.features.download.OamDownloadServiceClient
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxRouteServices
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxViewModel
import com.glancemap.glancemapwearos.presentation.features.maps.MapViewModel
import com.glancemap.glancemapwearos.presentation.features.maps.theme.ThemeViewModel
import com.glancemap.glancemapwearos.presentation.features.navigate.LocationViewModel
import com.glancemap.glancemapwearos.presentation.features.poi.PoiViewModel
import com.glancemap.glancemapwearos.presentation.features.recording.RecordingElevationProvider
import com.glancemap.glancemapwearos.presentation.features.recording.RecordingRuntimeDependencies
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingDraftStore
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingViewModel
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppContainer(
    private val applicationContext: Context,
    private val coroutineScope: CoroutineScope,
) {
    val syncManager: SyncManager by lazy { SyncManager(scope = coroutineScope) }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl.getInstance(applicationContext)
    }

    val gpxRepository: GpxRepositoryImpl by lazy {
        GpxRepositoryImpl(applicationContext)
    }

    val gpxExportRepository: GpxExportRepository by lazy {
        GpxExportRepositoryImpl(applicationContext)
    }

    val mapRepository: MapRepositoryImpl by lazy {
        MapRepositoryImpl(applicationContext)
    }

    val poiRepository: PoiRepository by lazy {
        PoiRepositoryImpl(applicationContext)
    }

    val userPoiRepository: UserPoiRepository by lazy {
        UserPoiRepository(applicationContext)
    }

    val themeRepository: ThemeRepository by lazy {
        ThemeRepositoryImpl(applicationContext)
    }

    val compassViewModel: CompassViewModel by lazy {
        CompassViewModel(applicationContext as Application)
    }

    private val routePlanner: RoutePlanner by lazy {
        BRouterRoutePlanner(applicationContext)
    }

    val gpxViewModel: GpxViewModel by lazy {
        GpxViewModel(
            gpxRepository = gpxRepository,
            gpxExportRepository = gpxExportRepository,
            syncManager = syncManager,
            settingsRepository = settingsRepository,
            poiRepository = poiRepository,
            routeServices =
                GpxRouteServices(
                    planner = routePlanner,
                    elevationProvider = RecordingElevationProvider(applicationContext),
                ),
        )
    }

    val mapViewModel: MapViewModel by lazy {
        // ✅ FIX: MapViewModel expects context first
        MapViewModel(
            context = applicationContext,
            settingsRepository = settingsRepository,
            mapRepository = mapRepository,
            syncManager = syncManager,
            themeRepository = themeRepository,
        )
    }

    val poiViewModel: PoiViewModel by lazy {
        PoiViewModel(
            poiRepository = poiRepository,
            userPoiRepository = userPoiRepository,
            settingsRepository = settingsRepository,
            syncManager = syncManager,
        )
    }

    val downloadViewModel: DownloadViewModel by lazy {
        DownloadViewModel(
            downloader =
                OamBundleDownloader(
                    context = applicationContext,
                    mapRepository = mapRepository,
                    poiRepository = poiRepository,
                ),
            notificationController = OamDownloadNotificationController(applicationContext),
            networkMonitor = OamDownloadNetworkMonitor(applicationContext),
            settingsRepository = settingsRepository,
            downloadServiceClient = OamDownloadServiceClient(applicationContext),
        )
    }

    val themeViewModel: ThemeViewModel by lazy {
        ThemeViewModel(
            themeRepository = themeRepository,
            context = applicationContext,
            settingsRepository = settingsRepository,
        )
    }

    val settingsViewModel: SettingsViewModel by lazy {
        SettingsViewModel(settingsRepository)
    }

    val locationViewModel: LocationViewModel by lazy {
        LocationViewModel(
            application = applicationContext as Application,
            settingsRepository = settingsRepository,
        )
    }

    val traceRecordingViewModel: TraceRecordingViewModel by lazy {
        val viewModel =
            TraceRecordingViewModel(
                gpxRepository = gpxRepository,
                settingsRepository = settingsRepository,
                syncManager = syncManager,
                elevationProvider = RecordingElevationProvider(applicationContext),
                draftStore = TraceRecordingDraftStore(applicationContext),
                runtimeDependencies =
                    RecordingRuntimeDependencies(
                        applicationContext = applicationContext,
                        requestImmediateLocation = { source ->
                            locationViewModel.requestImmediateLocation(source = source)
                        },
                    ),
            )
        startRecordingLocationBridge(viewModel)
        viewModel
    }

    private fun startRecordingLocationBridge(traceRecordingViewModel: TraceRecordingViewModel) {
        coroutineScope.launch {
            locationViewModel.recordingLocations.collect { location ->
                traceRecordingViewModel.onLocation(location)?.join()
            }
        }
        coroutineScope.launch {
            locationViewModel.gpsSignalSnapshot.collectLatest { snapshot ->
                traceRecordingViewModel.onGpsSignalSnapshot(snapshot)
            }
        }
        coroutineScope.launch {
            locationViewModel.effectiveGpsIntervalMs.collect { intervalMs ->
                traceRecordingViewModel.onEffectiveRecordingSamplingIntervalChanged(intervalMs)
            }
        }
    }
}
