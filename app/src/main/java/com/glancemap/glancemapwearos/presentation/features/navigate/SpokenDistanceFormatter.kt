package com.glancemap.glancemapwearos.presentation.features.navigate

import android.icu.text.NumberFormat
import android.icu.text.PluralFormat
import java.util.Locale

internal fun formatSpokenDistance(
    value: Double,
    fractionDigits: Int,
    pattern: String,
    locale: Locale,
): String {
    val numberFormat =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }
    return PluralFormat(locale)
        .apply {
            applyPattern(pattern)
            setNumberFormat(numberFormat)
        }.format(value)
}
