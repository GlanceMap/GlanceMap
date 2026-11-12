package com.glancemap.glancemapwearos.core.service.transfer.storage

import kotlinx.coroutines.Job
import java.io.File

internal fun findPartialCleanupFiles(
    roots: Collection<File>,
    bundleDownloads: File,
    includeBundleArchives: Boolean,
): List<File> =
    roots
        .flatMap { root ->
            root
                .walkTopDown()
                .filter { file ->
                    val fragment = file.name.startsWith(".") && file.name.endsWith(".part", ignoreCase = true)
                    val archive =
                        includeBundleArchives &&
                            file.parentFile == bundleDownloads &&
                            (file.name.endsWith(".map.zip", true) || file.name.endsWith(".poi.zip", true))
                    file.isFile && (fragment || archive)
                }.toList()
        }.distinct()

/** Protect inputs and fragments until the actual workers and their children have finished. */
internal class PartialFileUseGuard {
    private val lock = Any()
    private val jobs = mutableSetOf<Job>()

    fun protectUntilComplete(job: Job) {
        val added = synchronized(lock) { jobs.add(job) }
        if (added) job.invokeOnCompletion { synchronized(lock) { jobs.remove(job) } }
    }

    // Registration and each unlink are atomic; do not hold the lock across a directory scan.
    fun deleteIfIdle(file: File): Boolean? =
        synchronized(lock) {
            if (jobs.any { !it.isCompleted }) null else runCatching { file.delete() }.getOrDefault(false)
        }
}

internal val partialFileUseGuard = PartialFileUseGuard()
