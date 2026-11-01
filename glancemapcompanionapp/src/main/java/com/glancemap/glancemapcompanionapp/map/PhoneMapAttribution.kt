package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun phoneMapAttribution(
    attributions: List<String>,
    modifier: Modifier = Modifier,
) {
    if (attributions.isEmpty()) return
    val links =
        TextLinkStyles(
            style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
        )
    Surface(modifier = modifier.testTag("map-provider-attribution"), color = MaterialTheme.colorScheme.surface) {
        Text(
            text = AnnotatedString.fromHtml(attributions.distinct().joinToString(" · "), linkStyles = links),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
        )
    }
}
