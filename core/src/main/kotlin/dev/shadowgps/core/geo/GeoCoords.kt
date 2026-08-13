package dev.shadowgps.core.geo

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Polyline maths over flat `[lat0, lon0, lat1, lon1, …]` arrays.
 *
 * A city-sized road graph holds hundreds of thousands of directed edges. Keeping their
 * geometry as boxed [LatLon] lists costs tens of megabytes of object headers on a phone, so
 * edges store a packed [DoubleArray] instead and every hot path works directly on it. The
 * [List] equivalents in `Geo.kt` delegate here.
 */

fun coordsCount(coords: DoubleArray): Int = coords.size / 2

fun coordAt(coords: DoubleArray, index: Int): LatLon = LatLon(coords[index * 2], coords[index * 2 + 1])

fun listToCoords(points: List<LatLon>): DoubleArray {
    val out = DoubleArray(points.size * 2)
    for (i in points.indices) {
        out[i * 2] = points[i].lat
        out[i * 2 + 1] = points[i].lon
    }
    return out
}

fun coordsToList(coords: DoubleArray): List<LatLon> {
    val out = ArrayList<LatLon>(coordsCount(coords))
    for (i in 0 until coordsCount(coords)) out.add(coordAt(coords, i))
    return out
}

fun coordsLengthMeters(coords: DoubleArray): Double {
    var total = 0.0
    for (i in 1 until coordsCount(coords)) {
        total += haversineMeters(coordAt(coords, i - 1), coordAt(coords, i))
    }
    return total
}

fun coordsBounds(coords: DoubleArray): BoundingBox {
    require(coords.size >= 2) { "cannot bound an empty coordinate array" }
    var s = Double.MAX_VALUE
    var n = -Double.MAX_VALUE
    var w = Double.MAX_VALUE
    var e = -Double.MAX_VALUE
    for (i in 0 until coordsCount(coords)) {
        val lat = coords[i * 2]
        val lon = coords[i * 2 + 1]
        s = min(s, lat); n = max(n, lat)
        w = min(w, lon); e = max(e, lon)
    }
    return BoundingBox(s, w, n, e)
}

/** See [projectOntoPolyline]; this is the allocation-free implementation it delegates to. */
fun projectOntoCoords(coords: DoubleArray, target: LatLon): PolylineProjection {
    val count = coordsCount(coords)
    require(count > 0) { "cannot project onto an empty polyline" }
    if (count == 1) {
        val only = coordAt(coords, 0)
        return PolylineProjection(only, haversineMeters(only, target), 0, 0.0, 0.0)
    }

    val mPerLon = metersPerDegreeLon(target.lat)
    val tx = target.lon * mPerLon
    val ty = target.lat * METERS_PER_DEGREE_LAT

    var bestDistanceSq = Double.MAX_VALUE
    var bestSegment = 0
    var bestT = 0.0
    var bestAlong = 0.0
    var cumulative = 0.0

    for (i in 0 until count - 1) {
        val aLat = coords[i * 2]
        val aLon = coords[i * 2 + 1]
        val bLat = coords[i * 2 + 2]
        val bLon = coords[i * 2 + 3]
        val segmentLength = haversineMeters(LatLon(aLat, aLon), LatLon(bLat, bLon))

        val ax = aLon * mPerLon
        val ay = aLat * METERS_PER_DEGREE_LAT
        val dx = bLon * mPerLon - ax
        val dy = bLat * METERS_PER_DEGREE_LAT - ay
        val lengthSq = dx * dx + dy * dy

        val t = if (lengthSq <= 0.0) 0.0 else (((tx - ax) * dx + (ty - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        val distanceSq = (tx - px) * (tx - px) + (ty - py) * (ty - py)

        if (distanceSq < bestDistanceSq) {
            bestDistanceSq = distanceSq
            bestSegment = i
            bestT = t
            bestAlong = cumulative + t * segmentLength
        }
        cumulative += segmentLength
    }

    val a = coordAt(coords, bestSegment)
    val b = coordAt(coords, bestSegment + 1)
    return PolylineProjection(
        point = LatLon(a.lat + (b.lat - a.lat) * bestT, a.lon + (b.lon - a.lon) * bestT),
        distanceMeters = sqrt(bestDistanceSq),
        segmentIndex = bestSegment,
        alongMeters = bestAlong,
        headingDegrees = bearingDegrees(a, b),
    )
}

/** Point [meters] along the packed polyline, clamped to its ends. */
fun interpolateAlongCoords(coords: DoubleArray, meters: Double): LatLon {
    val count = coordsCount(coords)
    require(count > 0) { "empty polyline" }
    if (count == 1 || meters <= 0.0) return coordAt(coords, 0)
    var remaining = meters
    for (i in 0 until count - 1) {
        val a = coordAt(coords, i)
        val b = coordAt(coords, i + 1)
        val segment = haversineMeters(a, b)
        if (remaining <= segment) {
            val t = if (segment == 0.0) 0.0 else remaining / segment
            return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        }
        remaining -= segment
    }
    return coordAt(coords, count - 1)
}

/** Travel heading at [meters] along the packed polyline. */
fun headingAlongCoords(coords: DoubleArray, meters: Double): Double {
    val count = coordsCount(coords)
    if (count < 2) return 0.0
    var remaining = meters
    for (i in 0 until count - 1) {
        val a = coordAt(coords, i)
        val b = coordAt(coords, i + 1)
        val segment = haversineMeters(a, b)
        if (remaining <= segment || i == count - 2) return bearingDegrees(a, b)
        remaining -= segment
    }
    return bearingDegrees(coordAt(coords, count - 2), coordAt(coords, count - 1))
}

/**
 * The sub-polyline between two distances along [coords], with exact endpoints.
 *
 * Used to trim the first and last edge of a route down to where the driver actually joins
 * and leaves the road network.
 */
fun sliceCoords(coords: DoubleArray, fromMeters: Double, toMeters: Double): DoubleArray {
    val count = coordsCount(coords)
    if (count < 2) return coords.copyOf()
    val total = coordsLengthMeters(coords)
    val start = fromMeters.coerceIn(0.0, total)
    val end = toMeters.coerceIn(start, total)

    val out = ArrayList<LatLon>(count)
    out.add(interpolateAlongCoords(coords, start))
    var cumulative = 0.0
    for (i in 0 until count - 1) {
        cumulative += haversineMeters(coordAt(coords, i), coordAt(coords, i + 1))
        if (cumulative > start && cumulative < end) out.add(coordAt(coords, i + 1))
    }
    out.add(interpolateAlongCoords(coords, end))
    return listToCoords(out)
}

/** Joins consecutive edge geometries, dropping the duplicated shared vertex at each seam. */
fun concatCoords(parts: List<DoubleArray>): DoubleArray {
    if (parts.isEmpty()) return DoubleArray(0)
    val out = ArrayList<LatLon>()
    for (part in parts) {
        for (i in 0 until coordsCount(part)) {
            val point = coordAt(part, i)
            val last = out.lastOrNull()
            if (last != null && last.lat == point.lat && last.lon == point.lon) continue
            out.add(point)
        }
    }
    return listToCoords(out)
}

/** Reverses the direction of travel of a packed polyline. */
fun reverseCoords(coords: DoubleArray): DoubleArray {
    val count = coordsCount(coords)
    val out = DoubleArray(coords.size)
    for (i in 0 until count) {
        out[i * 2] = coords[(count - 1 - i) * 2]
        out[i * 2 + 1] = coords[(count - 1 - i) * 2 + 1]
    }
    return out
}
