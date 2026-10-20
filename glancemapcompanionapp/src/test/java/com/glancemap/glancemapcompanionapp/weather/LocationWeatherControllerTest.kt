package com.glancemap.glancemapcompanionapp.weather

import com.glancemap.glancemapcompanionapp.map.PhoneMapLocation
import com.glancemap.glancemapcompanionapp.routes.RouteWeatherUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocationWeatherControllerTest {
    private val first = WeatherForecastLocation(48.0, 2.0, null, "First place")
    private val second = WeatherForecastLocation(49.0, 3.0, null, "Second place")

    @Test
    fun selectedLocationsLoadWithoutARouteAndRefreshTheSamePoint() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val calls = mutableListOf<Pair<WeatherForecastLocation, Boolean>>()
            val controller =
                controller(scope, forecast = { location, force ->
                    calls += location to force
                    weather(location)
                })
            try {
                controller.load(first)
                controller.load(first, true)
                assertEquals(listOf(first to false, first to true), calls)
                assertEquals(
                    first,
                    controller.state.value.weather.forecast
                        ?.location,
                )
                assertFalse(controller.state.value.weather.isLoading)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun missingOrDeniedCurrentLocationDoesNotRequestWeather() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var calls = 0
            val controller =
                controller(scope, forecast = { _, _ ->
                    calls++
                    weather(first)
                })
            try {
                controller.current()
                assertEquals("Location unavailable", controller.state.value.weather.message)
                controller.locationDenied()
                assertEquals(0, calls)
                assertNull(controller.state.value.location)
                assertFalse(controller.state.value.isLocating)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun currentLocationUsesTheReturnedFixAndOldForecastsCannotReplaceNewChoices() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val old = CompletableDeferred<RouteWeatherUiState>()
            val controller =
                controller(scope, current = { first }, forecast = { location, _ ->
                    if (location == first) withContext(NonCancellable) { old.await() } else weather(location)
                })
            try {
                controller.current()
                assertEquals(first, controller.state.value.location)
                assertTrue(controller.state.value.weather.isLoading)
                controller.load(second)
                old.complete(weather(first))
                assertEquals(
                    second,
                    controller.state.value.weather.forecast
                        ?.location,
                )
            } finally {
                old.complete(weather(first))
                scope.cancel()
            }
        }

    @Test
    fun cancellingCurrentLocationNeverStartsWeatherAfterALateFix() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val fix = CompletableDeferred<WeatherForecastLocation?>()
            var calls = 0
            val controller =
                controller(scope, current = { withContext(NonCancellable) { fix.await() } }, forecast = { location, _ ->
                    calls++
                    weather(location)
                })
            try {
                controller.current()
                assertTrue(controller.state.value.isLocating)
                controller.cancel()
                fix.complete(first)
                assertEquals(0, calls)
                assertNull(controller.state.value.weather.forecast)
                assertFalse(controller.state.value.weather.isLoading)
            } finally {
                fix.complete(null)
                scope.cancel()
            }
        }

    @Test
    fun switchingPointsClearsOldWeatherAndFailedRefreshKeepsOnlyTheSamePoint() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val pending = CompletableDeferred<RouteWeatherUiState>()
            val controller =
                controller(scope, forecast = { location, force ->
                    if (force) throw IOException("Private diagnostics")
                    if (location == second) pending.await() else weather(location)
                })
            try {
                controller.load(first)
                controller.load(first, true)
                assertEquals(
                    first,
                    controller.state.value.weather.forecast
                        ?.location,
                )
                assertEquals("Forecast unavailable", controller.state.value.weather.message)
                controller.load(second)
                assertNull(controller.state.value.weather.forecast)
                assertNull(controller.state.value.weather.message)
                pending.complete(weather(second))
                controller.clear()
                assertEquals(LocationWeatherState(), controller.state.value)
            } finally {
                pending.complete(weather(second))
                scope.cancel()
            }
        }

    @Test
    fun currentFixRejectsStaleFutureMissingAndInvalidCoordinates() {
        val fix = PhoneMapLocation(48.0, 2.0, null, 200000L, 123.0)
        val target = currentWeatherLocation(fix, 200001L, "Current location")
        assertEquals(48.0, target?.latitude)
        assertNull(target?.elevationMeters)
        assertNull(currentWeatherLocation(fix, 320001L, "Current location"))
        assertNull(currentWeatherLocation(fix, 199999L, "Current location"))
        assertNull(currentWeatherLocation(null, 200001L, "Current location"))
        assertNull(currentWeatherLocation(fix.copy(latitude = 91.0), 200001L, "Current location"))
        assertNull(currentWeatherLocation(fix.copy(longitude = Double.NaN), 200001L, "Current location"))
        assertNull(currentWeatherLocation(fix.copy(fixElapsedRealtimeMillis = 0L), 200001L, "Current location"))
    }

    private fun controller(
        scope: CoroutineScope,
        current: suspend () -> WeatherForecastLocation? = { null },
        forecast: suspend (WeatherForecastLocation, Boolean) -> RouteWeatherUiState,
    ) = LocationWeatherController(scope, forecast, current, "Location unavailable", "Forecast unavailable")

    private fun weather(location: WeatherForecastLocation) =
        RouteWeatherUiState(
            forecast = WeatherForecast(location, 1L, WeatherCurrentConditions(12.0, null, null, null, null), null),
        )
}
