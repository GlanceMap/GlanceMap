package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class PhoneElevationFolderImporterTest {
    @Test
    fun importsOnlyValidElevationFilesIntoCanonicalDirectory() {
        withImporter { directory, importer ->
            val result =
                importer.synchronize(
                    listOf(
                        input("N45E010.hgt", "valid"),
                        input("notes.txt", "ignored"),
                        input("N46E010.hgt.gz", "bad"),
                    ),
                )

            assertEquals(1, result.importedCount)
            assertEquals(1, result.invalidCount)
            assertEquals("valid", File(directory, "N45E010.hgt").readText())
            assertFalse(File(directory, "notes.txt").exists())
        }
    }

    @Test
    fun keepsValidCanonicalElevationWithoutCopyingSourceAgain() {
        withImporter { directory, importer ->
            File(directory, "N45E010.hgt").apply {
                parentFile!!.mkdirs()
                writeText("valid target")
            }

            val result = importer.synchronize(listOf(input("N45E010.hgt", "valid source")))

            assertEquals(1, result.reusedCount)
            assertEquals(0, result.importedCount)
            assertEquals("valid target", File(directory, "N45E010.hgt").readText())
        }
    }

    @Test
    fun replacesInvalidCanonicalElevationOnlyAfterValidImportIsReady() {
        withImporter { directory, importer ->
            File(directory, "N45E010.hgt").apply {
                parentFile!!.mkdirs()
                writeText("bad")
            }

            val result = importer.synchronize(listOf(input("N45E010.hgt", "valid")))

            assertEquals(1, result.importedCount)
            assertEquals("valid", File(directory, "N45E010.hgt").readText())
            assertFalse(File(directory, "N45E010.hgt.import.part").exists())
        }
    }

    private fun input(
        name: String,
        contents: String,
    ): PhoneElevationFolderInput =
        PhoneElevationFolderInput(name, isFile = true) {
            ByteArrayInputStream(contents.encodeToByteArray())
        }

    private fun withImporter(block: (File, PhoneElevationFolderImporter) -> Unit) {
        val root = createTempDirectory(prefix = "phone-elevation-folder-").toFile()
        try {
            val directory = File(root, "elevation")
            block(
                directory,
                PhoneElevationFolderImporter(directory) { file ->
                    file.isFile && file.readText().startsWith("valid")
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
