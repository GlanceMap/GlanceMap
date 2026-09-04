package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import kotlin.io.path.createTempDirectory

class PhoneOfflineStorageMigrationTest {
    @Test
    fun copiesManagedDataVerifiesItAndDeletesTheOldTree() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-move-").toFile()
            try {
                val source = File(root, "source").apply { mkdirs() }
                val target = File(root, "target/GlanceMap")
                File(source, "maps/alps.map").apply {
                    parentFile!!.mkdirs()
                    writeBytes(byteArrayOf(1, 2, 3))
                }
                File(source, "refuges-poi/my-places.poi").apply {
                    parentFile!!.mkdirs()
                    writeText("poi")
                }
                File(source, "route-library/routes.json").apply {
                    parentFile!!.mkdirs()
                    writeText("routes")
                }
                File(source, "mission-plan/mission-plan.json").apply {
                    parentFile!!.mkdirs()
                    writeText("mission")
                }
                File(source, "weather-forecasts/snapshots-v2.json").apply {
                    parentFile!!.mkdirs()
                    writeText("weather")
                }
                File(source, "watch-gpx-exports/from-watch.gpx").apply {
                    parentFile!!.mkdirs()
                    writeText("gpx")
                }
                val journal = File(root, "migration.properties")

                val result =
                    PhoneOfflineStorageMigration(source, target, journal)
                        .move(PhoneOfflineStorageLocation.EXTERNAL)

                assertEquals(PhoneOfflineStorageMigrationResult.Success::class, result::class)
                assertEquals(
                    byteArrayOf(1, 2, 3).toList(),
                    File(target, "maps/alps.map").readBytes().toList(),
                )
                assertEquals("poi", File(target, "refuges-poi/my-places.poi").readText())
                assertEquals("routes", File(target, "route-library/routes.json").readText())
                assertEquals("mission", File(target, "mission-plan/mission-plan.json").readText())
                assertEquals("weather", File(target, "weather-forecasts/snapshots-v2.json").readText())
                assertEquals("gpx", File(target, "watch-gpx-exports/from-watch.gpx").readText())
                assertFalse(File(source, "maps").exists())
                assertFalse(File(source, "refuges-poi").exists())
                assertFalse(File(source, "mission-plan").exists())
                assertFalse(File(source, "weather-forecasts").exists())
                assertFalse(File(source, "watch-gpx-exports").exists())
                assertFalse(journal.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun interruptedCopyLeavesJournalAndCanResume() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-resume-").toFile()
            try {
                val source = File(root, "source").apply { mkdirs() }
                val target = File(root, "target/GlanceMap")
                repeat(2) { index ->
                    File(source, "maps/map-$index.map").apply {
                        parentFile!!.mkdirs()
                        writeBytes(ByteArray(index + 2) { index.toByte() })
                    }
                }
                val journal = File(root, "migration.properties")
                var cancelled = false

                try {
                    PhoneOfflineStorageMigration(source, target, journal).move(PhoneOfflineStorageLocation.EXTERNAL) {
                        if (!cancelled) {
                            cancelled = true
                            throw CancellationException("test interruption")
                        }
                    }
                } catch (_: CancellationException) {
                    // The journal is the recovery contract.
                }
                assertTrue(journal.isFile)
                assertEquals(
                    PhoneOfflineStorageMigrationPhase.COPYING,
                    PhoneOfflineStorageMigration(source, target, journal).pending()!!.phase,
                )

                val result =
                    PhoneOfflineStorageMigration(source, target, journal)
                        .move(PhoneOfflineStorageLocation.EXTERNAL)

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertTrue(File(target, "maps/map-0.map").isFile)
                assertTrue(File(target, "maps/map-1.map").isFile)
                assertFalse(journal.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun interruptedSwitchAfterTargetActivationCanResumeCleanup() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-switch-resume-").toFile()
            try {
                val source = File(root, "source").apply { mkdirs() }
                val target = File(root, "target/GlanceMap")
                val staging = File(target.parentFile, ".GlanceMap-migration-test")
                val backup = File(target.parentFile, ".GlanceMap-backup-test")
                File(source, "maps/source.map").apply {
                    parentFile!!.mkdirs()
                    writeText("source")
                }
                File(target, "maps/existing.map").apply {
                    parentFile!!.mkdirs()
                    writeText("existing")
                }
                File(staging, "maps/source.map").apply {
                    parentFile!!.mkdirs()
                    writeText("source")
                }
                assertTrue(target.renameTo(backup))
                assertTrue(staging.renameTo(target))
                val journal = File(root, "migration.properties")
                val properties =
                    Properties().apply {
                        setProperty("source", PhoneOfflineStorageLocation.INTERNAL.name)
                        setProperty("target", PhoneOfflineStorageLocation.EXTERNAL.name)
                        setProperty("sourceRoot", source.absolutePath)
                        setProperty("targetRoot", target.absolutePath)
                        setProperty("stagingRoot", staging.absolutePath)
                        setProperty("backupRoot", backup.absolutePath)
                        setProperty("phase", PhoneOfflineStorageMigrationPhase.SWITCHING.name)
                    }
                journal.outputStream().use { properties.store(it, "test") }

                val result =
                    PhoneOfflineStorageMigration(source, target, journal)
                        .move(PhoneOfflineStorageLocation.EXTERNAL)

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals("source", File(target, "maps/source.map").readText())
                assertFalse(File(source, "maps").exists())
                assertFalse(File(backup, "maps").exists())
                assertFalse(journal.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun existingTargetDataIsMergedAndTargetWinsWhenBothMapsAreInvalid() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-target-").toFile()
            try {
                val source = File(root, "source").apply { mkdirs() }
                val target = File(root, "target/GlanceMap")
                File(source, "maps/source.map").apply {
                    parentFile!!.mkdirs()
                    writeText("source")
                }
                File(source, "maps/shared.map").writeText("source version")
                File(target, "maps/existing.map").apply {
                    parentFile!!.mkdirs()
                    writeText("existing")
                }
                File(target, "maps/shared.map").writeText("old target version")
                File(target, "custom/user-note.txt").apply {
                    parentFile!!.mkdirs()
                    writeText("keep this file")
                }
                val journal = File(root, "migration.properties")
                val progress = mutableListOf<PhoneOfflineStorageMigrationProgress>()

                val result =
                    PhoneOfflineStorageMigration(source, target, journal)
                        .move(PhoneOfflineStorageLocation.EXTERNAL) { progress += it }

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals("source", File(target, "maps/source.map").readText())
                assertEquals("existing", File(target, "maps/existing.map").readText())
                assertEquals("old target version", File(target, "maps/shared.map").readText())
                assertEquals("keep this file", File(target, "custom/user-note.txt").readText())
                assertFalse(File(source, "maps").exists())
                assertEquals(0, progress.first().copiedFiles)
                assertEquals(4, progress.first().totalFiles)
                assertEquals(100, progress.last().copiedFiles * 100 / progress.last().totalFiles)
                assertFalse(journal.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun migrationStateExposesBoundedPercentage() {
        assertEquals(
            50,
            PhoneOfflineStorageMigrationState(copiedFiles = 1, totalFiles = 2).percent,
        )
        assertEquals(
            100,
            PhoneOfflineStorageMigrationState(copiedFiles = 9, totalFiles = 2).percent,
        )
        assertNull(PhoneOfflineStorageMigrationState(copiedFiles = 0, totalFiles = 0).percent)
    }

    @Test
    fun validTargetRoutingFileWinsOverInvalidSource() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-routing-target-").toFile()
            try {
                val source = File(root, "source")
                val target = File(root, "target/GlanceMap")
                File(source, "routing-segments/E10_N45.rd5").apply {
                    parentFile!!.mkdirs()
                    writeText("invalid")
                }
                File(target, "routing-segments/E10_N45.rd5").apply {
                    parentFile!!.mkdirs()
                    writeText("valid target")
                }

                val result =
                    migration(source, target, File(root, "journal.properties"))
                        .move(PhoneOfflineStorageLocation.EXTERNAL)

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals("valid target", File(target, "routing-segments/E10_N45.rd5").readText())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun targetOnlyManagedRoutingDataIsPreserved() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-target-only-").toFile()
            try {
                val source = File(root, "source").apply { mkdirs() }
                val target = File(root, "target/GlanceMap")
                File(target, "routing-segments/E5_N45.rd5").apply {
                    parentFile!!.mkdirs()
                    writeText("valid target")
                }

                val result =
                    migration(source, target, File(root, "journal.properties"))
                        .move(PhoneOfflineStorageLocation.EXTERNAL)

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals("valid target", File(target, "routing-segments/E5_N45.rd5").readText())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun validSourceRoutingFileReplacesInvalidTarget() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-routing-source-").toFile()
            try {
                val source = File(root, "source")
                val target = File(root, "target/GlanceMap")
                File(source, "routing-segments/E10_N45.rd5").apply {
                    parentFile!!.mkdirs()
                    writeText("valid source")
                }
                File(target, "routing-segments/E10_N45.rd5").apply {
                    parentFile!!.mkdirs()
                    writeText("invalid")
                }

                val result =
                    migration(source, target, File(root, "journal.properties"))
                        .move(PhoneOfflineStorageLocation.EXTERNAL)

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals("valid source", File(target, "routing-segments/E10_N45.rd5").readText())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun validTargetRoutingFinalSuppressesSourcePartial() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-routing-partial-").toFile()
            try {
                val source = File(root, "source")
                val target = File(root, "target/GlanceMap")
                File(source, "routing-segments/E10_N45.rd5.tmp").apply {
                    parentFile!!.mkdirs()
                    writeText("larger partial source")
                }
                File(target, "routing-segments/E10_N45.rd5").apply {
                    parentFile!!.mkdirs()
                    writeText("valid target")
                }

                val result =
                    migration(source, target, File(root, "journal.properties"))
                        .move(PhoneOfflineStorageLocation.EXTERNAL)

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals("valid target", File(target, "routing-segments/E10_N45.rd5").readText())
                assertFalse(File(target, "routing-segments/E10_N45.rd5.tmp").exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun differentValidFilesKeepTargetAndPreserveTheSourceForRecovery() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-conflict-").toFile()
            try {
                val source = File(root, "source")
                val target = File(root, "target/GlanceMap")
                File(source, "route-library/weekend.json").apply {
                    parentFile!!.mkdirs()
                    writeText("valid source")
                }
                File(target, "route-library/weekend.json").apply {
                    parentFile!!.mkdirs()
                    writeText("valid target")
                }

                val result =
                    migration(source, target, File(root, "journal.properties"))
                        .move(PhoneOfflineStorageLocation.EXTERNAL)

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals("valid target", File(target, "route-library/weekend.json").readText())
                assertEquals(
                    "valid source",
                    File(target, PHONE_OFFLINE_MIGRATION_CONFLICT_DIRECTORY_NAME)
                        .walkTopDown()
                        .first { file -> file.isFile }
                        .readText(),
                )
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun interruptedMigrationResumesWithoutLosingTargetOnlyRoutingData() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-target-resume-").toFile()
            try {
                val source = File(root, "source")
                val target = File(root, "target/GlanceMap")
                File(source, "route-library/new-route.json").apply {
                    parentFile!!.mkdirs()
                    writeText("new route")
                }
                File(target, "routing-segments/E5_N45.rd5").apply {
                    parentFile!!.mkdirs()
                    writeText("valid target")
                }
                val journal = File(root, "journal.properties")

                try {
                    PhoneOfflineStorageMigration(source, target, journal)
                        .move(PhoneOfflineStorageLocation.EXTERNAL) {
                            throw CancellationException("test interruption")
                        }
                } catch (_: CancellationException) {
                    // The persisted journal keeps the target tree available for resumption.
                }

                val result =
                    migration(source, target, journal).move(PhoneOfflineStorageLocation.EXTERNAL)

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals("valid target", File(target, "routing-segments/E5_N45.rd5").readText())
                assertEquals("new route", File(target, "route-library/new-route.json").readText())
            } finally {
                root.deleteRecursively()
            }
        }

    private fun migration(
        source: File,
        target: File,
        journal: File,
    ): PhoneOfflineStorageMigration =
        PhoneOfflineStorageMigration(
            sourceRoot = source,
            targetRoot = target,
            journalFile = journal,
            reconciler =
                PhoneOfflineStorageReconciler { _, file ->
                    file.isFile && file.readText().startsWith("valid")
                },
        )
}
