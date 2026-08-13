package dev.shadowgps.core

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.angularDifference
import dev.shadowgps.core.geo.bearingDegrees
import dev.shadowgps.core.geo.concatCoords
import dev.shadowgps.core.geo.coordsLengthMeters
import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.geo.interpolateAlongCoords
import dev.shadowgps.core.geo.listToCoords
import dev.shadowgps.core.geo.projectOntoPolyline
import dev.shadowgps.core.geo.reverseCoords
import dev.shadowgps.core.geo.signedTurnDegrees
import dev.shadowgps.core.geo.sliceCoords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeoTest {

    @Test
    fun `haversine matches a known city pair`() {
        // London to Paris, ~343 km great-circle.
        val london = LatLon(51.5074, -0.1278)
        val paris = LatLon(48.8566, 2.3522)
        assertEquals(343_000.0, haversineMeters(london, paris), 2_000.0)
    }

    @Test
    fun `haversine is zero for identical points`() {
        val p = LatLon(35.99, -78.89)
        assertEquals(0.0, haversineMeters(p, p), 1e-9)
    }

    @Test
    fun `bearing points the right way`() {
        val origin = LatLon(0.0, 0.0)
        assertEquals(0.0, bearingDegrees(origin, LatLon(1.0, 0.0)), 0.5)
        assertEquals(90.0, bearingDegrees(origin, LatLon(0.0, 1.0)), 0.5)
        assertEquals(180.0, bearingDegrees(origin, LatLon(-1.0, 0.0)), 0.5)
        assertEquals(270.0, bearingDegrees(origin, LatLon(0.0, -1.0)), 0.5)
    }

    @Test
    fun `destination point round-trips through bearing and distance`() {
        val start = LatLon(35.99, -78.89)
        val moved = destinationPoint(start, bearing = 45.0, meters = 1_000.0)
        assertEquals(1_000.0, haversineMeters(start, moved), 1.0)
        assertEquals(45.0, bearingDegrees(start, moved), 0.5)
    }

    @Test
    fun `angular difference wraps around north`() {
        assertEquals(20.0, angularDifference(350.0, 10.0), 1e-9)
        assertEquals(180.0, angularDifference(0.0, 180.0), 1e-9)
        assertEquals(0.0, angularDifference(45.0, 405.0), 1e-9)
    }

    @Test
    fun `signed turn is positive to the right`() {
        assertEquals(90.0, signedTurnDegrees(0.0, 90.0), 1e-9)
        assertEquals(-90.0, signedTurnDegrees(0.0, 270.0), 1e-9)
        assertEquals(20.0, signedTurnDegrees(350.0, 10.0), 1e-9)
        assertEquals(180.0, signedTurnDegrees(0.0, 180.0), 1e-9)
    }

    @Test
    fun `projection finds the perpendicular foot on a segment`() {
        // A 1 km east-west line at the equator, with a point 100 m north of its midpoint.
        val line = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.009))
        val offset = destinationPoint(LatLon(0.0, 0.0045), bearing = 0.0, meters = 100.0)

        val projection = projectOntoPolyline(line, offset)

        assertEquals(100.0, projection.distanceMeters, 1.0)
        assertEquals(0.0, projection.point.lat, 1e-6)
        assertEquals(0.0045, projection.point.lon, 1e-5)
        assertEquals(90.0, projection.headingDegrees, 0.5)
    }

    @Test
    fun `projection clamps to the nearest endpoint when the point is past the line`() {
        val line = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        val beyond = LatLon(0.0, 0.002)

        val projection = projectOntoPolyline(line, beyond)

        assertEquals(0.001, projection.point.lon, 1e-9)
        assertEquals(haversineMeters(LatLon(0.0, 0.001), beyond), projection.distanceMeters, 1.0)
    }

    @Test
    fun `along-distance accumulates over several segments`() {
        val coords = listToCoords(
            listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001), LatLon(0.001, 0.001)),
        )
        val total = coordsLengthMeters(coords)

        val midpoint = interpolateAlongCoords(coords, total / 2)
        val projection = dev.shadowgps.core.geo.projectOntoCoords(coords, midpoint)

        assertEquals(total / 2, projection.alongMeters, 1.0)
    }

    @Test
    fun `slice keeps only the requested span and lands exactly on its ends`() {
        val coords = listToCoords(
            listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.002), LatLon(0.0, 0.004)),
        )
        val total = coordsLengthMeters(coords)

        val middle = sliceCoords(coords, total * 0.25, total * 0.75)

        assertEquals(total * 0.5, coordsLengthMeters(middle), 1.0)
        assertEquals(
            interpolateAlongCoords(coords, total * 0.25).lon,
            middle[1],
            1e-9,
        )
    }

    @Test
    fun `reverse flips travel direction without changing length`() {
        val coords = listToCoords(listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001), LatLon(0.001, 0.001)))
        val reversed = reverseCoords(coords)

        assertEquals(coordsLengthMeters(coords), coordsLengthMeters(reversed), 1e-6)
        assertEquals(coords[0], reversed[reversed.size - 2], 1e-12)
        assertEquals(coords[1], reversed[reversed.size - 1], 1e-12)
    }

    @Test
    fun `concat drops the duplicated vertex at each seam`() {
        val first = listToCoords(listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001)))
        val second = listToCoords(listOf(LatLon(0.0, 0.001), LatLon(0.0, 0.002)))

        val joined = concatCoords(listOf(first, second))

        assertEquals(3, joined.size / 2)
    }

    @Test
    fun `bounding box grows by the requested distance`() {
        val box = BoundingBox.around(LatLon(35.99, -78.89), 500.0)

        assertTrue(box.contains(LatLon(35.99, -78.89)))
        assertEquals(1_000.0, haversineMeters(LatLon(box.south, -78.89), LatLon(box.north, -78.89)), 5.0)
    }

    @Test
    fun `bounding box area is about right`() {
        val box = BoundingBox.around(LatLon(0.0, 0.0), 5_000.0)
        // A 10 km square.
        assertEquals(100.0, box.areaKm2, 2.0)
    }
}
