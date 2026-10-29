package com.glancemap.glancemapwearos.presentation.features.gpx

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AssistantDirection
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Route
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog
import com.glancemap.glancemapwearos.presentation.ui.WearInfoText
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale

@Composable
fun GpxHelpBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val switchText = stringResource(R.string.gpx_help_switch_tracks, "__route__", "__hike__", "__bike__")
    val guidanceText = stringResource(R.string.gpx_help_start_guidance, "__guidance__")
    val sendText = stringResource(R.string.gpx_help_send_to_phone, "__sendToPhone__")
    val editText = stringResource(R.string.gpx_help_edit_tracks, "__edit__", "__delete__")
    val alternatives =
        mapOf(
            "route" to stringResource(R.string.gpx_help_route_icon),
            "hike" to stringResource(R.string.gpx_help_hike_icon),
            "bike" to stringResource(R.string.gpx_help_bike_icon),
            "guidance" to stringResource(R.string.gpx_help_guidance_icon),
            "sendToPhone" to stringResource(R.string.gpx_help_send_icon),
            "edit" to stringResource(R.string.gpx_help_edit_icon),
            "delete" to stringResource(R.string.gpx_help_delete_icon),
        )

    WearInfoDialog(
        visible = visible,
        title = stringResource(R.string.gpx_help_title),
        onDismiss = onDismiss,
    ) {
        item {
            WearInfoText(stringResource(R.string.gpx_help_toggle_tracks))
        }
        item {
            GpxHelpInlineText(
                iconContent =
                    mapOf(
                        "route" to Icons.Default.Route,
                        "hike" to Icons.AutoMirrored.Filled.DirectionsRun,
                        "bike" to Icons.AutoMirrored.Filled.DirectionsBike,
                    ),
            ) {
                append(gpxHelpAnnotatedText(switchText, alternatives))
            }
        }
        item {
            GpxHelpInlineText(iconContent = mapOf("guidance" to Icons.AutoMirrored.Filled.AssistantDirection)) {
                append(gpxHelpAnnotatedText(guidanceText, alternatives))
            }
        }
        item {
            WearInfoText(stringResource(R.string.gpx_help_elevation))
        }
        item {
            cappedFontScale(maxFontScale = 1.08f) {
                Text(
                    text =
                        buildAnnotatedString {
                            append(gpxHelpAnnotatedText(sendText, alternatives))
                        },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    inlineContent =
                        mapOf(
                            "sendToPhone" to
                                InlineTextContent(
                                    placeholder =
                                        Placeholder(
                                            width = 16.sp,
                                            height = 16.sp,
                                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                                        ),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_mobile_arrow_right),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                        ),
                )
            }
        }
        item {
            GpxHelpInlineText(
                iconContent =
                    mapOf(
                        "edit" to Icons.Default.Edit,
                        "delete" to Icons.Default.Delete,
                    ),
            ) {
                append(gpxHelpAnnotatedText(editText, alternatives))
            }
        }
    }
}

@Composable
private fun GpxHelpInlineText(
    iconContent: Map<String, ImageVector>,
    textBuilder: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit,
) {
    cappedFontScale(maxFontScale = 1.08f) {
        Text(
            text = buildAnnotatedString(textBuilder),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            inlineContent =
                iconContent.mapValues { (_, imageVector) ->
                    InlineTextContent(
                        placeholder =
                            Placeholder(
                                width = 16.sp,
                                height = 16.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                    ) {
                        Icon(
                            imageVector = imageVector,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
        )
    }
}

internal fun gpxHelpAnnotatedText(
    text: String,
    alternatives: Map<String, String>,
): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        val occurrences =
            alternatives.keys
                .flatMap { id ->
                    val token = "__${id}__"
                    generateSequence(text.indexOf(token).takeIf { it >= 0 }) { start ->
                        text.indexOf(token, start + token.length).takeIf { it >= 0 }
                    }.map { it to id }.toList()
                }.sortedBy { it.first }
        var start = 0
        occurrences.forEach { (position, id) ->
            append(text.substring(start, position))
            appendInlineContent(id, alternatives.getValue(id))
            start = position + id.length + 4
        }
        append(text.substring(start))
    }
