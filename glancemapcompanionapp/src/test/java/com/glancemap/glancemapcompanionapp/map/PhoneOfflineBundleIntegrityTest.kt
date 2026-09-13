package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class PhoneOfflineBundleIntegrityTest {
    @Test
    fun zipPartialUsesItsIntendedDestinationFormat() {
        withTemporaryDirectory { root ->
            val targetName = "N45E006.hgt.zip"
            val partial = File(root, ".$targetName.part")
            ZipOutputStream(FileOutputStream(partial)).use { zip ->
                zip.putNextEntry(ZipEntry("N45E006.hgt"))
                writeValidHgt(zip)
                zip.closeEntry()
            }

            assertTrue(isUsablePhoneDemFile(partial, targetName))
            assertFalse(isUsablePhoneDemFile(partial))
        }
    }

    @Test
    fun gzipPartialUsesItsIntendedDestinationFormat() {
        withTemporaryDirectory { root ->
            val targetName = "N45E006.hgt.gz"
            val partial = File(root, ".$targetName.part")
            GZIPOutputStream(FileOutputStream(partial)).use(::writeValidHgt)

            assertTrue(isUsablePhoneDemFile(partial, targetName))
            assertFalse(isUsablePhoneDemFile(partial))
        }
    }

    @Test
    fun corruptAndTruncatedCompressedPartialsAreRejected() {
        withTemporaryDirectory { root ->
            val zipPartial =
                File(root, ".N45E006.hgt.zip.part").apply {
                    writeBytes(byteArrayOf(0x50.toByte(), 0x4b.toByte()))
                }
            val gzipPartial =
                File(root, ".N45E006.hgt.gz.part").apply {
                    writeBytes(byteArrayOf(0x1f.toByte(), 0x8b.toByte()))
                }

            assertFalse(isUsablePhoneDemFile(zipPartial, "N45E006.hgt.zip"))
            assertFalse(isUsablePhoneDemFile(gzipPartial, "N45E006.hgt.gz"))
        }
    }

    @Test
    fun failedInstallLeavesThePartialAndDoesNotCreateAPartialDestination() {
        withTemporaryDirectory { root ->
            val partial = File(root, ".N45E006.hgt.gz.part").apply { writeBytes(byteArrayOf(1)) }
            val target = File(root, "missing/N45E006.hgt.gz")

            try {
                installPhoneDemFile(partial, target)
                fail("Expected installation to fail")
            } catch (_: IOException) {
                // Expected: the destination parent is intentionally unavailable.
            }

            assertTrue(partial.exists())
            assertFalse(target.exists())
        }
    }

    @Test
    fun successfulInstallReplacesDestinationWithoutLeavingTheOldFileActive() {
        withTemporaryDirectory { root ->
            val partial = File(root, ".N45E006.hgt.gz.part").apply { writeBytes(byteArrayOf(2)) }
            val target = File(root, "N45E006.hgt.gz").apply { writeBytes(byteArrayOf(1)) }

            installPhoneDemFile(partial, target)

            assertFalse(partial.exists())
            assertTrue(target.readBytes().contentEquals(byteArrayOf(2)))
            assertFalse(File(root, ".N45E006.hgt.gz.previous").exists())
        }
    }

    private fun writeValidHgt(output: java.io.OutputStream) {
        val row = ByteArray(1201 * 2)
        repeat(1201) { output.write(row) }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = createTempDirectory(prefix = "phone-dem-integrity-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
