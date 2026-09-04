package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PhoneOfflineStorageReconciliationTest {
    @Test
    fun validTargetWinsOverInvalidSource() {
        withFiles { source, target ->
            val result = reconciler { file -> file == target.file }.reconcile(source, target)

            assertEquals(PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_VALID, result.decision)
            assertEquals(target, result.selected)
        }
    }

    @Test
    fun validSourceReplacesInvalidTarget() {
        withFiles { source, target ->
            val result = reconciler { file -> file == source.file }.reconcile(source, target)

            assertEquals(PhoneOfflineStorageReconciliationDecision.REPLACE_INVALID_TARGET, result.decision)
            assertEquals(source, result.selected)
        }
    }

    @Test
    fun identicalFilesReuseTargetWithoutSourceCopy() {
        withFiles(sourceBytes = "same", targetBytes = "same") { source, target ->
            val result = reconciler { true }.reconcile(source, target)

            assertEquals(PhoneOfflineStorageReconciliationDecision.REUSE_TARGET_IDENTICAL, result.decision)
            assertEquals(target, result.selected)
            assertFalse(result.preserveSourceConflict)
        }
    }

    @Test
    fun differingValidAssetsKeepTargetAndPreserveSourceConflict() {
        withFiles { source, target ->
            val result = reconciler { true }.reconcile(source, target)

            assertEquals(PhoneOfflineStorageReconciliationDecision.PRESERVE_TARGET_CONFLICT, result.decision)
            assertEquals(target, result.selected)
            assertTrue(result.preserveSourceConflict)
        }
    }

    @Test
    fun differingInvalidAssetsKeepTargetAndPreserveSourceConflict() {
        withFiles { source, target ->
            val result = reconciler { false }.reconcile(source, target)

            assertEquals(PhoneOfflineStorageReconciliationDecision.KEEP_TARGET_INVALID, result.decision)
            assertEquals(target, result.selected)
            assertTrue(result.preserveSourceConflict)
        }
    }

    @Test
    fun largerRoutingPartialWinsWithoutRd5Validation() {
        withFiles(
            relativePath = "routing-segments/E10_N45.rd5.tmp",
            sourceBytes = "larger partial",
            targetBytes = "part",
        ) { source, target ->
            val result = reconciler { false }.reconcile(source, target)

            assertEquals(PhoneOfflineStorageReconciliationDecision.COPY_SOURCE, result.decision)
            assertEquals(source, result.selected)
        }
    }

    @Test
    fun largerTargetRoutingPartialIsNotReplaced() {
        withFiles(
            relativePath = "routing-segments/E10_N45.rd5.tmp",
            sourceBytes = "part",
            targetBytes = "larger partial target",
        ) { source, target ->
            val result = reconciler { false }.reconcile(source, target)

            assertEquals(PhoneOfflineStorageReconciliationDecision.KEEP_LARGER_ROUTING_PARTIAL, result.decision)
            assertEquals(target, result.selected)
        }
    }

    private fun reconciler(
        isValid: (File) -> Boolean,
    ): PhoneOfflineStorageReconciler = PhoneOfflineStorageReconciler { _, file -> isValid(file) }

    private fun withFiles(
        relativePath: String = "maps/Bayern.map",
        sourceBytes: String = "source",
        targetBytes: String = "target",
        block: (PhoneOfflineStorageFile, PhoneOfflineStorageFile) -> Unit,
    ) {
        val root = createTempDirectory(prefix = "phone-storage-reconcile-").toFile()
        try {
            val sourceFile =
                File(root, "source/$relativePath").apply {
                    parentFile!!.mkdirs()
                    writeText(sourceBytes)
                }
            val targetFile =
                File(root, "target/$relativePath").apply {
                    parentFile!!.mkdirs()
                    writeText(targetBytes)
                }
            block(
                sourceFile.asStorageFile(relativePath),
                targetFile.asStorageFile(relativePath),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun File.asStorageFile(relativePath: String): PhoneOfflineStorageFile =
        PhoneOfflineStorageFile(
            relativePath = relativePath,
            file = this,
            sizeBytes = length(),
            sha256 = sha256ForTest(readBytes()),
        )

    private fun sha256ForTest(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
