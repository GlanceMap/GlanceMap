package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

internal enum class PhoneOfflineStorageMigrationPhase {
    IDLE,
    COPYING,
    VERIFYING,
    SWITCHING,
    CLEANUP,
    COMPLETE,
    FAILED,
}

internal fun phoneOfflineStorageMigrationPercent(
    copiedFiles: Int,
    totalFiles: Int,
): Int? =
    totalFiles.takeIf { it > 0 }?.let {
        (copiedFiles.toLong().coerceAtLeast(0L) * 100L / it.toLong())
            .toInt()
            .coerceIn(0, 100)
    }

internal data class PhoneOfflineStorageMigrationState(
    val phase: PhoneOfflineStorageMigrationPhase = PhoneOfflineStorageMigrationPhase.IDLE,
    val source: PhoneOfflineStorageLocation? = null,
    val target: PhoneOfflineStorageLocation? = null,
    val copiedFiles: Int = 0,
    val totalFiles: Int = 0,
    val requiredSpaceBytes: Long = 0L,
    val availableSpaceBytes: Long = 0L,
    val message: String? = null,
) {
    val percent: Int?
        get() = phoneOfflineStorageMigrationPercent(copiedFiles, totalFiles)
}

internal data class PhoneOfflineStorageMigrationProgress(
    val phase: PhoneOfflineStorageMigrationPhase,
    val source: PhoneOfflineStorageLocation,
    val target: PhoneOfflineStorageLocation,
    val copiedFiles: Int,
    val totalFiles: Int,
    val requiredSpaceBytes: Long,
    val availableSpaceBytes: Long,
)

internal enum class PhoneOfflineStorageMigrationError {
    EXTERNAL_UNAVAILABLE,
    MIGRATION_ALREADY_PENDING,
    INSUFFICIENT_SPACE,
    TARGET_NOT_EMPTY,
    COPY_FAILED,
    VERIFY_FAILED,
    SWITCH_FAILED,
    CLEANUP_FAILED,
}

internal sealed interface PhoneOfflineStorageMigrationResult {
    data class Success(
        val source: PhoneOfflineStorageLocation,
        val target: PhoneOfflineStorageLocation,
        val movedFiles: Int,
    ) : PhoneOfflineStorageMigrationResult

    data class Failure(
        val error: PhoneOfflineStorageMigrationError,
        val message: String,
    ) : PhoneOfflineStorageMigrationResult
}

internal data class PhoneOfflineStorageMigrationJournal(
    val source: PhoneOfflineStorageLocation,
    val target: PhoneOfflineStorageLocation,
    val sourceRootPath: String,
    val targetRootPath: String,
    val stagingRootPath: String,
    val backupRootPath: String,
    val phase: PhoneOfflineStorageMigrationPhase,
)

