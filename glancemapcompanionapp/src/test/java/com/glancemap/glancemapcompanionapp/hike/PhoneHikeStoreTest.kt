package com.glancemap.glancemapcompanionapp.hike

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhoneHikeStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun restoresPinnedPlanPreviewCursorScheduleCheckpointsAndRouteRevision() {
        val folder = temporary.newFolder()
        val store = PhoneHikeStore(folder)
        val route = hikeRoute()
        store.saveRoute(route)
        val plan = PhoneHikePlan(route.id, "2026-10-15", "08:00", "Europe/Paris", 1, .25, true, listOf(checkpoint(1, .5, 15)))
        val data = PhoneHikeWorkspaceData().updatePlan(plan).useForToday(route.id).openPreview(route.id)
        store.save(data)
        val recreated = PhoneHikeStore(folder)
        assertEquals(data, recreated.load())
        assertEquals(route, recreated.loadRoute(route.id))
        assertFalse(requireNotNull(recreated.load().todayPlan).inspecting)
        assertTrue(requireNotNull(recreated.load().selectedPlan).inspecting)
        assertTrue(folder.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun corruptMetadataIsReportedAndKept() {
        val folder = temporary.newFolder()
        val file = File(folder, "workspace.json")
        file.writeText("{broken")
        assertTrue(runCatching { PhoneHikeStore(folder).load() }.isFailure)
        assertEquals("{broken", file.readText())
    }

    @Test
    fun failedSerializationKeepsThePreviousCompletePlan() {
        val folder = temporary.newFolder()
        val store = PhoneHikeStore(folder)
        val route = hikeRoute()
        val original = PhoneHikeWorkspaceData().useForToday(route.id)
        store.save(original)
        val invalid = original.copy(todayPlan = PhoneHikePlan(route.id, cursorFraction = Double.NaN))
        assertTrue(runCatching { store.save(invalid) }.isFailure)
        assertEquals(original, store.load())
        assertTrue(folder.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun invalidSnapshotCannotMasqueradeAsTheSelectedRevision() {
        val folder = temporary.newFolder()
        val route = hikeRoute()
        val store = PhoneHikeStore(folder)
        store.saveRoute(route)
        val file = File(folder, "routes/${route.id}.json")
        file.writeText(file.readText().replace("11.01", "11.03"))
        assertTrue(runCatching { store.loadRoute(route.id) }.isFailure)
        assertTrue(runCatching { store.loadRoute("../other") }.isFailure)
    }
}
