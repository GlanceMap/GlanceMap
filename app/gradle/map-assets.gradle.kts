import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

val dem3BaseUrl = providers.gradleProperty("dem3BaseUrl")
    .orElse("https://download.mapsforge.org/maps/dem/dem3")
val dem3Tiles = providers.gradleProperty("dem3Tiles").orElse("")
val dem3Bbox = providers.gradleProperty("dem3Bbox").orElse("")
val dem3Overwrite = providers.gradleProperty("dem3Overwrite").orElse("false")
val dem3FailOnMissing = providers.gradleProperty("dem3FailOnMissing").orElse("true")
val dem3OutputDirPath = providers.gradleProperty("dem3OutputDir")
    .orElse(layout.buildDirectory.dir("generated/dem3").get().asFile.absolutePath)

private data class DemTile(val lat: Int, val lon: Int) {
    fun id(): String {
        val latPrefix = if (lat >= 0) "N" else "S"
        val lonPrefix = if (lon >= 0) "E" else "W"
        return String.format(Locale.US, "%s%02d%s%03d", latPrefix, abs(lat), lonPrefix, abs(lon))
    }
}

private fun parseDemTileId(raw: String): DemTile? {
    val match = Regex("^([NS])(\\d{2})([EW])(\\d{3})$").matchEntire(raw.trim().uppercase(Locale.ROOT))
        ?: return null
    val lat = match.groupValues[2].toInt().let { if (match.groupValues[1] == "N") it else -it }
    val lon = match.groupValues[4].toInt().let { if (match.groupValues[3] == "E") it else -it }
    return DemTile(lat, lon)
}

private fun tilesFromBboxOrThrow(rawBbox: String): Set<String> {
    val token = rawBbox.trim()
    if (token.isEmpty()) return emptySet()
    val parts = token.split(',').map(String::trim)
    if (parts.size != 4) throw GradleException("dem3Bbox must be 'minLat,minLon,maxLat,maxLon'")

    val minLat = parts[0].toDoubleOrNull() ?: throw GradleException("dem3Bbox: invalid minLat")
    val minLon = parts[1].toDoubleOrNull() ?: throw GradleException("dem3Bbox: invalid minLon")
    val maxLat = parts[2].toDoubleOrNull() ?: throw GradleException("dem3Bbox: invalid maxLat")
    val maxLon = parts[3].toDoubleOrNull() ?: throw GradleException("dem3Bbox: invalid maxLon")
    if (minLat >= maxLat || minLon >= maxLon) throw GradleException("dem3Bbox invalid: min must be lower than max")

    return buildSet {
        for (lat in floor(minLat).toInt()..floor(Math.nextDown(maxLat)).toInt()) {
            for (lon in floor(minLon).toInt()..floor(Math.nextDown(maxLon)).toInt()) {
                add(DemTile(lat, lon).id())
            }
        }
    }
}

tasks.register("downloadMapsforgeDem3") {
    group = "map data"
    description = "Download Mapsforge DEM3 (.hgt.zip) tiles from explicit tile ids and/or a bbox."

    doLast {
        val explicitTiles =
            dem3Tiles.get()
                .split(',', ';', '\n', '\r', '\t', ' ')
                .filter(String::isNotBlank)
                .map { parseDemTileId(it) ?: throw GradleException("Invalid DEM3 tile id '$it'. Expected N46E006.") }
                .map(DemTile::id)
        val tileIds = (explicitTiles + tilesFromBboxOrThrow(dem3Bbox.get())).toSortedSet()
        if (tileIds.isEmpty()) {
            throw GradleException("Set -Pdem3Tiles=N46E006,N46E007 or -Pdem3Bbox=minLat,minLon,maxLat,maxLon")
        }

        val outputRoot = dem3OutputDirPath.get().trim().takeIf(String::isNotEmpty)?.let(::File)
            ?: layout.buildDirectory.dir("generated/dem3").get().asFile
        val baseUrl = dem3BaseUrl.get().trim().trimEnd('/')
        val overwrite = dem3Overwrite.get().toBoolean()
        val missing = mutableListOf<String>()
        var downloaded = 0
        var skipped = 0

        tileIds.forEach { tileId ->
            val folder = tileId.take(3)
            val fileName = "$tileId.hgt.zip"
            val localFile = File(outputRoot, "$folder/$fileName")
            if (localFile.exists() && !overwrite) {
                skipped += 1
                return@forEach
            }

            val temporaryFile = File(localFile.parentFile, "$fileName.tmp")
            temporaryFile.parentFile.mkdirs()
            temporaryFile.delete()
            try {
                val url = "$baseUrl/$folder/$fileName"
                val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "GlanceMap-DEM3/1.0")
                }
                if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) throw FileNotFoundException("HTTP 404")
                if (connection.responseCode !in 200..299) throw GradleException("HTTP ${connection.responseCode}")
                connection.inputStream.use { input -> Files.newOutputStream(temporaryFile.toPath()).use(input::copyTo) }
                if (!temporaryFile.renameTo(localFile)) throw GradleException("Failed to move $fileName into ${localFile.parent}")
                downloaded += 1
            } catch (error: Exception) {
                temporaryFile.delete()
                missing += tileId
                logger.warn("Missing/failed DEM tile $tileId (${error::class.java.simpleName}: ${error.message})")
            }
        }

        logger.lifecycle("DEM download done: downloaded=$downloaded skipped=$skipped missing=${missing.size}")
        if (missing.isNotEmpty() && dem3FailOnMissing.get().toBoolean()) {
            throw GradleException("Some DEM tiles are missing: ${missing.joinToString()}")
        }
    }
}
