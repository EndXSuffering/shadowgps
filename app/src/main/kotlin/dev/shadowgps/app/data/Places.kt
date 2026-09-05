package dev.shadowgps.app.data

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.search.AddressLabel
import dev.shadowgps.core.search.AddressParts
import dev.shadowgps.core.search.AddressQuery
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
    /** The full address exactly as the geocoder gave it. */
    val detail: String? = null,
    /**
     * The address under the name: street, town, region, postcode.
     *
     * A tidied version of [detail], which from Nominatim runs to county, country and
     * sometimes a continent — accurate, and far too long to read at a glance.
     */
    val locality: String? = null,
    /**
     * A suite, unit or floor taken from what the user typed.
     *
     * Never comes back from the geocoder: OpenStreetMap maps the building, not the
     * tenancies inside it. It is carried along anyway because it is precisely what the
     * driver needs at the far end of the journey.
     */
    val unit: String? = null,
    /** What sort of place this is, when known — "restaurant", "pharmacy". */
    val category: String? = null,
) {
    /**
     * Short label for a chip or a text field.
     *
     * Cutting at the first comma is right for "Acme Dental, 500 Elm Street" and badly wrong
     * for "500, Elm Street", which is how Nominatim writes a plain address — that reduces
     * the whole destination to a bare house number, which names nothing at all.
     */
    val shortName: String
        get() {
            val head = name.substringBefore(",").trim()
            val label = if (head.isEmpty() || head.none(Char::isLetter)) name.trim() else head
            return if (unit != null) "$label, $unit" else label
        }

    /**
     * The line under the name: where it is.
     *
     * The unit is deliberately not repeated here — [shortName] already carries it, and
     * saying "Suite 200" twice in a two-line result is noise.
     */
    val addressLine: String? get() = (locality ?: detail)?.takeIf { it.isNotBlank() }
}

@Serializable
private data class NominatimAddress(
    @SerialName("house_number") val houseNumber: String? = null,
    val road: String? = null,
    val pedestrian: String? = null,
    val suburb: String? = null,
    val neighbourhood: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val hamlet: String? = null,
    val municipality: String? = null,
    val state: String? = null,
    val postcode: String? = null,
) {
    /** The most specific human-scale place name on offer, largest units last. */
    val settlement: String?
        get() = city ?: town ?: village ?: hamlet ?: municipality ?: suburb ?: neighbourhood
}

@Serializable
private data class NominatimResult(
    @SerialName("display_name") val displayName: String = "",
    val lat: String = "",
    val lon: String = "",
    val name: String? = null,
    val type: String? = null,
    @SerialName("class") val category: String? = null,
    val address: NominatimAddress? = null,
) {
    fun toPlace(unit: String?): Place? {
        val latitude = lat.toDoubleOrNull() ?: return null
        val longitude = lon.toDoubleOrNull() ?: return null

        val parts = AddressParts(
            name = name,
            houseNumber = address?.houseNumber,
            road = address?.road ?: address?.pedestrian,
            settlement = address?.settlement,
            state = address?.state,
            postcode = address?.postcode,
            displayName = displayName,
        )
        val heading = AddressLabel.title(parts)

        return Place(
            name = heading,
            position = LatLon(latitude, longitude),
            detail = displayName.takeIf { it.isNotBlank() },
            locality = AddressLabel.locality(parts, heading),
            unit = unit,
            category = readableCategory(),
        )
    }

    /** "fast_food" is data; "Fast food" is something a driver can read at a glance. */
    private fun readableCategory(): String? {
        if (category == "boundary" || category == "place") return null
        val raw = type?.takeIf { it.isNotBlank() && it != "yes" && it != "house" } ?: return null
        return raw.replace('_', ' ').replaceFirstChar(Char::uppercaseChar)
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
    suspend fun search(query: String, near: LatLon? = null, limit: Int = 10): List<Place> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val split = AddressQuery.split(query)

            val url = "$endpoint/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", split.searchable)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", limit.toString())
                // Without this the response carries no structured address at all, which is
                // what forced the old code to guess at display_name with substringBefore.
                .addQueryParameter("addressdetails", "1")
                .addQueryParameter("dedupe", "1")
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
            val places = runCatching { json.decodeFromString<List<NominatimResult>>(body) }
                .getOrDefault(emptyList())
                .mapNotNull { it.toPlace(split.unit) }

            rankForDriver(places, near)
        }

    /**
     * Puts anything nearby first, in distance order, and leaves the rest as Nominatim ranked
     * them.
     *
     * Nominatim orders by how notable a place is, which is the wrong question for someone
     * about to drive there: searching a street name from home should offer the one down the
     * road before a more famous one three counties away. Only local results are reordered,
     * so a deliberate search for somewhere distant still comes back in sensible order.
     */
    private fun rankForDriver(places: List<Place>, near: LatLon?): List<Place> {
        if (near == null || places.size < 2) return places
        val (local, elsewhere) = places.partition {
            haversineMeters(near, it.position) <= LOCAL_RESULT_METERS
        }
        return local.sortedBy { haversineMeters(near, it.position) } + elsewhere
    }

    /** Turns a map tap into something with a name. */
    suspend fun reverse(position: LatLon): Place? = withContext(Dispatchers.IO) {
        val url = "$endpoint/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", position.lat.toString())
            .addQueryParameter("lon", position.lon.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("zoom", "18")
            .build()
            .toString()

        val body = fetchCached(url, REVERSE_CACHE_MILLIS) ?: return@withContext null
        runCatching { json.decodeFromString<NominatimResult>(body) }.getOrNull()?.toPlace(null)
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

        /** Within this of the driver, a result is "near me" and sorted by distance. */
        const val LOCAL_RESULT_METERS = 60_000.0
    }
}
