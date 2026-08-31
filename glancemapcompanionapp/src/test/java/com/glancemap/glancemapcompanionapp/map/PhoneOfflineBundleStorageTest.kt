package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PhoneOfflineBundleStorageTest {
    @Test
    fun switchingStorageChangesTheSharedRoot() {
        val internal = File("/tmp/glancemap-internal")
        val external = File("/tmp/glancemap-external")

        assertEquals(
            internal,
            resolvePhoneOfflineStorageRoot(PhoneOfflineStorageLocation.INTERNAL, internal, external),
        )
        assertEquals(
            external,
            resolvePhoneOfflineStorageRoot(PhoneOfflineStorageLocation.EXTERNAL, internal, external),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun unavailableExternalStorageIsReported() {
        resolvePhoneOfflineStorageRoot(
            location = PhoneOfflineStorageLocation.EXTERNAL,
            internalRoot = File("/tmp/glancemap-internal"),
            externalRoot = null,
        )
    }

    @Test
    fun partialDownloadDoesNotReportACompleteBundle() {
        val health =
            phoneOfflineBundleHealth(
                hasMap = true,
                hasPoi = true,
                expectsRefugesInfo = true,
                hasRefugesInfo = false,
                expectedRoutingFileNames = listOf("N45E006.rd5"),
                downloadedRoutingFileNames = emptyList(),
                expectedDemTileIds = listOf("N45E006"),
                downloadedDemTileIds = listOf("N45E006"),
            )

        assertEquals(PhoneOfflineBundleStatus.PARTIAL, health.status)
        assertTrue("refuges.info" in health.missingFileNames)
        assertTrue("N45E006.rd5" in health.missingFileNames)
    }

    @Test
    fun selectedDemLocationIsVisibleToMapsforge() {
        val root = createTempDirectory(prefix = "phone-dem-renderer-").toFile()
        try {
            val file = phoneOfflineDemFile(root, PhoneOfflineDemSource.STANDARD, "n45e006")
            file.parentFile!!.mkdirs()
            val rendererFile = File(file.parentFile, "N45E006.hgt")
            rendererFile.writeBytes(ByteArray(8))

            val files =
                PhoneMapsforgeDemFolder(
                    roots = listOf(File(root, PhoneOfflineDemSource.STANDARD.id)),
                    requiredTileIds = setOf("N45E006"),
                ).files()

            assertEquals(1, files.count())
        } finally {
            root.deleteRecursively()
        }
    }
}
