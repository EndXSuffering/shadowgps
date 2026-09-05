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
    /**
     * The position is somewhere on the right block rather than on the building.
     *
     * True of address-range data, which interpolates between the numbers at each end of a
     * street. Worth saying out loud: it is the difference between arriving at the door and
     * arriving near it, and the driver should not have to discover that on the way.
     */
    val approximate: Boolean = false,
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
 * Finding somewhere to drive to.
 *
 * Three sources, each covering the others' blind spots, none of them needing an account or
 * a key — which matters, because a key means a billing identity and a billing identity means
 * every search a driver makes is attributable to a person. Every one of these is anonymous
 * in the same way the map tiles are.
 *
 *  - The **Census** knows every street address in the United States, including the
 *    route-numbered ones OpenStreetMap has no house number for, and nothing whatsoever about
 *    what any business is called.
 *  - **Photon** knows what OpenStreetMap knows, through a search index built for people
 *    typing rather than for exact matching, so it forgives abbreviations and typos.
 *  - **Nominatim** is the backstop, and the only one of the three that does reverse
 *    geocoding well, which is what turns a long-press on the map into an address.
 *
 * Nominatim's usage policy caps requests at roughly one a second and requires an identifying
 * User-Agent, which is why search is debounced in the UI rather than fired on every
 * keystroke.
 */
class GeocodingClient(
    private val http: OkHttpClient,
    private val cache: DiskCache,
    private val endpoint: String = "https://nominatim.openstreetmap.org",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val cachedHttp = HttpCache(http, cache)
    private val census = CensusGeocoder(cachedHttp)
    private val photon = PhotonGeocoder(cachedHttp)

    /**
     * Looks up an address or a place.
     *
     * The order is chosen by what the query looks like, because the sources are good at
     * different things and asking all three every time would be slow and rude.
     *
     * A query that names a building — a house number with a street after it — goes to the
     * Census first, since that is the one lookup that can answer it authoritatively. An
     * exact match ends the search there. Anything else, and anything the Census could not
     * place, goes to Photon, which is the better of the two OpenStreetMap front ends at
     * matching what a person actually typed. Nominatim answers when Photon finds nothing.
     *
     * Each step only runs when the one before it came up short, so an ordinary search still
     * costs a single request.
     */
    suspend fun search(query: String, near: LatLon? = null, limit: Int = 10): List<Place> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val split = AddressQuery.split(query)
            val searchable = split.searchable

            if (AddressQuery.namesABuilding(searchable)) {
                val exact = census.search(searchable, limit).map { it.copy(unit = split.unit) }
                if (exact.isNotEmpty()) return@withContext rankForDriver(exact, near, searchable)
            }

            val fromPhoton = photon.search(searchable, near, limit, split.unit)
            if (fromPhoton.isNotEmpty()) return@withContext rankForDriver(fromPhoton, near, searchable)

            val fromNominatim = fetchPlaces(freeTextUrl(searchable, near, limit), split.unit)
            rankForDriver(fromNominatim, near, searchable)
        }

    private fun fetchPlaces(url: String, unit: String?): List<Place> {
        val body = cachedHttp.get(url, SEARCH_CACHE_MILLIS) ?: return emptyList()
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

        val body = cachedHttp.get(url, REVERSE_CACHE_MILLIS) ?: return@withContext null
        runCatching { json.decodeFromString<NominatimResult>(body) }.getOrNull()?.toPlace(null)
            // Falling back to coordinates keeps "drop a pin here" working offline.
            ?: Place(name = "Dropped pin", position = position, detail = position.toString())
    }

    private companion object {
        const val SEARCH_CACHE_MILLIS = 24L * 60 * 60 * 1000
        const val REVERSE_CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** Within this of the driver, a result is "near me" and sorted by distance. */
        const val LOCAL_RESULT_METERS = 60_000.0


    }
}
