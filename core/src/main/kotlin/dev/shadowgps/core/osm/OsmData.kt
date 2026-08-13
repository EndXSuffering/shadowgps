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
    /**
     * How Overpass reports a query it could not finish.
     *
     * A timed-out or overloaded server still answers 200 with a well-formed body; the only
     * sign of trouble is this field alongside an empty or partial `elements`. Taking that
     * at face value caches an empty map for the area, which then surfaces to the driver as
     * "no road near you" — a wrong and unactionable diagnosis.
     */
    val remark: String? = null,
) {
    /** The remark, when it describes a failure rather than an informational note. */
    val errorRemark: String?
        get() = remark?.takeIf { text ->
            OVERPASS_FAILURE_HINTS.any { text.contains(it, ignoreCase = true) }
        }
}

/**
 * Wording Overpass uses when a query did not complete.
 *
 * Kept at file scope rather than in a companion: `@Serializable` generates its own
 * `Companion` holding `serializer()`, and declaring a private one of our own makes that
 * generated member inaccessible to the rest of the file.
 */
private val OVERPASS_FAILURE_HINTS = listOf("error", "timed out", "timeout", "out of memory")

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
