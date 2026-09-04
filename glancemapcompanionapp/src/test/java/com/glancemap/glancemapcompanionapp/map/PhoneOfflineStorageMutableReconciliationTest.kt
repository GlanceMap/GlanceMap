package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.routes.MissionPlanDay
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibrarySummary
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory

class PhoneOfflineStorageMutableReconciliationTest {
    @Test
    fun routeLibraryMergesSourceAndTargetByStableId() {
        withRouteRoots(
            sourceRoutes = listOf(route("a", "Source A"), route("c", "Source C")),
            targetRoutes = listOf(route("a", "Target A"), route("b", "Target B")),
            sourceSelectedRouteId = "a",
        ) { source, target ->
            val result = reconcile(source, target)
            val index = routeIndex(result)

            assertEquals(listOf("a", "c", "b"), index.routes.map(RouteLibraryRoute::id))
            assertEquals("Source A", index.routes.first { route -> route.id == "a" }.displayName)
            assertEquals("a", index.selectedRouteId)
            val sourceFile =
                result.files
                    .first { file -> file.relativePath == "route-library/a.gpx" }
                    .source
                    ?.file
            assertTrue(sourceFile?.path?.contains("/source/") == true)
        }
    }

    @Test
    fun invalidSourceBackingGpxFallsBackToValidTargetRecord() {
        withRouteRoots(
            sourceRoutes = listOf(route("a", "Source A")),
            targetRoutes = listOf(route("a", "Target A")),
            sourceGpx = mapOf("a.gpx" to "not a gpx"),
        ) { source, target ->
            val index = routeIndex(reconcile(source, target))

            assertEquals(listOf("a"), index.routes.map(RouteLibraryRoute::id))
            assertEquals("Target A", index.routes.single().displayName)
        }
    }

    @Test
    fun malformedSourceIndexDoesNotDestroyValidTargetLibrary() {
        withRouteRoots(
            sourceRoutes = emptyList(),
            targetRoutes = listOf(route("b", "Target B")),
            sourceIndexText = "not json",
        ) { source, target ->
            val result = reconcile(source, target)

            assertEquals(listOf("b"), routeIndex(result).routes.map(RouteLibraryRoute::id))
            assertTrue(
                result.files.any { file ->
                    file.relativePath.startsWith("migration-conflicts/route-library/")
                },
            )
        }
    }

    @Test
    fun selectedRouteFallsBackToTargetWhenSourceSelectionIsMissing() {
        withRouteRoots(
            sourceRoutes = listOf(route("a", "Source A")),
            targetRoutes = listOf(route("b", "Target B")),
            sourceSelectedRouteId = "missing",
            targetSelectedRouteId = "b",
        ) { source, target ->
            assertEquals("b", routeIndex(reconcile(source, target)).selectedRouteId)
        }
    }

    @Test
    fun orphanedRoutesAreRemovedFromMergedIndex() {
        withRouteRoots(
            sourceRoutes = listOf(route("missing", "Orphan")),
            targetRoutes = emptyList(),
        ) { source, target ->
            val result = reconcile(source, target)

            assertTrue(routeIndex(result).routes.isEmpty())
            assertFalse(result.files.any { file -> file.relativePath == "route-library/missing.gpx" })
        }
    }

    @Test
    fun missionPlanUsesSourceAndPreservesDifferentTarget() {
        withMissionRoots(
            sourcePlan = missionPlanIndex("source"),
            targetPlan = missionPlanIndex("target"),
        ) { source, target ->
            val result = reconcile(source, target)
            val active =
                result.files.first { file -> file.relativePath == "mission-plan/mission-plan.json" }
            val recovery =
                result.files.first { file ->
                    file.relativePath.contains("migration-conflicts/mission-plan/") && file.source != null
                }

            val activeName =
                parseMissionPlan(active.source!!.file.readText())
                    .days
                    .single()
                    .name
            assertEquals("source", activeName)
            val recoveredName =
                recovery.source!!
                    .file
                    .readText()
                    .let(::parseMissionPlan)
                    .days
                    .single()
                    .name
            assertEquals(
                "target",
                recoveredName,
            )
            assertTrue(result.missionConflictPreserved)
        }
    }

