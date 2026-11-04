package io.github.jqssun.gpssetter.ui

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Key-free raster map styles (no account, no token). */
object MapStyles {

    const val DEFAULT_SAT_MAX_ZOOM = 17

    private const val OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    private const val ESRI_IMAGERY = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    private const val ESRI_LABELS = "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"
    private const val TOPO = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png"

    private fun source(id: String, url: String, maxZoom: Int, attribution: String) =
        "\"$id\":{\"type\":\"raster\",\"tiles\":[\"$url\"],\"tileSize\":256,\"maxzoom\":$maxZoom," +
            "\"attribution\":\"$attribution\"}"

    private fun layer(id: String) = "{\"id\":\"$id\",\"type\":\"raster\",\"source\":\"$id\"}"

    /** mapType: 1 Normal, 2 Satellite, 3 Terrain, 4 Hybrid. */
    fun styleJson(mapType: Int, satMaxZoom: Int): String {
        val sources: String
        val layers: String
        when (mapType) {
            2 -> {
                sources = source("imagery", ESRI_IMAGERY, satMaxZoom, "Tiles © Esri")
                layers = layer("imagery")
            }
            3 -> {
                sources = source("topo", TOPO, 17, "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors")
                layers = layer("topo")
            }
            4 -> {
                sources = source("imagery", ESRI_IMAGERY, satMaxZoom, "Tiles © Esri") + "," +
                    source("labels", ESRI_LABELS, satMaxZoom.coerceAtMost(17), "Labels © Esri")
                layers = layer("imagery") + "," + layer("labels")
            }
            else -> {
                sources = source("osm", OSM, 19, "© OpenStreetMap contributors")
                layers = layer("osm")
            }
        }
        return "{\"version\":8,\"sources\":{$sources},\"layers\":[$layers]}"
    }

    fun isEsri(mapType: Int) = mapType == 2 || mapType == 4
}

/**
 * Esri imagery has different maximum detail in different places; past it Esri sends a grey
 * "Map data not yet available" tile. We find the highest zoom whose tile is real imagery
 * (its MD5 differs from the placeholder tile of the same level, learned from a deep-ocean tile).
 */
object EsriProbe {

    private const val URL_TPL = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d"
    private val placeholder = ConcurrentHashMap<Int, String>()
    private val cache = ConcurrentHashMap<String, Int>()

    private fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val latRad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    fun cacheKey(lat: Double, lon: Double) = "${tileX(lon, 13)},${tileY(lat, 13)}"

    private fun md5(z: Int, x: Int, y: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(String.format(URL_TPL, z, y, x)).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "KHPAL-GPS")
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun placeholderMd5(z: Int): String? {
        placeholder[z]?.let { return it }
        // deep Pacific ocean, lat 0 / lon -140
        val v = md5(z, tileX(-140.0, z), tileY(0.0, z)) ?: return null
        placeholder[z] = v
        return v
    }

    /** Highest zoom (14..19) with real imagery under the given point; default 17 if unknown. */
    fun realMaxZoom(lat: Double, lon: Double): Int {
        val key = cacheKey(lat, lon)
        cache[key]?.let { return it }
        var anyAnswer = false
        for (z in 19 downTo 14) {
            val tile = md5(z, tileX(lon, z), tileY(lat, z)) ?: continue
            val ph = placeholderMd5(z) ?: continue
            anyAnswer = true
            if (tile != ph) {
                cache[key] = z
                return z
            }
        }
        if (!anyAnswer) return MapStyles.DEFAULT_SAT_MAX_ZOOM
        cache[key] = 14
        return 14
    }
}
