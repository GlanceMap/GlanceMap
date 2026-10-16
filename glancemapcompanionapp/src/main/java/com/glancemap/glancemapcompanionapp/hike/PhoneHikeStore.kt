package com.glancemap.glancemapcompanionapp.hike

import com.google.gson.Gson
import java.io.File
import java.util.UUID

internal class PhoneHikeStore(
    private val directory: File,
) {
    private val gson = Gson()

    fun load(): PhoneHikeWorkspaceData {
        val file = File(directory, "workspace.json")
        if (!file.exists()) return PhoneHikeWorkspaceData()
        return requireNotNull(file.reader().use { gson.fromJson(it, PhoneHikeWorkspaceData::class.java) }).also { data ->
            (data.plans + listOfNotNull(data.todayPlan)).forEach { plan ->
                routeFile(plan.routeId)
                require(
                    plan.cursorSegmentIndex >= 0 && plan.cursorFraction.isFinite() && plan.cursorFraction in 0.0..1.0,
                )
                plan.checkpoints.forEach { checkpoint ->
                    require(checkpoint.id.isNotBlank() && checkpoint.placeId.isNotBlank())
                    require(checkpoint.latitude.isFinite() && checkpoint.latitude in -90.0..90.0)
                    require(checkpoint.longitude.isFinite() && checkpoint.longitude in -180.0..180.0)
                    require(
                        checkpoint.segmentIndex >= 0 &&
                            checkpoint.fraction.isFinite() &&
                            checkpoint.fraction in 0.0..1.0,
                    )
                    require(checkpoint.stopMinutes in 0..1_440)
                }
            }
            listOfNotNull(data.todayId, data.previewId).forEach(::routeFile)
            require(data.todayPlan == null || data.todayPlan.routeId == data.todayId)
        }
    }

    fun save(data: PhoneHikeWorkspaceData) = write(File(directory, "workspace.json"), data)

    fun saveRoute(route: PhoneHikeRoute) = write(routeFile(route.id), route)

    fun loadRoute(id: String): PhoneHikeRoute? =
        routeFile(id).takeIf(File::isFile)?.reader()?.use { gson.fromJson(it, PhoneHikeRoute::class.java) }?.also { route ->
            require(route.id == id && route.trackId.isNotBlank() && route.title.isNotBlank())
            require(route.points.size >= 2 && route.routeOffsetMeters.isFinite() && route.routeOffsetMeters >= 0.0)
            require(route.etaConfig.flatSpeedMps.isFinite() && route.etaConfig.flatSpeedMps > 0.0)
            route.points.forEach { point ->
                require(point.location.latitude.isFinite() && point.location.latitude in -90.0..90.0)
                require(point.location.longitude.isFinite() && point.location.longitude in -180.0..180.0)
                require(point.elevationMeters?.isFinite() != false)
            }
            require(phoneHikeRouteId(route.trackId, route.points, route.routeOffsetMeters) == id)
        }

    private fun routeFile(id: String): File {
        require(id.matches(Regex("[0-9a-f]{64}")))
        return File(directory, "routes/$id.json")
    }

    private fun write(
        file: File,
        value: Any,
    ) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output ->
                val writer = output.writer(Charsets.UTF_8)
                gson.toJson(value, writer)
                writer.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(file))
        } finally {
            temporary.delete()
        }
    }
}
