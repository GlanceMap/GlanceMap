package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun nonEmptyTargetIsNotOverwritten() =
        runBlocking {
            val root = createTempDirectory(prefix = "phone-storage-target-").toFile()
            try {
                val source = File(root, "source").apply { mkdirs() }
                val target = File(root, "target/GlanceMap")
                File(source, "maps/source.map").apply {
                    parentFile!!.mkdirs()
                    writeText("source")
                }
                File(target, "maps/existing.map").apply {
                    parentFile!!.mkdirs()
                    writeText("existing")
                }
                val journal = File(root, "migration.properties")

                val result =
                    PhoneOfflineStorageMigration(source, target, journal)
                        .move(PhoneOfflineStorageLocation.EXTERNAL)

                assertEquals(
                    PhoneOfflineStorageMigrationError.TARGET_NOT_EMPTY,
                    (result as PhoneOfflineStorageMigrationResult.Failure).error,
                )
                assertEquals("source", File(source, "maps/source.map").readText())
                assertEquals("existing", File(target, "maps/existing.map").readText())
                assertFalse(journal.exists())
            } finally {
                root.deleteRecursively()
            }
        }
}
