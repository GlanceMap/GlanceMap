package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class PhoneRoutingFolderImporterTest {
    @Test
    fun importsOnlyFinalValidRd5Files() {
        withImporter { directory, importer ->
            val result =
                importer.synchronize(
                    listOf(
                        input("E5_N45.rd5", "valid source"),
                        input("E5_N45.rd5.tmp", "valid partial"),
                        input("E5_N45.rd5.import.part", "valid partial"),
                        input("notes.txt", "valid note"),
                        input("broken.rd5", "broken"),
                    ),
                )

            assertEquals(1, result.validCount)
            assertEquals(1, result.importedCount)
            assertEquals(1, result.invalidCount)
            assertEquals("valid source", File(directory, "E5_N45.rd5").readText())
            assertFalse(File(directory, "notes.txt").exists())
            assertFalse(File(directory, "E5_N45.rd5.tmp").exists())
        }
    }

    @Test
    fun reusesValidManagedRoutingPackWithoutCopyingIt() {
        withImporter { directory, importer ->
            File(directory, "E5_N45.rd5").apply {
                parentFile!!.mkdirs()
                writeText("valid managed")
            }

            val result = importer.synchronize(listOf(input("E5_N45.rd5", "valid source")))

            assertEquals(1, result.reusedCount)
            assertEquals(0, result.importedCount)
            assertEquals("valid managed", File(directory, "E5_N45.rd5").readText())
        }
    }

    @Test
    fun normalizesUppercaseExtensionToTheCanonicalManagedName() {
        withImporter { directory, importer ->
            val result = importer.synchronize(listOf(input("E5_N45.RD5", "valid source")))

            assertEquals(1, result.importedCount)
            assertEquals("valid source", File(directory, "E5_N45.rd5").readText())
            assertEquals("E5_N45.rd5", directory.listFiles()!!.single().name)
        }
    }

    @Test
    fun replacesInvalidManagedRoutingPackAtomicallyAfterValidation() {
        withImporter { directory, importer ->
            File(directory, "E5_N45.rd5").apply {
                parentFile!!.mkdirs()
                writeText("invalid managed")
            }

            val result = importer.synchronize(listOf(input("E5_N45.rd5", "valid source")))

            assertEquals(1, result.importedCount)
            assertEquals("valid source", File(directory, "E5_N45.rd5").readText())
            assertFalse(File(directory, ".E5_N45.rd5.import.part").exists())
            assertFalse(File(directory, ".E5_N45.rd5.import.backup").exists())
        }
    }

    @Test
    fun normalizesExtensionButRejectsPathsAndTemporaryArtifacts() {
        assertEquals("E5_N45.rd5", phoneRoutingFolderFileName(" E5_N45.RD5 "))
        assertEquals(null, phoneRoutingFolderFileName("folder/E5_N45.rd5"))
        assertEquals(null, phoneRoutingFolderFileName("E5_N45.rd5.tmp"))
        assertEquals(null, phoneRoutingFolderFileName("E5_N45.rd5.import.part"))
        assertEquals(null, phoneRoutingFolderFileName("notes.txt"))
    }

    @Test
    fun failedImportLeavesExistingDestinationIntact() {
        withImporter { directory, importer ->
            File(directory, "E5_N45.rd5").apply {
                parentFile!!.mkdirs()
                writeText("invalid managed")
            }

            val result = importer.synchronize(listOf(input("E5_N45.rd5", "invalid source")))

            assertEquals(1, result.invalidCount)
            assertEquals(0, result.importedCount)
            assertTrue(File(directory, "E5_N45.rd5").isFile)
            assertEquals("invalid managed", File(directory, "E5_N45.rd5").readText())
        }
    }

    private fun input(
        name: String,
        contents: String,
    ): PhoneRoutingFolderInput =
        PhoneRoutingFolderInput(name, isFile = true) {
            ByteArrayInputStream(contents.encodeToByteArray())
        }

    private fun withImporter(block: (File, PhoneRoutingFolderImporter) -> Unit) {
        val root = createTempDirectory(prefix = "phone-routing-folder-").toFile()
        try {
            val directory = File(root, "routing-segments")
            block(
                directory,
                PhoneRoutingFolderImporter(directory) { file ->
                    file.isFile && file.readText().startsWith("valid")
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
