package com.glancemap.glancemapwearos.presentation.features.maps

import org.mapsforge.map.layer.queue.Job
import org.mapsforge.map.layer.queue.JobQueue

/** Refresh priorities when a zoom revisits tiles already waiting in Mapsforge's queue. */
internal fun <T : Job> refreshMapTileJobPriorities(queue: JobQueue<T>): Boolean =
    synchronized(queue) {
        if (queue.size() == 0) return@synchronized false
        // Existing-job additions do not reschedule Mapsforge's queue. Requeue one waiting job
        // under its monitor; MAX_VALUE avoids waiting behind jobs already assigned to workers.
        val waitingJob = queue.get(Int.MAX_VALUE) ?: return@synchronized false
        queue.remove(waitingJob)
        queue.add(waitingJob)
        true
    }
