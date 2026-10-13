package com.glancemap.glancemapcompanionapp.map

import androidx.compose.runtime.saveable.mapSaver
import java.text.Normalizer
import java.util.Locale

internal data class PhoneMapSearchResult(
    val id: String,
    val label: String,
    val detail: String,
    val point: PhoneMapCoordinate,
)

internal data class PhoneMapSearchActions(
    val onQueryChanged: (String) -> Unit,
    val onSearch: () -> Unit,
    val onResultSelected: (PhoneMapSearchResult) -> Unit,
    val onDismiss: () -> Unit,
)

internal val phoneMapSearchResultSaver =
    mapSaver<PhoneMapSearchResult?>(
        save = { result ->
            result
                ?.let {
                    mapOf(
                        "id" to it.id,
                        "label" to it.label,
                        "detail" to it.detail,
                        "lat" to it.point.latitude,
                        "lon" to it.point.longitude,
                    )
                }.orEmpty()
        },
        restore = { values ->
            if (values.isEmpty()) {
                null
            } else {
                runCatching {
                    PhoneMapSearchResult(
                        values["id"] as String,
                        values["label"] as String,
                        values["detail"] as String,
                        PhoneMapCoordinate(values["lat"] as Double, values["lon"] as Double),
                    ).takeIf { it.point.isValidSearchPoint() }
                }.getOrNull()
            }
        },
    )

internal data class PhoneMapSearchRequest(
    val query: String,
    val online: Boolean,
    val bias: PhoneMapCoordinate,
    val poiFiles: Set<String>,
)

internal data class PhoneMapSearchReply(
    val results: List<PhoneMapSearchResult> = emptyList(),
    val onlineFailed: Boolean = false,
    val localFailed: Boolean = false,
)

internal fun normalizePhoneMapSearch(value: String): String =
    Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(SEARCH_DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .trim()

/** Only literal word prefixes reach FTS; user input cannot become query operators. */
internal fun phoneMapPoiSearchExpression(query: String): String =
    normalizePhoneMapSearch(query)
        .split(SEARCH_WORD_SEPARATORS)
        .filter(String::isNotBlank)
        .take(12)
        // FTS4 puts prefix stars inside quotes; implicit AND works with both SQLite query dialects.
        .joinToString(" ") { "\"$it*\"" }

internal fun PhoneMapCoordinate.isValidSearchPoint(): Boolean {
    val validLatitude = latitude.isFinite() && latitude in -90.0..90.0
    val validLongitude = longitude.isFinite() && longitude in -180.0..180.0
    return validLatitude && validLongitude
}

internal const val PHONE_MAP_SEARCH_LIMIT = 20
internal const val PHONE_MAP_SEARCH_QUERY_LIMIT = 120

private val SEARCH_DIACRITICS = Regex("\\p{M}+")
private val SEARCH_WORD_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
