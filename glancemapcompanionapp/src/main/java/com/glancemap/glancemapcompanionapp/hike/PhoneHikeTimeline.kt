package com.glancemap.glancemapcompanionapp.hike

/** A planned stop occupies time without changing the canonical route position. */
internal class PhoneHikeTimeline(
    private val model: PhoneHikeRouteModel,
    plan: PhoneHikePlan,
) {
    data class Stop(
        val position: PhoneHikePosition,
        val start: Double,
        val end: Double,
    )

    private val checkpoints = model.orderedCheckpoints(plan)

    val stops: List<Stop> =
        buildList {
            var preceding = 0.0
            checkpoints.forEach { (checkpoint, position) ->
                if (checkpoint.stopMinutes > 0 && position.distanceMeters < model.totalDistanceMeters - 0.01) {
                    val start = position.elapsedSeconds + preceding
                    val duration = checkpoint.stopMinutes * 60.0
                    add(Stop(position, start, start + duration))
                    preceding += duration
                }
            }
        }

    val totalSeconds: Double =
        model.plannedSecondsAt(requireNotNull(model.atDistance(model.totalDistanceMeters)), checkpoints)

    fun secondsAt(position: PhoneHikePosition): Double = model.plannedSecondsAt(position, checkpoints)

    fun atSeconds(seconds: Double): PhoneHikePosition {
        val target = seconds.coerceIn(0.0, totalSeconds)
        val stop = stops.firstOrNull { target >= it.start && target <= it.end }
        return when {
            stop != null -> stop.position
            target <= 0.0 -> requireNotNull(model.atDistance(0.0))
            target >= totalSeconds -> requireNotNull(model.atDistance(model.totalDistanceMeters))
            else -> positionBetweenStops(target)
        }
    }

    private fun positionBetweenStops(target: Double): PhoneHikePosition {
        var low = 0.0
        var high = model.totalDistanceMeters
        repeat(40) {
            val middle = (low + high) / 2.0
            val position = requireNotNull(model.atDistance(middle))
            if (secondsAt(position) < target) low = middle else high = middle
        }
        return requireNotNull(model.atDistance((low + high) / 2.0))
    }
}