/** Moves the shared file tree only after a verified copy is ready. */
internal class PhoneOfflineStorageMigration(
    private val storage: PhoneOfflineStorage?,
    private val fixedSourceRoot: File?,
    private val fixedTargetRoot: File?,
    private val fixedJournalFile: File?,
    private val fixedSourceLocation: PhoneOfflineStorageLocation?,
    private val fixedTargetLocation: PhoneOfflineStorageLocation?,
) {
    private val mutex = Mutex()

    constructor(context: Context) :
        this(
            storage = PhoneOfflineStorage(context.applicationContext),
            fixedSourceRoot = null,
            fixedTargetRoot = null,
            fixedJournalFile = null,
            fixedSourceLocation = null,
            fixedTargetLocation = null,
        )

    internal constructor(
        sourceRoot: File,
        targetRoot: File,
        journalFile: File,
    ) :
        this(
            storage = null,
            fixedSourceRoot = sourceRoot,
            fixedTargetRoot = targetRoot,
            fixedJournalFile = journalFile,
            fixedSourceLocation = PhoneOfflineStorageLocation.INTERNAL,
            fixedTargetLocation = PhoneOfflineStorageLocation.EXTERNAL,
        )

    fun pending(): PhoneOfflineStorageMigrationJournal? = readJournal(journalFile())

    suspend fun move(
        target: PhoneOfflineStorageLocation,
        onProgress: suspend (PhoneOfflineStorageMigrationProgress) -> Unit = {},
    ): PhoneOfflineStorageMigrationResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                moveLocked(target, onProgress)
            }
        }

    @Suppress(
        "ComplexCondition",
        "CyclomaticComplexMethod",
        "LongMethod",
        "ReturnCount",
        "TooGenericExceptionCaught",
    ) // The copy/verify/switch journal is one auditable transaction boundary.
    private suspend fun moveLocked(
        target: PhoneOfflineStorageLocation,
        onProgress: suspend (PhoneOfflineStorageMigrationProgress) -> Unit,
    ): PhoneOfflineStorageMigrationResult {
        val targetRoot =
            (fixedTargetRoot ?: storage?.canonicalRoot(target))
                ?: return PhoneOfflineStorageMigrationResult.Failure(
                    PhoneOfflineStorageMigrationError.EXTERNAL_UNAVAILABLE,
                    "The selected storage is unavailable.",
                )
        val pending = readJournal(journalFile())
        if (pending != null && pending.target != target) {
            return PhoneOfflineStorageMigrationResult.Failure(
                PhoneOfflineStorageMigrationError.MIGRATION_ALREADY_PENDING,
                "Finish the pending move to ${pending.target.label} first.",
            )
        }
        val sourceLocation = pending?.source ?: fixedSourceLocation ?: checkNotNull(storage).location()
        val sourceRoot = pending?.let(::sourceRoot) ?: fixedSourceRoot ?: checkNotNull(storage).activeRoot()
        if (sourceRoot.absoluteFile == targetRoot.absoluteFile && pending == null) {
            storage?.setLocation(target)
            return PhoneOfflineStorageMigrationResult.Success(sourceLocation, target, 0)
        }

        val journal =
            pending ?: PhoneOfflineStorageMigrationJournal(
                source = sourceLocation,
                target = target,
                sourceRootPath = sourceRoot.absolutePath,
                targetRootPath = targetRoot.absolutePath,
                stagingRootPath = File(targetRoot.parentFile, ".GlanceMap-migration-${UUID.randomUUID()}").absolutePath,
                backupRootPath = File(targetRoot.parentFile, ".GlanceMap-backup-${UUID.randomUUID()}").absolutePath,
                phase = PhoneOfflineStorageMigrationPhase.COPYING,
            )
        val stagingRoot = File(journal.stagingRootPath)
        val finalTargetRoot = File(journal.targetRootPath)
        val backupRoot = File(journal.backupRootPath)
        if (journal.phase == PhoneOfflineStorageMigrationPhase.CLEANUP) {
            return finishCleanup(journal, allFiles(finalTargetRoot).size)
        }
        val sourceFiles = managedFiles(sourceRoot)
        val targetFiles = allFiles(finalTargetRoot)
        val expectedFiles = mergeFiles(targetFiles, sourceFiles)
        val sourceFilesByPath = sourceFiles.associateBy(FileEntry::relativePath)
        val requiredSpace = expectedFiles.sumOf(FileEntry::sizeBytes)
        val targetParent = finalTargetRoot.parentFile
        if (targetParent == null || (!targetParent.exists() && !targetParent.mkdirs())) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                "The selected storage directory cannot be created.",
            )
        }
        val availableSpace = targetParent.usableSpace
        if (availableSpace < requiredSpace && journal.phase == PhoneOfflineStorageMigrationPhase.COPYING) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.INSUFFICIENT_SPACE,
                "Not enough free space: $requiredSpace bytes required, $availableSpace available.",
            )
        }

        return try {
            if (journal.phase == PhoneOfflineStorageMigrationPhase.COPYING) {
                writeJournal(journalFile(), journal)
                if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
                    return failure(
                        journal,
                        PhoneOfflineStorageMigrationError.COPY_FAILED,
                        "The migration staging directory cannot be created.",
                    )
                }
                onProgress(
                    PhoneOfflineStorageMigrationProgress(
                        phase = PhoneOfflineStorageMigrationPhase.COPYING,
                        source = journal.source,
                        target = journal.target,
                        copiedFiles = 0,
                        totalFiles = expectedFiles.size,
                        requiredSpaceBytes = requiredSpace,
                        availableSpaceBytes = availableSpace,
                    ),
                )
                expectedFiles.forEachIndexed { index, entry ->
                    currentCoroutineContext().ensureActive()
                    val sourceFile =
                        if (entry.relativePath in sourceFilesByPath) {
                            File(sourceRoot, entry.relativePath)
                        } else {
                            File(finalTargetRoot, entry.relativePath)
                        }
                    val stagingFile = File(stagingRoot, entry.relativePath)
                    if (!matches(stagingFile, entry)) {
                        copyFile(sourceFile, stagingFile)
                    }
                    if (!matches(stagingFile, entry)) {
                        return failure(
                            journal,
                            PhoneOfflineStorageMigrationError.COPY_FAILED,
                            "The copied file could not be verified: ${entry.relativePath}",
                        )
                    }
                    onProgress(
                        PhoneOfflineStorageMigrationProgress(
                            phase = PhoneOfflineStorageMigrationPhase.COPYING,
                            source = journal.source,
                            target = journal.target,
                            copiedFiles = index + 1,
                            totalFiles = expectedFiles.size,
                            requiredSpaceBytes = requiredSpace,
                            availableSpaceBytes = availableSpace,
                        ),
                    )
                }
                writeJournal(
                    journalFile(),
                    journal.copy(phase = PhoneOfflineStorageMigrationPhase.VERIFYING),
                )
            }

            val verified = allFiles(stagingRoot)
            if (
                journal.phase == PhoneOfflineStorageMigrationPhase.SWITCHING &&
                !stagingRoot.exists() &&
                allFiles(finalTargetRoot) == expectedFiles
            ) {
                val cleanupJournal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.CLEANUP)
                writeJournal(journalFile(), cleanupJournal)
                return finishCleanup(cleanupJournal, expectedFiles.size)
            }
            if (verified != expectedFiles) {
                return failure(
                    journal,
                    PhoneOfflineStorageMigrationError.VERIFY_FAILED,
                    "The staged GlanceMap data did not pass integrity verification.",
                )
            }
            onProgress(
                PhoneOfflineStorageMigrationProgress(
                    phase = PhoneOfflineStorageMigrationPhase.VERIFYING,
                    source = journal.source,
                    target = journal.target,
                    copiedFiles = verified.size,
                    totalFiles = expectedFiles.size,
                    requiredSpaceBytes = requiredSpace,
                    availableSpaceBytes = availableSpace,
                ),
            )

            val switchingJournal = journal.copy(phase = PhoneOfflineStorageMigrationPhase.SWITCHING)
            writeJournal(journalFile(), switchingJournal)
            if (finalTargetRoot.exists() && !finalTargetRoot.renameTo(backupRoot)) {
                return failure(
                    switchingJournal,
                    PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                    "The existing target directory could not be preserved.",
                )
            }
            if (!stagingRoot.renameTo(finalTargetRoot)) {
                if (backupRoot.exists()) backupRoot.renameTo(finalTargetRoot)
                return failure(
                    switchingJournal,
                    PhoneOfflineStorageMigrationError.SWITCH_FAILED,
                    "The verified GlanceMap directory could not be activated.",
                )
            }
            val cleanupJournal = switchingJournal.copy(phase = PhoneOfflineStorageMigrationPhase.CLEANUP)
            writeJournal(journalFile(), cleanupJournal)
            setActiveLocation(target)
            onProgress(
                PhoneOfflineStorageMigrationProgress(
                    phase = PhoneOfflineStorageMigrationPhase.CLEANUP,
                    source = journal.source,
                    target = journal.target,
                    copiedFiles = verified.size,
                    totalFiles = expectedFiles.size,
                    requiredSpaceBytes = requiredSpace,
                    availableSpaceBytes = availableSpace,
                ),
            )
            finishCleanup(cleanupJournal, expectedFiles.size)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            failure(
                journal,
                PhoneOfflineStorageMigrationError.COPY_FAILED,
                error.message ?: "The GlanceMap data move failed.",
            )
        }
    }

    private fun sourceRoot(journal: PhoneOfflineStorageMigrationJournal): File = File(journal.sourceRootPath)

    private fun journalFile(): File = fixedJournalFile ?: checkNotNull(storage).migrationJournalFile()

    private fun setActiveLocation(target: PhoneOfflineStorageLocation) {
        storage?.setLocation(fixedTargetLocation ?: target)
    }

    private fun finishCleanup(
        journal: PhoneOfflineStorageMigrationJournal,
        movedFiles: Int,
    ): PhoneOfflineStorageMigrationResult {
        setActiveLocation(journal.target)
        if (!deleteManagedData(sourceRoot(journal)) || !deleteMigrationBackup(File(journal.backupRootPath))) {
            return failure(
                journal,
                PhoneOfflineStorageMigrationError.CLEANUP_FAILED,
                "The new storage is active, but the old data could not be deleted yet.",
            )
        }
        journalFile().delete()
        return PhoneOfflineStorageMigrationResult.Success(journal.source, journal.target, movedFiles)
    }

    private fun failure(
        journal: PhoneOfflineStorageMigrationJournal,
        error: PhoneOfflineStorageMigrationError,
        message: String,
    ): PhoneOfflineStorageMigrationResult.Failure {
        writeJournal(
            journalFile(),
            journal.copy(
                phase =
                    if (error == PhoneOfflineStorageMigrationError.CLEANUP_FAILED) {
                        PhoneOfflineStorageMigrationPhase.CLEANUP
                    } else {
                        PhoneOfflineStorageMigrationPhase.COPYING
                    },
            ),
        )
        return PhoneOfflineStorageMigrationResult.Failure(error, message)
    }

    private companion object {
        fun managedFiles(root: File): List<FileEntry> =
            PHONE_OFFLINE_MANAGED_DIRECTORY_NAMES
                .asSequence()
                .map { name -> File(root, name) }
                .flatMap { directory -> directory.walkTopDown().filter(File::isFile) }
                .map { file ->
                    FileEntry(
                        relativePath = file.relativeTo(root).invariantSeparatorsPath,
                        sizeBytes = file.length(),
                        sha256 = sha256(file),
                    )
                }.sortedBy(FileEntry::relativePath)
                .toList()

        fun allFiles(root: File): List<FileEntry> =
            if (!root.exists()) {
                emptyList()
            } else {
                root.walkTopDown()
                    .filter(File::isFile)
                    .map { file ->
                        FileEntry(
                            relativePath = file.relativeTo(root).invariantSeparatorsPath,
                            sizeBytes = file.length(),
                            sha256 = sha256(file),
                        )
                    }.sortedBy(FileEntry::relativePath)
                    .toList()
            }

        fun mergeFiles(
            targetFiles: List<FileEntry>,
            sourceFiles: List<FileEntry>,
        ): List<FileEntry> =
            (targetFiles + sourceFiles)
                .associateBy(FileEntry::relativePath)
                .values
                .sortedBy(FileEntry::relativePath)

        fun matches(
            file: File,
            expected: FileEntry,
        ): Boolean =
            file.isFile &&
                file.length() == expected.sizeBytes &&
                runCatching { sha256(file) == expected.sha256 }.getOrDefault(false)

        fun copyFile(
            source: File,
            target: File,
        ) {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.part")
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not promote ${source.name}." }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        fun deleteManagedData(root: File): Boolean =
            PHONE_OFFLINE_MANAGED_DIRECTORY_NAMES.all { name ->
                val directory = File(root, name)
                !directory.exists() || directory.deleteRecursively()
            }

        fun deleteMigrationBackup(root: File): Boolean = !root.exists() || root.deleteRecursively()

        fun writeJournal(
            file: File,
            journal: PhoneOfflineStorageMigrationJournal,
        ) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            val properties =
                Properties().apply {
                    setProperty("source", journal.source.name)
                    setProperty("target", journal.target.name)
                    setProperty("sourceRoot", journal.sourceRootPath)
                    setProperty("targetRoot", journal.targetRootPath)
                    setProperty("stagingRoot", journal.stagingRootPath)
                    setProperty("backupRoot", journal.backupRootPath)
                    setProperty("phase", journal.phase.name)
                }
            FileOutputStream(temporary).use { output -> properties.store(output, "GlanceMap storage migration") }
            if (file.exists()) file.delete()
            check(temporary.renameTo(file)) { "Could not persist the storage migration journal." }
        }

        fun readJournal(file: File): PhoneOfflineStorageMigrationJournal? =
            runCatching {
                if (!file.isFile) return@runCatching null
                val properties = Properties()
                file.inputStream().use(properties::load)
                PhoneOfflineStorageMigrationJournal(
                    source = PhoneOfflineStorageLocation.valueOf(properties.getProperty("source")),
                    target = PhoneOfflineStorageLocation.valueOf(properties.getProperty("target")),
                    sourceRootPath = properties.getProperty("sourceRoot"),
                    targetRootPath = properties.getProperty("targetRoot"),
                    stagingRootPath = properties.getProperty("stagingRoot"),
                    backupRootPath = properties.getProperty("backupRoot"),
                    phase = PhoneOfflineStorageMigrationPhase.valueOf(properties.getProperty("phase")),
                )
            }.getOrNull()

        private data class FileEntry(
            val relativePath: String,
            val sizeBytes: Long,
            val sha256: String,
        )
    }
}
