package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorageFile
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorageLocation
import com.glancemap.glancemapcompanionapp.map.reconcilePhoneOfflineMutableData
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class MissionAdventureStorageTest {
    @Test
    fun `storage migration preserves draft adventures without any GPX`() {
        withFiles { source, target ->
            val adventure = MissionAdventure("trip", "Draft adventure")
            val day = MissionPlanDay("day", 1, adventureId = adventure.id, isRestDay = true)
            val sourceIndex =
                MissionPlanRepository.MissionPlanIndex(
                    days = listOf(day),
                    adventures = listOf(adventure),
                    selectedAdventureId = adventure.id,
                )
            val sourceRecord = record(source, sourceIndex)
            val targetRecord = record(target, MissionPlanRepository.MissionPlanIndex())
            val result =
                reconcilePhoneOfflineMutableData(
                    listOf(sourceRecord),
                    listOf(targetRecord),
                    PhoneOfflineStorageLocation.INTERNAL,
                    PhoneOfflineStorageLocation.EXTERNAL,
                    "test",
                )
            assertTrue(result.missionActiveSourceChosen)
            val active = result.files.first { it.relativePath == "mission-plan/mission-plan.json" }
            assertEquals(sourceRecord.file, active.source?.file)
        }
    }

    @Test
    fun `different inactive adventures are retained as migration conflicts`() {
        withFiles { source, target ->
            val first = MissionAdventure("first", "Active")
            val second = MissionAdventure("second", "Source inactive notes", notes = "Keep source")
            val index =
                MissionPlanRepository.MissionPlanIndex(
                    adventures = listOf(first, second),
                    selectedAdventureId = first.id,
                )
            val sourceRecord = record(source, index)
            val targetRecord =
                record(
                    target,
                    index.copy(adventures = listOf(first, second.copy(notes = "Keep target"))),
                )
            val result =
                reconcilePhoneOfflineMutableData(
                    listOf(sourceRecord),
                    listOf(targetRecord),
                    PhoneOfflineStorageLocation.INTERNAL,
                    PhoneOfflineStorageLocation.EXTERNAL,
                    "test",
                )
            assertFalse(result.missionIdentical)
            assertTrue(result.missionConflictPreserved)
            assertTrue(
                result.files.any {
                    it.relativePath.contains("migration-conflicts/mission-plan/") &&
                        it.source?.file == targetRecord.file
                },
            )
        }
    }

    private fun record(
        directory: File,
        index: MissionPlanRepository.MissionPlanIndex,
    ): PhoneOfflineStorageFile {
        val file = File(directory, "mission-plan.json")
        file.writeText(Gson().toJson(index))
        return PhoneOfflineStorageFile(
            "mission-plan/mission-plan.json",
            file,
            file.length(),
            file.readText().hashCode().toString(),
        )
    }

    private fun withFiles(block: (File, File) -> Unit) {
        val root = createTempDirectory("adventure-storage").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val target = File(root, "target").apply { mkdirs() }
            block(source, target)
        } finally {
            root.deleteRecursively()
        }
    }
}
