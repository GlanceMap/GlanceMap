package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

internal class WorldWindGridCache(
    private val maxBytes: Long,
) {
    private val grids = LinkedHashMap<Long, ShortArray>(16, 0.75f, true)
    private var generation: Long? = null
    val hits = AtomicLong()
    val misses = AtomicLong()
    val retainedBytes = AtomicLong()

    @Synchronized
    fun prepareGeneration(current: Long): Boolean {
        val changed = generation != null && generation != current
        if (changed) clear()
        generation = current
        return changed
    }

    @Synchronized
    fun get(key: Long): ShortArray? {
        val grid = grids[key]
        if (grid == null) {
            misses.incrementAndGet()
            return null
        }
        hits.incrementAndGet()
        return grid.copyOf()
    }

    @Synchronized
    fun put(
        key: Long,
        grid: ShortArray,
    ) {
        val bytes = grid.size.toLong() * Short.SIZE_BYTES
        if (bytes > maxBytes || grid.any { it == Short.MIN_VALUE }) return
        grids.remove(key)?.let { retainedBytes.addAndGet(-it.size.toLong() * Short.SIZE_BYTES) }
        grids[key] = grid.copyOf()
        retainedBytes.addAndGet(bytes)
        val iterator = grids.entries.iterator()
        while (retainedBytes.get() > maxBytes && iterator.hasNext()) {
            retainedBytes.addAndGet(
                -iterator
                    .next()
                    .value.size
                    .toLong() * Short.SIZE_BYTES,
            )
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        grids.clear()
        retainedBytes.set(0)
    }
}
