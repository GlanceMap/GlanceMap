package com.glancemap.glancemapcompanionapp.map

import org.mapsforge.map.layer.queue.Job
import org.mapsforge.map.layer.queue.JobQueue

/**
 * Mapsforge's default 1.2 overdraw enlarges the frame buffer by 20% per axis before squaring it to
 * the diagonal for rotation. A 720x1336 phone view then needs 16-25 tiles of 512 px per screen
 * (watch: 4-9). At 1.0 the square still covers the view diagonal, so rotation keeps full coverage,
 * and the same view needs 9-16 tiles. Less of the map beyond the screen edge is pre-rendered.
 */
internal const val PHONE_MAPSFORGE_OVERDRAW_FACTOR = 1.0

/**
 * Port of the watch's zoom queue refresh. Re-adding a job that is already queued does not make
 * Mapsforge reschedule, so after a zoom, tiles of the previous level can keep their priority over
 * the tiles now on screen. Requeueing one waiting job under the queue monitor forces a reschedule;
 * MAX_VALUE avoids waiting behind jobs already assigned to workers.
 */
internal fun <T : Job> refreshPhoneMapTileJobPriorities(queue: JobQueue<T>): Boolean =
    synchronized(queue) {
        if (queue.size() == 0) return@synchronized false
        val waitingJob = queue.get(Int.MAX_VALUE) ?: return@synchronized false
        queue.remove(waitingJob)
        queue.add(waitingJob)
        true
    }
