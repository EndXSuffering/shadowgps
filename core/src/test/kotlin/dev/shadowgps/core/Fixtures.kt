package dev.shadowgps.core

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorEnvelope
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.METERS_PER_DEGREE_LAT
import dev.shadowgps.core.geo.metersPerDegreeLon
import dev.shadowgps.core.osm.OsmElement

/**
 * A synthetic grid town used by the routing tests.
 *
 * Real OSM extracts make poor unit-test inputs: they are large, they change, and when an
 * assertion fails you cannot tell whether the router or the data is at fault. A grid has
 * the useful property that the correct answer is obvious by inspection.
 */
object Fixtures {

    const val DEFAULT_SPACING_METERS = 200.0

    val ORIGIN = LatLon(35.9940, -78.8986)

    /** Node id for the intersection at [row] (north-going) and [col] (east-going). */
    fun nodeId(row: Int, col: Int): Long = (row * 1000L + col) + 1

    fun position(row: Int, col: Int, spacing: Double = DEFAULT_SPACING_METERS): LatLon {
        val dLat = spacing / METERS_PER_DEGREE_LAT
        val dLon = spacing / metersPerDegreeLon(ORIGIN.lat)
        return LatLon(ORIGIN.lat + row * dLat, ORIGIN.lon + col * dLon)
    }

    /**
     * A [rows] x [cols] grid of two-way residential streets.
     *
     * Every east-west row is one way named `<letter> Street`, every north-south column one
     * named `<n>th Avenue`, so the direction generator has real names to work with.
     */
    fun grid(
        rows: Int,
        cols: Int,
        spacing: Double = DEFAULT_SPACING_METERS,
        highway: String = "residential",
        extraWayTags: Map<String, String> = emptyMap(),
    ): List<OsmElement> {
        val elements = ArrayList<OsmElement>()

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val p = position(row, col, spacing)
                elements.add(OsmElement(type = "node", id = nodeId(row, col), lat = p.lat, lon = p.lon))
            }
        }

        var wayId = 1_000_000L
        for (row in 0 until rows) {
            elements.add(
                OsmElement(
                    type = "way",
                    id = wayId++,
                    nodes = (0 until cols).map { nodeId(row, it) },
                    tags = mapOf("highway" to highway, "name" to "${('A' + row)} Street") + extraWayTags,
                ),
            )
        }
        for (col in 0 until cols) {
            elements.add(
                OsmElement(
                    type = "way",
                    id = wayId++,
                    nodes = (0 until rows).map { nodeId(it, col) },
                    tags = mapOf("highway" to highway, "name" to "${col + 1}th Avenue") + extraWayTags,
                ),
            )
        }

        return elements
    }

    /** A plate reader at an arbitrary point, facing [heading] if given. */
    fun alpr(
        id: String,
        position: LatLon,
        heading: Double? = null,
        kind: DetectorKind = DetectorKind.ALPR,
    ): Detector {
        val envelope = DetectorEnvelope.forKind(kind)
        return Detector(
            id = id,
            kind = kind,
            position = position,
            headingDegrees = heading,
            rangeMeters = envelope.rangeMeters,
            fovDegrees = envelope.fovDegrees,
            brand = if (kind == DetectorKind.ALPR) "Flock Safety" else null,
        )
    }

    /** Midpoint between two grid intersections, where a camera can watch one block. */
    fun midBlock(
        rowA: Int,
        colA: Int,
        rowB: Int,
        colB: Int,
        spacing: Double = DEFAULT_SPACING_METERS,
    ): LatLon {
        val a = position(rowA, colA, spacing)
        val b = position(rowB, colB, spacing)
        return LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
    }
}
