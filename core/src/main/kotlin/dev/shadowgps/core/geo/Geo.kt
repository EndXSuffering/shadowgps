package dev.shadowgps.core.geo

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius used by every distance calculation in the app. */
const val EARTH_RADIUS_METERS: Double = 6_371_008.8

private const val DEG_TO_RAD: Double = Math.PI / 180.0
private const val RAD_TO_DEG: Double = 180.0 / Math.PI

/** Metres covered by one degree of latitude. Constant enough anywhere on Earth. */
const val METERS_PER_DEGREE_LAT: Double = EARTH_RADIUS_METERS * DEG_TO_RAD

/** Metres covered by one degree of longitude at [lat]. */
fun metersPerDegreeLon(lat: Double): Double = METERS_PER_DEGREE_LAT * cos(lat * DEG_TO_RAD)

@Serializable
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "longitude out of range: $lon" }
    }

    override fun toString(): String = "%.6f,%.6f".format(lat, lon)
}

/** Great-circle distance in metres. */
fun haversineMeters(a: LatLon, b: LatLon): Double {
    val dLat = (b.lat - a.lat) * DEG_TO_RAD
    val dLon = (b.lon - a.lon) * DEG_TO_RAD
    val lat1 = a.lat * DEG_TO_RAD
    val lat2 = b.lat * DEG_TO_RAD
    val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
}

/** Initial bearing from [from] to [to], in degrees clockwise from north, in `[0, 360)`. */
fun bearingDegrees(from: LatLon, to: LatLon): Double {
    val lat1 = from.lat * DEG_TO_RAD
    val lat2 = to.lat * DEG_TO_RAD
    val dLon = (to.lon - from.lon) * DEG_TO_RAD
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return normalizeBearing(atan2(y, x) * RAD_TO_DEG)
}

/** Wraps any angle into `[0, 360)`. */
fun normalizeBearing(degrees: Double): Double {
    val m = degrees % 360.0
    return if (m < 0) m + 360.0 else m
}

/** Smallest absolute angle between two bearings, in `[0, 180]`. */
fun angularDifference(a: Double, b: Double): Double {
    val d = abs(normalizeBearing(a) - normalizeBearing(b))
    return if (d > 180.0) 360.0 - d else d
}

/** Turn from bearing [from] to bearing [to] in `(-180, 180]`; positive means a right turn. */
fun signedTurnDegrees(from: Double, to: Double): Double {
    val d = normalizeBearing(to - from + 180.0) - 180.0
    // normalizeBearing maps to [0,360), so the result lands in [-180, 180); flip the
    // degenerate -180 case so a straight reversal reads as a right u-turn.
    return if (d == -180.0) 180.0 else d
}

/** Point reached by travelling [meters] from [origin] along [bearing]. */
fun destinationPoint(origin: LatLon, bearing: Double, meters: Double): LatLon {
    val d = meters / EARTH_RADIUS_METERS
    val br = bearing * DEG_TO_RAD
    val lat1 = origin.lat * DEG_TO_RAD
    val lon1 = origin.lon * DEG_TO_RAD
    val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
    val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
    return LatLon(lat2 * RAD_TO_DEG, ((lon2 * RAD_TO_DEG + 540) % 360) - 180)
}

/** Total length of a polyline in metres. */
fun polylineLengthMeters(points: List<LatLon>): Double {
    var total = 0.0
    for (i in 1 until points.size) total += haversineMeters(points[i - 1], points[i])
    return total
}

/**
 * Result of dropping a perpendicular from a point onto a polyline.
 *
 * @property point the closest point that actually lies on the polyline
 * @property distanceMeters how far the queried point sits from [point]
 * @property segmentIndex index of the segment containing [point] (`points[i]` -> `points[i+1]`)
 * @property alongMeters distance from the start of the polyline to [point], measured along it
 * @property headingDegrees direction of travel of the containing segment
 */
data class PolylineProjection(
    val point: LatLon,
    val distanceMeters: Double,
    val segmentIndex: Int,
    val alongMeters: Double,
    val headingDegrees: Double,
)

