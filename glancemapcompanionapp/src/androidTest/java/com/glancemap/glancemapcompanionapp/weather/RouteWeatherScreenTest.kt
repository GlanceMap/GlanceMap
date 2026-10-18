package com.glancemap.glancemapcompanionapp.weather

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.routes.RouteWeatherUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class RouteWeatherScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectingTomorrowAndAnHourUpdatesTheSummaryAndExpandsMountainDetails() {
        val forecast = testForecast()
        val day = forecast.daily.single().date
        val hour = forecast.hourly.single().timeIso8601
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    routeWeatherForecastContent(forecast)
                }
            }
        }
        compose
            .onNodeWithTag("weather-day-$day")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithTag("weather-summary").assertTextContains("14° high / 4° low", substring = true)
        compose
            .onNodeWithTag("weather-hour-$hour")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithTag("weather-summary").assertTextContains("8°C", substring = true)
        compose.onNodeWithTag("weather-mountain-toggle").performScrollTo().performClick()
        compose.onNodeWithText("2500 m").assertExists()
        compose.onNodeWithText("5.0 km").assertExists()
    }

    @Test
    fun cachedForecastStaysVisibleDuringRefreshAndHeaderActionsUseExistingCallbacks() {
        var refresh = false
        val weather = mutableStateOf(RouteWeatherUiState(forecast = testForecast(), isCached = true, isStale = true))
        var choose = 0
        var back = 0
        compose.setContent {
            MaterialTheme {
                routeWeatherScreen(
                    state =
                        RouteWeatherScreenState(
                            "Forest loop",
                            false,
                            weather.value,
                        ),
                    onBack = { back++ },
                    onChooseRoute = { choose++ },
                    onLoadWeather = {
                        refresh = it
                        weather.value = weather.value.copy(isLoading = true)
                    },
                )
            }
        }
        compose.onNodeWithText("Forest loop").assertExists()
        compose.onNodeWithText("Cached").assertExists()
        compose.onNodeWithText("Showing a cached forecast — unable to update.").assertExists()
        compose.onNodeWithContentDescription("Refresh weather").performClick()
        compose.onNodeWithText("Loading route forecast…").assertExists()
        compose.onNodeWithTag("weather-summary").assertExists()
        compose.onNodeWithContentDescription("Refresh weather").assertIsNotEnabled()
        compose.onNodeWithText("Change route").performClick()
        compose.runOnIdle {
            assertEquals(true, refresh)
            assertEquals(1, choose)
            assertEquals(0, back)
        }
    }

    private fun testForecast(): WeatherForecast {
        val tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString()
        return WeatherForecast(
            location = WeatherForecastLocation(45.0, 6.0, 1500.0, "Route start"),
            fetchedAtEpochMillis = System.currentTimeMillis(),
            current = WeatherCurrentConditions(12.0, 10.0, 2, 10.0, 20.0),
            nextHour = null,
            hourly =
                listOf(
                    WeatherHourlyOutlook(
                        60.0,
                        1.5,
                        61,
                        14.0,
                        35.0,
                        5000.0,
                        2500.0,
                        "${tomorrow}T08:00",
                        8.0,
                        5.0,
                    ),
                ),
            daily =
                listOf(
                    WeatherDailyOutlook(
                        tomorrow,
                        61,
                        4.0,
                        14.0,
                        80.0,
                        5.0,
                        50.0,
                        "${tomorrow}T07:30",
                        "${tomorrow}T18:30",
                    ),
                ),
            timeZoneId = "UTC",
        )
    }
}
