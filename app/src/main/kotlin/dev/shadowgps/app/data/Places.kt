package dev.shadowgps.app.data

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.search.AddressLabel
import dev.shadowgps.core.search.AddressParts
import dev.shadowgps.core.search.AddressQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /**
     * The house number the geocoder actually matched, when it matched one.
     *
     * Kept so a result can admit it is a road rather than a building. On a long highway a
     * failed house-number match comes back as several stretches of the road itself, and
     * without this the app presented them as though they were addresses.
     */
    val houseNumber: String? = null,
    /** Kept apart from the address line so a typed postcode can be matched against it. */
    val postcode: String? = null,
) {
    /** Whether this pins a building, rather than a road or an area. */
    val isExactAddress: Boolean get() = houseNumber != null

    /**
     * Whether this is in the postcode the driver typed.
     *
     * Compared on the leading five characters, so a ZIP+4 on either side still matches the
     * plain ZIP on the other.
     */
    fun postcodeMatches(wanted: String): Boolean {
        val mine = postcode?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val head = { text: String -> text.filter { it.isDigit() || it.isLetter() }.take(5).uppercase() }
        return head(mine) == head(wanted)
    }

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
    val quarter: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val hamlet: String? = null,
    val municipality: String? = null,
    val state: String? = null,
    val postcode: String? = null,
) {
    /** The town this sits in, largest units last. */
    val settlement: String?
        get() = city ?: town ?: village ?: hamlet ?: municipality

    /** The part of town, which is what tells two ends of a long road apart. */
    val district: String?
        get() = suburb ?: neighbourhood ?: quarter
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
            settlement = address?.settlement ?: address?.district,
            district = address?.district?.takeIf { address.settlement != null },
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
            houseNumber = address?.houseNumber ?: AddressLabel.street(parts)
                ?.takeIf { street -> street != address?.road }
                ?.substringBefore(' ')
                ?.takeIf { it.any(Char::isDigit) },
            postcode = address?.postcode,
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

    /**
     * Looks up an address or a place.
     *
     * Two passes, because free-text matching has a specific and common failure: on a US
     * address built round a route number — "8227 TX-151, San Antonio, TX 78245" — it decides
     * the whole line is the highway and returns stretches of road rather than the building.
     * Those come back with no house number, several of them, miles apart and reading
     * identically. When that happens and the query clearly named a building, the address is
     * broken into fields and asked again; a geocoder told exactly which part is the street
     * reaches house-number data that free-text matching walks straight past.
     *
     * The second request only ever happens on that failure, so an ordinary search still
     * costs exactly one.
     */
    suspend fun search(query: String, near: LatLon? = null, limit: Int = 10): List<Place> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val split = AddressQuery.split(query)

            val free = fetchPlaces(freeTextUrl(split.searchable, near, limit), split.unit)
            if (free.any { it.isExactAddress } || !AddressQuery.namesABuilding(split.searchable)) {
                return@withContext rankForDriver(free, near, split.searchable)
            }

            val fields = AddressQuery.structure(split.searchable)
                ?: return@withContext rankForDriver(free, near, split.searchable)

            // Nominatim asks for no more than a request a second, and this one follows hard
            // on the heels of the last.
            delay(SECOND_PASS_DELAY_MILLIS)
            val exact = fetchPlaces(structuredUrl(fields, limit), split.unit)

            // Only worth having if it did what the first pass could not.
            val best = if (exact.any { it.isExactAddress }) exact else free
            rankForDriver(best, near, split.searchable)
        }

    private fun fetchPlaces(url: String, unit: String?): List<Place> {
        val body = fetchCached(url, SEARCH_CACHE_MILLIS) ?: return emptyList()
        return runCatching { json.decodeFromString<List<NominatimResult>>(body) }
            .getOrDefault(emptyList())
            .mapNotNull { it.toPlace(unit) }
    }

    private fun freeTextUrl(query: String, near: LatLon?, limit: Int): String =
        searchUrl(limit) {
            addQueryParameter("q", query)
            // A viewbox nudges results towards the driver without excluding others.
            if (near != null) {
                val box = dev.shadowgps.core.geo.BoundingBox.around(near, 40_000.0)
                addQueryParameter("viewbox", "${box.west},${box.north},${box.east},${box.south}")
            }
        }

    private fun structuredUrl(fields: AddressQuery.Structured, limit: Int): String =
        searchUrl(limit) {
            addQueryParameter("street", fields.street)
            fields.city?.let { addQueryParameter("city", it) }
            fields.state?.let { addQueryParameter("state", it) }
            fields.postalCode?.let { addQueryParameter("postalcode", it) }
        }

    private fun searchUrl(limit: Int, fill: okhttp3.HttpUrl.Builder.() -> Unit): String =
        "$endpoint/search".toHttpUrl().newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", limit.toString())
            // Without this the response carries no structured address at all, which is what
            // forced the old code to guess at display_name with substringBefore.
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("dedupe", "1")
            .apply(fill)
            .build()
            .toString()

    /**
     * Orders results the way a driver would.
     *
     * Three things, in order. A result whose postcode is the one that was typed goes first —
     * that is the driver telling the app which of several similar answers they meant, and it
     * is the difference between two identical-looking stretches of the same road. Then a
     * building beats a road, since a road result means the exact address was not found. Then
     * whatever is nearest, because Nominatim orders by how notable a place is and the nearby
     * match is nearly always the one meant. Anything far away keeps the geocoder's own order,
     * so a deliberate search for somewhere distant is not shuffled.
     */
    private fun rankForDriver(places: List<Place>, near: LatLon?, query: String): List<Place> {
        if (places.size < 2) return places

        val wantedPostcode = AddressQuery.postcodeIn(query)
        val ranked = places.sortedWith(
            compareByDescending<Place> { wantedPostcode != null && it.postcodeMatches(wantedPostcode) }
                .thenByDescending { it.isExactAddress },
        )
        if (near == null) return ranked

        val (local, elsewhere) = ranked.partition {
            haversineMeters(near, it.position) <= LOCAL_RESULT_METERS
        }
        return local.sortedWith(
            compareByDescending<Place> { wantedPostcode != null && it.postcodeMatches(wantedPostcode) }
                .thenByDescending { it.isExactAddress }
                .thenBy { haversineMeters(near, it.position) },
        ) + elsewhere
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

        /** Nominatim's usage policy is one request a second; the second pass respects it. */
        const val SECOND_PASS_DELAY_MILLIS = 1_100L
    }
}