/**
 * Projects [target] onto [polyline].
 *
 * Segments are treated as straight lines on a local equirectangular plane anchored at
 * [target]. Over the segment lengths this app deals with (tens to hundreds of metres) the
 * error is far below GPS noise, and it avoids the cost of a proper geodesic solution on a
 * path that runs once per edge per detector.
 */
fun projectOntoPolyline(polyline: List<LatLon>, target: LatLon): PolylineProjection =
    projectOntoCoords(listToCoords(polyline), target)

/** Point [meters] along [points], clamped to the polyline's ends. */
fun interpolateAlong(points: List<LatLon>, meters: Double): LatLon =
    interpolateAlongCoords(listToCoords(points), meters)

/** The part of [points] from [alongMeters] to the end, starting exactly at that point. */
fun sliceFrom(points: List<LatLon>, alongMeters: Double): List<LatLon> {
    if (points.size < 2) return points
    val coords = listToCoords(points)
    return coordsToList(sliceCoords(coords, alongMeters, coordsLengthMeters(coords)))
}

/** The part of [points] from the start up to [alongMeters], ending exactly at that point. */
fun sliceTo(points: List<LatLon>, alongMeters: Double): List<LatLon> {
    if (points.size < 2) return points
    return coordsToList(sliceCoords(listToCoords(points), 0.0, alongMeters))
}

/**
 * An axis-aligned latitude/longitude rectangle.
 *
 * Boxes that would cross the antimeridian are not supported; the app clamps to valid
 * ranges instead, which is acceptable because a single trip never spans it.
 */
@Serializable
data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    init {
        require(south <= north) { "south ($south) must not exceed north ($north)" }
        require(west <= east) { "west ($west) must not exceed east ($east)" }
    }

    val center: LatLon get() = LatLon((south + north) / 2, (west + east) / 2)

    /** Rough area in square kilometres, good enough for guarding oversized data requests. */
    val areaKm2: Double
        get() {
            val heightKm = (north - south) * METERS_PER_DEGREE_LAT / 1000.0
            val widthKm = (east - west) * metersPerDegreeLon(center.lat) / 1000.0
            return heightKm * widthKm
        }

    fun contains(p: LatLon): Boolean = p.lat in south..north && p.lon in west..east

    fun intersects(other: BoundingBox): Boolean =
        south <= other.north && north >= other.south && west <= other.east && east >= other.west

    fun contains(other: BoundingBox): Boolean =
        south <= other.south && north >= other.north && west <= other.west && east >= other.east

    fun union(other: BoundingBox): BoundingBox = BoundingBox(
        south = min(south, other.south),
        west = min(west, other.west),
        north = max(north, other.north),
        east = max(east, other.east),
    )

    fun expandMeters(meters: Double): BoundingBox {
        val dLat = meters / METERS_PER_DEGREE_LAT
        val dLon = meters / max(1.0, metersPerDegreeLon(center.lat))
        return BoundingBox(
            south = max(-90.0, south - dLat),
            west = max(-180.0, west - dLon),
            north = min(90.0, north + dLat),
            east = min(180.0, east + dLon),
        )
    }

    /** Overpass wants `south,west,north,east`. */
    fun toOverpassString(): String = "%.6f,%.6f,%.6f,%.6f".format(south, west, north, east)

    companion object {
        fun of(points: Iterable<LatLon>): BoundingBox {
            var s = Double.MAX_VALUE
            var w = Double.MAX_VALUE
            var n = -Double.MAX_VALUE
            var e = -Double.MAX_VALUE
            var any = false
            for (p in points) {
                any = true
                s = min(s, p.lat); n = max(n, p.lat)
                w = min(w, p.lon); e = max(e, p.lon)
            }
            require(any) { "cannot build a bounding box from no points" }
            return BoundingBox(s, w, n, e)
        }

        fun around(center: LatLon, radiusMeters: Double): BoundingBox =
            BoundingBox(center.lat, center.lon, center.lat, center.lon).expandMeters(radiusMeters)
    }
}
