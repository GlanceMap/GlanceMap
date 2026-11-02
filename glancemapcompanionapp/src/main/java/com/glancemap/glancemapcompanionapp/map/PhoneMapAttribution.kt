package com.glancemap.glancemapcompanionapp.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/** One compact credit line for every visible provider, without a background panel. */
@Composable
internal fun phoneMapAttribution(
    attributions: List<String>,
    modifier: Modifier = Modifier,
) {
    if (attributions.isEmpty()) return
    val links = TextLinkStyles(style = SpanStyle(color = Color.Black, textDecoration = TextDecoration.Underline))
    Text(
        text = AnnotatedString.fromHtml(attributions.distinct().joinToString(" · "), linkStyles = links),
        modifier = modifier.testTag("map-provider-attribution"),
        color = Color.Black,
        // A faint light halo keeps black text legible over dark terrain without drawing a panel.
        style =
            MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                lineHeight = 11.sp,
                shadow = Shadow(color = Color.White, blurRadius = 4f),
            ),
    )
}
