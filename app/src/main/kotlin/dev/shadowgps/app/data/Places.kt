package dev.shadowgps.app.data

import dev.shadowgps.core.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Somewhere the user can route to. */
@Serializable
data class Place(
    val name: String,
    val position: LatLon,
    val detail: String? = null,
) {
    /** Short label for a chip or a text field. */
    val shortName: String get() = name.substringBefore(",").trim()
}

@Serializable
private data class NominatimResult(
    @SerialName("display_name") val displayName: String = "",
    val lat: String = "",
    val lon: String = "",
    val name: String? = null,
    val type: String? = null,
) {
    fun toPlace(): Place? {
        val latitude = lat.toDoubleOrNull() ?: return null
        val longitude = lon.toDoubleOrNull() ?: return null
        val label = name?.takeIf { it.isNotBlank() } ?: displayName.substringBefore(",")
        return Place(
            name = label.ifBlank { displayName },
            position = LatLon(latitude, longitude),
            detail = displayName.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Address lookup through Nominatim, OpenStreetMap's geocoder.
 *
 * Nominatim's usage policy caps this at roughly one request a second and requires an
 * identifying User-Agent, which is why search is debounced in the UI rather than fired on
 * every keystroke.
 */
class GeocodingClient(
    private val http: OkHttpClient,
    private val cache: DiskCache,
    private val endpoint: String = "https://nominatim.openstreetmap.org",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Free-text search, biased towards [near] when a location is known. */
    suspend fun search(query: String, near: LatLon? = null, limit: Int = 8): List<Place> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            val url = "$endpoint/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", limit.toString())
                .apply {
                    // A viewbox nudges results towards the driver without excluding others.
                    if (near != null) {
                        val box = dev.shadowgps.core.geo.BoundingBox.around(near, 40_000.0)
                        addQueryParameter("viewbox", "${box.west},${box.north},${box.east},${box.south}")
                    }
                }
                .build()
                .toString()

            val body = fetchCached(url, SEARCH_CACHE_MILLIS) ?: return@withContext emptyList()
            runCatching { json.decodeFromString<List<NominatimResult>>(body) }
                .getOrDefault(emptyList())
                .mapNotNull { it.toPlace() }
        }

    /** Turns a map tap into something with a name. */
    suspend fun reverse(position: LatLon): Place? = withContext(Dispatchers.IO) {
        val url = "$endpoint/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", position.lat.toString())
            .addQueryParameter("lon", position.lon.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("zoom", "18")
            .build()
            .toString()

        val body = fetchCached(url, REVERSE_CACHE_MILLIS) ?: return@withContext null
        runCatching { json.decodeFromString<NominatimResult>(body) }.getOrNull()?.toPlace()
            // Falling back to coordinates keeps "drop a pin here" working offline.
            ?: Place(name = "Dropped pin", position = position, detail = position.toString())
    }

    private fun fetchCached(url: String, maxAgeMillis: Long): String? {
        cache.read(url, maxAgeMillis)?.let { return it }
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", OverpassClient.USER_AGENT)
                .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (response.isSuccessful && !text.isNullOrBlank()) {
                    cache.write(url, text)
                    text
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private companion object {
        const val SEARCH_CACHE_MILLIS = 24L * 60 * 60 * 1000
        const val REVERSE_CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