    @Test
    fun sourceWithoutMissionPlanClearsTargetAndPreservesIt() {
        withMissionRoots(
            sourcePlan = null,
            targetPlan = missionPlanIndex("old target"),
        ) { source, target ->
            val result = reconcile(source, target)

            assertFalse(result.files.any { file -> file.relativePath == "mission-plan/mission-plan.json" })
            assertTrue(
                result.files.any { file ->
                    val isMissionRecovery =
                        file.relativePath.contains("migration-conflicts/mission-plan/")
                    val containsPlan =
                        file.source
                            ?.file
                            ?.readText()
                            ?.contains("old target") == true
                    isMissionRecovery && containsPlan
                },
            )
        }
    }

    @Test
    fun identicalMissionPlansDoNotCreateConflictCopy() {
        withMissionRoots(
            sourcePlan = missionPlanIndex("same"),
            targetPlan = missionPlanIndex("same"),
        ) { source, target ->
            val result = reconcile(source, target)

            assertFalse(result.missionConflictPreserved)
            assertFalse(
                result.files.any { file ->
                    file.relativePath.startsWith("migration-conflicts/mission-plan/")
                },
            )
        }
    }

    @Test
    fun storageRoundTripMergesRoutesAndKeepsOldMissionRecoverable() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-mutable-round-trip-").toFile()
            try {
                val sd = File(root, "sd")
                val internal = File(root, "internal")
                writeRouteLibrary(sd, listOf(route("a", "A"), route("b", "B")), selectedRouteId = "a")
                writeMissionPlan(sd, missionPlanIndex("V1"))

                val first =
                    PhoneOfflineStorageMigration(sd, internal, File(root, "first.properties"))
                        .move(PhoneOfflineStorageLocation.EXTERNAL)
                assertTrue(first is PhoneOfflineStorageMigrationResult.Success)
                assertTrue(File(internal, "migration-conflicts").isDirectory)

                writeRouteLibrary(
                    internal,
                    listOf(route("a", "A"), route("b", "B"), route("c", "C")),
                    selectedRouteId = "c",
                )
                assertEquals(
                    listOf("a", "b", "c"),
                    readRouteIndex(File(internal, "route-library/routes.json"))
                        .routes
                        .map(RouteLibraryRoute::id),
                )
                writeMissionPlan(internal, missionPlanIndex("V2"))
                assertEquals(
                    listOf("a", "b", "c"),
                    routeIndex(reconcile(internal, sd)).routes.map(RouteLibraryRoute::id),
                )

                val second =
                    PhoneOfflineStorageMigration(internal, sd, File(root, "second.properties"))
                        .move(PhoneOfflineStorageLocation.EXTERNAL)
                assertTrue(second is PhoneOfflineStorageMigrationResult.Success)

                val finalRoutes = readRouteIndex(File(sd, "route-library/routes.json"))
                assertEquals(listOf("a", "b", "c"), finalRoutes.routes.map(RouteLibraryRoute::id).sorted())
                assertEquals("c", finalRoutes.selectedRouteId)
                finalRoutes.routes.forEach { route ->
                    assertTrue(File(sd, "route-library/${route.storedFileName}").isFile)
                }
                assertEquals(
                    "V2",
                    parseMissionPlan(File(sd, "mission-plan/mission-plan.json").readText())
                        .days
                        .single()
                        .name,
                )
                assertTrue(
                    File(sd, "migration-conflicts/mission-plan")
                        .walkTopDown()
                        .filter(File::isFile)
                        .any { file -> file.readText().contains("V1") },
                )
            } finally {
                root.deleteRecursively()
            }
        }

    private fun reconcile(
        source: File,
        target: File,
    ): PhoneOfflineStorageMutableReconciliation =
        reconcilePhoneOfflineMutableData(
            sourceFiles = storageFiles(source),
            targetFiles = storageFiles(target),
            sourceLocation = PhoneOfflineStorageLocation.INTERNAL,
            targetLocation = PhoneOfflineStorageLocation.EXTERNAL,
            migrationId = "test-migration",
        )

    @Suppress("LongParameterList")
    private fun withRouteRoots(
        sourceRoutes: List<RouteLibraryRoute>,
        targetRoutes: List<RouteLibraryRoute>,
        sourceGpx: Map<String, String> = emptyMap(),
        targetGpx: Map<String, String> = emptyMap(),
        sourceIndexText: String? = null,
        sourceSelectedRouteId: String? = null,
        targetSelectedRouteId: String? = null,
        block: (File, File) -> Unit,
    ) {
        val root = createTempDirectory(prefix = "phone-storage-routes-").toFile()
        try {
            val source = File(root, "source")
            val target = File(root, "target")
            writeRouteLibrary(source, sourceRoutes, sourceSelectedRouteId, sourceGpx, sourceIndexText)
            writeRouteLibrary(target, targetRoutes, targetSelectedRouteId, targetGpx)
            block(source, target)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withMissionRoots(
        sourcePlan: MissionPlanTestIndex?,
        targetPlan: MissionPlanTestIndex?,
        block: (File, File) -> Unit,
    ) {
        val root = createTempDirectory(prefix = "phone-storage-mission-").toFile()
        try {
            val source = File(root, "source")
            val target = File(root, "target")
            sourcePlan?.let { plan -> writeMissionPlan(source, plan) }
            targetPlan?.let { plan -> writeMissionPlan(target, plan) }
            block(source, target)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeRouteLibrary(
        root: File,
        routes: List<RouteLibraryRoute>,
        selectedRouteId: String? = null,
        gpx: Map<String, String> = emptyMap(),
        indexText: String? = null,
    ) {
        val directory = File(root, "route-library").apply { mkdirs() }
        routes.forEach { route ->
            File(directory, route.storedFileName).writeText(
                gpx[route.storedFileName] ?: validGpx(route.id.hashCode().toDouble()),
            )
        }
        File(directory, "routes.json").writeText(
            indexText ?: Gson().toJson(RouteLibraryTestIndex(routes, selectedRouteId)),
        )
    }

    private fun writeMissionPlan(
        root: File,
        plan: MissionPlanTestIndex,
    ) {
        File(root, "mission-plan/mission-plan.json").apply {
            parentFile!!.mkdirs()
            writeText(Gson().toJson(plan))
        }
    }

    private fun route(
        id: String,
        displayName: String,
    ): RouteLibraryRoute =
        RouteLibraryRoute(
            id = id,
            displayName = displayName,
            storedFileName = "$id.gpx",
            importedAtMillis = id.hashCode().toLong(),
            summary = emptySummary,
            metadataTitle = "Embedded $id",
        )

    private fun missionPlanIndex(name: String): MissionPlanTestIndex =
        MissionPlanTestIndex(
            days =
                listOf(
                    MissionPlanDay(
                        id = "day-$name",
                        dayNumber = 1,
                        routeId = "a",
                        name = name,
                    ),
                ),
            selectedDayId = "day-$name",
        )

    private fun routeIndex(result: PhoneOfflineStorageMutableReconciliation): RouteLibraryTestIndex {
        val file = result.files.first { item -> item.relativePath == "route-library/routes.json" }
        val json = String(requireNotNull(file.bytes), StandardCharsets.UTF_8)
        return Gson().fromJson(json, RouteLibraryTestIndex::class.java)
    }

    private fun parseMissionPlan(text: String): MissionPlanTestIndex = Gson().fromJson(text, MissionPlanTestIndex::class.java)

    private fun readRouteIndex(file: File): RouteLibraryTestIndex = Gson().fromJson(file.readText(), RouteLibraryTestIndex::class.java)

    private fun storageFiles(root: File): List<PhoneOfflineStorageFile> =
        if (!root.exists()) {
            emptyList()
        } else {
            root
                .walkTopDown()
                .filter(File::isFile)
                .map { file ->
                    PhoneOfflineStorageFile(
                        relativePath = file.relativeTo(root).invariantSeparatorsPath,
                        file = file,
                        sizeBytes = file.length(),
                        sha256 = sha256(file.readBytes()),
                    )
                }.toList()
        }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun validGpx(seed: Double): String =
        """
        <gpx version="1.1" creator="test">
          <trk><trkseg>
            <trkpt lat="46.0" lon="${6.0 + seed / 100000.0}" />
            <trkpt lat="46.01" lon="${6.01 + seed / 100000.0}" />
          </trkseg></trk>
        </gpx>
        """.trimIndent()

    private data class RouteLibraryTestIndex(
        val routes: List<RouteLibraryRoute> = emptyList(),
        val selectedRouteId: String? = null,
    )

    private data class MissionPlanTestIndex(
        val days: List<MissionPlanDay> = emptyList(),
        val selectedDayId: String? = null,
    )

    private companion object {
        val emptySummary =
            RouteLibrarySummary(
                distanceMeters = 0.0,
                elevationGainMeters = 0.0,
                elevationLossMeters = 0.0,
                estimatedDurationSeconds = 0.0,
                waypointCount = 0,
                firstThirtyMinutesDistanceMeters = 0.0,
                firstThirtyMinutesAscentMeters = 0.0,
            )
    }
}
