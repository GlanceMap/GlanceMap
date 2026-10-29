package com.glancemap.glancemapwearos.core.service.location.adapters

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28, maxSdkVersion = 29)
class PlatformLocationUpdatesCompatibilityTest {
    private lateinit var gateway: WatchGpsLocationGateway
    private lateinit var locationManager: LocationManager
    private lateinit var executor: ExecutorService

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        locationManager = manager
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))
        assumeTrue(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        executor = Executors.newSingleThreadExecutor()
        gateway =
            WatchGpsLocationGateway(
                locationManager = manager,
                packageManager = context.packageManager,
                callbackExecutor = executor,
                telemetry = LocationServiceTelemetry("GpsCompatibilityTest", 1_000L, telemetryEnabled = { false }),
            )
    }

    @After
    fun tearDown() {
        if (::gateway.isInitialized) gateway.removeLocationUpdatesBestEffort()
        if (::executor.isInitialized) executor.shutdownNow()
    }

    @Test
    fun currentLocationCanBeCancelledOnLegacyAndroid() =
        runBlocking {
            val request =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    gateway.getCurrentLocation(
                        CurrentLocationRequestParams(
                            priority = Priority.PRIORITY_HIGH_ACCURACY,
                            maxUpdateAgeMs = 0L,
                            durationMs = 30_000L,
                        ),
                    )
                }
            request.cancelAndJoin()
        }

    @Test
    fun locationUpdatesCanBeRegisteredReplacedAndRemovedOnLegacyAndroid() =
        runBlocking {
            val sink =
                object : LocationUpdateSink {
                    override fun onLocations(event: LocationUpdateEvent) = Unit
                }
            val request =
                LocationUpdateRequestParams(
                    priority = Priority.PRIORITY_HIGH_ACCURACY,
                    intervalMs = 1_000L,
                    minDistanceMeters = 0f,
                    waitForAccurateLocation = false,
                    maxUpdateDelayMs = 0L,
                )
            gateway.requestLocationUpdates(request, sink)
            gateway.requestLocationUpdates(request.copy(intervalMs = 2_000L), sink)
            gateway.removeLocationUpdates()
            gateway.removeLocationUpdates()
        }

    @Test
    fun passiveUpdatesCanBeRegisteredAndRemovedOnLegacyAndroid() =
        runBlocking {
            val passiveGateway = PassiveExternalLocationGateway(locationManager, executor)
            val sink =
                object : LocationUpdateSink {
                    override fun onLocations(event: LocationUpdateEvent) = Unit
                }
            try {
                passiveGateway.requestLocationUpdates(
                    LocationUpdateRequestParams(
                        priority = Priority.PRIORITY_PASSIVE,
                        intervalMs = 1_000L,
                        minDistanceMeters = 0f,
                        waitForAccurateLocation = false,
                        maxUpdateDelayMs = 0L,
                    ),
                    sink,
                )
                passiveGateway.removeLocationUpdates()
            } finally {
                passiveGateway.removeLocationUpdatesBestEffort()
            }
        }
}
