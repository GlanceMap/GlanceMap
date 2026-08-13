@file:Suppress("FunctionNaming")

package com.glancemap.glancemapwearos.presentation.features.navigate

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize

@Composable
fun AmbientScreen(
    ambientTick: Long,
    timeFormat: String,
    burnInProtectionRequired: Boolean = true,
    deviceHasLowBitAmbient: Boolean = false,
) {
    val screenSize = rememberWearScreenSize()
    val adaptive = rememberWearAdaptiveSpec()
    val timeFontSize = ambientTimeFontSize(isRound = adaptive.isRound, screenSize = screenSize)
    val burnInShiftRange = ambientBurnInShiftRange(isRound = adaptive.isRound, screenSize = screenSize)
    val time =
        remember(ambientTick, timeFormat) {
            formatNavigateClockTime(ambientTick, timeFormat)
        }
    val offset =
        remember(ambientTick, burnInProtectionRequired, burnInShiftRange) {
            resolveAmbientBurnInOffset(
                ambientTickMs = ambientTick,
                shiftRange = burnInShiftRange,
                required = burnInProtectionRequired,
            )
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AmbientTimeText(
            time = time,
            fontSize = timeFontSize,
            offset = offset,
            lowBitAmbient = deviceHasLowBitAmbient,
        )
    }
}

@Composable
private fun AmbientTimeText(
    time: String,
    fontSize: TextUnit,
    offset: Offset,
    lowBitAmbient: Boolean,
) {
    if (lowBitAmbient) {
        LowBitAmbientTimeText(
            time = time,
            fontSize = fontSize,
            offset = offset,
        )
    } else {
        Text(
            text = time,
            fontSize = fontSize,
            fontWeight = FontWeight.Light,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.offset(x = offset.x.dp, y = offset.y.dp),
        )
    }
}

@Composable
private fun LowBitAmbientTimeText(
    time: String,
    fontSize: TextUnit,
    offset: Offset,
) {
    val textPaint =
        remember {
            Paint().apply {
                isAntiAlias = false
                color = android.graphics.Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }
        }
    Canvas(modifier = Modifier.fillMaxSize()) {
        textPaint.textSize = fontSize.toPx()
        val baseline = size.height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        drawContext.canvas.nativeCanvas.drawText(
            time,
            size.width / 2f + offset.x.dp.toPx(),
            baseline + offset.y.dp.toPx(),
            textPaint,
        )
    }
}

private fun ambientTimeFontSize(
    isRound: Boolean,
    screenSize: WearScreenSize,
): TextUnit =
    when (screenSize) {
        WearScreenSize.LARGE -> if (isRound) 42.sp else 44.sp
        WearScreenSize.MEDIUM -> if (isRound) 38.sp else 40.sp
        WearScreenSize.SMALL -> if (isRound) 34.sp else 36.sp
    }

private fun ambientBurnInShiftRange(
    isRound: Boolean,
    screenSize: WearScreenSize,
): Int =
    when (screenSize) {
        WearScreenSize.LARGE -> if (isRound) 5 else 4
        WearScreenSize.MEDIUM -> if (isRound) 4 else 3
        WearScreenSize.SMALL -> if (isRound) 3 else 2
    }

internal fun resolveAmbientBurnInOffset(
    ambientTickMs: Long,
    shiftRange: Int,
    required: Boolean,
): Offset {
    if (!required || shiftRange <= 0) return Offset.Zero
    val span = shiftRange * 2 + 1
    val minuteIndex = ambientTickMs.coerceAtLeast(0L) / 60_000L
    return Offset(
        x = (minuteIndex % span).toFloat() - shiftRange,
        y = ((minuteIndex / span) % span).toFloat() - shiftRange,
    )
}
