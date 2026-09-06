package dev.shadowgps.app.data

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.search.AddressLabel
import dev.shadowgps.core.search.AddressParts
import dev.shadowgps.core.search.AddressQuery
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * A cached GET, shared by every geocoder.
 *
 * All of them are read-only public endpoints with usage policies rather than keys, so the
 * cache is doing double duty: it keeps the app responsive and it keeps the app from being a
 * nuisance to services that are given away for free.
 */
internal class HttpCache(
    private val http: OkHttpClient,
    private val cache: DiskCache,
) {
    fun get(url: String, maxAgeMillis: Long): String? {
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
}

// --------------------------------------------------------------------- Census

@Serializable
private data class CensusEnvelope(val result: CensusResult = CensusResult())

@Serializable
private data class CensusResult(
    @SerialName("addressMatches") val matches: List<CensusMatch> = emptyList(),
)

@Serializable
private data class CensusMatch(
    @SerialName("matchedAddress") val matchedAddress: String = "",
    val coordinates: CensusPoint = CensusPoint(),
)

/** Note the axis order: the Census returns x for longitude and y for latitude. */
@Serializable
private data class CensusPoint(val x: Double? = null, val y: Double? = null)

/**
 * The United States Census Bureau's address geocoder.
 *
 * Free, no key, no account, and built on TIGER — the Census's own road and address-range
 * data, which covers very nearly every street address in the country including the
 * route-numbered ones OpenStreetMap has never had a house number for. That is the gap it is
 * here to fill: OSM knows where TX-151 is and nothing about number 8227 on it, and no amount
 * of asking an OSM-backed geocoder more politely will change that.
 *
 * Two things it cannot do, which is why it is a first resort and never the only one. It has
 * no idea what a business is called — addresses only. And its positions are interpolated
 * along a block from the address range at each end, so they land on the right stretch of the
 * right street rather than on the building itself, which is why results are flagged
 * approximate.
 */
internal class CensusGeocoder(private val http: HttpCache) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Asks both ways before giving up.
     *
     * The structured form is the better question — it does not have to guess where the street
     * ends — but it is also the stricter one, and a street written the way people write it
     * rather than the way the file spells it ("TX-151" against "State Highway 151") can miss
     * on the fields and still hit through the one-line parser, which does its own
     * normalising. The second call only happens when the first found nothing.
     */
    fun search(query: String, limit: Int): List<Place> {
        val structured = AddressQuery.structure(query)?.let { fields ->
            lookUp(limit) {
                addPathSegment("address")
                addQueryParameter("street", fields.street)
                fields.city?.let { addQueryParameter("city", it) }
                fields.state?.let { addQueryParameter("state", it) }
                fields.postalCode?.let { addQueryParameter("zip", it) }
            }
        }.orEmpty()
        if (structured.isNotEmpty()) return structured

        return lookUp(limit) {
            addPathSegment("onelineaddress")
            addQueryParameter("address", query)
        }
    }

    private fun lookUp(limit: Int, fill: okhttp3.HttpUrl.Builder.() -> Unit): List<Place> {
        val url = "$ENDPOINT/geocoder/locations/".toHttpUrl().newBuilder()
            .apply(fill)
            .addQueryParameter("benchmark", BENCHMARK)
            .addQueryParameter("format", "json")
            .build()
            .toString()

        val body = http.get(url, CACHE_MILLIS) ?: return emptyList()
        return runCatching { json.decodeFromString<CensusEnvelope>(body) }
            .getOrNull()
            ?.result
            ?.matches
            ?.take(limit)
            ?.mapNotNull { it.toPlace() }
            .orEmpty()
    }

    private fun CensusMatch.toPlace(): Place? {
        val lat = coordinates.y ?: return null
        val lon = coordinates.x ?: return null

        // "8227 TX-151, SAN ANTONIO, TX, 78245"
        val chunks = matchedAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val street = chunks.firstOrNull()?.let(AddressLabel::readableCase) ?: return null

        val parts = AddressParts(
            houseNumber = street.substringBefore(' ').takeIf { it.any(Char::isDigit) },
            road = street.substringAfter(' ', "").takeIf { it.isNotBlank() },
            settlement = chunks.getOrNull(1)?.let(AddressLabel::readableCase),
            state = chunks.getOrNull(2),
            postcode = chunks.getOrNull(3),
        )

        return Place(
            name = street,
            position = LatLon(lat, lon),
            detail = AddressLabel.readableCase(matchedAddress),
            locality = AddressLabel.locality(parts, street),
            houseNumber = parts.houseNumber,
            postcode = parts.postcode,
            // Interpolated along the block rather than pinned to the building.
            approximate = true,
        )
    }

    private companion object {
        const val ENDPOINT = "https://geocoding.geo.census.gov"

        /** The current address range file, as opposed to a dated vintage. */
        const val BENCHMARK = "Public_AR_Current"

        /** Address ranges change slowly; a week is nothing. */
        const val CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

// --------------------------------------------------------------------- Photon

@Serializable
private data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
private data class PhotonFeature(
    val geometry: PhotonGeometry = PhotonGeometry(),
    val properties: PhotonProperties = PhotonProperties(),
)

@Serializable
private data class PhotonGeometry(val coordinates: List<Double> = emptyList())

@Serializable
private data class PhotonProperties(
    val name: String? = null,
    val housenumber: String? = null,
    val street: String? = null,
    val district: String? = null,
    val city: String? = null,
    val county: String? = null,
    val state: String? = null,
    val postcode: String? = null,
    @SerialName("osm_value") val osmValue: String? = null,
    @SerialName("osm_key") val osmKey: String? = null,
)

/**
 * Photon, an OpenStreetMap search engine.
 *
 * The same data as Nominatim behind a search index built for people typing rather than for
 * exact matching, which makes it markedly better at abbreviations, half-typed names and
 * plain typos — the ordinary business of finding somewhere. It will not conjure house
 * numbers OpenStreetMap does not have, so it does not replace the Census lookup; it replaces
 * the frustration of Nominatim refusing a query for want of a comma.
 */
internal class PhotonGeocoder(private val http: HttpCache) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun search(query: String, near: LatLon?, limit: Int, unit: String?): List<Place> {
        val url = "$ENDPOINT/api".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .apply {
                // Biases towards the driver without excluding anywhere else.
                if (near != null) {
                    addQueryParameter("lat", near.lat.toString())
                    addQueryParameter("lon", near.lon.toString())
                }
            }
            .build()
            .toString()

        val body = http.get(url, CACHE_MILLIS) ?: return emptyList()
        return runCatching { json.decodeFromString<PhotonResponse>(body) }
            .getOrNull()
            ?.features
            ?.mapNotNull { it.toPlace(unit) }
            .orEmpty()
    }

    private fun PhotonFeature.toPlace(unit: String?): Place? {
        // GeoJSON order: longitude first.
        val lon = geometry.coordinates.getOrNull(0) ?: return null
        val lat = geometry.coordinates.getOrNull(1) ?: return null

        val parts = AddressParts(
            name = properties.name,
            houseNumber = properties.housenumber,
            road = properties.street,
            settlement = properties.city ?: properties.county,
            district = properties.district,
            state = properties.state,
            postcode = properties.postcode,
            displayName = listOfNotNull(
                properties.name,
                properties.street,
                properties.city,
                properties.state,
            ).joinToString(", "),
        )
        val heading = AddressLabel.title(parts)

        return Place(
            name = heading,
            position = LatLon(lat, lon),
            detail = parts.displayName.takeIf { it.isNotBlank() },
            locality = AddressLabel.locality(parts, heading),
            unit = unit,
            category = readableCategory(),
            houseNumber = properties.housenumber,
            postcode = properties.postcode,
            isRoad = properties.osmKey == "highway",
        )
    }

    private fun PhotonFeature.readableCategory(): String? {
        if (properties.osmKey == "boundary" || properties.osmKey == "place") return null
        val raw = properties.osmValue?.takeIf { it.isNotBlank() && it != "yes" && it != "house" }
            ?: return null
        return raw.replace('_', ' ').replaceFirstChar(Char::uppercaseChar)
    }

    private companion object {
        const val ENDPOINT = "https://photon.komoot.io"
        const val CACHE_MILLIS = 24L * 60 * 60 * 1000
    }
}
