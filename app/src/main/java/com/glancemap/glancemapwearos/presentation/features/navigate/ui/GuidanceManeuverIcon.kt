package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.wear.compose.material3.Icon
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState

@Composable
internal fun GuidanceManeuverIcon(
    state: TurnByTurnGuidanceState,
    compassHeadingDeg: Float,
    guideBackToRouteActive: Boolean,
    modifier: Modifier,
    tint: Color = Color.White,
) {
    val bearingRotation =
        when {
            (guideBackToRouteActive || state.offRoute) && state.bearingToRouteDegrees != null ->
                state.bearingToRouteDegrees - compassHeadingDeg
            state.mode == GuidanceMode.TO_START ->
                (state.bearingToStartDegrees ?: 0f) - compassHeadingDeg
            else -> null
        }
    when {
        state.mode == GuidanceMode.FINISHED ->
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Route complete",
                tint = tint,
                modifier = modifier,
            )
        bearingRotation != null ->
            Icon(
                imageVector = Icons.Default.Navigation,
                contentDescription = if (state.mode == GuidanceMode.TO_START) "Direction to route start" else "Direction to route",
                tint = tint,
                modifier = modifier.rotate(bearingRotation),
            )
        else ->
            ManeuverArrow(
                command =
                    if (guidanceShowsCurrentStraight(state)) {
                        RouteInstructionCommand.CONTINUE
                    } else {
                        state.nextInstruction?.command
                    },
                tint = tint,
                modifier = modifier,
            )
    }
}

@Composable
private fun ManeuverArrow(
    command: RouteInstructionCommand?,
    tint: Color,
    modifier: Modifier,
) {
    Icon(
        imageVector = command.maneuverIcon(),
        contentDescription = command.maneuverContentDescription(),
        tint = tint,
        modifier = modifier,
    )
}

private fun RouteInstructionCommand?.maneuverIcon(): ImageVector =
    when (this) {
        RouteInstructionCommand.SLIGHT_LEFT -> Icons.Default.TurnSlightLeft
        RouteInstructionCommand.LEFT -> Icons.Default.TurnLeft
        RouteInstructionCommand.SHARP_LEFT -> SharpDogLegLeft
        RouteInstructionCommand.SLIGHT_RIGHT -> Icons.Default.TurnSlightRight
        RouteInstructionCommand.RIGHT -> Icons.Default.TurnRight
        RouteInstructionCommand.SHARP_RIGHT -> SharpDogLegRight
        RouteInstructionCommand.CONTINUE,
        RouteInstructionCommand.FINISH,
        null,
        -> Icons.Default.Straight
    }

private val SharpDogLegLeft: ImageVector by lazy {
    materialIcon(name = "SharpDogLegLeft") {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(17f, 21f)
            lineTo(17f, 11f)
            lineTo(10.7f, 17.3f)
        }
        materialPath {
            moveTo(5f, 22f)
            lineTo(8.8f, 14.5f)
            lineTo(12.5f, 18.2f)
            close()
        }
    }
}

private val SharpDogLegRight: ImageVector by lazy {
    materialIcon(name = "SharpDogLegRight") {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(7f, 21f)
            lineTo(7f, 11f)
            lineTo(13.3f, 17.3f)
        }
        materialPath {
            moveTo(19f, 22f)
            lineTo(15.2f, 14.5f)
            lineTo(11.5f, 18.2f)
            close()
        }
    }
}

private fun RouteInstructionCommand?.maneuverContentDescription(): String =
    when (this) {
        RouteInstructionCommand.SLIGHT_LEFT -> "Slight left"
        RouteInstructionCommand.LEFT -> "Turn left"
        RouteInstructionCommand.SHARP_LEFT -> "Sharp left"
        RouteInstructionCommand.SLIGHT_RIGHT -> "Slight right"
        RouteInstructionCommand.RIGHT -> "Turn right"
        RouteInstructionCommand.SHARP_RIGHT -> "Sharp right"
        RouteInstructionCommand.CONTINUE,
        RouteInstructionCommand.FINISH,
        null,
        -> "Continue straight"
    }
