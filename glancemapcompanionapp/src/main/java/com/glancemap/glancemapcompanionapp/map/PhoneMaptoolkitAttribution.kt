package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glancemap.glancemapcompanionapp.R

internal fun maptoolkitAttributionRequired(
    base: PhoneOnlineMapSource?,
    comparison: PhoneOnlineMapSource?,
): Boolean =
    listOf(base, comparison).any {
        it == PhoneOnlineMapSource.MAPTOOLKIT_HIKING || it == PhoneOnlineMapSource.MAPTOOLKIT_CYCLING
    }

/** Kept outside the map's controls so the provider's required credits cannot be covered by them. */
@Composable
internal fun phoneMaptoolkitAttribution() {
    val uriHandler = LocalUriHandler.current
    val linkStyles =
        TextLinkStyles(
            style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
        )
    Surface(modifier = Modifier.testTag("maptoolkit-attribution")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .clickable(role = Role.Button) { uriHandler.openUri("https://www.maptoolkit.org/") }
                        .padding(vertical = 12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.maptoolkit_attribution),
                    contentDescription = "Maptoolkit",
                    modifier = Modifier.height(24.dp).aspectRatio(227f / 72f),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text =
                    buildAnnotatedString {
                        withLink(LinkAnnotation.Url("https://www.maptoolkit.com/copyright/", linkStyles)) {
                            append("© Maptoolkit")
                        }
                        append(" ")
                        withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright", linkStyles)) {
                            append("© Openstreetmap")
                        }
                    },
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                textAlign = TextAlign.End,
            )
        }
    }
}
