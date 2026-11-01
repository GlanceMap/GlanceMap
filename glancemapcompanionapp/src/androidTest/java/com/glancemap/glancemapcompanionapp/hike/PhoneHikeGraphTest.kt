package com.glancemap.glancemapcompanionapp.hike

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.TrailGuidanceEtaConfig
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class PhoneHikeGraphTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun accessibleCursorAdvancesThroughStopAtTheSameMapPosition() {
        val points = listOf(TrailPoint(GeoPoint(46.0, 11.0), 100.0), TrailPoint(GeoPoint(46.0, 11.01), 200.0))
        val model = PhoneHikeRouteModel(PhoneHikeRoute("route", "track", "Route", points, 0.0, TrailGuidanceEtaConfig(1.2)))
        val checkpoint = PhoneHikeCheckpoint("stop", "place", "Stop", 46.0, 11.005, 0, .5, 30)
        val plan = PhoneHikePlan(model.route.id, checkpoints = listOf(checkpoint))
        val timeline = PhoneHikeTimeline(model, plan)
        val stop = requireNotNull(model.position(0, .5))
        val selected = mutableDoubleStateOf(0.0)
        composeRule.setContent {
            MaterialTheme {
                val inputs =
                    PhoneHikeGraphInputs(
                        model,
                        plan,
                        timeline,
                        selected.doubleValue,
                        null,
                        Instant.EPOCH,
                        ZoneId.of("UTC"),
                        true,
                        emptyList(),
                    )
                PhoneHikeGraph(inputs) {
                    selected.doubleValue = it
                }
            }
        }
        composeRule.onNodeWithTag("hike-profile").performSemanticsAction(SemanticsActions.SetProgress) { it((stop.elapsedSeconds + 900).toFloat()) }
        composeRule.runOnIdle {
            assertEquals(stop.coordinate, timeline.atSeconds(selected.doubleValue).coordinate)
            assertEquals(stop.elapsedSeconds + 900, selected.doubleValue, .01)
        }
    }
}
