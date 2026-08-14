package com.glancemap.glancemapwearos.domain.sensors

import android.content.Context
import android.location.Location
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CompassManager private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sensorManagerProvider = SensorManagerOrientationProvider(appContext)
    private val fusedOrientationProvider =
        FusedOrientationProviderAdapter(
            context = appContext,
            fallbackProvider = sensorManagerProvider,
        )
    private val providers: Map<CompassProviderType, CompassOrientationProvider> =
        mapOf(
            CompassProviderType.SENSOR_MANAGER to sensorManagerProvider,
            CompassProviderType.GOOGLE_FUSED to fusedOrientationProvider,
        )
    private val activeProvider =
        MutableStateFlow(
            providers.getValue(CompassProviderType.GOOGLE_FUSED),
        )

    @Volatile private var requestedProviderType: CompassProviderType = CompassProviderType.GOOGLE_FUSED

    @Volatile private var started = false

    @Volatile private var lowPowerMode = false

    @Volatile private var northReferenceMode: NorthReferenceMode = NorthReferenceMode.TRUE

    @Volatile private var headingSourceMode: CompassHeadingSourceMode = CompassHeadingSourceMode.AUTO

    @Volatile private var lastApproximateDeclinationSeed: ApproximateDeclinationSeed? = null

    @Volatile private var lastDeclinationLocation: Location? = null

    @Volatile private var pendingStopJob: Job? = null

    val renderState: StateFlow<CompassRenderState> =
        activeProvider
            .flatMapLatest { provider -> provider.renderState }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = activeProvider.value.renderState.value,
            )

    @Synchronized
    fun start(lowPower: Boolean = false) {
        cancelPendingStopLocked()
        lowPowerMode = lowPower
        started = true
        logDiagnostics(
            "manager start requested=${requestedProviderType.name} lowPower=$lowPower",
        )
        syncActiveProvider(forceRefresh = false)
        activeProvider.value.start(lowPower = lowPower)
    }

    @Synchronized
    fun stop(
        reason: String = "unspecified",
        delayMs: Long = 0L,
    ) {
        if (delayMs > 0L) {
            cancelPendingStopLocked()
            val scheduledJob =
                scope.launch {
                    delay(delayMs)
                    synchronized(this@CompassManager) {
                        if (pendingStopJob !== this.coroutineContext[Job]) return@synchronized
                        pendingStopJob = null
                        stopNow(reason = "${reason}_delayed")
                    }
                }
            pendingStopJob = scheduledJob
            logDiagnostics(
                "manager stop scheduled requested=${requestedProviderType.name} " +
                    "reason=$reason delayMs=$delayMs " +
                    "active=${activeProvider.value.providerType.name} " +
                    "renderProvider=${activeProvider.value.renderState.value.providerType.name}",
            )
            return
        }
        cancelPendingStopLocked()
        stopNow(reason = reason)
    }

    private fun stopNow(reason: String) {
        started = false
        logDiagnostics(
            "manager stop requested=${requestedProviderType.name} " +
                "reason=$reason " +
                "active=${activeProvider.value.providerType.name} " +
                "renderProvider=${activeProvider.value.renderState.value.providerType.name}",
        )
        activeProvider.value.stop()
    }

    @Synchronized
    fun recalibrate() {
        logDiagnostics(
            "manager recalibrate requested=${requestedProviderType.name} " +
                "active=${activeProvider.value.providerType.name} " +
                "renderProvider=${activeProvider.value.renderState.value.providerType.name}",
        )
        activeProvider.value.recalibrate()
    }

    @Synchronized
    fun updateDeclinationFromLocation(location: Location) {
        val locationSnapshot = Location(location)
        lastDeclinationLocation = locationSnapshot
        activeProvider.value.updateDeclinationFromLocation(locationSnapshot)
    }

    @Synchronized
    fun setLowPowerMode(enabled: Boolean) {
        lowPowerMode = enabled
        providers.values.forEach { provider ->
            provider.setLowPowerMode(enabled)
        }
    }

    @Synchronized
    fun primeDeclinationFromApproximateLocation(
        latitude: Double,
        longitude: Double,
        altitudeM: Float = 0f,
    ) {
        lastApproximateDeclinationSeed =
            ApproximateDeclinationSeed(
                latitude = latitude,
                longitude = longitude,
                altitudeM = altitudeM,
            )
        activeProvider.value.primeDeclinationFromApproximateLocation(
            latitude = latitude,
            longitude = longitude,
            altitudeM = altitudeM,
        )
    }

    @Synchronized
    fun setNorthReferenceMode(
        mode: NorthReferenceMode,
        forceRefresh: Boolean = false,
    ) {
        northReferenceMode = mode
        providers.values.forEach { provider ->
            provider.setNorthReferenceMode(mode, forceRefresh = forceRefresh)
        }
    }

    @Synchronized
    fun setHeadingSourceMode(
        mode: CompassHeadingSourceMode,
        forceRefresh: Boolean = false,
    ) {
        headingSourceMode = mode
        providers.values.forEach { provider ->
            provider.setHeadingSourceMode(mode, forceRefresh = forceRefresh)
        }
    }

    @Synchronized
    fun setProviderType(
        type: CompassProviderType,
        forceRefresh: Boolean = false,
    ) {
        logDiagnostics(
            "provider_request requested=${type.name} forceRefresh=$forceRefresh started=$started",
        )
        requestedProviderType = type
        syncActiveProvider(forceRefresh = forceRefresh)
    }

    companion object {
        @Volatile
        private var INSTANCE: CompassManager? = null

        fun getInstance(context: Context): CompassManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CompassManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    @Synchronized
    private fun syncActiveProvider(forceRefresh: Boolean) {
        val resolvedProvider = providers.getValue(requestedProviderType)
        val currentProvider = activeProvider.value
        if (resolvedProvider === currentProvider) {
            if (forceRefresh) {
                logDiagnostics(
                    "provider_refresh active=${currentProvider.providerType.name} " +
                        "forceRefresh=true started=$started",
                )
                applyProviderState(resolvedProvider, forceRefresh = true)
                if (started) {
                    resolvedProvider.stop()
                    resolvedProvider.start(lowPower = lowPowerMode)
                }
            }
            return
        }

        logDiagnostics(
            "provider_switch from=${currentProvider.providerType.name} " +
                "to=${resolvedProvider.providerType.name} forceRefresh=$forceRefresh started=$started",
        )
        if (started) {
            currentProvider.stop()
        }

        applyProviderState(resolvedProvider, forceRefresh = true)
        activeProvider.value = resolvedProvider

        if (started) {
            resolvedProvider.start(lowPower = lowPowerMode)
        }
    }

    private fun applyProviderState(
        provider: CompassOrientationProvider,
        forceRefresh: Boolean,
    ) {
        provider.setLowPowerMode(lowPowerMode)
        provider.setNorthReferenceMode(northReferenceMode, forceRefresh = forceRefresh)
        provider.setHeadingSourceMode(headingSourceMode, forceRefresh = forceRefresh)
        lastApproximateDeclinationSeed?.let { seed ->
            provider.primeDeclinationFromApproximateLocation(
                latitude = seed.latitude,
                longitude = seed.longitude,
                altitudeM = seed.altitudeM,
            )
        }
        lastDeclinationLocation?.let { location ->
            provider.updateDeclinationFromLocation(location)
        }
    }

    private fun cancelPendingStopLocked() {
        pendingStopJob?.cancel()
        pendingStopJob = null
    }

    private fun logDiagnostics(message: String) {
        if (!DebugTelemetry.isEnabled()) return
        DebugTelemetry.log(COMPASS_TELEMETRY_TAG, message)
    }
}

private data class ApproximateDeclinationSeed(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float,
)
