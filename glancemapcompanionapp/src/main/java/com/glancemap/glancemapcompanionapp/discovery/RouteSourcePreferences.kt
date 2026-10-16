package com.glancemap.glancemapcompanionapp.discovery

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class RouteSourceSettings(
    val enabled: Set<TrailProvider> = setOf(TrailProvider.OPEN_STREET_MAP),
    val overpassUrl: String = "https://overpass-api.de/api/interpreter",
    val geotrekRegistry: String = "",
)

/** Registry entries are explicitly supplied by the operator; no server represents Geotrek worldwide. */
internal data class GeotrekInstance(
    val name: String,
    val baseUrl: String,
    val license: String,
    val attribution: String,
    val hikingPracticeId: Int,
)

internal fun parseGeotrekRegistry(text: String): List<GeotrekInstance> =
    text
        .lineSequence()
        .filter(String::isNotBlank)
        .map { line ->
            val fields = line.split('|').map(String::trim)
            require(fields.size == 5 && fields.all(String::isNotBlank))
            val practice = fields[4].toInt()
            require(practice > 0)
            GeotrekInstance(fields[0], validatedDiscoveryUrl(fields[1]), fields[2], fields[3], practice)
        }.take(9)
        .toList()
        .also { require(it.size <= 8) }

internal fun validatedDiscoveryUrl(value: String): String {
    val url = value.trim().toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
    require(url.query == null && url.fragment == null)
    return url.toString()
}

internal class RouteSourcePreferences(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("phone_route_sources", Context.MODE_PRIVATE),
    )

    fun load(): RouteSourceSettings =
        RouteSourceSettings(
            enabled =
                TrailProvider.entries
                    .filter {
                        preferences.getBoolean(
                            it.name,
                            it == TrailProvider.OPEN_STREET_MAP,
                        )
                    }.toSet(),
            overpassUrl = preferences.getString("overpass_url", null) ?: RouteSourceSettings().overpassUrl,
            geotrekRegistry = preferences.getString("geotrek_registry", "").orEmpty(),
        )

    fun save(settings: RouteSourceSettings): RouteSourceSettings {
        val normalized = settings.copy(overpassUrl = validatedDiscoveryUrl(settings.overpassUrl))
        parseGeotrekRegistry(normalized.geotrekRegistry)
        preferences.edit {
            TrailProvider.entries.forEach { putBoolean(it.name, it in normalized.enabled) }
            putString("overpass_url", normalized.overpassUrl)
            putString("geotrek_registry", normalized.geotrekRegistry)
        }
        return normalized
    }
}
