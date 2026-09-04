package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PhoneOfflineBundleArchiveTest {
    @Test
    fun expectedMapEntryUsesOnlyItsSafeLeafName() {
        assertEquals(
            "alps.map",
            expectedPhoneBundleArchiveEntryName("maps/alps.map", ".map"),
        )
    }

    @Test
    fun traversalAndWrongEntryTypesAreRejected() {
        assertNull(expectedPhoneBundleArchiveEntryName("../alps.map", ".map"))
        assertNull(expectedPhoneBundleArchiveEntryName("/alps.map", ".map"))
        assertNull(expectedPhoneBundleArchiveEntryName("alps.poi", ".map"))
    }

    @Test
    fun demSourcesUseWatchCompatibleRemoteNamesAndFolders() {
        assertEquals(
            "N45E006.hgt.zip",
            PhoneOfflineDemSource.STANDARD.remoteFileName("n45e006"),
        )
        assertEquals(
            "https://download.mapsforge.org/maps/dem/dem3/N45/N45E006.hgt.zip",
            PhoneOfflineDemSource.STANDARD.remoteUrl("n45e006"),
        )
        assertEquals(
            "https://s3.amazonaws.com/elevation-tiles-prod/skadi/S45/S45E006.hgt.gz",
            PhoneOfflineDemSource.DETAILED.remoteUrl("s45e006"),
        )
    }

    @Test
    fun `completed archive is reused only when its expected entry and size are valid`() {
        val directory = Files.createTempDirectory("phone-bundle-archive").toFile()
        val archive = writeZip(directory, "area-map.zip", "maps/Area.map")

        try {
            assertEquals(
                archive,
                reusablePhoneBundleArchiveOrNull(
                    directory = directory,
                    fileName = archive.name,
                    entryExtension = ".map",
                    expectedSize = archive.length(),
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid or stale completed archive is removed before redownload`() {
        val directory = Files.createTempDirectory("phone-bundle-archive").toFile()
        val staleArchive = writeZip(directory, "stale-map.zip", "Area.map")
        val wrongEntryArchive = writeZip(directory, "wrong-map.zip", "Area.poi")

        try {
            assertNull(
                reusablePhoneBundleArchiveOrNull(
                    directory = directory,
                    fileName = staleArchive.name,
                    entryExtension = ".map",
                    expectedSize = staleArchive.length() + 1L,
                ),
            )
            assertFalse(staleArchive.exists())

            assertNull(
                reusablePhoneBundleArchiveOrNull(
                    directory = directory,
                    fileName = wrongEntryArchive.name,
                    entryExtension = ".map",
                ),
            )
            assertFalse(wrongEntryArchive.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeZip(
        directory: File,
        fileName: String,
        entryName: String,
    ): File =
        File(directory, fileName).also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
}
