package com.glancemap.glancemapwearos.presentation.ui

import android.text.Annotation
import android.text.Spanned
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.TextUnit

@Composable
internal fun superscriptResourceText(
    @StringRes id: Int,
    superscriptSize: TextUnit,
): AnnotatedString {
    val text = stringResource(id)
    val styledText = LocalResources.current.getText(id)
    return buildAnnotatedString {
        append(text)
        if (styledText is Spanned) {
            styledText
                .getSpans(0, styledText.length, Annotation::class.java)
                .filter { it.key == "superscript" }
                .forEach { annotation ->
                    addStyle(
                        SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = superscriptSize),
                        styledText.getSpanStart(annotation),
                        styledText.getSpanEnd(annotation),
                    )
                }
        }
    }
}
