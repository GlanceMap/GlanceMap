package com.glancemap.glancemapcompanionapp.routes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MissionAdventurePersistenceAndroidTest {
    @Test
    fun creatingAdventureSelectingGpxAndPlanningStagesSurviveReload() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.cacheDir, "adventure-persistence-${System.nanoTime()}")
            val plans = MissionPlanRepository(directory, "Legacy")
            val routes = RouteLibraryRepository(context)
            val routeId =
                requireNotNull(routes.saveGpxRoute("Adventure persistence test", gpx.toByteArray()).selectedRouteId)
            try {
                val created =
                    plans.saveAdventure(
                        null,
                        MissionAdventureUpdate("Trip", "2026-10-18", "2026-10-19", 2, "Trip notes"),
                    )
                val adventureId = requireNotNull(created.selectedAdventureId)
                val day = created.days.first()
                plans.editDay(day.id) { it.toUpdate().copy(lunch = MissionMeal.PACK, notes = "Keep this") }
                plans.assignRoute(day.id, routeId)
                val details = requireNotNull(routes.routeDetails(routeId))
                val source = MissionRouteStageSource(details, plans.load().days)
                assertEquals(adventureId, plans.load().selectedAdventureId)
                assertEquals(routeId, source.days.first().routeId)
                assertTrue(details.profile.totalDistanceMeters > 0)
                plans.planStages(
                    adventureId,
                    routeId,
                    source.days.map { it.id },
                    details.profile.suggestStageStops(2),
                    details.profile.totalDistanceMeters,
                )
                val restored = MissionPlanRepository(directory, "Legacy").load()
                assertEquals("Trip notes", restored.selectedAdventure?.notes)
                assertEquals("Keep this", restored.days.first().notes)
                assertEquals(MissionMeal.PACK, restored.days.first().lunch)
                assertEquals(restored.days.first().endDistanceMeters, restored.days.last().startDistanceMeters)
                val saved = JsonParser.parseString(File(directory, "mission-plan.json").readText()).asJsonObject
                assertTrue(saved.has("days"))
                assertTrue(saved.getAsJsonArray("days")[0].asJsonObject.has("routeId"))
            } finally {
                routes.deleteRoute(routeId)
                directory.deleteRecursively()
            }
        }

    private val gpx = """<gpx version="1.1"><trk><name>Test trail</name><trkseg>
        <trkpt lat="46.0" lon="11.0"><ele>1000</ele></trkpt>
        <trkpt lat="46.01" lon="11.01"><ele>1100</ele></trkpt>
        <trkpt lat="46.02" lon="11.02"><ele>1050</ele></trkpt>
        <trkpt lat="46.03" lon="11.03"><ele>1200</ele></trkpt>
        </trkseg></trk></gpx>"""
}
