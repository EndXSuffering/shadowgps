package dev.shadowgps.core.osm

import dev.shadowgps.core.geo.LatLon
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The subset of the Overpass API JSON response this app cares about.
 *
 * Overpass returns a flat `elements` array mixing nodes, ways and relations; which fields
 * are populated depends on the `out` statement used by the query.
 */
@Serializable
data class OverpassResponse(
    val elements: List<OsmElement> = emptyList(),
)

@Serializable
data class OsmElement(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Present for ways fetched with `out center`. */
    val center: OsmCenter? = null,
    /** Node ids making up a way, in order. Present with `out body`. */
    val nodes: List<Long> = emptyList(),
    /** Inline coordinates. Present with `out geom`. */
    val geometry: List<OsmCoordinate?> = emptyList(),
    val members: List<OsmMember> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
) {
    val isNode: Boolean get() = type == "node"
    val isWay: Boolean get() = type == "way"
    val isRelation: Boolean get() = type == "relation"

    /** Stable identity across Overpass queries, e.g. `node/1234`. */
    val ref: String get() = "$type/$id"

    /** Best available single position: own coordinates, else the `out center` centroid. */
    fun position(): LatLon? {
        if (lat != null && lon != null) return LatLon(lat, lon)
        val c = center ?: return null
        return LatLon(c.lat, c.lon)
    }

    fun tag(vararg keys: String): String? {
        for (key in keys) tags[key]?.let { return it }
        return null
    }
}

@Serializable
data class OsmCenter(val lat: Double, val lon: Double)

@Serializable
data class OsmCoordinate(val lat: Double, val lon: Double)

@Serializable
data class OsmMember(
    val type: String,
    @SerialName("ref") val reference: Long,
    val role: String = "",
)

/** Lenient JSON reader: Overpass adds metadata fields that are not modelled here. */
val OsmJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

fun parseOverpassResponse(body: String): OverpassResponse = OsmJson.decodeFromString(body)
